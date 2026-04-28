(ns app.members.effects
  (:require
   [app.datomic.shim :as datomic]
   [app.email :as email]
   [app.i18n :as i18n]
   [app.keycloak :as keycloak]
   [app.queries :as q]
   [app.secret-box :as secret-box]
   [com.brunobonacci.mulog :as μ]
   [taoensso.carmine :as redis]))

(def invite-ttl-seconds (* 60 60 24 30))

(defn- conn-from-req [req]
  (or (:datomic-conn req)
      (get-in req [:system :datomic :conn])))

(defn- db-from-req [req]
  (datomic/db (conn-from-req req)))

(defn- tr-from-req [req]
  (or (:tr req)
      (i18n/tr-with (get-in req [:system :i18n-langs]) [:de])))

(defn- email-sys [req]
  {:tr         (tr-from-req req)
   :env        (get-in req [:system :env])
   :i18n-langs (get-in req [:system :i18n-langs])
   :redis      (get-in req [:system :redis])
   :datomic-conn (conn-from-req req)})

(defn- invite-key [invite-code]
  (str "invite:" invite-code))

(defn generate-invite-code! [req member-id]
  (let [invite-code (secret-box/random-str 32)]
    (redis/wcar (get-in req [:system :redis])
                (redis/setex (invite-key invite-code) invite-ttl-seconds member-id))
    invite-code))

(defn delete-invitation! [req invite-code]
  (μ/log ::delete-member-invite)
  (redis/wcar (get-in req [:system :redis])
              (redis/del (invite-key invite-code))))

(defn fetch-invite-code [req invite-code]
  (redis/wcar (get-in req [:system :redis])
              (redis/get (invite-key invite-code))))

(defn load-invite [req invite-code]
  (when (seq invite-code)
    (when-let [member-id (fetch-invite-code req invite-code)]
      {:member      (q/retrieve-member (db-from-req req) member-id)
       :invite-code invite-code})))

(defn send-user-invitation! [req member-id]
  (let [member      (q/retrieve-member (db-from-req req) member-id)
        invite-code (generate-invite-code! req member-id)]
    (email/queue-email! (email-sys req)
                        (email/build-new-user-invite (email-sys req) member invite-code))
    invite-code))

(defn resend-invitation! [req invite-code]
  (when-let [{:keys [member invite-code]} (load-invite req invite-code)]
    (μ/log ::resend-member-invite)
    (email/queue-email! (email-sys req)
                        (email/build-new-user-invite (email-sys req) member invite-code))))

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
