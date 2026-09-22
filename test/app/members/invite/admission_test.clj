(ns app.members.invite.admission-test
  (:require
   [app.members.invite.admission :as admission]
   [app.members.invite.cells-test :as cells]
   [app.members.invite.domain :as domain]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(defn with-pending-invitation [f]
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [member-id (random-uuid)
            resources {:datomic-conn conn                            :write-runner (:write-runner runtime)
                       :clock        (constantly cells/requested-at)}]
        (writer/call! runtime
                      #(cells/seed-invitation! conn member-id
                                               {:status cells/pending           :generation 1
                                                :code   "admission-test-bearer" :expiry     cells/expires-at}))
        (f resources client member-id)))))

(deftest concurrent-admission-commits-one-claim-without-a-keycloak-adapter
  (with-pending-invitation
    (fn [{:keys [datomic-conn] :as resources} client member-id]
      (let [before (d/basis-t (d/db datomic-conn))]
        (doseq [code [nil "" "wrong-bearer"]]
          (is (= {:status :unavailable} (admission/request-setup! resources code))))
        (is (= {:status :unavailable}
               (admission/request-setup! (assoc resources :clock (constantly cells/expires-at)) "admission-test-bearer")))
        (is (= before (d/basis-t (d/db datomic-conn)))))
      (let [requests (mapv (fn [_] (future (admission/request-setup! resources "admission-test-bearer"))) (range 8))]
        (is (= (vec (repeat 8 {:status :creating :member-id member-id}))
               (mapv #(deref % 5000 ::timeout) requests))))
      (writer/call! resources (constantly nil))
      (is (= {:status cells/accepting :generation 2 :expires-at cells/expires-at}
             (domain/invitation-state (d/db datomic-conn) member-id)))
      (is (= 1 (count (drip/list-jobs client {}))))
      (is (= {:member-id member-id :claim-generation 2}
             (dissoc (:args (first (drip/list-jobs client {}))) :source-t)))
      (writer/call! resources
                    #(doseq [plan-fn [domain/begin-create-tx domain/link-keycloak-user-tx domain/finalize-tx]]
                       @(d/transact datomic-conn
                                    (:tx-data (plan-fn (d/db datomic-conn)
                                                       {:member-id       member-id             :state            (domain/invitation-state (d/db datomic-conn) member-id)
                                                        :transitioned-at cells/transitioned-at :keycloak-user-id "test-created-user"})))))
      (let [before (d/basis-t (d/db datomic-conn))]
        (is (= {:status :accepted :member-id member-id}
               (admission/request-setup! resources "admission-test-bearer")))
        (is (= before (d/basis-t (d/db datomic-conn)))))
      (is (= 1 (count (drip/list-jobs client {})))))))

(deftest admission-rechecks-the-bearer-after-writer-queue-wait
  (with-pending-invitation
    (fn [{:keys [datomic-conn] :as resources} client member-id]
      (let [entered    (promise)
            release    (promise)
            revocation (future
                         (writer/call! resources
                                       #(do
                                          (deliver entered true)
                                          @release
                                          @(d/transact datomic-conn
                                                       (:tx-data (domain/revoke-tx
                                                                  (d/db datomic-conn)
                                                                  {:member-id       member-id             :state (domain/invitation-state (d/db datomic-conn) member-id)
                                                                   :transitioned-at cells/transitioned-at}))))))]
        (try
          (is (= true (deref entered 5000 ::timeout)))
          (let [request (future (admission/request-setup! resources "admission-test-bearer"))]
            (is (= ::waiting (deref request 100 ::waiting)))
            (deliver release true)
            (is (map? (deref revocation 5000 nil)))
            (is (= {:status :unavailable} (deref request 5000 ::timeout)))
            (is (empty? (drip/list-jobs client {}))))
          (finally (deliver release true)))))))

(deftest admission-does-not-adopt-an-in-flight-legacy-claim
  (with-pending-invitation
    (fn [{:keys [datomic-conn] :as resources} client member-id]
      (writer/call! resources
                    #(deref (d/transact datomic-conn
                                        (:tx-data (domain/claim-tx
                                                   (d/db datomic-conn)
                                                   {:member-id    member-id          :state           (domain/invitation-state (d/db datomic-conn) member-id)
                                                    :requested-at cells/requested-at :transitioned-at cells/transitioned-at})))))
      (let [before (d/basis-t (d/db datomic-conn))]
        (is (= {:status :creating :member-id member-id}
               (admission/request-setup! resources "admission-test-bearer")))
        (is (= before (d/basis-t (d/db datomic-conn)))))
      (is (empty? (drip/list-jobs client {}))))))

(deftest closed-writer-rejects-admission-without-a-claim
  (with-pending-invitation
    (fn [{:keys [datomic-conn write-runner] :as resources} client _]
      (let [before (d/basis-t (d/db datomic-conn))]
        (writer/close! write-runner)
        (is (thrown-with-msg? Exception #"Writer is stopped"
                              (admission/request-setup! resources "admission-test-bearer")))
        (is (= before (d/basis-t (d/db datomic-conn)))))
      (is (empty? (drip/list-jobs client {}))))))
