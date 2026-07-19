(ns app.members.invite.cells
  "Mycelium cells for member invitations and account setup."
  (:require
   [app.members.invite.domain :as domain]
   [app.schemas :as s]
   [datomic.api :as d]
   [mycelium.cell :as cell]))

(def ^:private transaction-conflict ::transaction-conflict)

(defn- conflict-failure? [exception]
  (some
   #(or (contains? #{:db.error/cas-failed
                     :db.error/unique-conflict}
                   (:db/error (ex-data %)))
        (contains? #{:member.invite.error/active
                     :member.invite.error/keycloak-linked}
                   (:app/error-code (ex-data %))))
   (take-while some? (iterate ex-cause exception))))

(defn- transact-plan [conn plan]
  (if-not plan
    transaction-conflict
    (try
      @(d/transact conn (:tx-data plan))
      (catch Throwable exception
        (if (conflict-failure? exception)
          transaction-conflict
          (throw exception))))))

(defn- state-after [report member-id]
  (domain/invitation-state (:db-after report) member-id))

(cell/defcell :member-invite/create-invited-member!
  {:doc "Creates the member, ledger, and first pending invitation in one Datomic transaction."
   :input [:map
           [:member-invite ::domain/member-invite-form]]
   :output
   [:per-transition
    {:created
     [:map
      [:member-invite/persist-status [:= :created]]
      [:member-invite/code ::s/non-blank-string]
      [:member-invite/member ::domain/invited-member]
      [:member-invite/state ::domain/invitation-state]]
     :conflict
     [:map
      [:member-invite/persist-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock random-code random-uuid current-member-id]} data]
    (let [issued-at (clock)
          form (:member-invite data)
          member-id (random-uuid)
          code (random-code)
          expires-at (domain/invitation-expiry issued-at)
          ledger-id (random-uuid)
          plan (domain/create-invited-member-tx
                form
                {:code code
                 :expires-at expires-at
                 :transitioned-at issued-at}
                member-id
                ledger-id
                current-member-id)
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/persist-status :conflict}
        {:member-invite/persist-status :created
         :member-invite/code code
         :member-invite/member (domain/invited-member (:db-after report) member-id)
         :member-invite/state (domain/invitation-state (:db-after report) member-id)}))))

(cell/defcell :member-invite/queue-invitation-email!
  {:doc "Builds the invitation email and adds it to the application's email queue."
   :input [:map
           [:member-invite/member ::domain/invited-member]
           [:member-invite/code ::s/non-blank-string]]
   :output [:map [:member-invite/email-queued? [:= true]]]}
  (fn [{:keys [build-invitation-email queue-email!]} data]
    (queue-email!
     (build-invitation-email
      (:member-invite/member data)
      (:member-invite/code data)))
    {:member-invite/email-queued? true}))

(cell/defcell :member-invite/read-admin-state
  {:doc "Reads invitation state for an administrator's reissue or revoke request."
   :input [:map [:member/member-id :uuid]]
   :output [:map
            [:member-invite/state ::domain/maybe-invitation-state]]}
  (fn [{:keys [datomic-conn]} data]
    {:member-invite/state
     (domain/invitation-state
      (d/db datomic-conn)
      (:member/member-id data))}))

(cell/defcell :member-invite/read-acceptance-state
  {:doc "Reads invitation state and prepares the data required to resume its current account-setup phase."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/resolved-generation pos-int?]
           [:member-invite/requested-at ::s/inst]
           [:keycloak/group-name ::s/non-blank-string]]
   :output
   [:per-transition
    {:claim [:map
             [:member-invite/state ::domain/invitation-state]]
     :provision [:map
                 [:member-invite/state ::domain/invitation-state]]
     :configure [:map
                 [:member-invite/state ::domain/invitation-state]
                 [:keycloak/user-attributes ::domain/marker-attributes]]
     :activate [:map
                [:member-invite/state ::domain/invitation-state]
                [:keycloak/user-id ::s/non-blank-string]]
     :cleanup [:map
               [:member-invite/state ::domain/invitation-state]
               [:keycloak/user-attributes ::domain/marker-attributes]]
     :accepted [:map
                [:member-invite/state ::domain/invitation-state]]
     :default [:map
               [:member-invite/state ::domain/maybe-invitation-state]]}]}
  (fn [{:keys [datomic-conn]} data]
    (let [member-id (:member/member-id data)
          state (domain/invitation-state (d/db datomic-conn) member-id)
          context (assoc data :member-invite/state state)
          result {:member-invite/state state}]
      (cond
        (domain/invitation-ready-for-configuration? context)
        (assoc result
               :keycloak/user-attributes
               (domain/attempt-markers member-id (:generation state)))

        (domain/invitation-ready-for-activation? context)
        (assoc result :keycloak/user-id (:keycloak-id state))

        (domain/invitation-ready-for-cleanup? context)
        (assoc result
               :keycloak/user-attributes
               (domain/attempt-markers member-id (dec (:generation state))))

        :else
        result))))

