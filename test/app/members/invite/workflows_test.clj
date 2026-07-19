(ns app.members.invite.workflows-test
  (:require
   [app.keycloak.cells]
   [app.members.invite.cells]
   [app.members.invite.domain :as domain]
   [app.members.invite.workflows :as workflows]
   [app.schemas :as s]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [malli.core :as m]
   [mycelium.core :as myc]))

(def pending :member.invite.status/pending)
(def compensating :member.invite.status/compensating)
(def accepted :member.invite.status/accepted)

(def issued-at #inst "2026-07-16T08:00:00.000-00:00")
(def requested-at #inst "2026-07-16T08:05:00.000-00:00")
(def expires-at #inst "2026-08-15T08:00:00.000-00:00")

(def all-workflows
  [workflows/invite-member
   workflows/reissue-invitation
   workflows/revoke-invitation
   workflows/accept-or-recover])

(def workflow-options
  {:malli/registry domain/registry})

(deftest externally-run-workflows-are-precompiled-with-the-application-registry-test
  (let [workflow-vars ['invite-member-wf
                       'reissue-invitation-wf
                       'revoke-invitation-wf
                       'accept-or-recover-wf]
        compiled-vars (into {}
                            (map (fn [workflow-var]
                                   [workflow-var
                                    (some-> (ns-resolve 'app.members.invite.workflows workflow-var) deref)]))
                            workflow-vars)]
    (is (= (set workflow-vars)
           (into #{}
                 (keep (fn [[workflow-var compiled]]
                         (when (and (map? compiled)
                                    (contains? compiled :compiled-fsm)
                                    (contains? compiled :input-schema-compiled))
                           workflow-var)))
                 compiled-vars)))))

(defn fake-keycloak
  ([]
   (fake-keycloak []))
  ([users]
   (let [state (atom {:users (into {} (map (juxt :id identity)) users)
                      :memberships #{}})]
     {:state state
      :adapter
      {:find-users-by-attributes
       (fn [attributes]
         (->> (vals (:users @state))
              (filter
               (fn [user]
                 (= attributes
                    (select-keys (:attributes user)
                                 (keys attributes)))))
              vec))
       :get-user
       (fn [user-id]
         (get-in @state [:users user-id]))
       :find-groups-by-name
       (fn [group-name]
         (if (= "Mitglieder" group-name)
           [{:id "member-group" :name group-name}]
           []))
       :create-user!
       (fn [user-spec]
         (let [user-id "created-user"
               user (-> user-spec
                        (select-keys [:username :email :enabled? :attributes])
                        (assoc :id user-id))]
           (swap! state assoc-in [:users user-id] user)
           {:outcome :created :user-id user-id}))
       :add-user-to-group!
       (fn [user-id group-id]
         (swap! state update :memberships conj [user-id group-id])
         {:outcome :joined})
       :set-user-enabled!
       (fn [user-id enabled?]
         (swap! state assoc-in [:users user-id :enabled?] enabled?)
         {:outcome :updated})
       :delete-user!
       (fn [user-id]
         (let [found? (contains? (:users @state) user-id)]
           (swap! state update :users dissoc user-id)
           {:outcome (if found? :deleted :not-found)}))}})))

(defn seed-member! [conn member-id invitation]
  @(d/transact
    conn
    [(merge
      {:member/member-id member-id
       :member/name "Alice Example"
       :member/email "alice@example.com"
       :member/username "alice.example"}
      invitation)]))

(defn run-workflow [workflow resources input]
  (myc/run-workflow workflow resources input workflow-options))

(deftest workflow-manifests-have-local-ids-plain-docs-and-inline-schemas-test
  (doseq [workflow all-workflows]
    (testing (:id workflow)
      (is (= {:qualified-id? true
              :doc? true
              :schema-form? true
              :closed-map? false}
             {:qualified-id?
              (= "app.members.invite.workflows"
                 (namespace (:id workflow)))
              :doc? (boolean
                     (and (string? (:doc workflow))
                          (not-empty (:doc workflow))))
              :schema-form? (vector? (:input-schema workflow))
              :closed-map?
              (= {:closed true} (second (:input-schema workflow)))}))))
  (is (= #{:name
           :nick
           :email
           :username
           :phone
           :section-name
           :active}
         (into #{}
               (keep #(when (vector? %) (first %)))
               (rest
                (get-in workflows/invite-member
                        [:input-schema 1 1])))))
  (is (= #{:member/member-id
           :member-invite/resolved-generation
           :member-invite/requested-at
           :keycloak/group-name}
         (into #{}
               (keep #(when (vector? %) (first %)))
               (rest (:input-schema workflows/accept-or-recover)))))
  (is (nil? (:transforms workflows/accept-or-recover)))
  (is (not (re-find #"resend|recover-in-flight|repair-invitation"
                    (pr-str all-workflows)))))

(deftest all-workflows-precompile-with-the-application-malli-registry-test
  (doseq [workflow all-workflows]
    (testing (:id workflow)
      (is (map? (myc/pre-compile workflow workflow-options)))))
  (is (thrown? Exception
               (m/schema ::s/non-blank-string))))

(deftest invite-reissue-and-revoke-workflows-cover-the-admin-lifecycle-test
  (let [{:keys [conn] actor-member-id :member-id}
        (tc/new-system "invite-admin-workflows")
        invited-member-id (random-uuid)
        ledger-id (random-uuid)
        generated-ids (atom [invited-member-id ledger-id])
        codes (atom ["initial-code" "replacement-code"])
        queued (atom [])
        resources {:datomic-conn conn
                   :clock (constantly issued-at)
                   :current-member-id actor-member-id
                   :random-uuid
                   (fn []
                     (let [generated-id (first @generated-ids)]
                       (swap! generated-ids subvec 1)
                       generated-id))
                   :random-code
                   (fn []
                     (let [code (first @codes)]
                       (swap! codes subvec 1)
                       code))
                   :build-invitation-email
                   (fn [member code]
                     {:to (:member/email member) :code code})
                   :queue-email! #(swap! queued conj %)}
        member-invite {:name "Alice Example"
                       :nick "alice"
                       :email "alice@example.com"
                       :username "alice.example"
                       :phone "+43677123456"
                       :section-name "Sopran"
                       :active true}]
    @(d/transact conn [{:section/name "Sopran"}])
    (let [invite-result
          (run-workflow
           workflows/invite-member
           resources
           {:member-invite member-invite})]
      (is (= {:member-invite/persist-status :created
              :member-invite/email-queued? true
              :member/member-id invited-member-id}
             (merge
              (select-keys invite-result
                           [:member-invite/persist-status
                            :member-invite/email-queued?])
              (select-keys (:member-invite/member invite-result)
                           [:member/member-id])))))
    (let [reissue-result
          (run-workflow
           workflows/reissue-invitation
           resources
           {:member/member-id invited-member-id
            :member-invite/resolved-generation 1})]
      (is (= {:member-invite/reissue-step :reissue
              :member-invite/reissue-status :reissued
              :member-invite/email-queued? true}
             (select-keys reissue-result
                          [:member-invite/reissue-step
                           :member-invite/reissue-status
                           :member-invite/email-queued?]))))
    (let [revoke-result
          (run-workflow
           workflows/revoke-invitation
           resources
           {:member/member-id invited-member-id
            :member-invite/resolved-generation 2})]
      (is (= {:member-invite/revoke-step :revoke
              :member-invite/revoke-status :revoked}
             (select-keys revoke-result
                          [:member-invite/revoke-step
                           :member-invite/revoke-status]))))
    (let [member (d/entity
                  (d/db conn)
                  [:member/member-id invited-member-id])
          status (:member/invite-status member)]
      (is (= {:queued [{:to "alice@example.com" :code "initial-code"}
                       {:to "alice@example.com" :code "replacement-code"}]
              :status :member.invite.status/revoked
              :code nil
              :expiry nil}
             {:queued @queued
              :status (if (keyword? status) status (:db/ident status))
              :code (:member/invite-code member)
              :expiry (:member/invite-expires-at member)})))))

(deftest duplicate-invitation-submission-does-not-upsert-or-queue-another-email-test
  (let [{:keys [conn] actor-member-id :member-id}
        (tc/new-system "invite-duplicate-submission")
        first-member-id (random-uuid)
        first-ledger-id (random-uuid)
        second-member-id (random-uuid)
        second-ledger-id (random-uuid)
        generated-ids
        (atom [first-member-id
               first-ledger-id
               second-member-id
               second-ledger-id])
        codes (atom ["original-code" "conflicting-code"])
        queued (atom [])
        resources {:datomic-conn conn
                   :clock (constantly issued-at)
                   :current-member-id actor-member-id
                   :random-uuid
                   (fn []
                     (let [generated-id (first @generated-ids)]
                       (swap! generated-ids subvec 1)
                       generated-id))
                   :random-code
                   (fn []
                     (let [code (first @codes)]
                       (swap! codes subvec 1)
                       code))
                   :build-invitation-email
                   (fn [member code]
                     {:to (:member/email member) :code code})
                   :queue-email! #(swap! queued conj %)}
        input {:member-invite
               {:name "Alice Example"
                :nick "alice"
                :email "alice@example.com"
                :username "alice.example"
                :phone "+43677123456"
                :section-name "Sopran"
                :active true}}]
    @(d/transact conn [{:section/name "Sopran"}])
    (let [first-result (run-workflow workflows/invite-member resources input)
          second-result (run-workflow workflows/invite-member resources input)
          db (d/db conn)
          member (d/entity db [:member/member-id first-member-id])
          status (:member/invite-status member)]
      (is (= {:first-status :created
              :second-status :conflict
              :created-member-id first-member-id
              :member-count 1
              :ledger-count 1
              :invitation-code "original-code"
              :invitation-generation 1
              :invitation-status pending
              :remaining-generated-ids []
              :queued [{:to "alice@example.com" :code "original-code"}]}
             {:first-status (:member-invite/persist-status first-result)
              :second-status (:member-invite/persist-status second-result)
              :created-member-id
              (get-in first-result
                      [:member-invite/member :member/member-id])
              :member-count
              (d/q '[:find (count ?member) .
                     :where [?member :member/name]]
                   db)
              :ledger-count
              (d/q '[:find (count ?ledger) .
                     :where [?ledger :ledger/ledger-id]]
                   db)
              :invitation-code (:member/invite-code member)
              :invitation-generation (:member/invite-generation member)
              :invitation-status
              (if (keyword? status) status (:db/ident status))
              :remaining-generated-ids @generated-ids
              :queued @queued})))))

(deftest explicit-acceptance-workflow-creates-and-enables-the-account-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-accept-workflow")
        keycloak (fake-keycloak)]
    (seed-member!
     conn
     member-id
     {:member/invite-code "accept-code"
      :member/invite-expires-at expires-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at issued-at})
    (let [result
          (run-workflow
           workflows/accept-or-recover
           {:datomic-conn conn
            :clock (constantly requested-at)
            :keycloak (:adapter keycloak)}
           {:member/member-id member-id
            :member-invite/resolved-generation 1
            :member-invite/requested-at requested-at
            :keycloak/group-name "Mitglieder"})
          state (domain/invitation-state (d/db conn) member-id)]
      (is (= {:result :accepted
              :state {:status accepted
                      :generation 5
                      :keycloak-id "created-user"}
              :enabled? true
              :memberships #{["created-user" "member-group"]}
              :user-spec
              {:username "alice.example"
               :email "alice@example.com"
               :first-name "Alice Example"
               :enabled? false
               :email-verified? true
               :attributes (domain/attempt-markers member-id 3)}}
             {:result (:member-invite/result result)
              :state state
              :enabled? (get-in @(:state keycloak)
                                [:users "created-user" :enabled?])
              :memberships (:memberships @(:state keycloak))
              :user-spec (:keycloak/user-spec result)})))))

(deftest account-creation-timeout-recovery-does-not-create-a-second-user-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invite-create-timeout-recovery")
        keycloak (fake-keycloak)
        create! (get-in keycloak [:adapter :create-user!])
        create-calls (atom 0)
        timeout (ex-info "Keycloak create timed out" {:type :timeout})
        adapter
        (assoc (:adapter keycloak)
               :create-user!
               (fn [user-spec]
                 (swap! create-calls inc)
                 (let [result (create! user-spec)]
                   (if (= 1 @create-calls)
                     (throw timeout)
                     result))))]
    (seed-member!
     conn
     member-id
     {:member/invite-code "timeout-code"
      :member/invite-expires-at expires-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at issued-at})
    (let [first-failed?
          (try
            (run-workflow
             workflows/accept-or-recover
             {:datomic-conn conn
              :clock (constantly requested-at)
              :keycloak adapter}
             {:member/member-id member-id
              :member-invite/resolved-generation 1
              :member-invite/requested-at requested-at
              :keycloak/group-name "Mitglieder"})
            false
            (catch Throwable _exception
              true))
          state-after-timeout
          (domain/invitation-state (d/db conn) member-id)
          second-result
          (run-workflow
           workflows/accept-or-recover
           {:datomic-conn conn
            :clock (constantly requested-at)
            :keycloak adapter}
           {:member/member-id member-id
            :member-invite/resolved-generation
            (:generation state-after-timeout)
            :member-invite/requested-at requested-at
            :keycloak/group-name "Mitglieder"})]
      (is (= {:first-failed? true
              :state-after-timeout
              {:status :member.invite.status/creating
               :generation 3
               :expires-at expires-at}
              :second-result :accepted
              :final-state {:status accepted
                            :generation 5
                            :keycloak-id "created-user"}
              :create-calls 1
              :keycloak-user-count 1}
             {:first-failed? first-failed?
              :state-after-timeout state-after-timeout
              :second-result (:member-invite/result second-result)
              :final-state (domain/invitation-state (d/db conn) member-id)
              :create-calls @create-calls
              :keycloak-user-count (count (:users @(:state keycloak)))})))))

(deftest acceptance-workflow-enters-the-current-durable-phase-test
  (let [run (fn [conn member-id generation keycloak]
              (run-workflow
               workflows/accept-or-recover
               {:datomic-conn conn
                :clock (constantly requested-at)
                :keycloak keycloak}
               {:member/member-id member-id
                :member-invite/resolved-generation generation
                :member-invite/requested-at requested-at
                :keycloak/group-name "Mitglieder"}))]
    (testing "accepting resumes at account provisioning"
      (let [{:keys [conn member-id]}
            (tc/new-system "invite-resume-provisioning")
            keycloak (fake-keycloak)]
        (seed-member!
         conn
         member-id
         {:member/invite-code "provision-code"
          :member/invite-expires-at expires-at
          :member/invite-status domain/accepting
          :member/invite-generation 2
          :member/invite-status-at issued-at})
        (let [result (run conn member-id 2 (:adapter keycloak))]
          (is (= {:result :accepted
                  :first-cells [:start :provision-read-profile]
                  :state {:status accepted
                          :generation 5
                          :keycloak-id "created-user"}}
                 {:result (:member-invite/result result)
                  :first-cells (mapv :cell
                                     (take 2 (:mycelium/trace result)))
                  :state (domain/invitation-state (d/db conn) member-id)})))))
    (testing "activating resumes with the linked Keycloak account"
      (let [{:keys [conn member-id]}
            (tc/new-system "invite-resume-activation")
            attributes (domain/attempt-markers member-id 3)
            keycloak
            (fake-keycloak
             [{:id "linked-user"
               :username "alice.example"
               :email "alice@example.com"
               :enabled? false
               :attributes attributes}])]
        (seed-member!
         conn
         member-id
         {:member/invite-code "activation-code"
          :member/invite-expires-at expires-at
          :member/invite-status domain/activating
          :member/invite-generation 4
          :member/invite-status-at issued-at
          :member/keycloak-id "linked-user"})
        (let [result (run conn member-id 4 (:adapter keycloak))]
          (is (= {:result :accepted
                  :first-cells [:start :activate-load-user]
                  :state {:status accepted
                          :generation 5
                          :keycloak-id "linked-user"}}
                 {:result (:member-invite/result result)
                  :first-cells (mapv :cell
                                     (take 2 (:mycelium/trace result)))
                  :state (domain/invitation-state (d/db conn) member-id)})))))
    (testing "accepted state returns the accepted result"
      (let [{:keys [conn member-id]}
            (tc/new-system "invite-resume-accepted")
            keycloak (fake-keycloak)]
        (seed-member!
         conn
         member-id
         {:member/invite-status accepted
          :member/invite-generation 5
          :member/invite-status-at issued-at
          :member/keycloak-id "accepted-user"})
        (let [result (run conn member-id 5 (:adapter keycloak))]
          (is (= {:result :accepted
                  :cells [:start :accepted]
                  :state {:status accepted
                          :generation 5
                          :keycloak-id "accepted-user"}}
                 {:result (:member-invite/result result)
                  :cells (mapv :cell (:mycelium/trace result))
                  :state (domain/invitation-state (d/db conn) member-id)})))))))

(deftest explicit-acceptance-workflow-finishes-cleanup-that-already-started-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-cleanup-workflow")
        attributes (domain/attempt-markers member-id 3)
        user {:id "unfinished-user"
              :username "alice.example"
              :email "alice@example.com"
              :enabled? false
              :attributes attributes}
        keycloak (fake-keycloak [user])]
    (seed-member!
     conn
     member-id
     {:member/invite-code "cleanup-code"
      :member/invite-expires-at expires-at
      :member/invite-status compensating
      :member/invite-generation 4
      :member/invite-status-at issued-at})
    (let [result
          (run-workflow
           workflows/accept-or-recover
           {:datomic-conn conn
            :clock (constantly requested-at)
            :keycloak (:adapter keycloak)}
           {:member/member-id member-id
            :member-invite/resolved-generation 4
            :member-invite/requested-at requested-at
            :keycloak/group-name "Mitglieder"})]
      (is (= {:result :pending
              :state {:status pending
                      :generation 5
                      :expires-at expires-at}
              :keycloak-users {}}
             {:result (:member-invite/result result)
              :state (domain/invitation-state (d/db conn) member-id)
              :keycloak-users (:users @(:state keycloak))})))))

(deftest acceptance-workflow-connects-business-phases-directly-test
  (is (= {:start {:claim :claim
                  :provision :provision-read-profile
                  :configure :configure-find-user
                  :activate :activate-load-user
                  :cleanup :cleanup-find-user
                  :accepted :accepted
                  :default :stale}
          :claim {:claimed :provision-read-profile
                  :conflict :retry}
          :provision-read-profile :provision-find-group
          :provision-find-group {:found :provision-begin-create
                                 :not-found :retry
                                 :ambiguous :operator-required}
          :provision-begin-create {:begun :provision-create-user
                                   :conflict :retry}
          :provision-create-user {:created :configure-find-user
                                  :rejected :begin-compensation}
          :configure-find-user {:found :configure-check-user
                                :not-found :retry
                                :ambiguous :operator-required}
          :configure-check-user {:configure :configure-find-group
                                 :unsafe :operator-required}
          :configure-find-group {:found :configure-add-user-to-group
                                 :unavailable :begin-compensation}
          :configure-add-user-to-group {:joined :configure-link-user
                                        :rejected :begin-compensation}
          :configure-link-user {:linked :activate-load-user
                                :conflict :retry}
          :activate-load-user {:found :activate-check-user
                               :not-found :operator-required}
          :activate-check-user {:enable :activate-enable-user
                                :finalize :activate-finalize
                                :unsafe :operator-required}
          :activate-enable-user {:updated :activate-finalize
                                 :rejected :operator-required}
          :activate-finalize {:finalized :accepted
                              :conflict :retry}
          :begin-compensation {:begun :cleanup-find-user
                               :conflict :retry}
          :cleanup-find-user {:found :cleanup-check-user
                              :not-found :cleanup-release
                              :ambiguous :operator-required}
          :cleanup-check-user {:delete :cleanup-delete-user
                               :unsafe :operator-required}
          :cleanup-delete-user {:absent :cleanup-release
                                :rejected :operator-required}
          :cleanup-release {:released :pending
                            :conflict :retry}
          :accepted :end
          :pending :end
          :retry :end
          :stale :end
          :operator-required :end}
         (:edges workflows/accept-or-recover))))

(defn run-acceptance
  [conn member-id generation clock keycloak]
  (run-workflow
   workflows/accept-or-recover
   {:datomic-conn conn
    :clock clock
    :keycloak keycloak}
   {:member/member-id member-id
    :member-invite/resolved-generation generation
    :member-invite/requested-at requested-at
    :keycloak/group-name "Mitglieder"}))

(deftest flat-acceptance-workflow-returns-non-happy-terminal-outcomes-test
  (testing "stale generation"
    (let [{:keys [conn member-id]} (tc/new-system "invite-flat-stale")
          keycloak (fake-keycloak)]
      (seed-member!
       conn
       member-id
       {:member/invite-code "stale-code"
        :member/invite-expires-at expires-at
        :member/invite-status pending
        :member/invite-generation 2
        :member/invite-status-at issued-at})
      (let [result
            (run-acceptance
             conn
             member-id
             1
             (constantly requested-at)
             (:adapter keycloak))]
        (is (= {:member-invite/result :stale
                :start-transition :default}
               {:member-invite/result (:member-invite/result result)
                :start-transition
                (get-in result [:mycelium/trace 0 :transition])})))))

  (testing "claim transaction conflict"
    (let [{:keys [conn member-id]} (tc/new-system "invite-flat-retry")
          keycloak (fake-keycloak)
          raced? (atom false)
          clock
          (fn []
            (when (compare-and-set! raced? false true)
              @(d/transact
                conn
                [[:db.fn/cas
                  [:member/member-id member-id]
                  :member/invite-generation
                  1
                  2]]))
            requested-at)]
      (seed-member!
       conn
       member-id
       {:member/invite-code "retry-code"
        :member/invite-expires-at expires-at
        :member/invite-status pending
        :member/invite-generation 1
        :member/invite-status-at issued-at})
      (let [result
            (run-acceptance conn member-id 1 clock (:adapter keycloak))]
        (is (= {:member-invite/result :retry
                :member-invite/claim-status :conflict
                :start-transition :claim}
               {:member-invite/result (:member-invite/result result)
                :member-invite/claim-status
                (:member-invite/claim-status result)
                :start-transition
                (get-in result [:mycelium/trace 0 :transition])})))))

  (testing "ambiguous Keycloak group"
    (let [{:keys [conn member-id]} (tc/new-system "invite-flat-operator")
          keycloak (fake-keycloak)
          adapter
          (assoc (:adapter keycloak)
                 :find-groups-by-name
                 (fn [group-name]
                   [{:id "group-a" :name group-name}
                    {:id "group-b" :name group-name}]))]
      (seed-member!
       conn
       member-id
       {:member/invite-code "operator-code"
        :member/invite-expires-at expires-at
        :member/invite-status pending
        :member/invite-generation 1
        :member/invite-status-at issued-at})
      (is (= {:member-invite/result :operator-required
              :keycloak/group-lookup :ambiguous
              :keycloak/match-count 2}
             (select-keys
              (run-acceptance
               conn
               member-id
               1
               (constantly requested-at)
               adapter)
              [:member-invite/result
               :keycloak/group-lookup
               :keycloak/match-count])))))

  (testing "definite account-creation rejection is compensated"
    (let [{:keys [conn member-id]} (tc/new-system "invite-flat-compensation")
          keycloak (fake-keycloak)
          adapter (assoc (:adapter keycloak)
                         :create-user!
                         (fn [_user-spec] {:outcome :rejected}))]
      (seed-member!
       conn
       member-id
       {:member/invite-code "compensation-code"
        :member/invite-expires-at expires-at
        :member/invite-status pending
        :member/invite-generation 1
        :member/invite-status-at issued-at})
      (let [result
            (run-acceptance
             conn
             member-id
             1
             (constantly requested-at)
             adapter)]
        (is (= {:member-invite/result :pending
                :keycloak/create-status :rejected
                :member-invite/compensation-status :begun
                :member-invite/release-status :released
                :member-invite/state
                {:status pending
                 :generation 5
                 :expires-at expires-at}}
               (select-keys
                result
                [:member-invite/result
                 :keycloak/create-status
                 :member-invite/compensation-status
                 :member-invite/release-status
                 :member-invite/state])))))))

(def service-requested-at #inst "2026-07-16T10:00:00.000-00:00")
(def service-expires-at #inst "2026-07-17T10:00:00.000-00:00")
(def service-expired-at #inst "2026-07-15T10:00:00.000-00:00")

(defn setup-request [conn invite-code]
  {:db (d/db conn)
   :datomic-conn conn
   :params {:invite-code invite-code}
   :system {:keycloak {:adapter :fake}}})

(defn thrown-reason [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (:reason (ex-data exception)))))

(deftest setup-account-rejects-an-expired-pending-code-before-running-workflow-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-setup-expired")
        runs (atom 0)]
    (seed-member!
     conn
     member-id
     {:member/invite-code "expired"
      :member/invite-expires-at service-expired-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at service-requested-at})
    (is (= {:reason :code-expired
            :runs 0}
           {:reason
            (thrown-reason
             #(workflows/setup-account!
               {:now (constantly service-requested-at)
                :accept-or-recover! (fn [_resources _input]
                                      (swap! runs inc))}
               (setup-request conn "expired")))
            :runs @runs}))))

(deftest setup-account-passes-the-workflow-contract-and-verifies-accepted-state-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-setup-accepted")
        invocation (atom nil)]
    (seed-member!
     conn
     member-id
     {:member/invite-code "accepted"
      :member/invite-expires-at service-expires-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at service-requested-at})
    (let [member
          (workflows/setup-account!
           {:now (constantly service-requested-at)
            :accept-or-recover!
            (fn [resources input]
              (reset! invocation {:resources resources :input input})
              @(d/transact
                conn
                [[:db/add [:member/member-id member-id]
                  :member/keycloak-id "keycloak-123"]
                 [:db/add [:member/member-id member-id]
                  :member/invite-status accepted]
                 [:db/add [:member/member-id member-id]
                  :member/invite-generation 2]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-code "accepted"]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-expires-at service-expires-at]])
              {:member-invite/result :accepted
               :member/member-id member-id
               :member-invite/finalize-status :finalized
               :member-invite/state
               {:status accepted
                :generation 2
                :keycloak-id "keycloak-123"}})}
           (setup-request conn "accepted"))]
      (is (= {:member {:member/member-id member-id
                       :member/keycloak-id "keycloak-123"}
              :input {:member/member-id member-id
                      :member-invite/resolved-generation 1
                      :member-invite/requested-at service-requested-at
                      :keycloak/group-name "Mitglieder"}
              :resources {:datomic-conn conn
                          :keycloak {:adapter :fake}
                          :clock? true}}
             {:member (select-keys member
                                   [:member/member-id :member/keycloak-id])
              :input (:input @invocation)
              :resources
              {:datomic-conn (get-in @invocation [:resources :datomic-conn])
               :keycloak (get-in @invocation [:resources :keycloak])
               :clock? (fn? (get-in @invocation [:resources :clock]))}})))))

(deftest setup-account-reuses-an-accepted-receipt-without-rerunning-the-workflow-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invite-setup-accepted-receipt")
        keycloak (fake-keycloak)
        original-create! (get-in keycloak [:adapter :create-user!])
        create-calls (atom 0)
        workflow-runs (atom 0)
        adapter
        (assoc (:adapter keycloak)
               :create-user!
               (fn [user-spec]
                 (swap! create-calls inc)
                 (original-create! user-spec)))
        deps
        {:now (constantly service-requested-at)
         :accept-or-recover!
         (fn [resources input]
           (swap! workflow-runs inc)
           (workflows/accept-or-recover! resources input))}
        request
        (fn []
          (assoc-in (setup-request conn "receipt")
                    [:system :keycloak]
                    adapter))]
    (seed-member!
     conn
     member-id
     {:member/invite-code "receipt"
      :member/invite-expires-at service-expires-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at service-requested-at})
    (let [first-member (workflows/setup-account! deps (request))
          second-member (workflows/setup-account! deps (request))]
      (is (= {:member-ids [member-id member-id]
              :workflow-runs 1
              :create-calls 1
              :state {:status accepted
                      :generation 5
                      :keycloak-id "created-user"}}
             {:member-ids [(:member/member-id first-member)
                           (:member/member-id second-member)]
              :workflow-runs @workflow-runs
              :create-calls @create-calls
              :state (domain/invitation-state (d/db conn) member-id)})))))

