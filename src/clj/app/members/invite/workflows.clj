(ns app.members.invite.workflows
  "Mycelium workflows for issuing, accepting, and recovering member invitations."
  (:require
   [app.keycloak :as keycloak]
   [app.keycloak.cells]
   [app.members.invite.cells]
   [app.members.invite.domain :as domain]
   [app.members.queries :as members.queries]
   [app.queries :as q]
   [app.schemas :as schemas]
   [clojure.string :as str]
   [datomic.api :as d]
   [mycelium.core :as myc]
   [mycelium.manifest :as manifest]
   [tick.core :as t]))

(def ^:private workflow-input-schema
  (schemas/schema
   [:map {:closed true}
    [:member/member-id :uuid]
    [:member-invite/observed-status
     [:enum
      :member.invite.status/pending
      :member.invite.status/accepting
      :member.invite.status/creating
      :member.invite.status/activating
      :member.invite.status/compensating
      :member.invite.status/accepted
      :member.invite.status/revoked]]
    [:member-invite/observed-generation pos-int?]
    [:member-invite/requested-at domain/inst-schema]
    [:keycloak/group-name :app.schemas/non-blank-string]]))

(defn- outcome? [k expected]
  (fn [data]
    (= expected (get data k))))

(defn- outcome-in? [k expected]
  (fn [data]
    (contains? expected (get data k))))

