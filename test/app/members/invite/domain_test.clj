(ns app.members.invite.domain-test
  (:require
   [app.members.invite.domain :as domain]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [malli.core :as m]))

(def pending :member.invite.status/pending)
(def accepting :member.invite.status/accepting)
(def creating :member.invite.status/creating)
(def activating :member.invite.status/activating)
(def compensating :member.invite.status/compensating)
(def accepted :member.invite.status/accepted)
(def revoked :member.invite.status/revoked)

(def issued-at #inst "2026-07-16T08:00:00.000-00:00")
(def claimed-at #inst "2026-07-16T08:05:00.000-00:00")
(def linked-at #inst "2026-07-16T08:06:00.000-00:00")
(def completed-at #inst "2026-07-16T08:07:00.000-00:00")
(def expires-at #inst "2026-07-17T08:00:00.000-00:00")

(defn fixed-clock [inst]
  (constantly inst))

(defn resources [conn inst]
  {:datomic-conn conn
   :clock        (fixed-clock inst)})

(defn invitation-record [conn member-id]
  (let [member (d/entity (d/db conn) [:member/member-id member-id])
        status (:member/invite-status member)
        status (if (keyword? status) status (:db/ident status))]
    (cond-> {:status     status
             :generation (:member/invite-generation member)
             :status-at  (:member/invite-status-at member)}
      (:member/invite-code member)
      (assoc :code (:member/invite-code member))

      (:member/invite-expires-at member)
      (assoc :expires-at (:member/invite-expires-at member))

      (:member/keycloak-id member)
      (assoc :keycloak-id (:member/keycloak-id member))

      (:member/invite-accepted-code-digest member)
      (assoc :accepted-code-digest
             (:member/invite-accepted-code-digest member)))))

(defn issue-input [member-id code]
  {:member-id  member-id
   :code       code
   :expires-at expires-at})

(defn pending-state
  ([]
   (pending-state 1 expires-at))
  ([generation expiry]
   {:status     pending
    :generation generation
    :expires-at expiry}))

(defn seed-invitation! [conn member-id state]
  @(d/transact
    conn
    [(merge {:member/member-id        member-id
             :member/invite-status    (:status state)
             :member/invite-generation (:generation state)
             :member/invite-status-at issued-at}
            (when-let [code (:code state)]
              {:member/invite-code code})
            (when-let [expiry (:expires-at state)]
              {:member/invite-expires-at expiry})
            (when-let [keycloak-id (:keycloak-id state)]
              {:member/keycloak-id keycloak-id}))]))

(defn run-race [left right]
  (let [gate  (promise)
        left  (future @gate (left))
        right (future @gate (right))]
    (deliver gate true)
    [@left @right]))

(defn capture-outcome [operation success]
  (try
    (operation)
    success
    (catch Throwable _exception
      :conflict)))

(deftest invitation-schema-installs-direct-member-attributes
  (let [{:keys [conn]} (tc/new-system "member-invitation-schema")
        db             (d/db conn)
        expected
        {:member/invite-code
         {:value-type :db.type/string
          :unique     :db.unique/value
          :no-history true}
         :member/invite-expires-at
         {:value-type :db.type/instant
          :no-history true}
         :member/invite-status
         {:value-type :db.type/ref}
         :member/invite-generation
         {:value-type :db.type/long
          :no-history true}
         :member/invite-status-at
         {:value-type :db.type/instant}
         :member/invite-accepted-code-digest
         {:value-type :db.type/string
          :unique     :db.unique/value
          :no-history true}}]
    (doseq [[ident expected-metadata] expected]
      (let [attribute (d/pull db
                              [:db/ident
                               :db/noHistory
                               {:db/valueType [:db/ident]}
                               {:db/cardinality [:db/ident]}
                               {:db/unique [:db/ident]}]
                              ident)
            actual    (cond->
                       {:value-type (get-in attribute
                                            [:db/valueType :db/ident])}
                        (:db/noHistory attribute)
                        (assoc :no-history true)

                        (:db/unique attribute)
                        (assoc :unique (get-in attribute
                                               [:db/unique :db/ident])))]
        (is (= ident (:db/ident attribute)))
        (is (= :db.cardinality/one
               (get-in attribute [:db/cardinality :db/ident])))
        (is (= expected-metadata actual))))))

(deftest invitation-schema-installs-atomic-keycloak-guards
  (let [{:keys [conn]} (tc/new-system "member-invitation-guard-schema")
        db             (d/db conn)]
    (is (some? (:db/fn (d/entity db :member.invite/transact-if-unlinked))))
    (is (some? (:db/fn (d/entity db :member.invite/transact-profile-if-not-in-flight))))
    (is (some? (:db/fn (d/entity db :member/set-keycloak-id))))))

(deftest invitation-schema-installs-seven-status-idents-without-an-entity-model
  (let [{:keys [conn]} (tc/new-system "member-invitation-status-schema")
        db             (d/db conn)]
    (is (= #{pending accepting creating activating compensating accepted revoked}
           (into #{}
                 (keep (fn [ident]
                         (:db/ident (d/pull db [:db/ident] ident))))
                 [pending
                  accepting
                  creating
                  activating
                  compensating
                  accepted
                  revoked])))
    (is (nil? (d/pull db [:db/ident] :member/invitation)))
    (is (nil? (d/pull db [:db/ident] :member/invitation-id)))))

(deftest invitation-code-is-unique
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-code-unique")
        other-member-id          (random-uuid)]
    @(d/transact conn [{:member/member-id     member-id
                        :member/invite-code    "same-code"}
                       {:member/member-id other-member-id}])
    (is (thrown? Throwable
                 @(d/transact conn
                              [{:member/member-id  other-member-id
                                :member/invite-code "same-code"}])))))

(deftest issue-creates-the-initial-pending-generation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-issue")]
    (is (= {:outcome :issued :generation 1}
           (domain/issue!
            (resources conn issued-at)
            (issue-input member-id "initial-code"))))
    (is (= {:status     pending
            :generation 1
            :status-at  issued-at
            :code       "initial-code"
            :expires-at expires-at}
           (invitation-record conn member-id)))
    (is (= {:outcome :conflict}
           (domain/issue!
            (resources conn claimed-at)
            (issue-input member-id "replacement-code"))))
    (is (= "initial-code"
           (:code (invitation-record conn member-id))))))

(deftest invitation-times-require-inst-values
  (testing "invitation state accepts only t/inst values"
    (is (= {:t-inst            true
            :java-time-instant false}
           {:t-inst
            (m/validate domain/invitation-state-schema (pending-state))
            :java-time-instant
            (m/validate
             domain/invitation-state-schema
             (pending-state
              1
              (java.time.Instant/parse "2026-07-17T08:00:00Z")))})))

  (testing "the transition clock rejects java.time.Instant"
    (let [{:keys [conn member-id]}
          (tc/new-system "member-invitation-clock-java-time")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"clock must return an inst"
           (domain/issue!
            {:datomic-conn conn
             :clock #(java.time.Instant/parse "2026-07-16T08:00:00Z")}
            (issue-input member-id "java-time-clock"))))))

  (testing "claim timestamps reject java.time.Instant"
    (doseq [[label requested-at state]
            [["requested-at"
              (java.time.Instant/parse "2026-07-16T08:05:00Z")
              (pending-state)]
             ["expires-at"
              claimed-at
              (pending-state
               1
               (java.time.Instant/parse "2026-07-17T08:00:00Z"))]]]
      (testing label
        (let [{:keys [conn member-id]}
              (tc/new-system (str "member-invitation-claim-java-time-" label))]
          (seed-invitation! conn member-id
                            (assoc (pending-state) :code (str label "-code")))
          (is (= {:outcome :conflict}
                 (domain/claim!
                  (resources conn claimed-at)
                  {:member-id member-id
                   :requested-at requested-at
                   :state state}))))))))

(deftest issue-rejects-a-member-that-already-has-a-keycloak-account
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-issue-keycloak")]
    @(d/transact conn [{:member/member-id   member-id
                        :member/keycloak-id "existing-user"}])
    (is (= {:outcome :conflict}
           (domain/issue!
            (resources conn issued-at)
            (issue-input member-id "unused-code"))))))

(deftest issue-and-a-generic-keycloak-link-cannot-both-win
  (let [{:keys [conn member-id]}
        (tc/new-system "member-invitation-issue-keycloak-race")
        [issue-outcome link-outcome]
        (run-race
         #(-> (domain/issue!
               (resources conn issued-at)
               (issue-input member-id "race-code"))
              :outcome)
         #(capture-outcome
           (fn []
             @(d/transact conn
                          [[:member/set-keycloak-id
                            member-id
                            "external-user"]]))
           :linked))
        record (invitation-record conn member-id)]
    (is (contains? #{[:issued :conflict]
                     [:conflict :linked]}
                   [issue-outcome link-outcome]))
    (if (= :issued issue-outcome)
      (do
        (is (= :conflict link-outcome))
        (is (= pending (:status record)))
        (is (nil? (:keycloak-id record))))
      (do
        (is (= :conflict issue-outcome))
        (is (= :linked link-outcome))
        (is (= "external-user" (:keycloak-id record)))
        (is (nil? (:status record)))))))

