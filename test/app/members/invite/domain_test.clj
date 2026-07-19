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
(def requested-at #inst "2026-07-16T08:05:00.000-00:00")
(def transitioned-at #inst "2026-07-16T08:06:00.000-00:00")
(def expires-at #inst "2026-08-15T08:00:00.000-00:00")

(defn seed-member! [conn member-id]
  @(d/transact
    conn
    [{:member/member-id member-id
      :member/name "Alice Example"
      :member/email "alice@example.com"
      :member/username "alice.example"}]))

(defn apply-plan! [conn plan]
  @(d/transact conn (:tx-data plan)))

(defn invitation-record [db member-id]
  (let [member (d/entity db [:member/member-id member-id])
        status (:member/invite-status member)]
    (cond-> {:status (if (keyword? status) status (:db/ident status))
             :generation (:member/invite-generation member)
             :status-at (:member/invite-status-at member)}
      (:member/invite-code member)
      (assoc :code (:member/invite-code member))

      (:member/invite-expires-at member)
      (assoc :expires-at (:member/invite-expires-at member))

      (:member/keycloak-id member)
      (assoc :keycloak-id (:member/keycloak-id member))

      (:member/invite-accepted-code-digest member)
      (assoc :accepted-code-digest
             (:member/invite-accepted-code-digest member)))))

(deftest domain-registry-resolves-invitation-schemas-test
  (is (true?
       (m/validate
        ::domain/invitation-state
        {:status pending
         :generation 1
         :expires-at expires-at}
        {:registry domain/registry})))
  (is (true?
       (m/validate
        ::domain/invitation-state
        {:status pending
         :generation 1
         :expires-at (java.time.Instant/parse "2026-08-15T08:00:00Z")}
        {:registry domain/registry})))
  (is (true?
       (m/validate
        ::domain/keycloak-user-spec
        {:username "alice.example"
         :email "alice@example.com"
         :first-name "Alice Example"
         :enabled? false
         :email-verified? true
         :attributes {"member-id" ["member-1"]}}
        {:registry domain/registry}))))

(deftest invitation-expiry-is-thirty-days-after-issue-test
  (is (= expires-at (domain/invitation-expiry issued-at))))

(deftest create-invited-member-tx-creates-one-complete-record-test
  (let [{:keys [conn]} (tc/new-system "invite-domain-create-member")
        member-id (random-uuid)
        form {:name "Alice Example"
              :nick "alice"
              :email "alice@example.com"
              :username "alice.example"
              :phone "+43677123456"
              :section-name "Sopran"
              :active true}
        invitation {:code "initial-code"
                    :expires-at expires-at
                    :transitioned-at issued-at}
        ledger-id (random-uuid)]
    (is (true?
         (m/validate ::domain/member-invite-form
                     form
                     {:registry domain/registry})))
    @(d/transact conn [{:section/name "Sopran"}])
    (apply-plan!
     conn
     (domain/create-invited-member-tx
      form invitation member-id ledger-id nil))
    (let [db (d/db conn)
          member (d/entity db [:member/member-id member-id])
          ledger (d/q '[:find (pull ?ledger [:ledger/balance]) .
                        :in $ ?member-id
                        :where
                        [?member :member/member-id ?member-id]
                        [?ledger :ledger/owner ?member]]
                      db
                      member-id)]
      (is (= {:member/member-id member-id
              :member/name "Alice Example"
              :member/nick "alice"
              :member/email "alice@example.com"
              :member/username "alice.example"
              :member/phone "+43677123456"
              :member/active? true
              :section/name "Sopran"
              :ledger/balance 0
              :invitation {:status pending
                           :generation 1
                           :expires-at expires-at}}
             {:member/member-id (:member/member-id member)
              :member/name (:member/name member)
              :member/nick (:member/nick member)
              :member/email (:member/email member)
              :member/username (:member/username member)
              :member/phone (:member/phone member)
              :member/active? (:member/active? member)
              :section/name (:section/name (:member/section member))
              :ledger/balance (:ledger/balance ledger)
              :invitation (domain/invitation-state db member-id)})))))

(deftest invitation-routing-predicates-describe-current-durable-state-test
  (let [member-id (random-uuid)
        data (fn [state resolved-generation]
               {:member/member-id member-id
                :member-invite/resolved-generation resolved-generation
                :member-invite/requested-at requested-at
                :member-invite/state state})
        current #(data % (:generation %))
        pending-data (current {:status pending
                               :generation 1
                               :expires-at expires-at})
        accepting-data (current {:status accepting :generation 2})
        creating-data (current {:status creating :generation 3})
        activating-data (current {:status activating
                                  :generation 4
                                  :keycloak-id "keycloak-user"})
        cleanup-data (current {:status compensating :generation 4})
        accepted-data (current {:status accepted
                                :generation 5
                                :keycloak-id "keycloak-user"})]
    (is (= {:claim true
            :provision true
            :configure true
            :activate true
            :cleanup true
            :accepted true}
           {:claim (domain/claimable-invitation? pending-data)
            :provision (domain/invitation-ready-for-provisioning? accepting-data)
            :configure (domain/invitation-ready-for-configuration? creating-data)
            :activate (domain/invitation-ready-for-activation? activating-data)
            :cleanup (domain/invitation-ready-for-cleanup? cleanup-data)
            :accepted (domain/accepted-invitation? accepted-data)}))
    (testing "stale, expired, and revoked invitations match no active route"
      (is (false?
           (domain/claimable-invitation?
            (data {:status pending
                   :generation 2
                   :expires-at expires-at}
                  1))))
      (is (false?
           (domain/claimable-invitation?
            (current {:status pending
                      :generation 1
                      :expires-at requested-at}))))
      (let [revoked-data (current {:status revoked :generation 2})]
        (is (every? false?
                    [(domain/claimable-invitation? revoked-data)
                     (domain/invitation-ready-for-provisioning? revoked-data)
                     (domain/invitation-ready-for-configuration? revoked-data)
                     (domain/invitation-ready-for-activation? revoked-data)
                     (domain/invitation-ready-for-cleanup? revoked-data)
                     (domain/accepted-invitation? revoked-data)]))))))

(deftest reissue-and-revoke-decisions-use-current-generation-test
  (is (= :reissue
         (domain/reissue-step {:status pending :generation 2} 2)))
  (is (= :reissue
         (domain/reissue-step {:status revoked :generation 3} 3)))
  (is (= :stale
         (domain/reissue-step {:status pending :generation 3} 2)))
  (is (= :revoke
         (domain/revoke-step {:status pending :generation 2} 2)))
  (is (= :stale
         (domain/revoke-step {:status accepting :generation 2} 2))))

(deftest lifecycle-transaction-values-can-complete-acceptance-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-domain-lifecycle")]
    (seed-member! conn member-id)
    (apply-plan!
     conn
     (domain/issue-tx
      (d/db conn)
      {:member-id member-id
       :code "accept-code"
       :expires-at expires-at
       :transitioned-at issued-at}))
    (let [pending-state (domain/invitation-state (d/db conn) member-id)]
      (apply-plan!
       conn
       (domain/claim-tx
        (d/db conn)
        {:member-id member-id
         :state pending-state
         :requested-at requested-at
         :transitioned-at requested-at})))
    (let [accepting-state (domain/invitation-state (d/db conn) member-id)]
      (apply-plan!
       conn
       (domain/begin-create-tx
        (d/db conn)
        {:member-id member-id
         :state accepting-state
         :transitioned-at transitioned-at})))
    (let [creating-state (domain/invitation-state (d/db conn) member-id)]
      (apply-plan!
       conn
       (domain/link-keycloak-user-tx
        (d/db conn)
        {:member-id member-id
         :state creating-state
         :keycloak-user-id "keycloak-user"
         :transitioned-at transitioned-at})))
    (let [activating-state (domain/invitation-state (d/db conn) member-id)]
      (apply-plan!
       conn
       (domain/finalize-tx
        (d/db conn)
        {:member-id member-id
         :state activating-state
         :keycloak-user-id "keycloak-user"
         :transitioned-at transitioned-at})))
    (is (= {:status accepted
            :generation 5
            :status-at transitioned-at
            :keycloak-id "keycloak-user"
            :accepted-code-digest
            (domain/accepted-receipt-digest "accept-code")}
           (invitation-record (d/db conn) member-id)))))

(deftest compensation-transaction-values-clean-up-and-return-to-pending-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-domain-compensation")]
    (seed-member! conn member-id)
    (doseq [plan-fn [(fn [db]
                       (domain/issue-tx
                        db
                        {:member-id member-id
                         :code "cleanup-code"
                         :expires-at expires-at
                         :transitioned-at issued-at}))
                     (fn [db]
                       (domain/claim-tx
                        db
                        {:member-id member-id
                         :state (domain/invitation-state db member-id)
                         :requested-at requested-at
                         :transitioned-at requested-at}))
                     (fn [db]
                       (domain/begin-create-tx
                        db
                        {:member-id member-id
                         :state (domain/invitation-state db member-id)
                         :transitioned-at transitioned-at}))
                     (fn [db]
                       (domain/begin-compensation-tx
                        db
                        {:member-id member-id
                         :state (domain/invitation-state db member-id)
                         :transitioned-at transitioned-at}))
                     (fn [db]
                       (domain/release-tx
                        db
                        {:member-id member-id
                         :state (domain/invitation-state db member-id)
                         :transitioned-at transitioned-at}))]]
      (apply-plan! conn (plan-fn (d/db conn))))
    (is (= {:status pending
            :generation 5
            :status-at transitioned-at
            :code "cleanup-code"
            :expires-at expires-at}
           (invitation-record (d/db conn) member-id)))))

