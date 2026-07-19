(ns app.members.invite.cells-test
  (:require
   [app.members.invite.cells]
   [app.members.invite.domain :as domain]
   [app.members.invite.workflows :as workflows]
   [app.schemas :as s]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [malli.core :as m]
   [mycelium.cell :as cell]
   [mycelium.dev :as myc.dev]
   [mycelium.schema :as myc.schema]))

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

(def workflow-values
  [workflows/invite-member
   workflows/reissue-invitation
   workflows/revoke-invitation
   workflows/accept-or-recover])

(defn invite-cell-ids []
  (->> workflow-values
       (mapcat (comp vals :cells))
       (filter #(= "member-invite" (namespace %)))
       set))

(defn run-cell
  ([cell-id resources input]
   (run-cell cell-id resources input nil nil))
  ([cell-id resources input dispatches expected-dispatch]
   (myc.dev/test-cell
    cell-id
    (cond-> {:resources resources
             :input input
             :malli/registry domain/registry}
      dispatches
      (assoc :dispatches dispatches
             :expected-dispatch expected-dispatch)))))

(defn output [result]
  (is (true? (:pass? result)) (pr-str (:errors result)))
  (:output result))

(defn resources [conn]
  {:datomic-conn conn
   :clock (constantly transitioned-at)})

(defn seed-invitation!
  [conn member-id {:keys [status generation code expiry keycloak-id]}]
  @(d/transact
    conn
    [(cond-> {:member/member-id member-id
              :member/name "Alice Example"
              :member/email "alice@example.com"
              :member/username "alice.example"
              :member/invite-status status
              :member/invite-generation generation
              :member/invite-status-at issued-at}
       code (assoc :member/invite-code code)
       expiry (assoc :member/invite-expires-at expiry)
       keycloak-id (assoc :member/keycloak-id keycloak-id))]))

(deftest workflow-invite-cells-are-registered-with-local-registry-schemas-test
  (doseq [cell-id (invite-cell-ids)]
    (testing cell-id
      (let [spec (cell/get-cell cell-id)
            compiled (myc.schema/compile-cell-schemas
                      spec
                      {:malli/registry domain/registry})]
        (is (map? spec))
        (is (not-empty (:doc spec)))
        (is (m/schema? (get-in compiled [:schema :input])))
        (is (some? (get-in compiled [:schema :output])))))))

(deftest cell-inputs-describe-focused-and-workflow-boundary-values-test
  (is (= [:map [:member-invite ::domain/member-invite-form]]
         (get-in (cell/get-cell :member-invite/create-invited-member!)
                 [:schema :input])))
  (is (= [:map
          [:member-invite/member ::domain/invited-member]
          [:member-invite/code ::s/non-blank-string]]
         (get-in (cell/get-cell :member-invite/queue-invitation-email!)
                 [:schema :input])))
  (is (= [:map [:member/member-id :uuid]]
         (get-in (cell/get-cell :member-invite/read-admin-state)
                 [:schema :input])))
  (is (= [:map
          [:member/member-id :uuid]
          [:member-invite/resolved-generation pos-int?]
          [:member-invite/requested-at ::s/inst]
          [:keycloak/group-name ::s/non-blank-string]]
         (get-in (cell/get-cell :member-invite/read-acceptance-state)
                 [:schema :input])))
  (is (= [:map [:member/member-id :uuid]]
         (get-in (cell/get-cell :member-invite/read-keycloak-profile)
                 [:schema :input]))))

(deftest read-acceptance-state-returns-current-phase-context-test
  (letfn [(read-context [system-name invitation resolved-generation]
            (let [{:keys [conn member-id]} (tc/new-system system-name)]
              (seed-invitation! conn member-id invitation)
              {:member-id member-id
               :output
               (output
                (run-cell
                 :member-invite/read-acceptance-state
                 {:datomic-conn conn}
                 {:member/member-id member-id
                  :member-invite/resolved-generation resolved-generation
                  :member-invite/requested-at requested-at
                  :keycloak/group-name "Mitglieder"}))}))]
    (let [configure (read-context
                     "invite-cell-read-configure"
                     {:status creating :generation 3}
                     3)
          activate (read-context
                    "invite-cell-read-activate"
                    {:status activating
                     :generation 4
                     :keycloak-id "linked-user"}
                    4)
          cleanup (read-context
                   "invite-cell-read-cleanup"
                   {:status compensating :generation 4}
                   4)
          stale (read-context
                 "invite-cell-read-stale"
                 {:status creating :generation 3}
                 2)]
      (is (= {:configure
              {:member-invite/state {:status creating :generation 3}
               :keycloak/user-attributes
               (domain/attempt-markers (:member-id configure) 3)}
              :activate
              {:member-invite/state {:status activating
                                     :generation 4
                                     :keycloak-id "linked-user"}
               :keycloak/user-id "linked-user"}
              :cleanup
              {:member-invite/state {:status compensating :generation 4}
               :keycloak/user-attributes
               (domain/attempt-markers (:member-id cleanup) 3)}
              :stale
              {:member-invite/state {:status creating :generation 3}}}
             {:configure (:output configure)
              :activate (:output activate)
              :cleanup (:output cleanup)
              :stale (:output stale)})))))

(deftest create-invited-member-cell-generates-values-and-writes-one-transaction-test
  (let [{:keys [conn]} (tc/new-system "invite-cell-create-member")
        member-id (random-uuid)
        ledger-id (random-uuid)
        generated-ids (atom [member-id ledger-id])
        member-invite {:name "Alice Example"
                       :nick "alice"
                       :email "alice@example.com"
                       :username "alice.example"
                       :phone "+43677123456"
                       :section-name "Sopran"
                       :active true}]
    @(d/transact conn [{:section/name "Sopran"}])
    (let [result
          (run-cell
           :member-invite/create-invited-member!
           {:datomic-conn conn
            :clock (constantly issued-at)
            :random-code (constantly "new-code")
            :random-uuid
            (fn []
              (let [generated-id (first @generated-ids)]
                (swap! generated-ids subvec 1)
                generated-id))}
           {:member-invite member-invite}
           [[:created #(= :created (:member-invite/persist-status %))]
            [:conflict #(= :conflict (:member-invite/persist-status %))]]
           :created)]
      (is (= {:member-invite/persist-status :created
              :member-invite/code "new-code"
              :member-invite/member
              {:member/member-id member-id
               :member/name "Alice Example"
               :member/email "alice@example.com"
               :member/username "alice.example"}
              :member-invite/state
              {:status pending
               :generation 1
               :expires-at expires-at}}
             (output result))))
    (is (= [] @generated-ids))
    (is (= pending
           (:status (domain/invitation-state (d/db conn) member-id))))
    (is (= member-id
           (-> (d/entity (d/db conn) [:ledger/ledger-id ledger-id])
               :ledger/owner
               :member/member-id)))))

(deftest queue-invitation-email-cell-builds-and-queues-the-message-test
  (let [queued (atom [])
        member-id (random-uuid)
        data {:member-invite/code "email-code"
              :member-invite/member
              {:member/member-id member-id
               :member/name "Alice Example"
               :member/email "alice@example.com"
               :member/username "alice.example"}}
        result
        (run-cell
         :member-invite/queue-invitation-email!
         {:build-invitation-email
          (fn [member code]
            {:to (:member/email member)
             :code code})
          :queue-email! #(swap! queued conj %)}
         data)]
    (is (= {:member-invite/email-queued? true}
           (output result)))
    (is (= [{:to "alice@example.com" :code "email-code"}]
           @queued))))

(deftest admin-check-cells-return-domain-decisions-test
  (let [state {:status creating :generation 3}]
    (is (= {:member-invite/reissue-step :reissue}
           (output
            (run-cell
             :member-invite/check-reissue
             {}
             {:member-invite/resolved-generation 3
              :member-invite/state
              {:status pending :generation 3}}))))
    (is (= {:member-invite/revoke-step :stale}
           (output
            (run-cell
             :member-invite/check-revoke
             {}
             {:member-invite/resolved-generation 3
              :member-invite/state state}))))))

(deftest transition-cells-move-one-invitation-through-acceptance-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-cell-acceptance")]
    (seed-invitation!
     conn
     member-id
     {:status pending
      :generation 1
      :code "accept-code"
      :expiry expires-at})
    (let [profile {:username "alice.example"
                   :email "alice@example.com"
                   :first-name "Alice Example"}
          claim-output
          (output
           (run-cell
            :member-invite/claim!
            (resources conn)
            {:member/member-id member-id
             :member-invite/requested-at requested-at
             :member-invite/state
             (domain/invitation-state (d/db conn) member-id)}))
          create-input (merge {:member/member-id member-id
                               :member-invite/keycloak-profile profile}
                              claim-output)
          create-output
          (output
           (run-cell
            :member-invite/begin-create!
            (resources conn)
            create-input))
          create-conflict-output
          (output
           (run-cell
            :member-invite/begin-create!
            (resources conn)
            create-input))
          link-output
          (output
           (run-cell
            :member-invite/link-keycloak-user!
            (resources conn)
            (merge {:member/member-id member-id
                    :keycloak/user-id "keycloak-user"}
                   create-output)))
          finalize-output
          (output
           (run-cell
            :member-invite/finalize!
            (resources conn)
            (merge {:member/member-id member-id
                    :keycloak/user-id "keycloak-user"}
                   link-output)))]
      (is (= {:member-invite/claim-status :claimed
              :member-invite/attempt-generation 2
              :member-invite/state
              {:status accepting
               :generation 2
               :expires-at expires-at}}
             claim-output))
      (is (= {:member-invite/create-status :begun
              :member-invite/attempt-generation 3
              :member-invite/expected-status creating
              :member-invite/expected-generation 3
              :member-invite/state
              {:status creating
               :generation 3
               :expires-at expires-at}
              :keycloak/user-attributes
              (domain/attempt-markers member-id 3)
              :keycloak/user-spec
              {:username "alice.example"
               :email "alice@example.com"
               :first-name "Alice Example"
               :enabled? false
               :email-verified? true
               :attributes (domain/attempt-markers member-id 3)}}
             create-output))
      (is (= {:member-invite/create-status :conflict}
             create-conflict-output))
      (is (= {:member-invite/link-status :linked
              :member-invite/activation-generation 4
              :member-invite/state
              {:status activating
               :generation 4
               :expires-at expires-at
               :keycloak-id "keycloak-user"}
              :keycloak/enabled? true}
             link-output))
      (is (= {:member-invite/finalize-status :finalized
              :member-invite/state
              {:status accepted
               :generation 5
               :keycloak-id "keycloak-user"}}
             finalize-output)))))

(deftest compensation-cells-delete-no-data-and-return-invitation-to-pending-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-cell-compensation")]
    (seed-invitation!
     conn
     member-id
     {:status creating
      :generation 3
      :code "cleanup-code"
      :expiry expires-at})
    (let [compensation-output
          (output
           (run-cell
            :member-invite/begin-compensation!
            (resources conn)
            {:member/member-id member-id
             :member-invite/state
             (domain/invitation-state (d/db conn) member-id)}))
          release-output
          (output
           (run-cell
            :member-invite/release!
            (resources conn)
            (merge {:member/member-id member-id}
                   compensation-output)))]
      (is (= {:member-invite/compensation-status :begun
              :member-invite/compensation-generation 4
              :member-invite/state
              {:status compensating
               :generation 4
               :expires-at expires-at}}
             compensation-output))
      (is (= {:member-invite/release-status :released
              :member-invite/state
              {:status pending
               :generation 5
               :expires-at expires-at}}
             release-output)))))

(deftest reissue-and-revoke-cells-change-only-the-matching-invitation-test
  (let [{:keys [conn member-id]} (tc/new-system "invite-cell-admin-transitions")]
    (seed-invitation!
     conn
     member-id
     {:status pending
      :generation 1
      :code "old-code"
      :expiry expires-at})
    (let [reissue-output
          (output
           (run-cell
            :member-invite/reissue!
            {:datomic-conn conn
             :clock (constantly issued-at)
             :random-code (constantly "new-code")}
            {:member/member-id member-id
             :member-invite/state
             (domain/invitation-state (d/db conn) member-id)}))]
      (is (= :reissued (:member-invite/reissue-status reissue-output)))
      (is (= "new-code" (:member-invite/code reissue-output)))
      (is (= "new-code"
             (:member/invite-code
              (d/entity (d/db conn) [:member/member-id member-id])))))
    (let [revoke-output
          (output
           (run-cell
            :member-invite/revoke!
            (resources conn)
            {:member/member-id member-id
             :member-invite/state
             (domain/invitation-state (d/db conn) member-id)}))]
      (is (= {:member-invite/revoke-status :revoked
              :member-invite/state
              {:status revoked :generation 3}}
             revoke-output)))))

(deftest keycloak-check-cells-return-domain-decisions-test
  (let [member-id (random-uuid)
        attributes (domain/attempt-markers member-id 3)
        disabled-user {:id "user-1"
                       :username "alice.example"
                       :email "alice@example.com"
                       :enabled? false
                       :attributes attributes}]
    (is (= {:member-invite/user-step :configure}
           (output
            (run-cell
             :member-invite/check-creating-user
             {}
             {:member/member-id member-id
              :member-invite/state {:status creating :generation 3}
              :keycloak/user disabled-user}))))
    (is (= {:member-invite/user-step :enable
            :keycloak/enabled? true}
           (output
            (run-cell
             :member-invite/check-activating-user
             {}
             {:member/member-id member-id
              :member-invite/state {:status activating :generation 4}
              :keycloak/user disabled-user}))))
    (is (= {:member-invite/user-step :delete}
           (output
            (run-cell
             :member-invite/check-compensation-user
             {}
             {:member/member-id member-id
              :member-invite/state {:status compensating :generation 4}
              :keycloak/user disabled-user}))))))