(deftest generic-keycloak-links-cannot-enter-active-invitation-states
  (doseq [[label database-suffix state transition expected-outcome]
          [["pending reissue"
            "reissue"
            (assoc (pending-state) :code "reissue-code")
            #(domain/reissue!
              (resources %1 claimed-at)
              {:member-id %2
               :state (pending-state)
               :code "new-code"
               :expires-at expires-at})
            :reissued]
           ["pending claim"
            "claim"
            (assoc (pending-state) :code "claim-code")
            #(domain/claim!
              (resources %1 claimed-at)
              {:member-id %2
               :requested-at claimed-at
               :state (pending-state)})
            :claimed]
           ["accepting create"
            "create"
            {:status accepting
             :generation 2
             :code "create-code"
             :expires-at expires-at}
            #(domain/begin-create!
              (resources %1 claimed-at)
              {:member-id %2
               :attempt-generation 2})
            :create-begun]
           ["accepting link"
            "link"
            {:status accepting
             :generation 2
             :code "link-code"
             :expires-at expires-at}
            #(domain/link-keycloak-user!
              (resources %1 claimed-at)
              {:member-id %2
               :expected-status accepting
               :expected-generation 2
               :attempt-generation 2
               :keycloak-user-id "workflow-user"})
            :linked]]]
    (testing label
      (let [{:keys [conn member-id]}
            (tc/new-system
             (str "member-invitation-keycloak-race-" database-suffix))]
        (seed-invitation! conn member-id state)
        (let [[transition-outcome generic-outcome]
              (run-race
               #(-> (transition conn member-id) :outcome)
               #(capture-outcome
                 (fn []
                   @(d/transact conn
                                [[:member/set-keycloak-id
                                  member-id
                                  "external-user"]]))
                 :linked))]
          (is (= expected-outcome transition-outcome))
          (is (= :conflict generic-outcome))
          (is (not= "external-user"
                    (:keycloak-id (invitation-record conn member-id)))))))))

