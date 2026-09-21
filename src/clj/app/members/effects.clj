(ns app.members.effects
  (:require
   [app.datastar :as datastar]
   [app.datomic :as db]
   [app.datomic.shim :as datomic]
   [app.email.mailers :as mailers]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.write-runner :as writer]
   [app.keycloak :as keycloak]
   [app.members.invite.cells]
   [app.members.invite.workflows :as invite.workflows]
   [app.members.queries :as members.queries]
   [app.queries :as q]
   [app.util.crypto :as crypto]
   [com.brunobonacci.mulog :as μ]
   [mycelium.core :as myc]
   [tick.core :as t]))

(def default-invitation-deps
  {:now         t/inst
   :random-code #(crypto/rand-string 32)
   :random-uuid random-uuid})

(defn- conn-from-req [req]
  (or (:datomic-conn req)
      (get-in req [:system :datomic :conn])))

(defn- db-from-req [req]
  (datomic/db (conn-from-req req)))

(defn- current-member-id [req]
  (get-in req [:app/session :session/member :member/member-id]))

(defn- invitation-workflow-resources [deps req default-action]
  (let [member-id (current-member-id req)]
    {:datomic-conn      (conn-from-req req)
     :write-runner      (get-in req [:system :frame-loop :write-runner])
     :current-locale    (:current-locale req)
     :clock             (:now deps)
     :random-code       (:random-code deps)
     :random-uuid       (:random-uuid deps)
     :current-member-id member-id
     :audit             (cond-> {:audit/action (or (:app.nexus/audit-action req) default-action)
                                 :audit/origin :app.origin/browser}
                          member-id (assoc :audit/user [:member/member-id member-id]))}))

(defn invite-member!
  "Creates an invited member and returns the invitation workflow result.

  `member-invite` must enable SNO ID creation."
  ([req member-invite]
   (invite-member! default-invitation-deps req member-invite))
  ([deps req member-invite]
   (when-not (:create-sno-id member-invite)
     (throw (ex-info "NOT YET IMPLEMENTED Member invitations require SNO ID creation"
                     {:create-sno-id false})))
   (let [deps (merge default-invitation-deps deps)
         result
         (myc/run-compiled
          invite.workflows/invite-member-wf
          (invitation-workflow-resources deps req ::invite-member)
          {:member-invite member-invite})]
     (when (myc/error? result)
       (throw (ex-info "Member invitation workflow failed"
                       (myc/workflow-error result))))
     result)))

(defn invite-member-fx
  "Runs the invitation form effect and returns its finite SSE response plan."
  [_ {:keys [request system]} member-invite]
  (let [result (invite-member! (assoc request :system system) member-invite)]
    (case (:member-invite/persist-status result)
      :created
      (datastar/sse-response-plan
       [[:app.datastar.sse/redirect
         (str "/member/"
              (get-in result
                      [:member-invite/member :member/member-id]))]])

      :conflict
      (datastar/sse-response-plan
       [[:app.datastar.sse/merge-signals
         {:loading false :targetid false}]])

      (throw (ex-info "Member invitation workflow ended without a status"
                      {:result result})))))

(defn reissue-invitation!
  "Rotates the expired pending invitation identified by `invite-code`.

  A stale bearer is a no-op. The email is queued only after the guarded
  workflow transition succeeds."
  ([req invite-code]
   (reissue-invitation! default-invitation-deps req invite-code))
  ([deps req invite-code]
   (let [deps (merge default-invitation-deps deps)
         now  ((:now deps))]
     (when-let [{:member/keys [member-id
                               invite-expires-at
                               invite-status
                               invite-generation]}
                (members.queries/invitation-state-by-code (db-from-req req) invite-code)]
       (when (and (= :member.invite.status/pending invite-status)
                  invite-expires-at
                  (not (t/> invite-expires-at now)))
         (let [result
               (myc/run-compiled
                invite.workflows/reissue-invitation-wf
                (invitation-workflow-resources deps req ::reissue-invitation)
                {:member/member-id                  member-id
                 :member-invite/resolved-generation invite-generation})]
           (when (myc/error? result)
             (throw (ex-info "Member invitation reissue workflow failed"
                             (myc/workflow-error result))))
           (when (and (= :reissue (:member-invite/reissue-step result))
                      (= :reissued (:member-invite/reissue-status result))
                      (:member-invite/email-queued? result))
             (:member-invite/code result))))))))

