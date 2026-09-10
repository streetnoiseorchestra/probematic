(ns app.jobs.invitations-test
  (:require
   [app.game-loop :as game]
   [app.ig]
   [app.jobs.invitations :as invitations]
   [app.members.invite.cells-test :as cells]
   [app.members.invite.domain :as domain]
   [app.members.invite.workflows :as acceptance]
   [app.members.invite.workflows-test :as workflows]
   [app.system :as system]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]
   [integrant.core :as ig]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(deftest invitation-worker-follows-durable-runtime-and-resource-dependencies
  (let [config (:ig/system (system/config {:profile :test}))]
    (is (= {:frame-loop (ig/ref :app.ig/frame-loop)
            :datomic    (ig/ref :app.ig/datomic-db)
            :keycloak   (ig/ref :app.ig/keycloak)
            :job-queue  (ig/ref :app.ig/job-queue)}
           (:app.ig/invitation-worker config))))
  (is (nil? (ig/init-key :app.ig/invitation-worker {})))
  (is (nil? (ig/init-key :app.ig/invitation-worker {:frame-loop {:durable-jobs? false}})))
  (is (nil? (ig/halt-key! :app.ig/invitation-worker nil))))

(defn with-claim [f]
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (let [member-id (random-uuid)
            keycloak  (workflows/fake-keycloak)
            resources {:datomic-conn  conn                            :write-runner (:write-runner runtime)
                       :clock         (constantly cells/requested-at) :keycloak     (:adapter keycloak)
                       :durable-jobs? true}]
        (writer/call! (:write-runner runtime)
                      #(cells/seed-invitation! conn member-id
                                               {:status cells/pending          :generation 1
                                                :code   "recovery-test-bearer" :expiry     cells/expires-at}))
        (let [claim (cells/run-cell :member-invite/claim! resources
                                    {:member/member-id    member-id                                       :member-invite/requested-at cells/requested-at
                                     :member-invite/state (domain/invitation-state (d/db conn) member-id)})]
          (is (= :claimed (get-in claim [:output :member-invite/claim-status])))
          (f runtime resources {:member-id member-id :claim-generation 2} keycloak))))))

(deftest recovery-creates-once-and-resumes-after-a-lost-create-response
  (doseq [lose-response? [false true]]
    (testing (str "lost response " lose-response?)
      (with-claim
        (fn [runtime resources job keycloak]
          (let [calls     (atom [])
                create!   (get-in resources [:keycloak :create-user!])
                resources (assoc-in resources [:keycloak :create-user!]
                                    (fn [spec]
                                      (swap! calls conj (Thread/currentThread))
                                      (let [result (create! spec)]
                                        (if lose-response?
                                          (throw (ex-info "Lost create response" {}))
                                          result))))]
            (when lose-response?
              (is (thrown? Exception (invitations/resume! resources job)))
              (is (= :member.invite.status/creating
                     (:status (domain/invitation-state (d/db (:datomic-conn resources)) (:member-id job))))))
            (is (= :accepted (invitations/resume! resources job)))
            (let [basis (d/basis-t (d/db (:datomic-conn resources)))]
              (is (= :accepted (invitations/resume! resources job)))
              (is (= basis (d/basis-t (d/db (:datomic-conn resources))))))
            (is (= 1 (count @calls)))
            (is (not (identical? (::game/thread runtime) (first @calls))))
            (is (= 1 (count (:users @(:state keycloak)))))
            (is (true? (get-in @(:state keycloak) [:users "created-user" :enabled?])))))))))

(deftest missing-account-after-create-permission-never-causes-another-create
  (with-claim
    (fn [_ resources job keycloak]
      (let [conn      (:datomic-conn resources)
            member-id (:member-id job)]
        (writer/call! (:write-runner resources)
                      #(deref (d/transact conn
                                          (:tx-data (domain/begin-create-tx
                                                     (d/db conn)
                                                     {:member-id       member-id             :state (domain/invitation-state (d/db conn) member-id)
                                                      :transitioned-at cells/transitioned-at})))))
        (dotimes [_ 3]
          (is (= :retry (invitations/resume! resources job))))
        (is (empty? (:users @(:state keycloak))))
        (is (= :member.invite.status/creating (:status (domain/invitation-state (d/db conn) member-id))))))))

(deftest superseded-job-does-not-touch-keycloak-or-the-new-claim
  (with-claim
    (fn [_ resources job keycloak]
      (let [conn      (:datomic-conn resources)
            member-id (:member-id job)]
        (writer/call! (:write-runner resources)
                      #(doseq [plan-fn [domain/begin-compensation-tx domain/release-tx domain/claim-tx]]
                         @(d/transact conn (:tx-data (plan-fn (d/db conn)
                                                              {:member-id    member-id          :state           (domain/invitation-state (d/db conn) member-id)
                                                               :requested-at cells/requested-at :transitioned-at cells/transitioned-at})))))
        (let [basis  (d/basis-t (d/db conn))
              before @(:state keycloak)]
          (is (= :superseded (invitations/resume! resources job)))
          (is (= basis (d/basis-t (d/db conn))))
          (is (= before @(:state keycloak)))
          (is (= 5 (:generation (domain/invitation-state (d/db conn) member-id)))))))))

