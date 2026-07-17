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
   [jakarta.ws.rs NotFoundException WebApplicationException]
   [jakarta.ws.rs.core Response]
   [org.keycloak.admin.client Keycloak]
   [org.keycloak.admin.client.resource UserResource UsersResource]
   [org.keycloak.representations.idm GroupRepresentation UserRepresentation]))

(declare operations)

(def member-group-name "Mitglieder")

(defn create-client [sys]
  (let [kc-env (-> sys :env :keycloak)]
    (-> (keycloak/client-conf (dissoc kc-env :client-secret))
        (keycloak/keycloak-client (:client-secret kc-env)))))

(defn close-client! [^Keycloak client]
  (when client
    (.close client)))

(defn init! [sys]
  (let [kc {:client (create-client sys)
            :realm (-> sys :env :keycloak :realm)}]
    (merge kc (operations kc))))

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
            [[:member/set-keycloak-id
              (:member/member-id m)
              (:member/keycloak-id m)]
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

(defn delete-user!
  "Deletes the Keycloak user identified by `user-id`."
  [{:keys [client realm]} user-id]
  (assert (not (str/blank? user-id)))
  (admin/delete-user-by-id! client realm user-id))

(defn- normalize-attributes [attributes]
  (into {}
        (map (fn [[attribute values]]
               [(str attribute) (mapv str values)]))
        (or attributes {})))

(defn user-representation->keycloak-user
  "Normalizes a Keycloak user while preserving all custom attributes."
  [^UserRepresentation user]
  (cond-> {:id (.getId user)
           :enabled? (boolean (.isEnabled user))
           :attributes (normalize-attributes (.getAttributes user))}
    (.getUsername user) (assoc :username (.getUsername user))
    (.getEmail user) (assoc :email (.getEmail user))))

(defn exact-user-matches
  "Returns normalized users whose queried custom attributes match exactly."
  [users attributes]
  (let [attributes (normalize-attributes attributes)]
    (->> users
         (map user-representation->keycloak-user)
         (filter (fn [user]
                   (every? (fn [[attribute values]]
                             (= values (get-in user [:attributes attribute])))
                           attributes)))
         vec)))

(def ^:private user-search-page-size 1000)

(defn- attribute-query [attributes]
  (->> attributes
       (sort-by (comp str key))
       (keep (fn [[attribute values]]
               (when (= 1 (count values))
                 (str attribute ":" (first values)))))
       (str/join " ")))

(defn- find-keycloak-users-in-resource
  [^UsersResource users-resource attributes]
  (let [query (attribute-query attributes)
        fetch-page (if (seq query)
                     (fn [first-result]
                       (.searchByAttributes users-resource
                                            (int first-result)
                                            (int user-search-page-size)
                                            nil
                                            false
                                            query))
                     (fn [first-result]
                       (.list users-resource
                              (int first-result)
                              (int user-search-page-size))))]
    (loop [first-result 0
           matches []]
      (let [candidates (vec (fetch-page first-result))
            matches (into matches
                          (exact-user-matches candidates attributes))]
        (if (= user-search-page-size (count candidates))
          (recur (long (+ first-result user-search-page-size)) matches)
          matches)))))

(defn- find-keycloak-users-by-attributes
  [{:keys [client realm]} attributes]
  (let [^UsersResource users-resource (-> ^Keycloak client
                                          (.realm realm)
                                          (.users))]
    (find-keycloak-users-in-resource users-resource attributes)))

(defn- close-exception-response! [^WebApplicationException exception]
  (when-let [^Response response (.getResponse exception)]
    (.close response)))

(defn- get-keycloak-user [{:keys [client realm]} user-id]
  (try
    (some-> (admin/get-user client realm user-id)
            user-representation->keycloak-user)
    (catch NotFoundException exception
      (close-exception-response! exception)
      nil)))

(defn- find-keycloak-groups-by-name
  [{:keys [client realm]} group-name]
  (->> (admin/list-groups client realm group-name)
       (keep (fn [^GroupRepresentation group]
               (when (= group-name (.getName group))
                 {:id (.getId group)
                  :name (.getName group)})))
       vec))

(defn- user-spec->representation
  [{:keys [username email first-name enabled? email-verified? attributes]}]
  (doto (UserRepresentation.)
    (.setUsername username)
    (.setEmail email)
    (.setFirstName first-name)
    (.setEnabled enabled?)
    (.setEmailVerified email-verified?)
    (.setAttributes (java.util.HashMap. ^java.util.Map attributes))))

(defn- response-location-id [^Response response]
  (let [location (some-> response .getLocation str)
        user-id (when location
                  (subs location (inc (str/last-index-of location "/"))))]
    (when (str/blank? user-id)
      (throw (ex-info "Keycloak create response did not include a user ID"
                      {:location location})))
    user-id))

(defn- definite-client-error? [status]
  (<= 400 status 499))

(defn- rejected-on-web-application-error [f]
  (try
    (f)
    (catch WebApplicationException exception
      (let [^Response response (.getResponse exception)]
        (try
          (if (and response
                   (definite-client-error? (.getStatus response)))
            {:outcome :rejected}
            (throw exception))
          (finally
            (when response
              (.close response))))))))

(defn- create-keycloak-user!
  [{:keys [client realm]} user-spec]
  (rejected-on-web-application-error
   (fn []
     (let [^UsersResource users-resource (-> ^Keycloak client
                                             (.realm realm)
                                             (.users))
           ^Response response (.create users-resource
                                       (user-spec->representation user-spec))]
       (when-not response
         (throw (ex-info "Keycloak create returned no response" {})))
       (try
         (let [status (.getStatus response)]
           (cond
             (<= 200 status 299)
             {:outcome :created
              :user-id (response-location-id response)}

             (definite-client-error? status)
             {:outcome :rejected}

             :else
             (throw (ex-info "Keycloak create returned an ambiguous response"
                             {:status status}))))
         (finally
           (.close response)))))))

(defn- add-keycloak-user-to-group!
  [{:keys [client realm]} user-id group-id]
  (rejected-on-web-application-error
   (fn []
     (let [^UserResource user-resource (-> ^Keycloak client
                                           (.realm realm)
                                           (.users)
                                           (.get user-id))]
       (.joinGroup user-resource group-id)
       {:outcome :joined}))))

(defn- set-keycloak-user-enabled!
  [{:keys [client realm]} user-id enabled?]
  (rejected-on-web-application-error
   (fn []
     (let [^UserResource user-resource (-> ^Keycloak client
                                           (.realm realm)
                                           (.users)
                                           (.get user-id))]
       (.update user-resource (user/user-for-enablement enabled?))
       {:outcome :updated}))))

(defn- delete-keycloak-user!
  [{:keys [client realm]} user-id]
  (rejected-on-web-application-error
   (fn []
     (try
       (let [^UsersResource users-resource (-> ^Keycloak client
                                               (.realm realm)
                                               (.users))
             ^Response response (.delete users-resource user-id)]
         (when-not response
           (throw (ex-info "Keycloak delete returned no response" {})))
         (try
           (let [status (.getStatus response)]
             (cond
               (= 404 status) {:outcome :not-found}
               (<= 200 status 299) {:outcome :deleted}
               (definite-client-error? status) {:outcome :rejected}
               :else
               (throw
                (ex-info "Keycloak delete returned an ambiguous response"
                         {:status status}))))
           (finally
             (.close response))))
       (catch NotFoundException exception
         (close-exception-response! exception)
         {:outcome :not-found})))))

(defn operations
  "Builds reusable Keycloak operation functions backed by `kc`."
  [kc]
  {:find-users-by-attributes
   (fn [attributes]
     (find-keycloak-users-by-attributes kc attributes))
   :get-user
   (fn [user-id]
     (get-keycloak-user kc user-id))
   :find-groups-by-name
   (fn [group-name]
     (find-keycloak-groups-by-name kc group-name))
   :create-user!
   (fn [user-spec]
     (create-keycloak-user! kc user-spec))
   :add-user-to-group!
   (fn [user-id group-id]
     (add-keycloak-user-to-group! kc user-id group-id))
   :set-user-enabled!
   (fn [user-id enabled?]
     (set-keycloak-user-enabled! kc user-id enabled?))
   :delete-user!
   (fn [user-id]
     (delete-keycloak-user! kc user-id))})

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
                                         :group member-group-name
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
