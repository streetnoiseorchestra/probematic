(ns app.members.effects
  (:require
   [app.datomic.shim :as datomic]
   [app.email :as email]
   [app.i18n :as i18n]
   [app.keycloak :as keycloak]
   [app.members.invite.domain :as invite.domain]
   [app.members.queries :as members.queries]
   [app.queries :as q]
   [app.secret-box :as secret-box]
   [com.brunobonacci.mulog :as μ]
   [tick.core :as t]))

(def invite-ttl-seconds (* 60 60 24 30))

(def default-invitation-deps
  {:now                   t/inst
   :random-code           #(secret-box/random-str 32)
   :build-new-user-invite email/build-new-user-invite
   :queue-email!          email/queue-email!})

(defn- conn-from-req [req]
  (or (:datomic-conn req)
      (get-in req [:system :datomic :conn])))

(defn- db-from-req [req]
  (datomic/db (conn-from-req req)))

(defn- tr-from-req [req]
  (or (:tr req)
      (i18n/tr-with (get-in req [:system :i18n-langs]) [:de])))

(defn- email-sys [req]
  {:tr           (tr-from-req req)
   :env          (get-in req [:system :env])
   :i18n-langs   (get-in req [:system :i18n-langs])
   :redis        (get-in req [:system :redis])
   :datomic-conn (conn-from-req req)})

(defn- invitation-resources [deps req]
  {:datomic-conn (conn-from-req req)
   :clock        (:now deps)})

(defn- expiry [issued-at]
  (t/inst
   (t/>> (t/instant issued-at)
         (t/new-duration invite-ttl-seconds :seconds))))

(defn- queue-invitation! [deps req member invite-code]
  ((:queue-email! deps)
   (email-sys req)
   ((:build-new-user-invite deps) (email-sys req) member invite-code)))

(defn- ensure-outcome! [expected result]
  (when-not (= expected (:outcome result))
    (throw (ex-info "Member invitation transition conflicted"
                    {:expected expected
                     :outcome  (:outcome result)})))
  result)

(defn send-user-invitation!
  "Issues a new Datomic invitation, then queues its email."
  ([req member-id]
   (send-user-invitation! default-invitation-deps req member-id))
  ([deps req member-id]
   (let [deps        (merge default-invitation-deps deps)
         issued-at   ((:now deps))
         invite-code ((:random-code deps))
         result      (invite.domain/issue!
                      (invitation-resources deps req)
                      {:member-id  member-id
                       :code       invite-code
                       :expires-at (expiry issued-at)})]
     (ensure-outcome! :issued result)
     (queue-invitation!
      deps
      req
      (q/retrieve-member (db-from-req req) member-id)
      invite-code)
     invite-code)))

(defn reissue-invitation!
  "Rotates the expired pending invitation identified by `invite-code`.

  A stale bearer is a no-op. The email is queued only after the guarded
  transition succeeds."
  ([req invite-code]
   (reissue-invitation! default-invitation-deps req invite-code))
  ([deps req invite-code]
   (let [deps (merge default-invitation-deps deps)
         now  ((:now deps))
         db   (db-from-req req)]
     (when-let [{:member/keys [member-id
                               invite-expires-at
                               invite-status
                               invite-generation]}
                (members.queries/invitation-state-by-code db invite-code)]
       (when (and (= :member.invite.status/pending invite-status)
                  invite-expires-at
                  (not (t/> invite-expires-at now)))
         (let [next-invite-code ((:random-code deps))
               result           (invite.domain/reissue!
                                 (invitation-resources deps req)
                                 {:member-id  member-id
                                  :state      {:status invite-status
                                               :generation invite-generation}
                                  :code       next-invite-code
                                  :expires-at (expiry now)})]
           (ensure-outcome! :reissued result)
           (queue-invitation!
            deps
            req
            (q/retrieve-member (db-from-req req) member-id)
            next-invite-code)
           next-invite-code))))))

(defn reissue-revoked-invitation!
  "Issues a new bearer when `observed-generation` is still current.

  Stale and losing concurrent requests are no-ops. The email is queued only
  after the guarded transition wins."
  ([req member-id observed-generation]
   (reissue-revoked-invitation!
    default-invitation-deps
    req
    member-id
    observed-generation))
  ([deps req member-id observed-generation]
   (let [deps (merge default-invitation-deps deps)
         now  ((:now deps))
         db   (db-from-req req)]
     (when-let [{:member/keys [invite-status invite-generation]}
                (members.queries/revoked-invitation-by-member-id db member-id)]
       (when (= observed-generation invite-generation)
         (let [next-invite-code ((:random-code deps))
               result           (invite.domain/reissue!
                                 (invitation-resources deps req)
                                 {:member-id  member-id
                                  :state      {:status invite-status
                                               :generation invite-generation}
                                  :code       next-invite-code
                                  :expires-at (expiry now)})]
           (when (= :reissued (:outcome result))
             (queue-invitation!
              deps
              req
              (q/retrieve-member (db-from-req req) member-id)
              next-invite-code)
             next-invite-code)))))))

(defn resend-invitation!
  "Queues the current unexpired pending invitation without changing its state."
  ([req invite-code]
   (resend-invitation! default-invitation-deps req invite-code))
  ([deps req invite-code]
   (let [deps (merge default-invitation-deps deps)
         now  ((:now deps))]
     (when-let [{:member/keys [member-id
                               invite-expires-at
                               invite-status]}
                (members.queries/invitation-state-by-code
                 (db-from-req req)
                 invite-code)]
       (when (and (= :member.invite.status/pending invite-status)
                  invite-expires-at
                  (t/> invite-expires-at now))
         (μ/log ::resend-member-invite)
         (queue-invitation!
          deps
          req
          (q/retrieve-member (db-from-req req) member-id)
          invite-code))))))

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
         (invite.domain/revoke!
          (invitation-resources deps req)
          {:member-id member-id
           :state {:status invite-status
                   :generation invite-generation}}))))))

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
