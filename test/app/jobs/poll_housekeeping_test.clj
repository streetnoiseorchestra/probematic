(ns app.jobs.poll-housekeeping-test
  (:require
   [app.game-loop :as game]
   [app.jobs.poll-housekeeping :as housekeeping]
   [app.poll.test-support :as polls]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [tick.core :as t])
  (:import (java.util.concurrent ConcurrentHashMap)))

(use-fixtures :each tc/with-released-test-connections)

(deftest scheduled-poll-closing-waits-for-the-writer
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (let [member-id (random-uuid)
            now       (t/instant "2026-06-17T18:00:00Z")
            system    {:datomic {:conn conn} :write-runner (:write-runner runtime)}
            entered   (promise)
            release   (promise)
            result    (promise)
            poll-ids  (writer/call!
                       runtime
                       (fn []
                         @(d/transact conn [{:member/member-id member-id}])
                         (mapv (fn [attrs] (:poll-id (polls/seed-poll! conn member-id attrs)))
                               [{:poll/poll-status :poll.status/open}
                                {:poll/poll-status :poll.status/draft}
                                {:poll/poll-status :poll.status/open :poll/closes-at (t/date-time "2030-06-17T19:00")}])))]
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (future (deliver result (try (housekeeping/close-expired-polls! system now) :done
                                       (catch Throwable e e))))
          (is (= ::waiting (deref result 50 ::waiting)))
          (is (= :poll.status/open (:poll/poll-status (d/entity (d/db conn) (polls/poll-ref (first poll-ids))))))
          (deliver release true)
          (is (= :done (deref result 5000 ::timeout)))
          (is (= [:poll.status/closed :poll.status/draft :poll.status/open]
                 (mapv #(:poll/poll-status (d/entity (d/db conn) (polls/poll-ref %))) poll-ids)))
          (let [basis (d/basis-t (d/db conn))]
            (housekeeping/close-expired-polls! system now)
            (is (= basis (d/basis-t (d/db conn)))))
          (finally (deliver release true)))))))