(defn reissue-revoked-invitation!
  "Issues a new bearer when `observed-generation` is still current.

  Stale and losing concurrent requests are no-ops. The email is queued only
  after the guarded workflow transition wins."
  ([req member-id observed-generation]
   (reissue-revoked-invitation!
    default-invitation-deps
    req
    member-id
    observed-generation))
  ([deps req member-id observed-generation]
   (let [deps (merge default-invitation-deps deps)]
     (when-let [{:member/keys [invite-generation]}
                (members.queries/revoked-invitation-by-member-id
                 (db-from-req req)
                 member-id)]
       (when (= observed-generation invite-generation)
         (let [result
               (myc/run-compiled
                invite.workflows/reissue-invitation-wf
                (invitation-workflow-resources deps req ::reissue-invitation)
                {:member/member-id                  member-id
                 :member-invite/resolved-generation invite-generation})]
           (when (myc/error? result)
             (throw (ex-info
                     "Revoked member invitation reissue workflow failed"
                     (myc/workflow-error result))))
           (when (and (= :reissue (:member-invite/reissue-step result))
                      (= :reissued (:member-invite/reissue-status result))
                      (:member-invite/email-queued? result))
             (:member-invite/code result))))))))

(defn resend-invitation!
  "Queues the current unexpired pending invitation without changing its state."
  ([req invite-code]
   (resend-invitation! default-invitation-deps req invite-code))
  ([deps req invite-code]
   (let [deps       (merge default-invitation-deps deps)
         frame-loop (get-in req [:system :frame-loop])
         resend!
         (fn []
           (let [db  (db-from-req req)
                 now ((:now deps))
                 {:member/keys [member-id invite-expires-at invite-status]}
                 (members.queries/invitation-state-by-code db invite-code)]
             (when (and (= :member.invite.status/pending invite-status)
                        invite-expires-at (t/> invite-expires-at now))
               (μ/log ::resend-member-invite)
               (let [job (assoc-in (mailers/job {:current-locale (or (:current-locale req) :de)}
                                                ::mailers/member-invitation {:member-id member-id})
                                   [1 :email-id] (random-uuid))]
                 (db/transact (conn-from-req req)
                              {:tx-data (log-dispatch/intent-tx [job])
                               :audit   {:audit/action ::resend-member-invite
                                         :audit/origin :app.origin/browser
                                         :audit/user   (when-let [actor-id (current-member-id req)]
                                                         [:member/member-id actor-id])}})))))]
     (if-let [control (:write-runner frame-loop)]
       (writer/call! control resend!)
       (resend!)))))

(defn delete-invitation!
  "Revokes the pending invitation currently identified by `invite-code`."
  ([req invite-code]
   (delete-invitation! default-invitation-deps req invite-code))
  ([deps req invite-code]
   (let [deps (merge default-invitation-deps deps)]
     (when-let [{:member/keys [member-id invite-status invite-generation]}
                (members.queries/invitation-state-by-code
                 (db-from-req req)
                 invite-code)]
       (when (= :member.invite.status/pending invite-status)
         (μ/log ::delete-member-invite)
         (let [result
               (myc/run-compiled
                invite.workflows/revoke-invitation-wf
                (invitation-workflow-resources deps req ::delete-invitation)
                {:member/member-id                  member-id
                 :member-invite/resolved-generation invite-generation})]
           (when (myc/error? result)
             (throw (ex-info "Member invitation revoke workflow failed"
                             (myc/workflow-error result))))
           (when (and (= :revoke (:member-invite/revoke-step result))
                      (= :revoked (:member-invite/revoke-status result)))
             {:outcome    :revoked
              :generation (get-in result
                                  [:member-invite/state :generation])})))))))

(defn update-keycloak-meta! [req member-id]
  (when-let [member (q/retrieve-member (db-from-req req) member-id)]
    (when (:member/keycloak-id member)
      (keycloak/update-user-meta! (get-in req [:system :keycloak]) member))))

(defn set-keycloak-account-enabled! [req member-id enabled?]
  (when-let [member (q/retrieve-member (db-from-req req) member-id)]
    (when (:member/keycloak-id member)
      (if enabled?
        (keycloak/unlock-account! (get-in req [:system :keycloak]) member)
        (keycloak/lock-account! (get-in req [:system :keycloak]) member)))))
