(ns app.members.invite.cells
  "Mycelium cells for member invitation acceptance and recovery."
  (:require
   [app.members.domain :as members.domain]
   [app.members.invite.domain :as domain]
   [app.schemas :as schemas]
   [datomic.api :as d]
   [mycelium.cell :as cell]
   [mycelium.core :as myc]
   [tick.core :as t]))

(def ^:private member-id-marker
  "probematic-member-id")

(def ^:private invite-generation-marker
  "probematic-invite-generation")

(defn- keycloak-attempt-markers [member-id generation]
  {member-id-marker [(str member-id)]
   invite-generation-marker [(str generation)]})

(myc/defcell
  :member-invite/read-entry-state
  {:doc
   "Reads canonical invitation state at the safe accept-or-recover workflow boundary."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/observed-status
             domain/invitation-status-schema]
            [:member-invite/observed-generation pos-int?]
            [:member-invite/requested-at domain/inst-schema]
            [:keycloak/group-name :string]])
   :output (schemas/schema
            [:map
             [:member-invite/state domain/invitation-state-schema]])
   :requires [:datomic-conn]}
  (fn [{:keys [datomic-conn]} {:member/keys [member-id]}]
    {:member-invite/state (domain/state (d/db datomic-conn) member-id)}))

(myc/defcell
  :member/read-invitation-state
  {:doc
   "Reads the member's narrow invitation lifecycle projection from the current Datomic database."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]])
   :output (schemas/schema
            [:map
             [:member-invite/state domain/invitation-state-schema]])
   :requires [:datomic-conn]}
  (fn [{:keys [datomic-conn]} {:member/keys [member-id]}]
    {:member-invite/state (domain/state (d/db datomic-conn) member-id)}))

(myc/defcell
  :member/read-keycloak-profile
  {:doc
   "Reads the member fields required to construct a Keycloak account specification."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]])
   :output (schemas/schema
            [:map
             [:member-invite/keycloak-profile
              domain/keycloak-profile-schema]])
   :requires [:datomic-conn]}
  (fn [{:keys [datomic-conn]} {:member/keys [member-id]}]
    {:member-invite/keycloak-profile
     (domain/keycloak-profile (d/db datomic-conn) member-id)}))

