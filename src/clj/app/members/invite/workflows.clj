(ns app.members.invite.workflows
  (:require
   [app.keycloak :as keycloak]
   [app.keycloak.cells]
   [app.members.invite.cells]
   [app.members.invite.domain :as invite.domain]
   [app.members.queries :as members.queries]
   [app.queries :as q]
   [app.schemas :as s]
   [clojure.string :as str]
   [datomic.api :as d]
   [mycelium.core :as myc]
   [tick.core :as t]))

(def invite-member
  {:id ::invite-member
   :doc "Creates the member, ledger, and pending invitation, then queues the invitation email."
   :input-schema
   [:map
    [:member-invite
     [:map
      [:name ::s/non-blank-string]
      [:nick {:optional true} :string]
      [:email ::s/email-address]
      [:username ::s/non-blank-string]
      [:phone ::s/non-blank-string]
      [:section-name ::s/non-blank-string]
      [:active :boolean]]]]

   :cells
   {:start       :member-invite/create-invited-member!
    :queue-email :member-invite/queue-invitation-email!}

   :edges
   {:start {:created  :queue-email
            :conflict :end}
    :queue-email :end}

   :dispatches
   {:start
    [[:created #(= :created (:member-invite/persist-status %))]
     [:conflict #(= :conflict (:member-invite/persist-status %))]]}})

(def ^:private admin-invitation-input-schema
  [:map
   [:member/member-id :uuid]
   [:member-invite/resolved-generation pos-int?]])

(def reissue-invitation
  {:id ::reissue-invitation
   :doc "Creates a new code and expiration date for a pending or revoked invitation, then queues a new email."
   :input-schema admin-invitation-input-schema

   :cells
   {:start       :member-invite/read-admin-state
    :check       :member-invite/check-reissue
    :persist     :member-invite/reissue!
    :queue-email :member-invite/queue-invitation-email!}

   :edges
   {:start :check
    :check {:reissue :persist
            :stale   :end}
    :persist {:reissued :queue-email
              :conflict :end}
    :queue-email :end}

   :dispatches
   {:check
    [[:reissue #(= :reissue (:member-invite/reissue-step %))]
     [:stale #(= :stale (:member-invite/reissue-step %))]]

    :persist
    [[:reissued #(= :reissued (:member-invite/reissue-status %))]
     [:conflict #(= :conflict (:member-invite/reissue-status %))]]}

   :transforms
   {:start
    {:output
     {:fn identity
      :schema {:input admin-invitation-input-schema
               :output admin-invitation-input-schema}}}}})

(def revoke-invitation
  {:id ::revoke-invitation
   :doc "Cancels a pending invitation so its code can no longer be used."
   :input-schema admin-invitation-input-schema

   :cells
   {:start  :member-invite/read-admin-state
    :check  :member-invite/check-revoke
    :revoke :member-invite/revoke!}

   :edges
   {:start :check
    :check {:revoke :revoke
            :stale  :end}
    :revoke :end}

   :dispatches
   {:check
    [[:revoke #(= :revoke (:member-invite/revoke-step %))]
     [:stale #(= :stale (:member-invite/revoke-step %))]]}

   :transforms
   {:start
    {:output
     {:fn identity
      :schema {:input admin-invitation-input-schema
               :output admin-invitation-input-schema}}}}})

(def ^:private accept-or-recover-input-schema
  [:map
   [:member/member-id :uuid]
   [:member-invite/resolved-generation pos-int?]
   [:member-invite/requested-at ::s/inst]
   [:keycloak/group-name ::s/non-blank-string]])

(def accept-or-recover
  {:id           ::accept-or-recover
   :doc
   "Runs or resumes invitation account setup, compensating only when the unfinished
   Keycloak account can be identified safely."
   :input-schema accept-or-recover-input-schema

   :cells
   {:start                       :member-invite/read-acceptance-state
    :claim                       :member-invite/claim!
    :provision-read-profile      :member-invite/read-keycloak-profile
    :provision-find-group        :keycloak/find-group-by-name
    :provision-begin-create      :member-invite/begin-create!
    :provision-create-user       :keycloak/create-user
    :configure-find-user         :keycloak/find-users-by-attributes
    :configure-check-user        :member-invite/check-creating-user
    :configure-find-group        :keycloak/find-group-by-name
    :configure-add-user-to-group :keycloak/add-user-to-group
    :configure-link-user         :member-invite/link-keycloak-user!
    :activate-load-user          :keycloak/get-user
    :activate-check-user         :member-invite/check-activating-user
    :activate-enable-user        :keycloak/set-user-enabled
    :activate-finalize           :member-invite/finalize!
    :begin-compensation          :member-invite/begin-compensation!
    :cleanup-find-user           :keycloak/find-users-by-attributes
    :cleanup-check-user          :member-invite/check-compensation-user
    :cleanup-delete-user         :keycloak/delete-user
    :cleanup-release             :member-invite/release!
    :accepted                    :member-invite/accepted-result
    :pending                     :member-invite/pending-result
    :retry                       :member-invite/retry-result
    :stale                       :member-invite/stale-result
    :operator-required           :member-invite/operator-required-result}

   :edges
   {:start                       {:claim     :claim
                                  :provision :provision-read-profile
                                  :configure :configure-find-user
                                  :activate  :activate-load-user
                                  :cleanup   :cleanup-find-user
                                  :accepted  :accepted
                                  :default   :stale}
    :claim                       {:claimed  :provision-read-profile
                                  :conflict :retry}
    :provision-read-profile      :provision-find-group
    :provision-find-group        {:found     :provision-begin-create
                                  :not-found :retry
                                  :ambiguous :operator-required}
    :provision-begin-create      {:begun    :provision-create-user
                                  :conflict :retry}
    :provision-create-user       {:created  :configure-find-user
                                  :rejected :begin-compensation}
    :configure-find-user         {:found     :configure-check-user
                                  :not-found :retry
                                  :ambiguous :operator-required}
    :configure-check-user        {:configure :configure-find-group
                                  :unsafe    :operator-required}
    :configure-find-group        {:found       :configure-add-user-to-group
                                  :unavailable :begin-compensation}
    :configure-add-user-to-group {:joined   :configure-link-user
                                  :rejected :begin-compensation}
    :configure-link-user         {:linked   :activate-load-user
                                  :conflict :retry}
    :activate-load-user          {:found     :activate-check-user
                                  :not-found :operator-required}
    :activate-check-user         {:enable   :activate-enable-user
                                  :finalize :activate-finalize
                                  :unsafe   :operator-required}
    :activate-enable-user        {:updated  :activate-finalize
                                  :rejected :operator-required}
    :activate-finalize           {:finalized :accepted
                                  :conflict  :retry}
    :begin-compensation          {:begun    :cleanup-find-user
                                  :conflict :retry}
    :cleanup-find-user           {:found     :cleanup-check-user
                                  :not-found :cleanup-release
                                  :ambiguous :operator-required}
    :cleanup-check-user          {:delete :cleanup-delete-user
                                  :unsafe :operator-required}
    :cleanup-delete-user         {:absent   :cleanup-release
                                  :rejected :operator-required}
    :cleanup-release             {:released :pending
                                  :conflict :retry}
    :accepted                    :end
    :pending                     :end
    :retry                       :end
    :stale                       :end
    :operator-required           :end}

   :dispatches
   {:start                       [[:claim invite.domain/claimable-invitation?]
                                  [:provision invite.domain/invitation-ready-for-provisioning?]
                                  [:configure invite.domain/invitation-ready-for-configuration?]
                                  [:activate invite.domain/invitation-ready-for-activation?]
                                  [:cleanup invite.domain/invitation-ready-for-cleanup?]
                                  [:accepted invite.domain/accepted-invitation?]]
    :claim                       [[:claimed #(= :claimed (:member-invite/claim-status %))]
                                  [:conflict #(= :conflict (:member-invite/claim-status %))]]
    :provision-find-group        [[:found #(= :found (:keycloak/group-lookup %))]
                                  [:not-found #(= :not-found (:keycloak/group-lookup %))]
                                  [:ambiguous #(= :ambiguous (:keycloak/group-lookup %))]]
    :provision-begin-create      [[:begun #(= :begun (:member-invite/create-status %))]
                                  [:conflict #(= :conflict (:member-invite/create-status %))]]
    :provision-create-user       [[:created #(= :created (:keycloak/create-status %))]
                                  [:rejected #(= :rejected (:keycloak/create-status %))]]
    :configure-find-user         [[:found #(= :found (:keycloak/user-lookup %))]
                                  [:not-found #(= :not-found (:keycloak/user-lookup %))]
                                  [:ambiguous #(= :ambiguous (:keycloak/user-lookup %))]]
    :configure-check-user        [[:configure #(= :configure (:member-invite/user-step %))]
                                  [:unsafe #(= :unsafe (:member-invite/user-step %))]]
    :configure-find-group        [[:found #(= :found (:keycloak/group-lookup %))]
                                  [:unavailable #(contains? #{:not-found :ambiguous}
                                                            (:keycloak/group-lookup %))]]
    :configure-add-user-to-group [[:joined #(= :joined (:keycloak/group-membership-status %))]
                                  [:rejected #(= :rejected (:keycloak/group-membership-status %))]]
    :configure-link-user         [[:linked #(= :linked (:member-invite/link-status %))]
                                  [:conflict #(= :conflict (:member-invite/link-status %))]]
    :activate-load-user          [[:found #(= :found (:keycloak/user-lookup %))]
                                  [:not-found #(= :not-found (:keycloak/user-lookup %))]]
    :activate-check-user         [[:enable #(= :enable (:member-invite/user-step %))]
                                  [:finalize #(= :finalize (:member-invite/user-step %))]
                                  [:unsafe #(= :unsafe (:member-invite/user-step %))]]
    :activate-enable-user        [[:updated #(= :updated (:keycloak/update-status %))]
                                  [:rejected #(= :rejected (:keycloak/update-status %))]]
    :activate-finalize           [[:finalized #(= :finalized (:member-invite/finalize-status %))]
                                  [:conflict #(= :conflict (:member-invite/finalize-status %))]]
    :begin-compensation          [[:begun #(= :begun (:member-invite/compensation-status %))]
                                  [:conflict #(= :conflict (:member-invite/compensation-status %))]]
    :cleanup-find-user           [[:found #(= :found (:keycloak/user-lookup %))]
                                  [:not-found #(= :not-found (:keycloak/user-lookup %))]
                                  [:ambiguous #(= :ambiguous (:keycloak/user-lookup %))]]
    :cleanup-check-user          [[:delete #(= :delete (:member-invite/user-step %))]
                                  [:unsafe #(= :unsafe (:member-invite/user-step %))]]
    :cleanup-delete-user         [[:absent #(contains? #{:deleted :not-found}
                                                       (:keycloak/delete-status %))]
                                  [:rejected #(= :rejected (:keycloak/delete-status %))]]
    :cleanup-release             [[:released #(= :released (:member-invite/release-status %))]
                                  [:conflict #(= :conflict (:member-invite/release-status %))]]}})

(def ^:private workflow-options
  {:malli/registry invite.domain/registry})

(def invite-member-wf
  (myc/pre-compile invite-member workflow-options))

(def reissue-invitation-wf
  (myc/pre-compile reissue-invitation workflow-options))

(def revoke-invitation-wf
  (myc/pre-compile revoke-invitation workflow-options))

(def accept-or-recover-wf
  (myc/pre-compile accept-or-recover workflow-options))

(defn accept-or-recover!
  "Runs the precompiled account-setup workflow with the supplied resources and safe input."
  [resources input]
  (myc/run-compiled accept-or-recover-wf resources input))

(def default-acceptance-deps
  {:now t/inst
   :accept-or-recover! accept-or-recover!})

(def ^:private outcome->reason
  {:pending :acceptance-retry
   :retry :acceptance-retry
   :stale :code-expired
   :operator-required :operator-required})

(defn- accepted-member
  [db member-id]
  (let [{:keys [status keycloak-id]}
        (invite.domain/invitation-state db member-id)]
    (when (and (= :member.invite.status/accepted status)
               (not (str/blank? keycloak-id)))
      (q/retrieve-member db member-id))))

(defn setup-account!
  "Runs or resumes account setup for the submitted invitation bearer.

  A completed receipt returns the canonical member without rerunning the workflow.
  Retryable, stale, and unsafe outcomes are exposed as stable `:reason` values."
  ([req]
   (setup-account! default-acceptance-deps req))
  ([deps {:keys [db datomic-conn] :as req}]
   (let [{:keys [now accept-or-recover!]} (merge default-acceptance-deps deps)
         db (or db (d/db datomic-conn))
         requested-at (now)
         invite-code (or (get-in req [:params :invite-code])
                         (get-in req [:params "invite-code"])
                         (get-in req [:params :code])
                         (get-in req [:params "code"]))
         accepted-invitation
         (members.queries/accepted-invitation-by-code db invite-code)
         {:keys [member-id invite-generation]}
         (members.queries/acceptance-invitation db requested-at invite-code)]
     (if accepted-invitation
       (:member accepted-invitation)
       (do
         (when-not member-id
           (throw (ex-info "Invite code expired during setup"
                           {:reason :code-expired})))
         (try
           (let [result
                 (accept-or-recover!
                  {:datomic-conn datomic-conn
                   :clock now
                   :keycloak (keycloak/kc-from-req req)}
                  {:member/member-id member-id
                   :member-invite/resolved-generation invite-generation
                   :member-invite/requested-at requested-at
                   :keycloak/group-name keycloak/member-group-name})
                 outcome (:member-invite/result result)]
             (when (myc/error? result)
               (throw (ex-info "Member invitation acceptance workflow failed"
                               (myc/workflow-error result))))
             (if (= :accepted outcome)
               (or (and (= member-id (:member/member-id result))
                        (accepted-member (d/db datomic-conn) member-id))
                   (throw
                    (ex-info "Accepted invitation state could not be verified"
                             {:reason :operator-required})))
               (throw
                (ex-info "Member invitation acceptance did not complete"
                         {:reason (get outcome->reason
                                       outcome
                                       :operator-required)}))))
           (catch Throwable exception
             (or (accepted-member (d/db datomic-conn) member-id)
                 (throw exception)))))))))
