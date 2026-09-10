(ns app.members.invite.writer-test
  (:require
   [app.game-loop :as game]
   [app.members.invite.cells-test :as cells]
   [app.members.invite.domain :as domain]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest invitation-transition-planning-and-commit-wait-for-the-writer
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (let [member-id  (random-uuid)
            entered    (promise)
            release    (promise)
            planned-on (promise)
            resources  {:datomic-conn  conn                                                                    :write-runner (:write-runner runtime)
                        :durable-jobs? true
                        :clock         #(do (deliver planned-on (Thread/currentThread)) cells/transitioned-at)}]
        (writer/call! (:write-runner runtime)
                      #(cells/seed-invitation! conn member-id {:status cells/pending :generation 1
                                                               :code   "test-bearer" :expiry     cells/expires-at}))
        (let [input    {:member/member-id    member-id                                       :member-invite/requested-at cells/requested-at
                        :member-invite/state (domain/invitation-state (d/db conn) member-id)}
              before-t (d/basis-t (d/db conn))]
          (try
            (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                  (fn [_] (deliver entered true) @release))
            (is (= true (deref entered 5000 ::timeout)))
            (let [result (future (cells/run-cell :member-invite/claim! resources input))]
              (is (= ::waiting (deref result 250 ::waiting)))
              (is (not (realized? planned-on)))
              (is (= before-t (d/basis-t (d/db conn))))
              (deliver release true)
              (let [result (deref result 5000 ::timeout)]
                (is (true? (:pass? result)))
                (is (= :claimed (get-in result [:output :member-invite/claim-status]))))
              (is (identical? (::game/thread runtime) @planned-on))
              (let [after-t (d/basis-t (d/db conn))
                    retry   (cells/run-cell :member-invite/claim! resources input)]
                (is (true? (:pass? retry)))
                (is (= :conflict (get-in retry [:output :member-invite/claim-status])))
                (is (= after-t (d/basis-t (d/db conn))))))
            (finally (deliver release true))))))))