(deftest reissue-and-revoke-transaction-values-rotate-and-remove-the-code-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-domain-reissue-revoke")]
    (seed-member! conn member-id)
    (apply-plan!
     conn
     (domain/issue-tx
      (d/db conn)
      {:member-id member-id
       :code "old-code"
       :expires-at expires-at
       :transitioned-at issued-at}))
    (apply-plan!
     conn
     (domain/reissue-tx
      (d/db conn)
      {:member-id member-id
       :state (domain/invitation-state (d/db conn) member-id)
       :code "new-code"
       :expires-at expires-at
       :transitioned-at transitioned-at}))
    (is (= {:status pending
            :generation 2
            :status-at transitioned-at
            :code "new-code"
            :expires-at expires-at}
           (invitation-record (d/db conn) member-id)))
    (apply-plan!
     conn
     (domain/revoke-tx
      (d/db conn)
      {:member-id member-id
       :state (domain/invitation-state (d/db conn) member-id)
       :transitioned-at transitioned-at}))
    (is (= {:status revoked
            :generation 3
            :status-at transitioned-at}
           (invitation-record (d/db conn) member-id)))))

(deftest keycloak-values-and-decisions-are-pure-domain-data-test
  (let [member-id (random-uuid)
        markers (domain/attempt-markers member-id 3)
        disabled-user {:id "user-1"
                       :username "alice.example"
                       :email "alice@example.com"
                       :enabled? false
                       :attributes markers}
        enabled-user (assoc disabled-user :enabled? true)]
    (is (= {"probematic-member-id" [(str member-id)]
            "probematic-invite-generation" ["3"]}
           markers))
    (is (= {:username "alice.example"
            :email "alice@example.com"
            :first-name "Alice Example"
            :enabled? false
            :email-verified? true
            :attributes markers}
           (domain/keycloak-user-spec
            {:username "alice.example"
             :email "alice@example.com"
             :first-name "Alice Example"}
            markers)))
    (is (= :configure
           (domain/creating-user-step member-id 3 disabled-user)))
    (is (= :unsafe
           (domain/creating-user-step member-id 3 enabled-user)))
    (is (= :enable
           (domain/activating-user-step member-id 3 disabled-user)))
    (is (= :finalize
           (domain/activating-user-step member-id 3 enabled-user)))
    (is (= :delete
           (domain/compensation-user-step member-id 3 disabled-user)))
    (is (= :unsafe
           (domain/compensation-user-step member-id 3 enabled-user)))))