(deftest setup-account-preserves-workflow-error-diagnostics-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invite-setup-workflow-error")
        workflow-result
        (workflows/accept-or-recover!
         {}
         {:member/member-id member-id})]
    (seed-member!
     conn
     member-id
     {:member/invite-code "workflow-error"
      :member/invite-expires-at service-expires-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at service-requested-at})
    (let [exception
          (try
            (workflows/setup-account!
             {:now (constantly service-requested-at)
              :accept-or-recover!
              (fn [_resources _input] workflow-result)}
             (setup-request conn "workflow-error"))
            nil
            (catch clojure.lang.ExceptionInfo exception
              exception))]
      (is (= {:message "Member invitation acceptance workflow failed"
              :data (myc/workflow-error workflow-result)}
             {:message (ex-message exception)
              :data (ex-data exception)})))))

(deftest setup-account-maps-the-public-terminal-outcomes-test
  (doseq [[outcome context expected-reason]
          [[:pending
            {:member-invite/result :pending
             :member-invite/release-status :released
             :member-invite/state {:status pending :generation 2}}
            :acceptance-retry]
           [:retry
            {:member-invite/result :retry
             :member-invite/link-status :conflict
             :member-invite/state
             {:status :member.invite.status/creating :generation 3}}
            :acceptance-retry]
           [:stale
            {:member-invite/result :stale
             :member-invite/state {:status pending :generation 2}}
            :code-expired]
           [:operator-required
            {:member-invite/result :operator-required
             :member-invite/user-step :unsafe
             :member-invite/state
             {:status :member.invite.status/activating :generation 4}}
            :operator-required]]]
    (testing outcome
      (let [{:keys [conn member-id]}
            (tc/new-system (str "invite-setup-" (name outcome)))
            code (name outcome)]
        (seed-member!
         conn
         member-id
         {:member/invite-code code
          :member/invite-expires-at service-expires-at
          :member/invite-status pending
          :member/invite-generation 1
          :member/invite-status-at service-requested-at})
        (is (= expected-reason
               (thrown-reason
                #(workflows/setup-account!
                  {:now (constantly service-requested-at)
                   :accept-or-recover!
                   (fn [_resources _input]
                     (assoc context :member/member-id member-id))}
                  (setup-request conn code)))))))))

(deftest setup-account-recovers-a-committed-finalization-response-failure-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-setup-finalize-timeout")
        timeout (ex-info "Datomic response timed out" {:type :timeout})]
    (seed-member!
     conn
     member-id
     {:member/invite-code "committed"
      :member/invite-expires-at service-expires-at
      :member/invite-status pending
      :member/invite-generation 1
      :member/invite-status-at service-requested-at})
    (let [member
          (workflows/setup-account!
           {:now (constantly service-requested-at)
            :accept-or-recover!
            (fn [_resources _input]
              @(d/transact
                conn
                [[:db/add [:member/member-id member-id]
                  :member/keycloak-id "committed-user"]
                 [:db/add [:member/member-id member-id]
                  :member/invite-status accepted]
                 [:db/add [:member/member-id member-id]
                  :member/invite-generation 2]
                 [:db/add [:member/member-id member-id]
                  :member/invite-accepted-code-digest
                  (domain/accepted-receipt-digest "committed")]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-code "committed"]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-expires-at service-expires-at]])
              (throw timeout))}
           (setup-request conn "committed"))]
      (is (= {:member/member-id member-id
              :member/keycloak-id "committed-user"}
             (select-keys member
                          [:member/member-id :member/keycloak-id]))))))