(cell/defcell :member-invite/check-reissue
  {:doc "Checks whether the administrator is replacing the current pending or revoked invitation."
   :input [:map
           [:member-invite/resolved-generation pos-int?]
           [:member-invite/state ::domain/maybe-invitation-state]]
   :output
   [:per-transition
    {:reissue [:map [:member-invite/reissue-step [:= :reissue]]]
     :stale [:map [:member-invite/reissue-step [:= :stale]]]}]}
  (fn [_resources data]
    {:member-invite/reissue-step
     (domain/reissue-step
      (:member-invite/state data)
      (:member-invite/resolved-generation data))}))

(cell/defcell :member-invite/reissue!
  {:doc "Generates a new bearer and replaces a pending or revoked invitation in Datomic."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]]
   :output
   [:per-transition
    {:reissued
     [:map
      [:member-invite/reissue-status [:= :reissued]]
      [:member-invite/code ::s/non-blank-string]
      [:member-invite/member ::domain/invited-member]
      [:member-invite/state ::domain/invitation-state]]
     :conflict
     [:map [:member-invite/reissue-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock random-code]} data]
    (let [issued-at (clock)
          member-id (:member/member-id data)
          code (random-code)
          plan
          (domain/reissue-tx
           (d/db datomic-conn)
           {:member-id member-id
            :state (:member-invite/state data)
            :code code
            :expires-at (domain/invitation-expiry issued-at)
            :transitioned-at issued-at})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/reissue-status :conflict}
        {:member-invite/reissue-status :reissued
         :member-invite/code code
         :member-invite/member
         (domain/invited-member (:db-after report) member-id)
         :member-invite/state (state-after report member-id)}))))

(cell/defcell :member-invite/check-revoke
  {:doc "Checks whether the administrator is cancelling the current pending invitation."
   :input [:map
           [:member-invite/resolved-generation pos-int?]
           [:member-invite/state ::domain/maybe-invitation-state]]
   :output
   [:per-transition
    {:revoke [:map [:member-invite/revoke-step [:= :revoke]]]
     :stale [:map [:member-invite/revoke-step [:= :stale]]]}]}
  (fn [_resources data]
    {:member-invite/revoke-step
     (domain/revoke-step
      (:member-invite/state data)
      (:member-invite/resolved-generation data))}))

