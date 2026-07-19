(ns app.members.invite.domain
  "Pure domain model for member invitations and account setup.

  An invitation is durable state on a member entity. This namespace projects that
  state from immutable Datomic database values, decides which transition is safe,
  and returns transaction plans. It does not access a connection, transact, read a
  clock, generate random values, send email, or call Keycloak.

  ## Durable state

  `:member/invite-status` is the current state.
  `:member/invite-generation` is a monotonically increasing version number used
  for concurrency checks. Every successful transition compares the expected status
  and generation and then increments the generation. A retry based on stale data
  therefore cannot change a newer setup request. `:member/invite-status-at` records
  when the latest transition occurred; callers supply that time explicitly.

  The bearer `:member/invite-code` and `:member/invite-expires-at` remain on the
  member while account setup can be started or recovered. Revocation removes both.
  Finalization replaces them with `:member/invite-accepted-code-digest`, allowing an
  immediate browser retry to recognize a committed acceptance without retaining the
  bearer code.

  ## States

  - `:member.invite.status/pending` means the bearer is valid before its exact
    expiry time. An explicit recipient request may claim it.
  - `:member.invite.status/accepting` means account setup has been claimed, but
    Keycloak account creation has not yet been authorized.
  - `:member.invite.status/creating` permits creation of one disabled Keycloak
    account. Recovery must find that account by its member and generation markers
    rather than create another account.
  - `:member.invite.status/activating` means the Keycloak account is linked to the
    member. Recovery may verify it, enable it if necessary, and finalize acceptance.
  - `:member.invite.status/compensating` means setup cannot continue. Recovery may
    delete only the clearly identified disabled Keycloak account before returning
    the invitation to `pending`.
  - `:member.invite.status/accepted` means account setup is complete, the Keycloak
    account is linked, and the bearer has been removed. This state is terminal.
  - `:member.invite.status/revoked` means the invitation was cancelled and its
    bearer was removed. Reissue creates a new bearer.

  ## Transitions

  Transaction-plan functions describe the durable transitions:

  - [[create-invited-member-tx]] atomically creates a member, their ledger, and the
    first `pending` invitation.
  - [[issue-tx]] adds the first `pending` invitation to an existing member.
  - [[reissue-tx]] changes `pending` or `revoked` to `pending` with a new bearer.
  - [[revoke-tx]] changes `pending` to `revoked` and removes the bearer.
  - [[claim-tx]] changes an unexpired `pending` invitation to `accepting`.
  - [[begin-create-tx]] changes `accepting` to `creating`.
  - [[link-keycloak-user-tx]] changes `creating` to `activating` and records the
    Keycloak user ID.
  - [[begin-compensation-tx]] changes `accepting` or `creating` to `compensating`.
  - [[release-tx]] changes `compensating` to `pending` after the external account is
    absent.
  - [[finalize-tx]] changes `activating` to `accepted`, records the receipt, and
    removes the bearer.

  ```text
  (No Invitation)
    |-- create-invited-member-tx -----------------> Pending
    `-- issue-tx ---------------------------------> Pending

  Pending
    |-- reissue-tx ------------------------------> Pending
    |-- revoke-tx -------------------------------> Revoked
    `-- claim-tx --------------------------------> Accepting

  Revoked
    `-- reissue-tx ------------------------------> Pending

  Accepting
    |-- begin-create-tx -------------------------> Creating
    `-- begin-compensation-tx -------------------> Compensating

  Creating
    |-- link-keycloak-user-tx -------------------> Activating
    `-- begin-compensation-tx -------------------> Compensating

  Activating
    `-- finalize-tx -----------------------------> Accepted (terminal)

  Compensating
    `-- release-tx ------------------------------> Pending
  ```

  A transaction-plan function returns `{:tx-data ... :state ...}` when its
  preconditions hold and `nil` otherwise. Datomic compare-and-swap operations make
  the same checks again when a cell executes the plan. Cells execute transactions
  and classify expected conflicts; unexpected Datomic failures remain errors.

  ## Decisions and projections

  [[invitation-state]] projects non-secret workflow state without exposing the
  bearer. The invitation predicates describe which account-setup edges apply.
  [[creating-user-step]], [[activating-user-step]], and [[compensation-user-step]]
  verify Keycloak users against the stable attributes returned by [[attempt-markers]].

  ## Related Namespaces

  - [[app.members.invite.cells]] executes one side effect per cell.
  - [[app.members.invite.workflows]] connects cells into invitation workflows."
  (:require
   [app.members.domain :as members.domain]
   [app.schemas :as s]
   [clojure.string :as str]
   [datomic.api :as d]
   [malli.core :as m]
   [malli.registry :as mr]
   [tick.core :as t])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]
   [java.util Date HexFormat]))

(def pending :member.invite.status/pending)
(def accepting :member.invite.status/accepting)
(def creating :member.invite.status/creating)
(def activating :member.invite.status/activating)
(def compensating :member.invite.status/compensating)
(def accepted :member.invite.status/accepted)
(def revoked :member.invite.status/revoked)

(def invite-ttl-seconds (* 60 60 24 30))

(def registry
  "Malli registry for invitation domain values and Mycelium cell contracts."
  (mr/composite-registry
   s/registry
   {::invitation-status      [:enum pending accepting creating activating compensating accepted revoked]
    ::attempt-source-status  [:enum accepting creating]
    ::invitation-state       [:map
                              [:status ::invitation-status]
                              [:generation pos-int?]
                              [:expires-at {:optional true} ::s/inst]
                              [:keycloak-id {:optional true} ::s/non-blank-string]]
    ::maybe-invitation-state [:maybe ::invitation-state]
    ::marker-attributes      [:map-of :string [:vector :string]]
    ::keycloak-profile       [:map
                              [:username [:and
                                          ::s/non-blank-string
                                          [:re members.domain/username-regex]]]
                              [:email ::s/email-address]
                              [:first-name ::s/non-blank-string]]
    ::keycloak-user-spec     [:map
                              [:username ::s/non-blank-string]
                              [:email ::s/email-address]
                              [:first-name ::s/non-blank-string]
                              [:enabled? :boolean]
                              [:email-verified? :boolean]
                              [:attributes ::marker-attributes]]
    ::invited-member         [:map
                              [:member/member-id :uuid]
                              [:member/name ::s/non-blank-string]
                              [:member/email ::s/email-address]
                              [:member/username ::s/non-blank-string]]
    ::keycloak-user          [:map
                              [:id ::s/non-blank-string]
                              [:username {:optional true} ::s/non-blank-string]
                              [:email {:optional true} ::s/email-address]
                              [:enabled? :boolean]
                              [:attributes ::marker-attributes]]
    ::member-invite-form     [:map
                              [:name ::s/non-blank-string]
                              [:nick {:optional true} :string]
                              [:email ::s/email-address]
                              [:username ::s/non-blank-string]
                              [:phone ::s/non-blank-string]
                              [:section-name ::s/non-blank-string]
                              [:active :boolean]]}))

(def ^:private accepted-receipt-domain "probematic:member-invitation:accepted:v1:")
(def ^:private member-id-marker "probematic-member-id")
(def ^:private invite-generation-marker "probematic-invite-generation")

(defn accepted-receipt-digest
  "Returns a stable SHA-256 receipt for an invitation code."
  [invite-code]
  (when (str/blank? invite-code)
    (throw (ex-info "Invitation receipt requires a non-blank code" {})))
  (let [input (.getBytes (str accepted-receipt-domain invite-code)
                         StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") input)]
    (.formatHex (HexFormat/of) digest)))

(defn invitation-expiry
  "Returns the expiration time for an invitation issued at `issued-at`."
  [issued-at]
  (when-not (instance? Date issued-at)
    (throw (ex-info "Invitation issue time must be an inst"
                    {:issued-at issued-at})))
  (t/inst
   (t/>> (t/instant issued-at)
         (t/new-duration invite-ttl-seconds :seconds))))

(defn attempt-markers
  "Returns the Keycloak attributes that identify one account-setup attempt."
  [member-id generation]
  {member-id-marker [(str member-id)]
   invite-generation-marker [(str generation)]})

(defn keycloak-user-spec
  "Returns the disabled Keycloak account data for an invited member."
  [profile attributes]
  {:username (:username profile)
   :email (:email profile)
   :first-name (:first-name profile)
   :enabled? false
   :email-verified? true
   :attributes attributes})

(defn- member-ref [member-id]
  [:member/member-id member-id])

(defn- member [db member-id]
  (let [entity (d/entity db (member-ref member-id))]
    (when-not (:db/id entity)
      (throw (ex-info "Member does not exist"
                      {:type ::member-not-found
                       :member-id member-id})))
    entity))

(defn- status-ident [member]
  (let [status (:member/invite-status member)]
    (if (keyword? status)
      status
      (:db/ident status))))

(defn invitation-state
  "Returns the invitation state for `member-id` without its invitation code."
  [db member-id]
  (let [member (member db member-id)
        status (status-ident member)
        generation (:member/invite-generation member)]
    (cond
      (and (nil? status) (nil? generation))
      nil

      (or (nil? status) (nil? generation))
      (throw (ex-info "Member has partial invitation state"
                      {:type ::partial-state
                       :member-id member-id
                       :status status
                       :generation generation}))

      :else
      (cond-> {:status status
               :generation generation}
        (:member/invite-expires-at member)
        (assoc :expires-at (:member/invite-expires-at member))

        (:member/keycloak-id member)
        (assoc :keycloak-id (:member/keycloak-id member))))))

(defn invited-member
  "Returns the member fields needed to build an invitation email."
  [db member-id]
  (let [member (member db member-id)]
    {:member/member-id (:member/member-id member)
     :member/name (:member/name member)
     :member/email (:member/email member)
     :member/username (:member/username member)}))

(defn keycloak-profile
  "Returns the member fields needed to create a Keycloak account."
  [db member-id]
  (let [member (member db member-id)
        profile {:username (:member/username member)
                 :email (:member/email member)
                 :first-name (:member/name member)}]
    (when-not (m/validate ::keycloak-profile profile {:registry registry})
      (throw (ex-info "Member has an invalid Keycloak profile"
                      {:type ::invalid-keycloak-profile
                       :member-id member-id})))
    profile))

(defn reissue-step
  "Returns `:reissue` when the invitation can be replaced, otherwise `:stale`."
  [state resolved-generation]
  (if (and (= resolved-generation (:generation state))
           (contains? #{pending revoked} (:status state))
           (str/blank? (:keycloak-id state)))
    :reissue
    :stale))

(defn revoke-step
  "Returns `:revoke` when the pending invitation still matches the request."
  [state resolved-generation]
  (if (and (= resolved-generation (:generation state))
           (= pending (:status state)))
    :revoke
    :stale))

(defn- current-generation?
  "Returns true when the invitation generation matches the submitted generation."
  [data]
  (= (:member-invite/resolved-generation data)
     (get-in data [:member-invite/state :generation])))

(defn- current-status?
  "Returns true when the current invitation has `expected-status`."
  [expected-status data]
  (and (current-generation? data)
       (= expected-status (get-in data [:member-invite/state :status]))))

(defn claimable-invitation?
  "Returns true when the submitted invitation is current, pending, and unexpired."
  [data]
  (let [{:keys [expires-at]} (:member-invite/state data)
        requested-at (:member-invite/requested-at data)]
    (and (current-status? pending data)
         (instance? Date expires-at)
         (instance? Date requested-at)
         (t/> expires-at requested-at))))

(defn invitation-ready-for-provisioning?
  "Returns true when the current invitation is waiting for Keycloak account creation."
  [data]
  (current-status? accepting data))

(defn invitation-ready-for-configuration?
  "Returns true when the current invitation is waiting for Keycloak account configuration."
  [data]
  (let [{:keys [generation keycloak-id]} (:member-invite/state data)]
    (and (current-status? creating data)
         (pos-int? generation)
         (str/blank? keycloak-id))))

(defn invitation-ready-for-activation?
  "Returns true when the current invitation has a linked Keycloak account to activate."
  [data]
  (let [{:keys [generation keycloak-id]} (:member-invite/state data)]
    (and (current-status? activating data)
         (> generation 1)
         (not (str/blank? keycloak-id)))))

(defn invitation-ready-for-cleanup?
  "Returns true when the current invitation is waiting for unfinished account cleanup."
  [data]
  (let [{:keys [generation keycloak-id]} (:member-invite/state data)]
    (and (current-status? compensating data)
         (> generation 1)
         (str/blank? keycloak-id))))

(defn accepted-invitation?
  "Returns true when the current invitation has completed with a linked Keycloak account."
  [data]
  (and (current-status? accepted data)
       (not (str/blank? (get-in data [:member-invite/state :keycloak-id])))))

(defn- expected-attempt-user?
  [member-id generation user]
  (let [expected (attempt-markers member-id generation)]
    (= expected
       (select-keys (:attributes user) (keys expected)))))

(defn creating-user-step
  "Returns `:configure` for the expected disabled account, otherwise `:unsafe`."
  [member-id generation user]
  (if (and (expected-attempt-user? member-id generation user)
           (false? (:enabled? user)))
    :configure
    :unsafe))

(defn activating-user-step
  "Returns whether the linked account should be enabled or the invitation finalized."
  [member-id generation user]
  (if-not (expected-attempt-user? member-id generation user)
    :unsafe
    (if (:enabled? user) :finalize :enable)))

(defn compensation-user-step
  "Returns `:delete` for the expected disabled account, otherwise `:unsafe`."
  [member-id generation user]
  (if (and (expected-attempt-user? member-id generation user)
           (false? (:enabled? user)))
    :delete
    :unsafe))

(defn- transition-tx
  [db member-id from-status to-status generation transitioned-at]
  (let [ref (member-ref member-id)
        next-generation (inc generation)]
    [[:db.fn/cas ref :member/invite-status
      (d/entid db from-status)
      (d/entid db to-status)]
     [:db.fn/cas ref :member/invite-generation generation next-generation]
     [:db/add ref :member/invite-status-at transitioned-at]]))

(defn- transact-if-unlinked [member-id tx-data]
  [[:member.invite/transact-if-unlinked member-id tx-data]])

(defn- bearer [db member-id]
  (let [member (member db member-id)]
    {:code (:member/invite-code member)
     :expires-at (:member/invite-expires-at member)}))

(defn create-invited-member-tx
  "Returns the transaction that creates a member, ledger, and pending invitation."
  [form invitation member-id ledger-id actor-member-id]
  (let [{:keys [name nick email username phone section-name active]} form
        {:keys [code expires-at transitioned-at]} invitation
        member-tx
        (cond-> {:db/id "new-member"
                 :member/member-id member-id
                 :member/name name
                 :member/email email
                 :member/username username
                 :member/phone phone
                 :member/section [:section/name section-name]
                 :member/active? active
                 :member/invite-code code
                 :member/invite-expires-at expires-at
                 :member/invite-status pending
                 :member/invite-generation 1
                 :member/invite-status-at transitioned-at}
          (seq nick) (assoc :member/nick nick))
        ledger-tx {:db/id "new-ledger"
                   :ledger/ledger-id ledger-id
                   :ledger/owner "new-member"
                   :ledger/balance 0}
        tx-data (cond-> [member-tx ledger-tx]
                  actor-member-id
                  (conj [:db/add "datomic.tx"
                         :audit/user
                         [:member/member-id actor-member-id]]))]
    {:tx-data tx-data
     :state {:status pending
             :generation 1
             :expires-at expires-at}}))

(defn issue-tx
  "Returns the transaction that adds the first invitation to an existing member."
  [db {:keys [member-id code expires-at transitioned-at]}]
  (let [member (member db member-id)]
    (when (and (nil? (status-ident member))
               (nil? (:member/invite-generation member))
               (str/blank? (:member/keycloak-id member)))
      (let [ref (member-ref member-id)]
        {:tx-data
         (transact-if-unlinked
          member-id
          [[:db.fn/cas ref :member/invite-status nil (d/entid db pending)]
           [:db.fn/cas ref :member/invite-generation nil 1]
           [:db.fn/cas ref :member/invite-code nil code]
           [:db.fn/cas ref :member/invite-expires-at nil expires-at]
           [:db/add ref :member/invite-status-at transitioned-at]])
         :state {:status pending
                 :generation 1
                 :expires-at expires-at}}))))

(defn reissue-tx
  "Returns the transaction that replaces a pending or revoked invitation."
  [db {:keys [member-id state code expires-at transitioned-at]}]
  (let [{:keys [status generation]} state]
    (when (contains? #{pending revoked} status)
      (let [{old-code :code old-expiry :expires-at} (bearer db member-id)
            ref (member-ref member-id)
            tx-data
            (into
             (transition-tx db
                            member-id
                            status
                            pending
                            generation
                            transitioned-at)
             [[:db.fn/cas ref :member/invite-code old-code code]
              [:db.fn/cas ref
               :member/invite-expires-at
               old-expiry
               expires-at]])]
        {:tx-data (transact-if-unlinked member-id tx-data)
         :state {:status pending
                 :generation (inc generation)
                 :expires-at expires-at}}))))

(defn revoke-tx
  "Returns the transaction that revokes a pending invitation."
  [db {:keys [member-id state transitioned-at]}]
  (let [{:keys [status generation]} state
        {:keys [code expires-at]} (bearer db member-id)]
    (when (and (= pending status) code expires-at)
      (let [ref (member-ref member-id)]
        {:tx-data
         (into
          (transition-tx db
                         member-id
                         pending
                         revoked
                         generation
                         transitioned-at)
          [[:db/retract ref :member/invite-code code]
           [:db/retract ref :member/invite-expires-at expires-at]])
         :state {:status revoked
                 :generation (inc generation)}}))))

(defn claim-tx
  "Returns the transaction that starts account setup for a pending invitation."
  [db {:keys [member-id state requested-at transitioned-at]}]
  (let [{:keys [status generation expires-at]} state]
    (when (and (= pending status)
               (instance? Date expires-at)
               (instance? Date requested-at)
               (t/> expires-at requested-at))
      (let [ref (member-ref member-id)
            tx-data
            (into
             (transition-tx db
                            member-id
                            pending
                            accepting
                            generation
                            transitioned-at)
             [[:db.fn/cas ref
               :member/invite-expires-at
               expires-at
               expires-at]])]
        {:tx-data (transact-if-unlinked member-id tx-data)
         :state {:status accepting
                 :generation (inc generation)
                 :expires-at expires-at}}))))

(defn begin-create-tx
  "Returns the transaction that permits one Keycloak account creation."
  [db {:keys [member-id state transitioned-at]}]
  (let [{:keys [status generation expires-at]} state]
    (when (= accepting status)
      {:tx-data
       (transact-if-unlinked
        member-id
        (transition-tx db
                       member-id
                       accepting
                       creating
                       generation
                       transitioned-at))
       :state (cond-> {:status creating
                       :generation (inc generation)}
                expires-at (assoc :expires-at expires-at))})))

(defn link-keycloak-user-tx
  "Returns the transaction that links the created Keycloak account to the member."
  [db {:keys [member-id state keycloak-user-id transitioned-at]}]
  (let [{:keys [status generation expires-at]} state]
    (when (= creating status)
      (let [ref (member-ref member-id)]
        {:tx-data
         (into
          (transition-tx db
                         member-id
                         creating
                         activating
                         generation
                         transitioned-at)
          [[:db.fn/cas ref :member/keycloak-id nil keycloak-user-id]])
         :state (cond-> {:status activating
                         :generation (inc generation)
                         :keycloak-id keycloak-user-id}
                  expires-at (assoc :expires-at expires-at))}))))

(defn begin-compensation-tx
  "Returns the transaction that starts cleanup after account setup fails."
  [db {:keys [member-id state transitioned-at]}]
  (let [{:keys [status generation expires-at]} state]
    (when (contains? #{accepting creating} status)
      {:tx-data
       (transact-if-unlinked
        member-id
        (transition-tx db
                       member-id
                       status
                       compensating
                       generation
                       transitioned-at))
       :state (cond-> {:status compensating
                       :generation (inc generation)}
                expires-at (assoc :expires-at expires-at))})))

(defn release-tx
  "Returns the transaction that makes a cleaned-up invitation pending again."
  [db {:keys [member-id state transitioned-at]}]
  (let [{:keys [status generation expires-at]} state]
    (when (= compensating status)
      {:tx-data
       (transact-if-unlinked
        member-id
        (transition-tx db
                       member-id
                       compensating
                       pending
                       generation
                       transitioned-at))
       :state (cond-> {:status pending
                       :generation (inc generation)}
                expires-at (assoc :expires-at expires-at))})))

(defn finalize-tx
  "Returns the transaction that marks account setup complete and removes the code."
  [db {:keys [member-id state keycloak-user-id transitioned-at]}]
  (let [{:keys [status generation]} state
        {:keys [code expires-at]} (bearer db member-id)]
    (when (and (= activating status) code expires-at)
      (let [ref (member-ref member-id)]
        {:tx-data
         (into
          (transition-tx db
                         member-id
                         activating
                         accepted
                         generation
                         transitioned-at)
          [[:db.fn/cas ref
            :member/keycloak-id
            keycloak-user-id
            keycloak-user-id]
           [:db/add ref
            :member/invite-accepted-code-digest
            (accepted-receipt-digest code)]
           [:db/retract ref :member/invite-code code]
           [:db/retract ref :member/invite-expires-at expires-at]])
         :state {:status accepted
                 :generation (inc generation)
                 :keycloak-id keycloak-user-id}}))))