(deftest invitation-projections-exclude-the-bearer-code-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-domain-projections")]
    @(d/transact
      conn
      [{:member/member-id member-id
        :member/name "Alice Example"
        :member/email "alice@example.com"
        :member/username "alice.example"
        :member/invite-code "secret-code"
        :member/invite-expires-at expires-at
        :member/invite-status pending
        :member/invite-generation 1}])
    (is (= {:status pending
            :generation 1
            :expires-at expires-at}
           (domain/invitation-state (d/db conn) member-id)))
    (is (= {:username "alice.example"
            :email "alice@example.com"
            :first-name "Alice Example"}
           (domain/keycloak-profile (d/db conn) member-id)))
    (is (= {:member/member-id member-id
            :member/name "Alice Example"
            :member/email "alice@example.com"
            :member/username "alice.example"}
           (domain/invited-member (d/db conn) member-id)))))

(deftest accepted-receipt-digest-is-stable-and-does-not-contain-the-code-test
  (let [code "invite-code"
        digest (domain/accepted-receipt-digest code)]
    (is (= {:length 64
            :hex? true
            :stable? true
            :contains-code? false}
           {:length (count digest)
            :hex? (boolean (re-matches #"[0-9a-f]{64}" digest))
            :stable? (= digest (domain/accepted-receipt-digest code))
            :contains-code? (str/includes? digest code)}))))
