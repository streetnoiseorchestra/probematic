(ns app.members.invite.durable-acceptance-test
  (:require
   [app.members.invite.cells-test :as cells]
   [app.members.invite.domain :as domain]
   [app.members.invite.jobs :as jobs]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(deftest claim-commits-one-correlated-intent
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [member-id (random-uuid)
            resources {:datomic-conn conn                               :write-runner (:write-runner runtime)
                       :clock        (constantly cells/transitioned-at)}]
        (writer/call! (:write-runner runtime)
                      #(cells/seed-invitation! conn member-id
                                               {:status cells/pending        :generation 1
                                                :code   "secret-test-bearer" :expiry     cells/expires-at}))
        (let [input    {:member/member-id    member-id                                       :member-invite/requested-at cells/requested-at
                        :member-invite/state (domain/invitation-state (d/db conn) member-id)}
              result   (cells/run-cell :member-invite/claim! resources input)
              db       (d/db conn)
              claim-tx (d/q '[:find ?tx . :in $ ?id
                              :where [?e :member/member-id ?id]
                              [?e :member/invite-generation 2 ?tx]] db member-id)
              audit    (d/entity db claim-tx)]
          (is (true? (:pass? result)))
          (is (= :claimed (get-in result [:output :member-invite/claim-status])))
          (is (= 2 (get-in result [:output :member-invite/attempt-generation])))
          (is (= {:version 1 :jobs [(jobs/acceptance-job member-id 2)]}
                 (edn/read-string (:audit/jobs audit))))
          (is (= ::jobs/claim (:audit/action audit)))
          (is (= :app.origin/browser (:audit/origin audit)))
          (let [retry (cells/run-cell :member-invite/claim! resources input)]
            (is (true? (:pass? retry)))
            (is (= :conflict (get-in retry [:output :member-invite/claim-status])))
            (is (= (d/basis-t db) (d/basis-t (d/db conn)))))
          (writer/call! (:write-runner runtime) (constantly nil))
          (let [queued (drip/list-jobs client {})]
            (is (= 1 (count queued)))
            (is (= {:member-id member-id :claim-generation 2 :source-t (d/tx->t claim-tx)}
                   (:args (first queued))))))))))
