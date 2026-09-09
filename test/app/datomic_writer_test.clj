(ns app.datomic-writer-test
  (:require
   [app.datomic :as app-db]
   [app.game-loop :as game]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest legacy-request-transactions-wait-for-the-writer-and-preserve-audit
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (let [member-id  (random-uuid)
            member-ref [:member/member-id member-id]
            entered    (promise)
            release    (promise)
            req        {:datomic-conn conn
                        :system       {:frame-loop runtime}
                        :app/session  {:session/member {:member/member-id member-id}}}]
        (writer/call! (:write-runner runtime)
                      #(deref (d/transact conn [{:member/member-id member-id :member/name "Before"}])))
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (let [result (future (app-db/transact-wrapper! req {:tx-data [[:db/add member-ref :member/name "After"]]} "Legacy mutation"))]
            (is (= ::waiting (deref result 1000 ::waiting)))
            (is (= "Before" (:member/name (d/entity (d/db conn) member-ref))))
            (deliver release true)
            (let [report (deref result 5000 ::timeout)
                  db     (:db-after report)
                  audit  (d/entity db (d/t->tx (d/basis-t db)))]
              (is (= "After" (:member/name (d/entity db member-ref))))
              (is (= member-id (get-in audit [:audit/user :member/member-id])))
              (is (= "Legacy mutation" (:audit/comment audit)))))
          (writer/close! (:write-runner runtime))
          (is (thrown-with-msg? Exception #"Writer is stopped"
                                (app-db/transact-wrapper! req {:tx-data [[:db/add member-ref :member/name "Rejected"]]})))
          (is (= "After" (:member/name (d/entity (d/db conn) member-ref))))
          (finally (deliver release true)))))))