(deftest generic-keycloak-link-changes-obey-invitation-lifecycle
  (testing "same-id writes remain idempotent while acceptance is active"
    (doseq [status [pending accepting creating activating compensating]]
      (let [{:keys [conn member-id]}
            (tc/new-system (str "member-keycloak-same-id-" (name status)))]
        (seed-invitation! conn member-id {:status status
                                          :generation 2
                                          :keycloak-id "owned-user"})
        (is (= :unchanged
               (capture-outcome
                #(deref
                  (d/transact conn
                              [[:member/set-keycloak-id
                                member-id
                                "owned-user"]]))
                :unchanged)))
        (is (= :conflict
               (capture-outcome
                #(deref
                  (d/transact conn
                              [[:member/set-keycloak-id
                                member-id
                                "different-user"]]))
                :changed)))
        (is (= :conflict
               (capture-outcome
                #(deref
                  (d/transact conn
                              [[:member/set-keycloak-id member-id nil]]))
                :cleared))))))

  (testing "links may change and clear after terminal invitations"
    (doseq [status [accepted revoked]]
      (let [{:keys [conn member-id]}
            (tc/new-system (str "member-keycloak-terminal-" (name status)))]
        (seed-invitation! conn member-id {:status status
                                          :generation 5
                                          :keycloak-id "old-user"})
        (is (= :changed
               (capture-outcome
                #(deref
                  (d/transact conn
                              [[:member/set-keycloak-id
                                member-id
                                "new-user"]]))
                :changed)))
        (is (= "new-user"
               (:keycloak-id (invitation-record conn member-id))))
        (is (= :cleared
               (capture-outcome
                #(deref
                  (d/transact conn
                              [[:member/set-keycloak-id member-id nil]]))
                :cleared)))
        (is (nil? (:keycloak-id (invitation-record conn member-id))))))))