(cell/defcell :member-invite/revoke!
  {:doc "Cancels the pending invitation and removes its code and expiration date from Datomic."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]]
   :output
   [:per-transition
    {:revoked
     [:map
      [:member-invite/revoke-status [:= :revoked]]
      [:member-invite/state ::domain/invitation-state]]
     :conflict
     [:map [:member-invite/revoke-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan
          (domain/revoke-tx
           (d/db datomic-conn)
           {:member-id member-id
            :state (:member-invite/state data)
            :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/revoke-status :conflict}
        {:member-invite/revoke-status :revoked
         :member-invite/state (state-after report member-id)}))))

(cell/defcell :member-invite/claim!
  {:doc "Changes a current, unexpired invitation from pending to accepting."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/requested-at ::s/inst]
           [:member-invite/state ::domain/invitation-state]]
   :output [:per-transition
            {:claimed [:map
                       [:member-invite/claim-status [:= :claimed]]
                       [:member-invite/attempt-generation pos-int?]
                       [:member-invite/state ::domain/invitation-state]]
             :conflict [:map [:member-invite/claim-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan (domain/claim-tx
                (d/db datomic-conn)
                {:member-id member-id
                 :state (:member-invite/state data)
                 :requested-at (:member-invite/requested-at data)
                 :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/claim-status :conflict}
        (let [state (state-after report member-id)]
          {:member-invite/claim-status :claimed
           :member-invite/attempt-generation (:generation state)
           :member-invite/state state})))))

(cell/defcell :member-invite/read-keycloak-profile
  {:doc "Reads the member name, email address, and username needed by Keycloak."
   :input [:map [:member/member-id :uuid]]
   :output [:map
            [:member-invite/keycloak-profile ::domain/keycloak-profile]]}
  (fn [{:keys [datomic-conn]} data]
    {:member-invite/keycloak-profile (domain/keycloak-profile
                                      (d/db datomic-conn)
                                      (:member/member-id data))}))

(cell/defcell :member-invite/begin-create!
  {:doc "Records that this account-setup request may create one Keycloak account."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]
           [:member-invite/keycloak-profile ::domain/keycloak-profile]]
   :output [:per-transition
            {:begun [:map
                     [:member-invite/create-status [:= :begun]]
                     [:member-invite/attempt-generation pos-int?]
                     [:member-invite/expected-status [:= :member.invite.status/creating]]
                     [:member-invite/expected-generation pos-int?]
                     [:member-invite/state ::domain/invitation-state]
                     [:keycloak/user-attributes ::domain/marker-attributes]
                     [:keycloak/user-spec ::domain/keycloak-user-spec]]
             :conflict [:map [:member-invite/create-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan (domain/begin-create-tx
                (d/db datomic-conn)
                {:member-id member-id
                 :state (:member-invite/state data)
                 :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/create-status :conflict}
        (let [state (state-after report member-id)
              generation (:generation state)
              attributes (domain/attempt-markers member-id generation)]
          {:member-invite/create-status :begun
           :member-invite/attempt-generation generation
           :member-invite/expected-status domain/creating
           :member-invite/expected-generation generation
           :member-invite/state state
           :keycloak/user-attributes attributes
           :keycloak/user-spec
           (domain/keycloak-user-spec
            (:member-invite/keycloak-profile data)
            attributes)})))))

(cell/defcell :member-invite/check-creating-user
  {:doc "Checks that the Keycloak account found during setup is the expected disabled account."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]
           [:keycloak/user ::domain/keycloak-user]]
   :output [:per-transition
            {:configure [:map [:member-invite/user-step [:= :configure]]]
             :unsafe [:map [:member-invite/user-step [:= :unsafe]]]}]}
  (fn [_resources data]
    {:member-invite/user-step (domain/creating-user-step
                               (:member/member-id data)
                               (get-in data [:member-invite/state :generation])
                               (:keycloak/user data))}))

(cell/defcell :member-invite/link-keycloak-user!
  {:doc "Links the created Keycloak account to the member and prepares it to be enabled."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]
           [:keycloak/user-id ::s/non-blank-string]]
   :output [:per-transition
            {:linked [:map
                      [:member-invite/link-status [:= :linked]]
                      [:member-invite/activation-generation pos-int?]
                      [:member-invite/state ::domain/invitation-state]
                      [:keycloak/enabled? [:= true]]]
             :conflict [:map [:member-invite/link-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan (domain/link-keycloak-user-tx
                (d/db datomic-conn)
                {:member-id member-id
                 :state (:member-invite/state data)
                 :keycloak-user-id (:keycloak/user-id data)
                 :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/link-status :conflict}
        (let [state (state-after report member-id)]
          {:member-invite/link-status :linked
           :member-invite/activation-generation (:generation state)
           :member-invite/state state
           :keycloak/enabled? true})))))

(cell/defcell :member-invite/check-activating-user
  {:doc "Checks whether the linked Keycloak account needs to be enabled or is ready to finish."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]
           [:keycloak/user ::domain/keycloak-user]]
   :output [:per-transition
            {:enable [:map
                      [:member-invite/user-step [:= :enable]]
                      [:keycloak/enabled? [:= true]]]
             :finalize [:map [:member-invite/user-step [:= :finalize]]]
             :unsafe [:map [:member-invite/user-step [:= :unsafe]]]}]}
  (fn [_resources data]
    (let [step (domain/activating-user-step
                (:member/member-id data)
                (dec (get-in data [:member-invite/state :generation]))
                (:keycloak/user data))]
      (cond-> {:member-invite/user-step step}
        (= :enable step) (assoc :keycloak/enabled? true)))))

