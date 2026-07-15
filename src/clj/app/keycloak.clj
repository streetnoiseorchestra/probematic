(ns app.keycloak
  (:require
   [app.datomic :as d]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]
   [app.datomic.shim :as datomic]
   [jsonista.core :as j]
   [keycloak.admin :as admin]
   [keycloak.deployment :as keycloak]
   [keycloak.user :as user]
   [keycloak.utils :as keycloak.utils]
   [medley.core :as m])
  (:import
   (org.keycloak.admin.client Keycloak)
   (org.keycloak.representations.idm UserRepresentation)))

(defn create-client [sys]
  (let [kc-env (-> sys :env :keycloak)]
    (-> (keycloak/client-conf (dissoc kc-env :client-secret))
        (keycloak/keycloak-client (:client-secret kc-env)))))

(defn close-client! [^Keycloak client]
  (when client
    (.close client)))

(defn init! [sys]
  {:client (create-client sys)
   :realm (-> sys :env :keycloak :realm)})

(defn halt! [sys]
  (close-client! (:client sys)))

(defn client [{:keys [client]}]
  client)

(defn realm [{:keys [realm]}]
  realm)

(defn user-representation-> [^UserRepresentation u]
  {:user/user-id (.getId u)
   :user/username (.getUsername u)
   :user/email (.getEmail u)
   :user/first-name (.getFirstName u)
   :user/last-name (.getLastName u)
   :user/enabled? (.isEnabled u)})

(defn list-users [kc]
  (->> (admin/list-users (client kc) (realm kc))
       (map user-representation->)))

(defn match-members [members kc]
  (let [users (list-users kc)
        all (for [{:member/keys [email] :as m} members]
              (if-let [matched-user (m/find-first #(= (str/lower-case email)  (str/lower-case (:user/email %))) users)]
                (-> m
                    (assoc :member/keycloak-id  (:user/user-id matched-user))
                    (assoc :member/username  (:user/username matched-user)))
                m))

        matched (remove #(nil? (:member/keycloak-id %)) all)
        unmatched (filter #(nil? (:member/keycloak-id %)) all)]
    {:matched matched
     :unmatched unmatched}))

(defn match-txs [matches]
  (mapcat (fn [m]
            [[:db/add [:member/member-id (:member/member-id m)] :member/keycloak-id (:member/keycloak-id m)]
             [:db/add [:member/member-id (:member/member-id m)] :member/username (:member/username m)]])
          matches))

(defn find-members-matches [{:keys [db kc]}]
  (->
   (->> (d/find-all db :member/member-id q/member-pattern)
        (mapv #(first %)))
   (match-members  kc)
   :matched
   match-txs))

(defn match-members-to-keycloak!
  "Attaches :member/keycloak-id to all members whose email address matches exactly to a keycloak user.
  Will also update the :member/username field for matched"
  [{:keys [datomic-conn] :as sys}]
  (datomic/transact datomic-conn {:tx-data (find-members-matches sys)}))

(defn link-user-edit
  "Return a string containing the URL to edit a user in the keycloak admin console."
  [env keycloak-id]
  (let [server-url (-> env :keycloak :auth-server-url)
        realm (-> env :keycloak :realm)]
    (str server-url "/admin/master/console/#/" realm "/users/" keycloak-id "/setttings")))

(defn get-user! [{:keys [client realm]} keycloak-id]
  (when keycloak-id
    (-> (admin/get-user client realm keycloak-id)
        (user-representation->))))

(defn- user-for-update [{:keys [username first-name last-name email enabled email-verified]}]
  (keycloak.utils/hint-typed-doto "org.keycloak.representations.idm.UserRepresentation" (UserRepresentation.)
                                  (.setUsername username)
                                  (.setFirstName first-name)
                                  (.setLastName last-name)
                                  (.setEmailVerified email-verified)
                                  (.setEmail email)
                                  (.setEnabled enabled)))

(defn- update-user [{:keys [client realm] :as kc} keycloak-id person]
  (-> ^Keycloak client
      (.realm realm)
      (.users)
      (.get keycloak-id)
      (.update (user-for-update (util/remove-nils person))))
  (get-user! kc keycloak-id))

(defn update-user-meta! [kc {:member/keys [username email name keycloak-id]}]
  (assert (not (str/blank? email)))
  (assert (not (str/blank? username)))
  (assert (not (str/blank? name)))
  (assert (not (str/blank? keycloak-id)))
  (update-user kc keycloak-id
               {:username username
                :email email
                :first-name name}))

(defn lock-account! [kc {:member/keys [keycloak-id]}]
  (assert (not (str/blank? keycloak-id)))
  (update-user kc keycloak-id {:enabled false}))

(defn unlock-account! [kc {:member/keys [keycloak-id]}]
  (assert (not (str/blank? keycloak-id)))
  (update-user kc keycloak-id {:enabled true}))

(defn- maybe-parse-json [s]
  (if (or (nil? s) (str/blank? s))
    s
    (j/read-value s j/keyword-keys-object-mapper)))

(defn- parse-response [^jakarta.ws.rs.core.Response resp]
  (when resp
    (let [r {:status (.getStatus resp)
             :headers (.getStringHeaders resp)
             :body (-> resp (.readEntity java.lang.String) maybe-parse-json)}]
      (.close resp)
      r)))

(defn- extract-id [resp]
  (let [loc (first (get-in resp [:headers "Location"]))]
    (subs (str loc) (+ (str/last-index-of (str loc) "/") 1))))

(defn- create-user! [{:keys [client realm] :as kc} person]
  (let [resp (-> ^Keycloak client (.realm realm) (.users) (.create (user/user-for-update person)) parse-response)]
    (if-not (= 201 (:status resp))
      (throw (ex-info "Create Keycloak User Failed" {:response (:body resp)}))
      (let [group-id (admin/get-group-id client realm (:group person))
            user-id (extract-id resp)]
        (admin/add-user-to-group! client realm group-id user-id)
        (update-user kc user-id {:enabled (:enabled person) :email-verified true})))))

(defn create-new-member!
  ([kc member can-login?]
   (create-new-member! kc member nil can-login?))
  ([kc {:member/keys [email username name]} password can-login?]
   (assert (not (str/blank? email)))
   (assert (not (str/blank? username)))
   (assert (not (str/blank? name)))
   (let [new-user (create-user! kc
                                (cond-> {:username username
                                         :email email
                                         :enabled can-login?
                                         :group "Mitglieder"
                                         :first-name name}
                                  (not (str/blank? password))
                                  (assoc :password password)))]
     new-user)))

(defn kc-from-req [req]
  (-> req :system :keycloak))

(defn user-account-enabled? [kc keycloak-id]
  (:user/enabled? (get-user! kc keycloak-id)))

(comment

  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (require '[keycloak.admin :as admin])
    (def env (-> state/system :app.ig/env))
    (def kc (-> state/system :app.ig/keycloak))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn)))
  ;; rcf

  (find-members-matches {:db db :kc kc})
  (match-members-to-keycloak! {:db db :kc kc :datomic-conn conn})

  (get-user! kc "bcaa73f1-e080-420f-ac15-27882dbcb330")
  (get-user! kc "db15536d-9708-42fb-a1e5-61ddf6d7c190")
  (update-user-meta! kc
                     {:member/username "testusertest"
                      :member/keycloak-id  "bcaa73f1-e080-420f-ac15-27882dbcb330"
                      :member/email "testuser+test@example.com"
                      :member/active? true
                      :member/name "Testuser Testing"})

  (user-account-enabled? kc "e0acd2e3-1362-4854-b4fe-46813adced84")

  ;;
  )