(deftest reissue-rotates-pending-and-revoked-invitations
  (do
    (testing "pending invitation"
      (let [{:keys [conn member-id]} (tc/new-system "member-invitation-reissue")
            new-expiry #inst "2026-07-18T08:00:00.000-00:00"]
        (seed-invitation! conn member-id
                          (assoc (pending-state) :code "old-code"))
        (is (= {:outcome :reissued :generation 2}
               (domain/reissue!
                (resources conn claimed-at)
                {:member-id  member-id
                 :state      (pending-state)
                 :code       "new-code"
                 :expires-at new-expiry})))
        (is (= {:status     pending
                :generation 2
                :status-at  claimed-at
                :code       "new-code"
                :expires-at new-expiry}
               (invitation-record conn member-id)))))
    (testing "revoked invitation"
      (let [{:keys [conn member-id]} (tc/new-system "member-invitation-reissue-revoked")]
        (seed-invitation! conn member-id {:status revoked :generation 4})
        (is (= {:outcome :reissued :generation 5}
               (domain/reissue!
                (resources conn claimed-at)
                {:member-id  member-id
                 :state      {:status revoked :generation 4}
                 :code       "restored-code"
                 :expires-at expires-at})))
        (is (= {:status     pending
                :generation 5
                :status-at  claimed-at
                :code       "restored-code"
                :expires-at expires-at}
               (invitation-record conn member-id)))))))

(deftest reissue-refuses-in-flight-and-stale-state
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-reissue-stale")]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "attempt-code"
                       :expires-at expires-at})
    (doseq [state [(pending-state)
                   {:status accepting :generation 2}]]
      (is (= {:outcome :conflict}
             (domain/reissue!
              (resources conn claimed-at)
              {:member-id  member-id
               :state      state
               :code       "unsafe-code"
               :expires-at expires-at}))))))

(deftest revoke-closes-only-the-matching-pending-invitation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-revoke")]
    (seed-invitation! conn member-id
                      (assoc (pending-state) :code "revoke-code"))
    (is (= {:outcome :revoked :generation 2}
           (domain/revoke!
            (resources conn claimed-at)
            {:member-id member-id
             :state     (pending-state)})))
    (is (= {:status revoked :generation 2 :status-at claimed-at}
           (invitation-record conn member-id)))
    (is (= {:outcome :conflict}
           (domain/revoke!
            (resources conn linked-at)
            {:member-id member-id
             :state     (pending-state)})))))