(cell/defcell :member-invite/finalize!
  {:doc "Marks account setup complete and removes the invitation code and expiration date."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]
           [:keycloak/user-id ::s/non-blank-string]]
   :output
   [:per-transition
    {:finalized
     [:map
      [:member-invite/finalize-status [:= :finalized]]
      [:member-invite/state ::domain/invitation-state]]
     :conflict
     [:map [:member-invite/finalize-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan
          (domain/finalize-tx
           (d/db datomic-conn)
           {:member-id member-id
            :state (:member-invite/state data)
            :keycloak-user-id (:keycloak/user-id data)
            :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/finalize-status :conflict}
        {:member-invite/finalize-status :finalized
         :member-invite/state (state-after report member-id)}))))

(cell/defcell :member-invite/begin-compensation!
  {:doc "Records that account setup has stopped and the unfinished Keycloak account must be removed."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]]
   :output
   [:per-transition
    {:begun
     [:map
      [:member-invite/compensation-status [:= :begun]]
      [:member-invite/compensation-generation pos-int?]
      [:member-invite/state ::domain/invitation-state]]
     :conflict
     [:map [:member-invite/compensation-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan
          (domain/begin-compensation-tx
           (d/db datomic-conn)
           {:member-id member-id
            :state (:member-invite/state data)
            :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/compensation-status :conflict}
        (let [state (state-after report member-id)]
          {:member-invite/compensation-status :begun
           :member-invite/compensation-generation (:generation state)
           :member-invite/state state})))))

(cell/defcell :member-invite/check-compensation-user
  {:doc "Checks that the unfinished Keycloak account is the expected disabled account before deleting it."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]
           [:keycloak/user ::domain/keycloak-user]]
   :output
   [:per-transition
    {:delete [:map [:member-invite/user-step [:= :delete]]]
     :unsafe [:map [:member-invite/user-step [:= :unsafe]]]}]}
  (fn [_resources data]
    {:member-invite/user-step
     (domain/compensation-user-step
      (:member/member-id data)
      (dec (get-in data [:member-invite/state :generation]))
      (:keycloak/user data))}))

(cell/defcell :member-invite/release!
  {:doc "Makes the invitation pending again after the unfinished Keycloak account is gone."
   :input [:map
           [:member/member-id :uuid]
           [:member-invite/state ::domain/invitation-state]]
   :output
   [:per-transition
    {:released
     [:map
      [:member-invite/release-status [:= :released]]
      [:member-invite/state ::domain/invitation-state]]
     :conflict
     [:map [:member-invite/release-status [:= :conflict]]]}]}
  (fn [{:keys [datomic-conn clock]} data]
    (let [member-id (:member/member-id data)
          plan
          (domain/release-tx
           (d/db datomic-conn)
           {:member-id member-id
            :state (:member-invite/state data)
            :transitioned-at (clock)})
          report (transact-plan datomic-conn plan)]
      (if (= transaction-conflict report)
        {:member-invite/release-status :conflict}
        {:member-invite/release-status :released
         :member-invite/state (state-after report member-id)}))))

(cell/defcell :member-invite/accepted-result
  {:doc "Returns that account setup completed."
   :input [:map]
   :output [:map [:member-invite/result [:= :accepted]]]}
  (fn [_resources _data] {:member-invite/result :accepted}))

(cell/defcell :member-invite/pending-result
  {:doc "Returns that cleanup completed and the invitation can be tried again."
   :input [:map]
   :output [:map [:member-invite/result [:= :pending]]]}
  (fn [_resources _data] {:member-invite/result :pending}))

(cell/defcell :member-invite/retry-result
  {:doc "Returns that the request should be tried again."
   :input [:map]
   :output [:map [:member-invite/result [:= :retry]]]}
  (fn [_resources _data] {:member-invite/result :retry}))

(cell/defcell :member-invite/stale-result
  {:doc "Returns that the invitation changed or can no longer be used."
   :input [:map]
   :output [:map [:member-invite/result [:= :stale]]]}
  (fn [_resources _data] {:member-invite/result :stale}))

(cell/defcell :member-invite/operator-required-result
  {:doc "Returns that an administrator must inspect the account before setup can continue."
   :input [:map]
   :output [:map [:member-invite/result [:= :operator-required]]]}
  (fn [_resources _data] {:member-invite/result :operator-required}))
