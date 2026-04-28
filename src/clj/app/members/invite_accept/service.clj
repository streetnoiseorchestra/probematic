(ns app.members.invite-accept.service
  (:require
   [app.datomic.shim :as datomic]
   [app.keycloak :as keycloak]
   [app.members.effects :as members.effects]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]))

(defn- param [req k]
  (or (get-in req [:params k])
      (get-in req [:params (name k)])))

(defn invite-code [req]
  (param req :code))

(defn form-invite-code [req]
  (or (param req :invite-code)
      (invite-code req)))

(defn load-invite [req]
  (members.effects/load-invite req (invite-code req)))

(defn login-link [req member]
  (str (urls/absolute-link-login (get-in req [:system :env]))
       "?login_hint="
       (some-> (:member/email member) util/url-encode)))

(def default-deps
  {:fetch-invite-code  members.effects/fetch-invite-code
   :create-new-member! keycloak/create-new-member!
   :delete-invitation! members.effects/delete-invitation!
   :transact!          datomic/transact})

(defn setup-account!
  ([req]
   (setup-account! default-deps req))
  ([deps {:keys [db datomic-conn] :as req}]
   (let [deps             (merge default-deps deps)
         fetch-invite-code (:fetch-invite-code deps)
         create-new-member! (:create-new-member! deps)
         delete-invitation! (:delete-invitation! deps)
         transact!        (:transact! deps)
         invite-code      (form-invite-code req)
         member-id        (fetch-invite-code req invite-code)]
     (if-not member-id
       (throw (ex-info "Invite code expired during setup" {:reason :code-expired}))
       (let [member    (q/retrieve-member db member-id)
             new-user  (create-new-member! (keycloak/kc-from-req req) member true)
             tx-result (transact! datomic-conn
                                  {:tx-data [[:db/add
                                              [:member/member-id member-id]
                                              :member/keycloak-id
                                              (:user/user-id new-user)]]})]
         (delete-invitation! req invite-code)
         (q/retrieve-member (:db-after tx-result) member-id))))))