(deftest claim-is-strictly-before-expiry-and-increments-generation
  (do
    (testing "request before expiry"
      (let [{:keys [conn member-id]} (tc/new-system "member-invitation-claim")]
        (seed-invitation! conn member-id
                          (assoc (pending-state) :code "claim-code"))
        (is (= {:outcome :claimed :generation 2}
               (domain/claim!
                (resources conn claimed-at)
                {:member-id   member-id
                 :requested-at claimed-at
                 :state       (pending-state)})))
        (is (= {:status     accepting
                :generation 2
                :status-at  claimed-at
                :code       "claim-code"
                :expires-at expires-at}
               (invitation-record conn member-id)))))
    (testing "request exactly at expiry"
      (let [{:keys [conn member-id]} (tc/new-system "member-invitation-claim-boundary")]
        (seed-invitation! conn member-id
                          (assoc (pending-state 1 expires-at)
                                 :code "expired-code"))
        (is (= {:outcome :conflict}
               (domain/claim!
                (resources conn completed-at)
                {:member-id   member-id
                 :requested-at expires-at
                 :state       (pending-state 1 expires-at)})))))))

(deftest claim-races-safely-with-reissue-and-revoke
  (do
    (testing "claim versus reissue"
      (let [{:keys [conn member-id]} (tc/new-system "member-invitation-claim-reissue-race")
            state                    (pending-state)]
        (seed-invitation! conn member-id (assoc state :code "race-code"))
        (let [results  (run-race
                        #(domain/claim!
                          (resources conn claimed-at)
                          {:member-id member-id
                           :requested-at claimed-at
                           :state state})
                        #(domain/reissue!
                          (resources conn linked-at)
                          {:member-id member-id
                           :state state
                           :code "race-reissued-code"
                           :expires-at expires-at}))
              outcomes (set (map :outcome results))]
          (is (contains? #{#{:claimed :conflict}
                           #{:reissued :conflict}}
                         outcomes))
          (is (= (if (contains? outcomes :claimed)
                   {:status accepting
                    :generation 2
                    :status-at claimed-at
                    :code "race-code"
                    :expires-at expires-at}
                   {:status pending
                    :generation 2
                    :status-at linked-at
                    :code "race-reissued-code"
                    :expires-at expires-at})
                 (invitation-record conn member-id))))))
    (testing "claim versus revoke"
      (let [{:keys [conn member-id]} (tc/new-system "member-invitation-claim-revoke-race")
            state                    (pending-state)]
        (seed-invitation! conn member-id (assoc state :code "race-code"))
        (let [results  (run-race
                        #(domain/claim!
                          (resources conn claimed-at)
                          {:member-id member-id
                           :requested-at claimed-at
                           :state state})
                        #(domain/revoke!
                          (resources conn linked-at)
                          {:member-id member-id
                           :state state}))
              outcomes (set (map :outcome results))]
          (is (contains? #{#{:claimed :conflict}
                           #{:revoked :conflict}}
                         outcomes))
          (is (= (if (contains? outcomes :claimed)
                   {:status accepting
                    :generation 2
                    :status-at claimed-at
                    :code "race-code"
                    :expires-at expires-at}
                   {:status revoked
                    :generation 2
                    :status-at linked-at})
                 (invitation-record conn member-id))))))))

(deftest two-claimers-have-one-winner
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-claim-race")
        state                    (pending-state)
        claim                    #(domain/claim!
                                   (resources conn claimed-at)
                                   {:member-id member-id
                                    :requested-at claimed-at
                                    :state state})]
    (seed-invitation! conn member-id (assoc state :code "claim-race-code"))
    (is (= #{:claimed :conflict}
           (set (map :outcome (run-race claim claim)))))
    (is (= {:status accepting
            :generation 2
            :status-at claimed-at
            :code "claim-race-code"
            :expires-at expires-at}
           (invitation-record conn member-id)))))