(def manifest
  "Declares the complete accept-or-recover workflow contract."
  {:id :member-invitation/accept-or-recover
   :doc "Claims or resumes one member invitation acceptance attempt without carrying its bearer code."
   :input-schema workflow-input-schema

   :cells
   {:start
    {:id :member-invite/read-entry-state
     :doc "Reads canonical invitation state at the safe accept-or-recover workflow boundary."
     :schema :inherit
     :requires [:datomic-conn]
     :on-error nil}
    :plan-entry
    {:id :member-invite/plan-entry
     :doc "Compares the resolver observation with canonical invitation state and chooses whether to claim, resume, complete, or reject stale work."
     :schema :inherit
     :requires []
     :on-error nil}
    :claim
    {:id :member/claim-invitation
     :doc "Atomically moves a matching, unexpired pending invitation to accepting and returns its attempt generation."
     :schema :inherit
     :requires [:datomic-conn :clock]
     :on-error nil}
    :read-after-claim
    {:id :member/read-invitation-state
     :doc "Reads the member's narrow invitation lifecycle projection from the current Datomic database."
     :schema :inherit
     :requires [:datomic-conn]
     :on-error nil}
    :plan-progress
    {:id :member-invite/plan-progress
     :doc "Derives the durable recovery phase and the exact Keycloak attempt markers from canonical member state."
     :schema :inherit
     :requires []
     :on-error nil}

    :find-provisioning-user
    {:id :keycloak/find-users-by-attributes
     :doc "Finds Keycloak users by exact custom attributes and classifies zero, one, or multiple matches."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :classify-existing-user
    {:id :member-invite/classify-attempt-user
     :doc "Verifies exact marker ownership and chooses the only safe next operation for the current durable phase."
     :schema :inherit
     :requires []
     :on-error nil}
    :read-profile
    {:id :member/read-keycloak-profile
     :doc "Reads the member fields required to construct a Keycloak account specification."
     :schema :inherit
     :requires [:datomic-conn]
     :on-error nil}
    :find-group-before-create
    {:id :keycloak/find-group-by-name
     :doc "Finds a Keycloak group by exact name and rejects missing or ambiguous results without choosing one."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :begin-create
    {:id :member/begin-keycloak-create
     :doc "Atomically fences one accepting attempt before any Keycloak create can start."
     :schema :inherit
     :requires [:datomic-conn :clock]
     :on-error nil}
    :build-user-spec
    {:id :member-invite/build-keycloak-user-spec
     :doc "Builds a disabled, email-verified Keycloak user specification with permanent invitation-attempt markers."
     :schema :inherit
     :requires []
     :on-error nil}
    :create-user
    {:id :keycloak/create-user
     :doc "Creates one Keycloak user from a generic user specification and classifies only definite server rejection as rejected."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :get-created-user
    {:id :keycloak/get-user
     :doc "Reads one Keycloak user by ID and distinguishes a missing user from a returned normalized representation."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :refind-after-create-rejection
    {:id :keycloak/find-users-by-attributes
     :doc "Finds Keycloak users by exact custom attributes and classifies zero, one, or multiple matches."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :classify-created-user
    {:id :member-invite/classify-attempt-user
     :doc "Verifies exact marker ownership and chooses the only safe next operation for the current durable phase."
     :schema :inherit
     :requires []
     :on-error nil}
    :find-creating-user
    {:id :keycloak/find-users-by-attributes
     :doc "Finds Keycloak users by exact custom attributes and classifies zero, one, or multiple matches."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :classify-creating-user
    {:id :member-invite/classify-attempt-user
     :doc "Verifies exact marker ownership and chooses the only safe next operation for the current durable phase."
     :schema :inherit
     :requires []
     :on-error nil}
    :flag-ambiguous-creating-user
    {:id :member-invite/flag-ambiguous-attempt-user
     :doc "Marks an ambiguous attempt-marker lookup for operator reconciliation."
     :schema :inherit
     :requires []
     :on-error nil}
    :find-group-before-configure
    {:id :keycloak/find-group-by-name
     :doc "Finds a Keycloak group by exact name and rejects missing or ambiguous results without choosing one."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :add-user-to-group
    {:id :keycloak/add-user-to-group
     :doc "Adds a Keycloak user to a group and classifies only a definite server rejection as rejected."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :link-user
    {:id :member/link-keycloak-user
     :doc "Atomically links an attempt-owned user from its guarded source state and moves it to activating."
     :schema :inherit
     :requires [:datomic-conn :clock]
     :on-error nil}
    :enable-after-link
    {:id :keycloak/set-user-enabled
     :doc "Sets one Keycloak user's enabled flag and classifies only a definite server rejection as rejected."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}

    :get-activating-user
    {:id :keycloak/get-user
     :doc "Reads one Keycloak user by ID and distinguishes a missing user from a returned normalized representation."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :classify-activating-user
    {:id :member-invite/classify-attempt-user
     :doc "Verifies exact marker ownership and chooses the only safe next operation for the current durable phase."
     :schema :inherit
     :requires []
     :on-error nil}
    :enable-during-recovery
    {:id :keycloak/set-user-enabled
     :doc "Sets one Keycloak user's enabled flag and classifies only a definite server rejection as rejected."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :finalize
    {:id :member/finalize-invitation
     :doc "Atomically finalizes a matching activating invitation and removes its bearer code and expiry."
     :schema :inherit
     :requires [:datomic-conn :clock]
     :on-error nil}

    :begin-compensation
    {:id :member/begin-invitation-compensation
     :doc "Atomically acquires compensation from a guarded attempt source so deletion cannot race linking."
     :schema :inherit
     :requires [:datomic-conn :clock]
     :on-error nil}
    :find-compensation-user
    {:id :keycloak/find-users-by-attributes
     :doc "Finds Keycloak users by exact custom attributes and classifies zero, one, or multiple matches."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :classify-compensation-user
    {:id :member-invite/classify-attempt-user
     :doc "Verifies exact marker ownership and chooses the only safe next operation for the current durable phase."
     :schema :inherit
     :requires []
     :on-error nil}
    :delete-user
    {:id :keycloak/delete-user
     :doc "Deletes one Keycloak user by ID and distinguishes deleted, already absent, and definite rejection."
     :schema :inherit
     :requires [:keycloak]
     :on-error nil}
    :release
    {:id :member/release-invitation
     :doc "Atomically returns matching compensating state to pending after the attempt user is definitely absent."
     :schema :inherit
     :requires [:datomic-conn :clock]
     :on-error nil}}

   :edges
   {:start :plan-entry
    :plan-entry {:claim :claim
                 :resume :plan-progress
                 :done :end}
    :claim {:claimed :read-after-claim
            :conflict :end}
    :read-after-claim :plan-progress
    :plan-progress {:provision :find-provisioning-user
                    :reconcile-create :find-creating-user
                    :activate :get-activating-user
                    :compensate :find-compensation-user
                    :done :end}

    :find-provisioning-user {:found :classify-existing-user
                             :not-found :read-profile
                             :ambiguous :end}
    :classify-existing-user {:configure :find-group-before-configure
                             :stop :end}
    :read-profile :find-group-before-create
    :find-group-before-create {:found :begin-create
                               :unavailable :begin-compensation}
    :begin-create {:begun :build-user-spec
                   :conflict :end}
    :build-user-spec :create-user
    :create-user {:created :get-created-user
                  :rejected :refind-after-create-rejection}
    :get-created-user {:found :classify-created-user
                       :not-found :end}
    :refind-after-create-rejection {:found :classify-created-user
                                    :not-found :begin-compensation
                                    :ambiguous :end}
    :classify-created-user {:configure :add-user-to-group
                            :stop :end}
    :find-creating-user {:found :classify-creating-user
                         :not-found :end
                         :ambiguous :flag-ambiguous-creating-user}
    :classify-creating-user {:configure :find-group-before-configure
                             :stop :end}
    :flag-ambiguous-creating-user :end
    :find-group-before-configure {:found :add-user-to-group
                                  :unavailable :begin-compensation}
    :add-user-to-group {:joined :link-user
                        :rejected :begin-compensation}
    :link-user {:linked :enable-after-link
                :conflict :end}
    :enable-after-link {:updated :finalize
                        :rejected :end}

    :get-activating-user {:found :classify-activating-user
                          :not-found :end}
    :classify-activating-user {:enable :enable-during-recovery
                               :finalize :finalize
                               :stop :end}
    :enable-during-recovery {:updated :finalize
                             :rejected :end}
    :finalize :end

    :begin-compensation {:begun :find-compensation-user
                         :conflict :end}
    :find-compensation-user {:found :classify-compensation-user
                             :not-found :release
                             :ambiguous :end}
    :classify-compensation-user {:delete :delete-user
                                 :stop :end}
    :delete-user {:absent :release
                  :rejected :end}
    :release :end}

   :dispatches
   {:plan-entry [[:claim (outcome? :member-invite/entry :claim)]
                 [:resume (outcome? :member-invite/entry :resume)]
                 [:done (outcome-in? :member-invite/entry
                                     #{:complete :stale})]]
    :claim [[:claimed (outcome? :member-invite/claim-status :claimed)]
            [:conflict (outcome? :member-invite/claim-status :conflict)]]
    :plan-progress [[:provision (outcome? :member-invite/progress :provision)]
                    [:reconcile-create
                     (outcome? :member-invite/progress :reconcile-create)]
                    [:activate (outcome? :member-invite/progress :activate)]
                    [:compensate (outcome? :member-invite/progress :compensate)]
                    [:done (outcome-in? :member-invite/progress
                                        #{:complete :stale})]]

    :find-provisioning-user [[:found (outcome? :keycloak/user-lookup :found)]
                             [:not-found (outcome? :keycloak/user-lookup :not-found)]
                             [:ambiguous (outcome? :keycloak/user-lookup :ambiguous)]]
    :classify-existing-user [[:configure (outcome? :member-invite/user-step :configure)]
                             [:stop (outcome-in? :member-invite/user-step
                                                 #{:enable :finalize :delete :unsafe})]]
    :find-group-before-create [[:found (outcome? :keycloak/group-lookup :found)]
                               [:unavailable (outcome-in? :keycloak/group-lookup
                                                          #{:not-found :ambiguous})]]
    :begin-create [[:begun (outcome? :member-invite/create-status :begun)]
                   [:conflict (outcome? :member-invite/create-status :conflict)]]
    :create-user [[:created (outcome? :keycloak/create-status :created)]
                  [:rejected (outcome? :keycloak/create-status :rejected)]]
    :get-created-user [[:found (outcome? :keycloak/user-lookup :found)]
                       [:not-found (outcome? :keycloak/user-lookup :not-found)]]
    :refind-after-create-rejection [[:found (outcome? :keycloak/user-lookup :found)]
                                    [:not-found (outcome? :keycloak/user-lookup :not-found)]
                                    [:ambiguous (outcome? :keycloak/user-lookup :ambiguous)]]
    :classify-created-user [[:configure (outcome? :member-invite/user-step :configure)]
                            [:stop (outcome-in? :member-invite/user-step
                                                #{:enable :finalize :delete :unsafe})]]
    :find-creating-user [[:found (outcome? :keycloak/user-lookup :found)]
                         [:not-found (outcome? :keycloak/user-lookup :not-found)]
                         [:ambiguous (outcome? :keycloak/user-lookup :ambiguous)]]
    :classify-creating-user
    [[:configure (outcome? :member-invite/user-step :configure)]
     [:stop (outcome-in? :member-invite/user-step
                         #{:enable :finalize :delete :unsafe})]]
    :find-group-before-configure [[:found (outcome? :keycloak/group-lookup :found)]
                                  [:unavailable (outcome-in? :keycloak/group-lookup
                                                             #{:not-found :ambiguous})]]
    :add-user-to-group [[:joined (outcome? :keycloak/group-membership-status :joined)]
                        [:rejected (outcome? :keycloak/group-membership-status :rejected)]]
    :link-user [[:linked (outcome? :member-invite/link-status :linked)]
                [:conflict (outcome? :member-invite/link-status :conflict)]]
    :enable-after-link [[:updated (outcome? :keycloak/update-status :updated)]
                        [:rejected (outcome? :keycloak/update-status :rejected)]]

    :get-activating-user [[:found (outcome? :keycloak/user-lookup :found)]
                          [:not-found (outcome? :keycloak/user-lookup :not-found)]]
    :classify-activating-user [[:enable (outcome? :member-invite/user-step :enable)]
                               [:finalize (outcome? :member-invite/user-step :finalize)]
                               [:stop (outcome-in? :member-invite/user-step
                                                   #{:configure :delete :unsafe})]]
    :enable-during-recovery [[:updated (outcome? :keycloak/update-status :updated)]
                             [:rejected (outcome? :keycloak/update-status :rejected)]]
    :begin-compensation [[:begun (outcome? :member-invite/compensation-status :begun)]
                         [:conflict (outcome? :member-invite/compensation-status :conflict)]]
    :find-compensation-user [[:found (outcome? :keycloak/user-lookup :found)]
                             [:not-found (outcome? :keycloak/user-lookup :not-found)]
                             [:ambiguous (outcome? :keycloak/user-lookup :ambiguous)]]
    :classify-compensation-user [[:delete (outcome? :member-invite/user-step :delete)]
                                 [:stop (outcome-in? :member-invite/user-step
                                                     #{:configure :enable :finalize :unsafe})]]
    :delete-user [[:absent (outcome-in? :keycloak/delete-status
                                        #{:deleted :not-found})]
                  [:rejected (outcome? :keycloak/delete-status :rejected)]]}

   :regions
   {:entry [:start :plan-entry :claim :read-after-claim :plan-progress]
    :provisioning [:find-provisioning-user
                   :classify-existing-user
                   :read-profile
                   :find-group-before-create
                   :begin-create
                   :build-user-spec
                   :create-user
                   :get-created-user
                   :refind-after-create-rejection
                   :classify-created-user
                   :find-creating-user
                   :classify-creating-user
                   :flag-ambiguous-creating-user
                   :find-group-before-configure
                   :add-user-to-group
                   :link-user
                   :enable-after-link]
    :activation [:get-activating-user
                 :classify-activating-user
                 :enable-during-recovery
                 :finalize]
    :compensation [:begin-compensation
                   :find-compensation-user
                   :classify-compensation-user
                   :delete-user
                   :release]}

   :constraints
   [{:type :must-precede
     :cell :add-user-to-group
     :before :link-user}
    {:type :must-precede
     :cell :begin-create
     :before :create-user}
    {:type :never-together
     :cells [:finalize :release]}
    {:type :never-together
     :cells [:delete-user :enable-after-link]}
    {:type :never-together
     :cells [:delete-user :enable-during-recovery]}]})

(def validated-manifest
  "Contains the strict, normalized workflow manifest."
  (manifest/validate-manifest manifest))

(defn workflow-definition
  "Builds the Mycelium workflow definition and preserves manifest path constraints."
  []
  (assoc (manifest/manifest->workflow validated-manifest)
         :constraints (:constraints validated-manifest)
         :input-schema workflow-input-schema))

(defn pre-compile
  "Pre-compiles the validated workflow after all registry cells are loaded."
  []
  (myc/pre-compile (workflow-definition)))

(def ^:private input-keys
  #{:member/member-id
    :member-invite/observed-status
    :member-invite/observed-generation
    :member-invite/requested-at
    :keycloak/group-name})

(defn- original-error [error]
  (loop [error error]
    (if-let [nested-error (some-> error ex-data :error)]
      (recur nested-error)
      error)))

(defn- on-workflow-error [_resources fsm]
  (if-let [error (:error fsm)]
    (throw (original-error error))
    (:data fsm)))

(def ^:private compiled-workflow
  (delay
    (myc/pre-compile
     (workflow-definition)
     {:on-error on-workflow-error})))

(defn- workflow-error! [data]
  (when (myc/error? data)
    (let [error (myc/workflow-error data)]
      (throw
       (ex-info
        "Member invitation workflow failed"
        (select-keys error
                     [:error-type :cell-id :cell-name :message]))))))

(defn- terminal-result [data]
  (or (:member-invite/result data)
      (cond
        (= :ambiguous (:keycloak/user-lookup data))
        :operator-required

        (= :rejected (:keycloak/update-status data))
        :operator-required

        (= :rejected (:keycloak/delete-status data))
        :operator-required

        (and (= :not-found (:keycloak/user-lookup data))
             (= :provision (:member-invite/progress data))
             (= :created (:keycloak/create-status data)))
        :retry

        (and (= :not-found (:keycloak/user-lookup data))
             (= :reconcile-create (:member-invite/progress data)))
        :retry

        (and (= :not-found (:keycloak/user-lookup data))
             (= :activate (:member-invite/progress data)))
        :operator-required)))

(defn accept-or-recover!
  "Runs one finite acceptance or recovery attempt and returns its safe result."
  [resources input]
  (when-not (= input-keys (set (keys input)))
    (throw (ex-info "Invalid member invitation workflow input"
                    {:expected-keys input-keys
                     :actual-keys   (set (keys input))})))
  (let [data (myc/run-compiled @compiled-workflow resources input)]
    (workflow-error! data)
    (if-let [result (terminal-result data)]
      {:member/member-id (:member/member-id input)
       :member-invite/result result}
      (throw (ex-info "Member invitation workflow ended without a result"
                      {:member-id (:member/member-id input)
                       :progress  (:member-invite/progress data)})))))

(def default-deps
  {:now t/inst
   :accept-or-recover! accept-or-recover!})

(def ^:private result->reason
  {:pending :acceptance-retry
   :retry :acceptance-retry
   :stale :code-expired
   :operator-required :operator-required})

(defn- accepted-member [db member-id]
  (let [{:keys [status keycloak-id]} (domain/state db member-id)]
    (when (and (= :member.invite.status/accepted status)
               (not (str/blank? keycloak-id)))
      (q/retrieve-member db member-id))))

(defn setup-account!
  ([req]
   (setup-account! default-deps req))
  ([deps {:keys [db datomic-conn] :as req}]
   (let [{:keys [now accept-or-recover!]} (merge default-deps deps)
         requested-at (now)
         invite-code (or (get-in req [:params :invite-code])
                         (get-in req [:params "invite-code"])
                         (get-in req [:params :code])
                         (get-in req [:params "code"]))
         accepted-invitation
         (members.queries/accepted-invitation-by-code db invite-code)
         {:keys [member-id invite-status invite-generation]}
         (members.queries/acceptance-invitation db requested-at invite-code)]
     (if accepted-invitation
       (:member accepted-invitation)
       (do
         (when-not member-id
           (throw (ex-info "Invite code expired during setup"
                           {:reason :code-expired})))
         (try
           (let [{result-member-id :member/member-id
                  result :member-invite/result}
                 (accept-or-recover!
                  {:datomic-conn datomic-conn
                   :clock now
                   :keycloak (keycloak/kc-from-req req)}
                  {:member/member-id member-id
                   :member-invite/observed-status invite-status
                   :member-invite/observed-generation invite-generation
                   :member-invite/requested-at requested-at
                   :keycloak/group-name keycloak/member-group-name})]
             (if (= :accepted result)
               (or (and (= member-id result-member-id)
                        (accepted-member (d/db datomic-conn) member-id))
                   (throw
                    (ex-info "Accepted invitation state could not be verified"
                             {:reason :operator-required})))
               (throw
                (ex-info "Member invitation acceptance did not complete"
                         {:reason (get result->reason
                                       result
                                       :operator-required)}))))
           (catch Throwable exception
             (or (accepted-member (d/db datomic-conn) member-id)
                 (throw exception)))))))))