(deftest unsafe-enabled-account-requires-an-operator-without-mutations
  (with-claim
    (fn [_ resources job keycloak]
      (let [conn      (:datomic-conn resources)
            member-id (:member-id job)
            user      {:id       "unexpected-enabled-user" :username   "alice.example"                      :email "alice@example.com"
                       :enabled? true                      :attributes (domain/attempt-markers member-id 3)}]
        (writer/call! (:write-runner resources)
                      #(deref (d/transact conn (:tx-data (domain/begin-create-tx
                                                          (d/db conn)
                                                          {:member-id       member-id             :state (domain/invitation-state (d/db conn) member-id)
                                                           :transitioned-at cells/transitioned-at})))))
        (swap! (:state keycloak) assoc-in [:users (:id user)] user)
        (let [basis  (d/basis-t (d/db conn))
              before @(:state keycloak)]
          (is (= :operator-required (invitations/resume! resources job)))
          (is (= basis (d/basis-t (d/db conn))))
          (is (= before @(:state keycloak))))))))

(deftest committed-acceptance-survives-a-lost-workflow-response
  (with-claim
    (fn [_ resources job _]
      (let [run! acceptance/accept-or-recover!]
        (with-redefs [acceptance/accept-or-recover!
                      (fn [resources input]
                        (run! resources input)
                        (throw (ex-info "Lost completed workflow response" {})))]
          (is (= :accepted (invitations/resume! resources job))))))))

(defn await-job-state [client id state]
  (loop [remaining 400]
    (let [job (drip/get-job client id)]
      (if (or (= state (:state job)) (zero? remaining))
        job
        (do (Thread/sleep 25) (recur (dec remaining)))))))

(deftest worker-finalizes-safe-outcomes-and-bounds-retries
  (doseq [[scenario status generation expected attempts]
          [[:accept cells/accepting 2 :completed 1]
           [:missing cells/creating 3 :discarded 2]
           [:unsafe cells/creating 3 :discarded 1]
           [:superseded cells/accepting 5 :completed 1]
           [:compensate cells/compensating 3 :completed 1]]]
    (testing (name scenario)
      (fixtures/with-runtime
        (fn [runtime client conn]
          (let [member-id (random-uuid)
                keycloak  (workflows/fake-keycloak)
                system    {:datomic   {:conn conn}     :frame-loop runtime
                           :job-queue {:client client} :keycloak   (:adapter keycloak)}]
            (writer/call! (:write-runner runtime)
                          #(cells/seed-invitation! conn member-id
                                                   {:status status               :generation generation
                                                    :code   "worker-test-bearer" :expiry     cells/expires-at}))
            (when (= :unsafe scenario)
              (swap! (:state keycloak) assoc-in [:users "unsafe-user"]
                     {:id         "unsafe-user"                        :enabled? true
                      :attributes (domain/attempt-markers member-id 3)}))
            (let [job    (drip/insert-job client "accept-invitation"
                                          {:member-id member-id :claim-generation 2}
                                          {:queue "invitation-setup" :max-attempts 2})
                  worker (ig/init-key :app.ig/invitation-worker system)]
              (try
                (let [finished (await-job-state client (:id job) expected)]
                  (is (= expected (:state finished)))
                  (is (= attempts (:attempt finished)))
                  (case scenario
                    :accept (is (= cells/accepted (:status (domain/invitation-state (d/db conn) member-id))))
                    :compensate (is (= cells/pending (:status (domain/invitation-state (d/db conn) member-id))))
                    :unsafe (is (true? (get-in @(:state keycloak) [:users "unsafe-user" :enabled?])))
                    (is (empty? (:users @(:state keycloak))))))
                (finally (ig/halt-key! :app.ig/invitation-worker worker))))))))))

(deftest worker-restart-recovers-a-lost-create-response-without-creating-again
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [member-id    (random-uuid)
            keycloak     (workflows/fake-keycloak)
            create!      (get-in keycloak [:adapter :create-user!])
            create-count (atom 0)
            system       {:datomic  {:conn conn}                                           :frame-loop runtime :job-queue {:client client}
                          :keycloak (assoc (:adapter keycloak) :create-user!
                                           (fn [spec]
                                             (swap! create-count inc)
                                             (create! spec)
                                             (throw (ex-info "Lost create response" {}))))}]
        (writer/call! (:write-runner runtime)
                      #(cells/seed-invitation! conn member-id
                                               {:status cells/accepting       :generation 2
                                                :code   "restart-test-bearer" :expiry     cells/expires-at}))
        (let [job          (drip/insert-job client "accept-invitation" {:member-id member-id :claim-generation 2}
                                            {:queue "invitation-setup" :max-attempts 2})
              first-worker (invitations/start! system)]
          (try
            (is (= :retryable (:state (await-job-state client (:id job) :retryable))))
            (is (= cells/creating (:status (domain/invitation-state (d/db conn) member-id))))
            (finally (invitations/stop! first-worker)))
          (let [restarted-worker (invitations/start! system)]
            (try
              (let [finished (await-job-state client (:id job) :completed)]
                (is (= :completed (:state finished)))
                (is (= 2 (:attempt finished)))
                (is (= 1 @create-count))
                (is (= 1 (count (:users @(:state keycloak)))))
                (is (= cells/accepted (:status (domain/invitation-state (d/db conn) member-id)))))
              (finally (invitations/stop! restarted-worker)))))))))