(deftest two-create-callers-have-one-owner
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-create-race")
        begin-create #(domain/begin-create!
                       (resources conn linked-at)
                       {:member-id member-id
                        :attempt-generation 2})]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "create-race-code"
                       :expires-at expires-at})
    (is (= #{:create-begun :conflict}
           (set (map :outcome (run-race begin-create begin-create)))))
    (is (= {:status creating
            :generation 3
            :status-at linked-at
            :code "create-race-code"
            :expires-at expires-at}
           (invitation-record conn member-id)))))

(deftest begin-create-races-with-accepting-compensation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-create-compensate-race")]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "create-compensate-code"
                       :expires-at expires-at})
    (let [results
          (run-race
           #(domain/begin-create!
             (resources conn linked-at)
             {:member-id member-id
              :attempt-generation 2})
           #(domain/begin-compensation!
             (resources conn linked-at)
             {:member-id member-id
              :expected-status accepting
              :expected-generation 2
              :attempt-generation 2}))
          outcomes (set (map :outcome results))]
      (is (contains? #{#{:create-begun :conflict}
                       #{:compensation-begun :conflict}}
                     outcomes))
      (is (= {:status (if (contains? outcomes :create-begun)
                        creating
                        compensating)
              :generation 3
              :status-at linked-at
              :code "create-compensate-code"
              :expires-at expires-at}
             (invitation-record conn member-id))))))

(deftest creating-state-links-with-current-and-attempt-generations
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-link-creating")]
    (seed-invitation! conn member-id
                      {:status creating
                       :generation 8
                       :code "creating-link-code"
                       :expires-at expires-at})
    (doseq [input [{:member-id member-id
                    :expected-status creating
                    :expected-generation 7
                    :attempt-generation 7
                    :keycloak-user-id "stale-generation-user"}
                   {:member-id member-id
                    :expected-status creating
                    :expected-generation 8
                    :attempt-generation 7
                    :keycloak-user-id "wrong-attempt-user"}]]
      (is (= {:outcome :conflict}
             (domain/link-keycloak-user!
              (resources conn linked-at)
              input))))
    (is (= {:outcome :linked :generation 9}
           (domain/link-keycloak-user!
            (resources conn linked-at)
            {:member-id member-id
             :expected-status creating
             :expected-generation 8
             :attempt-generation 8
             :keycloak-user-id "creating-user"})))
    (is (= {:status activating
            :generation 9
            :status-at linked-at
            :code "creating-link-code"
            :expires-at expires-at
            :keycloak-id "creating-user"}
           (invitation-record conn member-id)))))

(deftest creating-state-begins-compensation-for-its-attempt
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-compensate-creating")]
    (seed-invitation! conn member-id
                      {:status creating
                       :generation 3
                       :code "creating-compensate-code"
                       :expires-at expires-at})
    (doseq [input [{:member-id member-id
                    :expected-status accepting
                    :expected-generation 3
                    :attempt-generation 2}
                   {:member-id member-id
                    :expected-status creating
                    :expected-generation 3
                    :attempt-generation 2}]]
      (is (= {:outcome :conflict}
             (domain/begin-compensation!
              (resources conn linked-at)
              input))))
    (is (= {:outcome :compensation-begun :generation 4}
           (domain/begin-compensation!
            (resources conn linked-at)
            {:member-id member-id
             :expected-status creating
             :expected-generation 3
             :attempt-generation 3})))
    (is (= {:status compensating
            :generation 4
            :status-at linked-at
            :code "creating-compensate-code"
            :expires-at expires-at}
           (invitation-record conn member-id)))))

