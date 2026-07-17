(ns app.members.invite.domain
  "Owns the durable lifecycle of an invitation stored on a member entity.

  An invitation is a state machine. Its current state is
  `:member/invite-status`; `:member/invite-generation` is a monotonically
  increasing concurrency fence. Each successful transition compares both
  values and increments the generation, so a retry based on stale state cannot
  take ownership of a newer attempt. `:member/invite-status-at` records when
  the latest transition occurred.

  The bearer `:member/invite-code` and `:member/invite-expires-at` remain on
  the member while acceptance can be attempted or recovered. Revocation
  removes both. Finalization replaces them with
  `:member/invite-accepted-code-digest`, which lets an immediate browser retry
  recover a committed acceptance without retaining the bearer code.

  ## States

  - `:member.invite.status/pending` means the bearer is valid until its
    strict expiry boundary. A new acceptance may claim it.
  - `:member.invite.status/accepting` means one acceptance attempt owns the
    invitation, but Keycloak user creation has not been fenced yet.
  - `:member.invite.status/creating` means the attempt has acquired permission
    to create one disabled Keycloak user. Recovery may reconcile that external
    operation but must not start a second create.
  - `:member.invite.status/activating` means the attempt-owned Keycloak user is
    linked to the member. Recovery may verify it, enable it if needed, and
    finalize acceptance.
  - `:member.invite.status/compensating` means recovery owns cleanup of an
    attempt-created Keycloak user that could not be safely activated.
  - `:member.invite.status/accepted` means acceptance is complete, the
    Keycloak user is linked, and the bearer has been removed. This state is
    terminal.
  - `:member.invite.status/revoked` means the invitation was cancelled and its
    bearer was removed. A fresh bearer may reissue it.

  ## Events and transitions

  Public mutation functions trigger transitions. Each successful return value
  contains the event outcome and new generation:

  - [[issue!]] returns `:issued` for no invitation -> `pending`.
  - [[reissue!]] returns `:reissued` for `pending` or `revoked` -> `pending`,
    with a new bearer.
  - [[revoke!]] returns `:revoked` for `pending` -> `revoked` and removes the
    bearer.
  - [[claim!]] returns `:claimed` for unexpired `pending` -> `accepting`.
  - [[begin-create!]] returns `:create-begun` for `accepting` -> `creating`.
  - [[link-keycloak-user!]] returns `:linked` for attempt-owned `accepting` or
    `creating` -> `activating`.
  - [[begin-compensation!]] returns `:compensation-begun` for attempt-owned
    `accepting` or `creating` -> `compensating`.
  - [[release!]] returns `:released` for `compensating` -> `pending`, after the
    external user is absent.
  - [[finalize!]] returns `:finalized` for `activating` -> `accepted`, records
    the receipt, and removes the bearer.

  If the expected state, generation, bearer, expiry, or Keycloak ownership no
  longer matches, the function returns `{:outcome :conflict}` without changing
  the member. A conflict is not a transition event.

  ## State machine

  The diagram abbreviates the `:member.invite.status` namespace. State labels
  use Title Case; event labels use lower case. This display convention does not
  change the function names, outcome keywords, or status idents.

  ```text
  (No Invitation)
    `-- issue! ------------------------------> Pending

  Pending
    |-- reissue! ----------------------------> Pending
    |-- revoke! -----------------------------> Revoked
    `-- claim! ------------------------------> Accepting

  Revoked
    `-- reissue! ----------------------------> Pending

  Accepting
    |-- begin-create! -----------------------> Creating
    |-- link-keycloak-user! -----------------> Activating
    `-- begin-compensation! -----------------> Compensating

  Creating
    |-- link-keycloak-user! -----------------> Activating
    `-- begin-compensation! -----------------> Compensating

  Activating
    `-- finalize! ---------------------------> Accepted (terminal)

  Compensating
    `-- release! ----------------------------> Pending
  ```

  Every mutation receives a `:clock`, a zero-argument function returning the
  [[java.util.Date]] produced by [[tick.core/inst]], so callers control
  transition time in production and tests. Expected compare-and-swap conflicts
  are data; unexpected Datomic failures escape to the caller."
  (:require
   [app.members.domain :as members.domain]
   [app.schemas :as schemas]
   [clojure.string :as str]
   [datomic.api :as d]
   [tick.core :as t])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]
   [java.util Date HexFormat]))

(def invitation-status-schema
  (schemas/schema
   [:enum
    :member.invite.status/pending
    :member.invite.status/accepting
    :member.invite.status/creating
    :member.invite.status/activating
    :member.invite.status/compensating
    :member.invite.status/accepted
    :member.invite.status/revoked]))

(def attempt-source-status-schema
  (schemas/schema
   [:enum
    :member.invite.status/accepting
    :member.invite.status/creating]))

(def inst-schema
  "Accepts only the [[java.util.Date]] representation returned by
  [[tick.core/inst]]."
  (schemas/schema
   [:fn
    {:error/message "should be an inst returned by tick.core/inst"}
    #(instance? Date %)]))

(def invitation-state-schema
  (schemas/schema
   [:map
    [:status invitation-status-schema]
    [:generation pos-int?]
    [:expires-at {:optional true} inst-schema]
    [:keycloak-id {:optional true} :string]]))

(def marker-attributes-schema
  (schemas/schema
   [:map-of :string [:vector :string]]))

(def keycloak-profile-schema
  (schemas/schema
   [:map
    [:username
     [:and
      :app.schemas/non-blank-string
      [:re members.domain/username-regex]]]
    [:email :app.schemas/email-address]
    [:first-name :app.schemas/non-blank-string]]))

(def ^:private accepted-receipt-domain
  "probematic:member-invitation:accepted:v1:")

(defn accepted-receipt-digest
  "Returns a stable, domain-separated SHA-256 receipt for `invite-code`."
  [invite-code]
  (when (str/blank? invite-code)
    (throw (ex-info "Invitation receipt requires a non-blank code" {})))
  (let [input (.getBytes (str accepted-receipt-domain invite-code)
                         StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") input)]
    (.formatHex (HexFormat/of) digest)))

(def ^:private pending :member.invite.status/pending)
(def ^:private accepting :member.invite.status/accepting)
(def ^:private creating :member.invite.status/creating)
(def ^:private activating :member.invite.status/activating)
(def ^:private compensating :member.invite.status/compensating)
(def ^:private accepted :member.invite.status/accepted)
(def ^:private revoked :member.invite.status/revoked)

(defn- member-ref [member-id]
  [:member/member-id member-id])

(defn- member [db member-id]
  (let [entity (d/entity db (member-ref member-id))]
    (when-not (:db/id entity)
      (throw (ex-info "Member does not exist"
                      {:type      ::member-not-found
                       :member-id member-id})))
    entity))

(defn- status-ident [member]
  (let [status (:member/invite-status member)]
    (if (keyword? status)
      status
      (:db/ident status))))

(defn state
  "Returns the safe invitation lifecycle projection for `member-id`.

  The bearer code is deliberately excluded. A member without invitation
  state returns `nil`; a missing member or partial lifecycle state throws."
  [db member-id]
  (let [member     (member db member-id)
        status     (status-ident member)
        generation (:member/invite-generation member)]
    (cond
      (and (nil? status) (nil? generation))
      nil

      (or (nil? status) (nil? generation))
      (throw (ex-info "Member has partial invitation state"
                      {:type       ::partial-state
                       :member-id  member-id
                       :status     status
                       :generation generation}))

      :else
      (cond-> {:status status :generation generation}
        (:member/invite-expires-at member)
        (assoc :expires-at (:member/invite-expires-at member))

        (:member/keycloak-id member)
        (assoc :keycloak-id (:member/keycloak-id member))))))

(defn keycloak-profile
  "Returns the member fields required to provision a Keycloak user."
  [db member-id]
  (let [member  (member db member-id)
        profile {:username   (:member/username member)
                 :email      (:member/email member)
                 :first-name (:member/name member)}]
    (when-not (schemas/valid? keycloak-profile-schema profile)
      (throw (ex-info "Member has an invalid Keycloak profile"
                      {:type      ::invalid-keycloak-profile
                       :member-id member-id})))
    profile))

(defn- transition-time [{:keys [clock]}]
  (when-not (fn? clock)
    (throw (ex-info "Invitation transitions require a zero-argument :clock function"
                    {:type  ::invalid-clock
                     :clock clock})))
  (let [transitioned-at (clock)]
    (if (instance? Date transitioned-at)
      transitioned-at
      (throw (ex-info "Invitation transition clock must return an inst"
                      {:type ::invalid-clock})))))

(defn- conflict-failure? [exception]
  (some #(or (= :db.error/cas-failed (:db/error (ex-data %)))
             (contains? #{:member.invite.error/active
                          :member.invite.error/keycloak-linked}
                        (:app/error-code (ex-data %))))
        (take-while some? (iterate ex-cause exception))))

(defn- transact! [conn tx-data applied]
  (try
    @(d/transact conn tx-data)
    applied
    (catch Throwable exception
      (if (conflict-failure? exception)
        {:outcome :conflict}
        (throw exception)))))

(defn- transact-if-unlinked [member-id tx-data]
  [[:member.invite/transact-if-unlinked member-id tx-data]])

(defn- transition-tx
  [db member-id from-status to-status generation transitioned-at]
  (let [ref             (member-ref member-id)
        next-generation (inc generation)]
    [[:db.fn/cas ref :member/invite-status
      (d/entid db from-status)
      (d/entid db to-status)]
     [:db.fn/cas ref :member/invite-generation generation next-generation]
     [:db/add ref :member/invite-status-at transitioned-at]]))

(defn- attempt-source?
  [expected-status expected-generation attempt-generation]
  (and (contains? #{accepting creating} expected-status)
       (= expected-generation attempt-generation)))

(defn- current-bearer [conn member-id]
  (let [member (member (d/db conn) member-id)]
    {:code       (:member/invite-code member)
     :expires-at (:member/invite-expires-at member)}))

(defn issue!
  "Creates generation one for a member that has never had an invitation.

  `resources` requires `:datomic-conn` and the injected `:clock`. `invitation`
  requires `:member-id`, `:code`, and `:expires-at`."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id code expires-at]}]
  (let [db     (d/db datomic-conn)
        member (member db member-id)]
    (if (or (status-ident member)
            (:member/invite-generation member)
            (:member/keycloak-id member))
      {:outcome :conflict}
      (let [ref             (member-ref member-id)
            transitioned-at (transition-time resources)]
        (transact!
         datomic-conn
         (transact-if-unlinked
          member-id
          [[:db.fn/cas ref :member/invite-status nil (d/entid db pending)]
           [:db.fn/cas ref :member/invite-generation nil 1]
           [:db.fn/cas ref :member/invite-code nil code]
           [:db.fn/cas ref :member/invite-expires-at nil expires-at]
           [:db/add ref :member/invite-status-at transitioned-at]])
         {:outcome :issued :generation 1})))))

(defn reissue!
  "Rotates a matching pending or revoked invitation to a new pending generation."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id state code expires-at]}]
  (let [{:keys [status generation]} state]
    (if-not (contains? #{pending revoked} status)
      {:outcome :conflict}
      (let [db (d/db datomic-conn)
            {old-code :code old-expiry :expires-at}
            (current-bearer datomic-conn member-id)
            ref             (member-ref member-id)
            next-generation (inc generation)
            transitioned-at (transition-time resources)]
        (transact!
         datomic-conn
         (transact-if-unlinked
          member-id
          (into (transition-tx db
                               member-id
                               status
                               pending
                               generation
                               transitioned-at)
                [[:db.fn/cas ref :member/invite-code old-code code]
                 [:db.fn/cas ref :member/invite-expires-at
                  old-expiry
                  expires-at]]))
         {:outcome :reissued :generation next-generation})))))

(defn revoke!
  "Moves a matching pending invitation to revoked and retracts its bearer."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id state]}]
  (let [{:keys [status generation]} state]
    (if-not (= pending status)
      {:outcome :conflict}
      (let [{:keys [code expires-at]} (current-bearer datomic-conn member-id)]
        (if-not (and code expires-at)
          {:outcome :conflict}
          (let [db              (d/db datomic-conn)
                ref             (member-ref member-id)
                next-generation (inc generation)
                transitioned-at (transition-time resources)]
            (transact!
             datomic-conn
             (into (transition-tx db
                                  member-id
                                  pending
                                  revoked
                                  generation
                                  transitioned-at)
                   [[:db/retract ref :member/invite-code code]
                    [:db/retract ref :member/invite-expires-at expires-at]])
             {:outcome :revoked :generation next-generation})))))))

(defn claim!
  "Claims a matching pending invitation when `requested-at` is before expiry."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id requested-at state]}]
  (let [{:keys [status generation expires-at]} state]
    (if-not (and (= pending status)
                 (instance? Date expires-at)
                 (instance? Date requested-at)
                 (t/> expires-at requested-at))
      {:outcome :conflict}
      (let [db              (d/db datomic-conn)
            ref             (member-ref member-id)
            next-generation (inc generation)
            transitioned-at (transition-time resources)]
        (transact!
         datomic-conn
         (transact-if-unlinked
          member-id
          (into (transition-tx db
                               member-id
                               pending
                               accepting
                               generation
                               transitioned-at)
                [[:db.fn/cas ref :member/invite-expires-at
                  expires-at
                  expires-at]]))
         {:outcome :claimed :generation next-generation})))))

(defn begin-create!
  "Acquires exclusive Keycloak creation ownership for an accepting attempt."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id attempt-generation]}]
  (let [db                (d/db datomic-conn)
        create-generation (inc attempt-generation)
        transitioned-at   (transition-time resources)]
    (transact!
     datomic-conn
     (transact-if-unlinked
      member-id
      (transition-tx db
                     member-id
                     accepting
                     creating
                     attempt-generation
                     transitioned-at))
     {:outcome :create-begun :generation create-generation})))

(defn link-keycloak-user!
  "Links an attempt-owned Keycloak user from the expected attempt source."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id
           expected-status
           expected-generation
           attempt-generation
           keycloak-user-id]}]
  (if-not (attempt-source? expected-status
                           expected-generation
                           attempt-generation)
    {:outcome :conflict}
    (let [db                    (d/db datomic-conn)
          ref                   (member-ref member-id)
          activation-generation (inc expected-generation)
          transitioned-at       (transition-time resources)]
      (transact!
       datomic-conn
       (into (transition-tx db
                            member-id
                            expected-status
                            activating
                            expected-generation
                            transitioned-at)
             [[:db.fn/cas ref :member/keycloak-id nil keycloak-user-id]])
       {:outcome :linked :generation activation-generation}))))

(defn begin-compensation!
  "Acquires compensation ownership from the expected attempt source."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id
           expected-status
           expected-generation
           attempt-generation]}]
  (if-not (attempt-source? expected-status
                           expected-generation
                           attempt-generation)
    {:outcome :conflict}
    (let [db                      (d/db datomic-conn)
          compensation-generation (inc expected-generation)
          transitioned-at         (transition-time resources)]
      (transact!
       datomic-conn
       (transact-if-unlinked
        member-id
        (transition-tx db
                       member-id
                       expected-status
                       compensating
                       expected-generation
                       transitioned-at))
       {:outcome :compensation-begun
        :generation compensation-generation}))))

(defn release!
  "Returns exact compensating state to pending after its user is absent."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id compensation-generation]}]
  (let [db              (d/db datomic-conn)
        next-generation (inc compensation-generation)
        transitioned-at (transition-time resources)]
    (transact!
     datomic-conn
     (transact-if-unlinked
      member-id
      (transition-tx db
                     member-id
                     compensating
                     pending
                     compensation-generation
                     transitioned-at))
     {:outcome :released :generation next-generation})))

(defn finalize!
  "Finalizes exact activating state and retracts its bearer code and expiry."
  [{:keys [datomic-conn] :as resources}
   {:keys [member-id activation-generation keycloak-user-id]}]
  (let [{:keys [code expires-at]} (current-bearer datomic-conn member-id)]
    (if-not (and code expires-at)
      {:outcome :conflict}
      (let [db              (d/db datomic-conn)
            ref             (member-ref member-id)
            next-generation (inc activation-generation)
            transitioned-at (transition-time resources)]
        (transact!
         datomic-conn
         (into (transition-tx db
                              member-id
                              activating
                              accepted
                              activation-generation
                              transitioned-at)
               [[:db.fn/cas ref :member/keycloak-id
                 keycloak-user-id
                 keycloak-user-id]
                [:db/add ref
                 :member/invite-accepted-code-digest
                 (accepted-receipt-digest code)]
                [:db/retract ref :member/invite-code code]
                [:db/retract ref :member/invite-expires-at expires-at]])
         {:outcome :finalized :generation next-generation})))))