(myc/defcell
  :member/claim-invitation
  {:doc
   "Atomically moves a matching, unexpired pending invitation to accepting and returns its attempt generation."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/requested-at domain/inst-schema]
            [:member-invite/state domain/invitation-state-schema]])
   :output (let [output
                 {:claimed
                  [:map
                   [:member-invite/claim-status [:enum :claimed]]
                   [:member-invite/attempt-generation pos-int?]]
                  :conflict
                  [:map
                   [:member-invite/claim-status [:enum :conflict]]
                   [:member-invite/result [:enum :retry]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:datomic-conn :clock]}
  (fn [resources
       {:member/keys [member-id]
        :member-invite/keys [requested-at state]}]
    (let [{:keys [outcome generation]}
          (domain/claim! resources
                         {:member-id    member-id
                          :requested-at requested-at
                          :state        state})]
      (case outcome
        :claimed
        {:member-invite/claim-status :claimed
         :member-invite/attempt-generation generation}

        :conflict
        {:member-invite/claim-status :conflict
         :member-invite/result :retry}))))

(myc/defcell
  :member/begin-keycloak-create
  {:doc
   "Atomically fences one accepting attempt before any Keycloak create can start."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/attempt-generation pos-int?]])
   :output (let [output
                 {:begun
                  [:map
                   [:member-invite/create-status [:enum :begun]]
                   [:member-invite/attempt-generation pos-int?]
                   [:member-invite/expected-status
                    [:enum :member.invite.status/creating]]
                   [:member-invite/expected-generation pos-int?]
                   [:keycloak/user-attributes
                    domain/marker-attributes-schema]]
                  :conflict
                  [:map
                   [:member-invite/create-status [:enum :conflict]]
                   [:member-invite/result [:enum :retry]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:datomic-conn :clock]}
  (fn [resources
       {:member/keys [member-id]
        :member-invite/keys [attempt-generation]}]
    (let [{:keys [outcome generation]}
          (domain/begin-create!
           resources
           {:member-id member-id
            :attempt-generation attempt-generation})]
      (case outcome
        :create-begun
        {:member-invite/create-status :begun
         :member-invite/attempt-generation generation
         :member-invite/expected-status :member.invite.status/creating
         :member-invite/expected-generation generation
         :keycloak/user-attributes
         (keycloak-attempt-markers member-id generation)}

        :conflict
        {:member-invite/create-status :conflict
         :member-invite/result :retry}))))

(myc/defcell
  :member/link-keycloak-user
  {:doc
   "Atomically links an attempt-owned user from its guarded source state and moves it to activating."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/attempt-generation pos-int?]
            [:member-invite/expected-status
             domain/attempt-source-status-schema]
            [:member-invite/expected-generation pos-int?]
            [:keycloak/user-id :string]])
   :output (let [output
                 {:linked
                  [:map
                   [:member-invite/link-status [:enum :linked]]
                   [:member-invite/activation-generation pos-int?]
                   [:keycloak/enabled? [:enum true]]]
                  :conflict
                  [:map
                   [:member-invite/link-status [:enum :conflict]]
                   [:member-invite/result [:enum :retry]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:datomic-conn :clock]}
  (fn [resources
       {:member/keys [member-id]
        :member-invite/keys [attempt-generation
                             expected-status
                             expected-generation]
        :keycloak/keys [user-id]}]
    (let [{:keys [outcome generation]}
          (domain/link-keycloak-user!
           resources
           {:member-id           member-id
            :attempt-generation  attempt-generation
            :expected-status     expected-status
            :expected-generation expected-generation
            :keycloak-user-id    user-id})]
      (case outcome
        :linked
        {:member-invite/link-status :linked
         :member-invite/activation-generation generation
         :keycloak/enabled? true}

        :conflict
        {:member-invite/link-status :conflict
         :member-invite/result :retry}))))

(myc/defcell
  :member/begin-invitation-compensation
  {:doc
   "Atomically acquires compensation from a guarded attempt source so deletion cannot race linking."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/attempt-generation pos-int?]
            [:member-invite/expected-status
             domain/attempt-source-status-schema]
            [:member-invite/expected-generation pos-int?]])
   :output (let [output
                 {:begun
                  [:map
                   [:member-invite/compensation-status [:enum :begun]]
                   [:member-invite/compensation-generation pos-int?]]
                  :conflict
                  [:map
                   [:member-invite/compensation-status [:enum :conflict]]
                   [:member-invite/result [:enum :retry]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:datomic-conn :clock]}
  (fn [resources
       {:member/keys [member-id]
        :member-invite/keys [attempt-generation
                             expected-status
                             expected-generation]}]
    (let [{:keys [outcome generation]}
          (domain/begin-compensation!
           resources
           {:member-id           member-id
            :attempt-generation  attempt-generation
            :expected-status     expected-status
            :expected-generation expected-generation})]
      (case outcome
        :compensation-begun
        {:member-invite/compensation-status :begun
         :member-invite/compensation-generation generation}

        :conflict
        {:member-invite/compensation-status :conflict
         :member-invite/result :retry}))))

(myc/defcell
  :member/release-invitation
  {:doc
   "Atomically returns matching compensating state to pending after the attempt user is definitely absent."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/compensation-generation pos-int?]])
   :output (let [output
                 {:released
                  [:map
                   [:member-invite/release-status [:enum :released]]
                   [:member-invite/result [:enum :pending]]]
                  :conflict
                  [:map
                   [:member-invite/release-status [:enum :conflict]]
                   [:member-invite/result [:enum :retry]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:datomic-conn :clock]}
  (fn [resources
       {:member/keys [member-id]
        :member-invite/keys [compensation-generation]}]
    (let [{:keys [outcome]}
          (domain/release!
           resources
           {:member-id member-id
            :compensation-generation compensation-generation})]
      (case outcome
        :released
        {:member-invite/release-status :released
         :member-invite/result :pending}

        :conflict
        {:member-invite/release-status :conflict
         :member-invite/result :retry}))))

(myc/defcell
  :member/finalize-invitation
  {:doc
   "Atomically finalizes a matching activating invitation and removes its bearer code and expiry."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/activation-generation pos-int?]
            [:keycloak/user-id :string]])
   :output (let [output
                 {:finalized
                  [:map
                   [:member-invite/finalize-status [:enum :finalized]]
                   [:member-invite/result [:enum :accepted]]]
                  :conflict
                  [:map
                   [:member-invite/finalize-status [:enum :conflict]]
                   [:member-invite/result [:enum :retry]]]}]
             (run! schemas/schema (vals output))
             output)
   :requires [:datomic-conn :clock]}
  (fn [resources
       {:member/keys [member-id]
        :member-invite/keys [activation-generation]
        :keycloak/keys [user-id]}]
    (let [{:keys [outcome]}
          (domain/finalize!
           resources
           {:member-id             member-id
            :activation-generation activation-generation
            :keycloak-user-id      user-id})]
      (case outcome
        :finalized
        {:member-invite/finalize-status :finalized
         :member-invite/result :accepted}

        :conflict
        {:member-invite/finalize-status :conflict
         :member-invite/result :retry}))))

(cell/defcell
  :member-invite/plan-entry
  {:doc (str "Compares the resolver observation with canonical invitation state "
             "and chooses whether to claim, resume, complete, or reject stale work.")
   :input (schemas/schema
           [:map
            [:member-invite/observed-status
             domain/invitation-status-schema]
            [:member-invite/observed-generation pos-int?]
            [:member-invite/requested-at domain/inst-schema]
            [:member-invite/state domain/invitation-state-schema]])
   :output (let [complete-schema
                 [:map
                  [:member-invite/entry [:enum :complete]]
                  [:member-invite/result [:enum :accepted]]]
                 stale-schema
                 [:map
                  [:member-invite/entry [:enum :stale]]
                  [:member-invite/result [:enum :stale]]]
                 output
                 {:claim [:map
                          [:member-invite/entry [:enum :claim]]]
                  :resume [:map
                           [:member-invite/entry [:enum :resume]]]
                  :complete complete-schema
                  :stale stale-schema
                  :done [:or complete-schema stale-schema]}]
             (run! schemas/schema (vals output))
             output)}
  (fn [_resources data]
    (let [state                (:member-invite/state data)
          status               (:status state)
          requested-at         (:member-invite/requested-at data)
          current-observation? (and
                                (= (:member-invite/observed-status data)
                                   status)
                                (= (:member-invite/observed-generation data)
                                   (:generation state)))
          unexpired?           (and (:expires-at state)
                                    (t/> (:expires-at state) requested-at))
          stale                {:member-invite/entry :stale
                                :member-invite/result :stale}]
      (if-not current-observation?
        stale
        (case status
          :member.invite.status/pending
          (if unexpired?
            {:member-invite/entry :claim}
            stale)

          (:member.invite.status/accepting
           :member.invite.status/creating
           :member.invite.status/activating
           :member.invite.status/compensating)
          {:member-invite/entry :resume}

          :member.invite.status/accepted
          {:member-invite/entry :complete
           :member-invite/result :accepted}

          stale)))))

(cell/defcell
  :member-invite/plan-progress
  {:doc
   "Derives the durable recovery phase and the exact Keycloak attempt markers from canonical member state."
   :input (schemas/schema
           [:map
            [:member/member-id :uuid]
            [:member-invite/state domain/invitation-state-schema]])
   :output (let [complete-schema
                 [:map
                  [:member-invite/progress [:enum :complete]]
                  [:member-invite/result [:enum :accepted]]]
                 stale-schema
                 [:map
                  [:member-invite/progress [:enum :stale]]
                  [:member-invite/result [:enum :stale]]]
                 output
                 {:provision
                  [:map
                   [:member-invite/progress [:enum :provision]]
                   [:member-invite/attempt-generation pos-int?]
                   [:member-invite/expected-status
                    [:enum :member.invite.status/accepting]]
                   [:member-invite/expected-generation pos-int?]
                   [:keycloak/user-attributes
                    domain/marker-attributes-schema]]
                  :reconcile-create
                  [:map
                   [:member-invite/progress [:enum :reconcile-create]]
                   [:member-invite/attempt-generation pos-int?]
                   [:member-invite/expected-status
                    [:enum :member.invite.status/creating]]
                   [:member-invite/expected-generation pos-int?]
                   [:keycloak/user-attributes
                    domain/marker-attributes-schema]]
                  :activate
                  [:map
                   [:member-invite/progress [:enum :activate]]
                   [:member-invite/attempt-generation pos-int?]
                   [:member-invite/activation-generation pos-int?]
                   [:keycloak/user-id :string]
                   [:keycloak/user-attributes
                    domain/marker-attributes-schema]
                   [:keycloak/enabled? [:enum true]]]
                  :compensate
                  [:map
                   [:member-invite/progress [:enum :compensate]]
                   [:member-invite/attempt-generation pos-int?]
                   [:member-invite/compensation-generation pos-int?]
                   [:keycloak/user-attributes
                    domain/marker-attributes-schema]]
                  :complete complete-schema
                  :stale stale-schema
                  :done [:or complete-schema stale-schema]}]
             (run! schemas/schema (vals output))
             output)}
  (fn [_resources data]
    (let [member-id   (:member/member-id data)
          state       (:member-invite/state data)
          status      (:status state)
          generation  (:generation state)
          keycloak-id (:keycloak-id state)
          stale       {:member-invite/progress :stale
                       :member-invite/result :stale}]
      (case status
        :member.invite.status/accepting
        (if keycloak-id
          stale
          {:member-invite/progress :provision
           :member-invite/attempt-generation generation
           :member-invite/expected-status status
           :member-invite/expected-generation generation
           :keycloak/user-attributes
           (keycloak-attempt-markers member-id generation)})

        :member.invite.status/creating
        (if (and (> generation 1) (nil? keycloak-id))
          {:member-invite/progress :reconcile-create
           :member-invite/attempt-generation generation
           :member-invite/expected-status status
           :member-invite/expected-generation generation
           :keycloak/user-attributes
           (keycloak-attempt-markers member-id generation)}
          stale)

        :member.invite.status/activating
        (if (and (> generation 1) (seq keycloak-id))
          (let [attempt-generation (dec generation)]
            {:member-invite/progress :activate
             :member-invite/attempt-generation attempt-generation
             :member-invite/activation-generation generation
             :keycloak/user-id keycloak-id
             :keycloak/user-attributes
             (keycloak-attempt-markers
              member-id
              attempt-generation)
             :keycloak/enabled? true})
          stale)

        :member.invite.status/compensating
        (if (and (> generation 1) (nil? keycloak-id))
          (let [attempt-generation (dec generation)]
            {:member-invite/progress :compensate
             :member-invite/attempt-generation attempt-generation
             :member-invite/compensation-generation generation
             :keycloak/user-attributes
             (keycloak-attempt-markers
              member-id
              attempt-generation)})
          stale)

        :member.invite.status/accepted
        (if (seq keycloak-id)
          {:member-invite/progress :complete
           :member-invite/result :accepted}
          stale)

        stale))))

(cell/defcell
  :member-invite/build-keycloak-user-spec
  {:doc
   "Builds a disabled, email-verified Keycloak user specification with permanent invitation-attempt markers."
   :input (schemas/schema
           [:map
            [:member-invite/keycloak-profile
             domain/keycloak-profile-schema]
            [:keycloak/user-attributes
             domain/marker-attributes-schema]])
   :output (schemas/schema
            [:map
             [:keycloak/user-spec
              [:map
               [:username
                [:and
                 :app.schemas/non-blank-string
                 [:re members.domain/username-regex]]]
               [:email :app.schemas/email-address]
               [:first-name :app.schemas/non-blank-string]
               [:enabled? :boolean]
               [:email-verified? :boolean]
               [:attributes domain/marker-attributes-schema]]]])}
  (fn [_resources data]
    (let [{:keys [username email first-name]}
          (:member-invite/keycloak-profile data)]
      {:keycloak/user-spec
       {:username username
        :email email
        :first-name first-name
        :enabled? false
        :email-verified? true
        :attributes (:keycloak/user-attributes data)}})))

(cell/defcell
  :member-invite/classify-attempt-user
  {:doc
   "Verifies exact marker ownership and chooses the only safe next operation for the current durable phase."
   :input (schemas/schema
           [:map
            [:member-invite/progress
             [:enum :provision :reconcile-create :activate :compensate]]
            [:member-invite/attempt-generation pos-int?]
            [:member/member-id :uuid]
            [:keycloak/user
             [:map
              [:id :string]
              [:username {:optional true} :string]
              [:email {:optional true} :app.schemas/email-address]
              [:enabled? :boolean]
              [:attributes domain/marker-attributes-schema]]]])
   :output (let [configure-schema
                 [:map
                  [:member-invite/user-step [:enum :configure]]]
                 enable-schema
                 [:map
                  [:member-invite/user-step [:enum :enable]]
                  [:keycloak/enabled? [:enum true]]]
                 finalize-schema
                 [:map
                  [:member-invite/user-step [:enum :finalize]]]
                 delete-schema
                 [:map
                  [:member-invite/user-step [:enum :delete]]]
                 unsafe-schema
                 [:map
                  [:member-invite/user-step [:enum :unsafe]]
                  [:member-invite/result [:enum :operator-required]]]
                 output
                 {:configure configure-schema
                  :enable enable-schema
                  :finalize finalize-schema
                  :delete delete-schema
                  :unsafe unsafe-schema
                  :stop [:or
                         configure-schema
                         enable-schema
                         finalize-schema
                         delete-schema
                         unsafe-schema]}]
             (run! schemas/schema (vals output))
             output)}
  (fn [_resources data]
    (let [expected-attributes
          (keycloak-attempt-markers
           (:member/member-id data)
           (:member-invite/attempt-generation data))
          attributes (get-in data [:keycloak/user :attributes])
          attempt-owned?
          (= expected-attributes
             (select-keys attributes (keys expected-attributes)))
          progress (:member-invite/progress data)
          enabled? (get-in data [:keycloak/user :enabled?])
          unsafe   {:member-invite/user-step :unsafe
                    :member-invite/result :operator-required}]
      (if-not attempt-owned?
        unsafe
        (case progress
          (:provision :reconcile-create)
          (if enabled?
            unsafe
            {:member-invite/user-step :configure})

          :activate
          (if enabled?
            {:member-invite/user-step :finalize}
            {:member-invite/user-step :enable
             :keycloak/enabled? true})

          :compensate
          (if enabled?
            unsafe
            {:member-invite/user-step :delete})

          unsafe)))))

(cell/defcell
  :member-invite/flag-ambiguous-attempt-user
  {:doc
   "Marks an ambiguous attempt-marker lookup for operator reconciliation."
   :input (schemas/schema
           [:map
            [:keycloak/user-lookup [:enum :ambiguous]]
            [:keycloak/match-count pos-int?]])
   :output (schemas/schema
            [:map
             [:member-invite/result [:enum :operator-required]]])}
  (fn [_resources _data]
    {:member-invite/result :operator-required}))