(deftest link-and-compensation-race-for-exclusive-ownership
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-link-compensate-race")]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "attempt-code"
                       :expires-at expires-at})
    (let [results  (run-race
                    #(domain/link-keycloak-user!
                      (resources conn linked-at)
                      {:member-id member-id
                       :expected-status accepting
                       :expected-generation 2
                       :attempt-generation 2
                       :keycloak-user-id "attempt-user"})
                    #(domain/begin-compensation!
                      (resources conn linked-at)
                      {:member-id member-id
                       :expected-status accepting
                       :expected-generation 2
                       :attempt-generation 2}))
          outcomes (set (map :outcome results))]
      (is (contains? #{#{:linked :conflict}
                       #{:compensation-begun :conflict}}
                     outcomes))
      (is (= (if (contains? outcomes :linked)
               {:status activating
                :generation 3
                :status-at linked-at
                :code "attempt-code"
                :expires-at expires-at
                :keycloak-id "attempt-user"}
               {:status compensating
                :generation 3
                :status-at linked-at
                :code "attempt-code"
                :expires-at expires-at})
             (invitation-record conn member-id))))))

(deftest two-linkers-cannot-replace-the-winning-keycloak-id
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-link-race")
        _       (seed-invitation! conn member-id
                                  {:status accepting
                                   :generation 2
                                   :code "link-race-code"
                                   :expires-at expires-at})
        results (run-race
                 #(assoc
                   (domain/link-keycloak-user!
                    (resources conn linked-at)
                    {:member-id member-id
                     :expected-status accepting
                     :expected-generation 2
                     :attempt-generation 2
                     :keycloak-user-id "linker-a"})
                   :candidate "linker-a")
                 #(assoc
                   (domain/link-keycloak-user!
                    (resources conn linked-at)
                    {:member-id member-id
                     :expected-status accepting
                     :expected-generation 2
                     :attempt-generation 2
                     :keycloak-user-id "linker-b"})
                   :candidate "linker-b"))
        winner  (:candidate (first (filter #(= :linked (:outcome %))
                                           results)))
        loser   (if (= "linker-a" winner) "linker-b" "linker-a")]
    (is (= #{:linked :conflict}
           (set (map :outcome results))))
    (is (= winner (:keycloak-id (invitation-record conn member-id))))
    (is (= {:outcome :conflict}
           (domain/link-keycloak-user!
            (resources conn completed-at)
            {:member-id member-id
             :expected-status accepting
             :expected-generation 2
             :attempt-generation 2
             :keycloak-user-id loser})))
    (is (= winner (:keycloak-id (invitation-record conn member-id))))))

(deftest two-compensators-have-one-owner
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-compensation-race")
        compensate #(domain/begin-compensation!
                     (resources conn linked-at)
                     {:member-id member-id
                      :expected-status accepting
                      :expected-generation 2
                      :attempt-generation 2})]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "compensation-race-code"
                       :expires-at expires-at})
    (is (= #{:compensation-begun :conflict}
           (set (map :outcome (run-race compensate compensate)))))
    (is (= {:status compensating
            :generation 3
            :status-at linked-at
            :code "compensation-race-code"
            :expires-at expires-at}
           (invitation-record conn member-id)))))

(deftest activation-finalizes-only-the-exact-generation-and-user
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-finalize")]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "finalize-code"
                       :expires-at expires-at})
    (is (= {:outcome :linked :generation 3}
           (domain/link-keycloak-user!
            (resources conn linked-at)
            {:member-id member-id
             :expected-status accepting
             :expected-generation 2
             :attempt-generation 2
             :keycloak-user-id "finalize-user"})))
    (doseq [input [{:member-id member-id
                    :activation-generation 2
                    :keycloak-user-id "finalize-user"}
                   {:member-id member-id
                    :activation-generation 3
                    :keycloak-user-id "other-user"}]]
      (is (= {:outcome :conflict}
             (domain/finalize!
              (resources conn completed-at)
              input))))
    (is (nil? (:accepted-code-digest
               (invitation-record conn member-id))))
    (is (= {:outcome :finalized :generation 4}
           (domain/finalize!
            (resources conn completed-at)
            {:member-id member-id
             :activation-generation 3
             :keycloak-user-id "finalize-user"})))
    (is (= {:status accepted
            :generation 4
            :status-at completed-at
            :keycloak-id "finalize-user"
            :accepted-code-digest
            (domain/accepted-receipt-digest "finalize-code")}
           (invitation-record conn member-id)))))

(deftest compensation-releases-only-the-exact-generation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-release")]
    (seed-invitation! conn member-id
                      {:status accepting
                       :generation 2
                       :code "release-code"
                       :expires-at expires-at})
    (is (= {:outcome :compensation-begun :generation 3}
           (domain/begin-compensation!
            (resources conn linked-at)
            {:member-id member-id
             :expected-status accepting
             :expected-generation 2
             :attempt-generation 2})))
    (is (= {:outcome :conflict}
           (domain/release!
            (resources conn completed-at)
            {:member-id member-id
             :compensation-generation 2})))
    (is (= {:outcome :released :generation 4}
           (domain/release!
            (resources conn completed-at)
            {:member-id member-id
             :compensation-generation 3})))
    (is (= {:status     pending
            :generation 4
            :status-at  completed-at
            :code       "release-code"
            :expires-at expires-at}
           (invitation-record conn member-id)))))

(deftest reads-return-only-the-safe-workflow-projections
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-read")]
    @(d/transact
      conn
      [{:member/member-id         member-id
        :member/name              "Ada Lovelace"
        :member/email             "ada@example.test"
        :member/username          "ada_l"
        :member/invite-code       "never-return-this"
        :member/invite-expires-at expires-at
        :member/invite-status     compensating
        :member/invite-generation 7}])
    (is (= {:status compensating
            :generation 7
            :expires-at expires-at}
           (domain/state (d/db conn) member-id)))
    (is (= {:username "ada_l"
            :email "ada@example.test"
            :first-name "Ada Lovelace"}
           (domain/keycloak-profile (d/db conn) member-id)))))

(deftest keycloak-profile-rejects-invalid-provisioning-fields
  (doseq [[label member]
          [["blank name"
            {:member/name ""
             :member/email "alice@example.com"
             :member/username "alice.example"}]
           ["invalid email"
            {:member/name "Alice Example"
             :member/email "not-an-email"
             :member/username "alice.example"}]
           ["invalid username"
            {:member/name "Alice Example"
             :member/email "alice@example.com"
             :member/username "bad username"}]]]
    (testing label
      (let [{:keys [conn member-id]}
            (tc/new-system (str "member-invitation-profile-" label))]
        @(d/transact conn [(assoc member :member/member-id member-id)])
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"invalid Keycloak profile"
             (domain/keycloak-profile (d/db conn) member-id)))))))

(deftest non-cas-transaction-failures-escape
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-non-cas")
        other-member-id          (random-uuid)]
    @(d/transact conn [{:member/member-id other-member-id}])
    (is (= {:outcome :issued :generation 1}
           (domain/issue!
            (resources conn issued-at)
            (issue-input member-id "duplicate-code"))))
    (is (thrown? Throwable
                 (domain/issue!
                  (resources conn issued-at)
                  (issue-input other-member-id "duplicate-code"))))))

(deftest accepted-receipt-digest-test
  (testing "the receipt is deterministic, domain-separated, and never retains the bearer"
    (let [bearer "invite-code"
          receipt (domain/accepted-receipt-digest bearer)]
      (is (= 64 (count receipt)))
      (is (re-matches #"[0-9a-f]{64}" receipt))
      (is (= receipt
             (domain/accepted-receipt-digest bearer)))
      (is (not (str/includes? receipt bearer)))))

  (testing "blank values are not valid invitation bearers"
    (is (thrown? clojure.lang.ExceptionInfo
                 (domain/accepted-receipt-digest "  ")))))
