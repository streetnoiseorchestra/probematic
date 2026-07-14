(ns app.nextcloud
  (:require
   [app.sardine :as sardine]
   [clojure.data.xml :as xml]
   [jsonista.core :as j]
   [org.httpkit.client :as client]
   [reitit.coercion.malli :as rcm]))

(def base-url-v1 "https://data.streetnoise.at/ocs/v1.php/cloud")
(def base-url-v2 "https://data.streetnoise.at/ocs/v2.php")

(defn add-url [req base-url]
  (update-in req [:url] (fn [url] (str base-url url))))

(defn add-auth [req config]
  (assoc-in req [:basic-auth] [(:username config) (:password config)]))

(defn add-ocs [req]
  (assoc-in req [:headers "OCS-APIRequest"] "true"))

(defn request-v1 [req config]
  (-> req
      (add-auth config)
      (add-url base-url-v1)
      add-ocs
      client/request))

(defn request-v2 [req config]
  (-> req
      (add-auth config)
      (add-url base-url-v2)
      add-ocs
      client/request))

(defn parse-body
  "Parse json in http response body"
  [response]
  (try
    (if-let [json-body (some-> response :body (j/read-value j/keyword-keys-object-mapper))]
      (assoc response :body json-body)
      response)
    (catch Exception _e
      response)))

(defn parse-body-xml
  [response]
  (try
    (if-let [xml-body (some-> response :body  (java.io.StringReader.) (xml/parse))]
      (assoc response :body xml-body)
      response)
    (catch Exception _e
      response)))

(defn get-user [config user-id]
  (let [resp @(request-v1 {:method  :get
                           :url     (str "/users/" user-id)
                           :headers {"accept" "application/json"}} config)]
    (->  resp parse-body :body :ocs :data)))

(defn list-users [config]
  (let [resp @(request-v1 {:method  :get
                           :url     "/users"
                           :headers {"accept" "application/json"}} config)]
    (->  resp parse-body :body :ocs :data)))

(defn get-shares [config dir]
  (let [resp @(request-v2 {:method :get
                           :query-params {:path dir}
                           :url "/apps/files_sharing/api/v1/shares"} config)]
    (->> resp
         parse-body-xml
         :body
         xml-seq
         (filter (comp #{:ocs} :tag))
         (map :content)
         flatten
         (filter #(= :data (:tag %)))
         (map :content)
         flatten
         (filter #(= :element (:tag %)))
         (map :content)
         (map (fn [thing]
                (->> thing (filter #(some? (:tag %)))
                     (map (fn [{:keys [tag content]}]
                            [tag (first content)]))
                     (into {})))))))

(defn get-shares-with-label [config dir label]
  (let [shares (get-shares config dir)]
    (filter #(= (:label %) label) shares)))

(defn share-folder-public-ro [config  dir label]
  (let [resp @(request-v2 {:method :post
                           ;; :headers {"content-type" "application/x-www-form-urlencoded"}
                           :form-params {:path dir
                                         :shareType "3"
                                         :permissions "1"
                                         :label label}
                           :url "/apps/files_sharing/api/v1/shares"} config)]
    (->> resp
         parse-body-xml
         :body
         xml-seq
         (filter (comp #{:ocs} :tag))
         (map :content)
         flatten
         (filter #(= :data (:tag %)))
         (map :content)
         flatten
         (filter #(some? (:tag %)))
         (map (fn [{:keys [tag content]}]
                [tag (first content)]))
         (into {}))))

(defn format-internal-url [nc-url path]
  (let [base-path "/apps/files/?dir="]
    (str nc-url base-path path)))

(defn routes []
  ["/nextcloud-fetch" {:parameters {:query [:map [:path string?]]}
                       :coercion rcm/coercion
                       :app.route/name :app/nextcloud-fetch
                       :handler (fn [req] (sardine/fetch-file-handler req false))}])

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require  '[app.datomic.shim :as datomic])
    (require '[app.datomic :as d])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))
    (def password (-> state/system :app.ig/env :nextcloud :password))
    (def username (-> state/system :app.ig/env :nextcloud :username))
    (def config {:username username :password password})) ;; rcf

  (def result (share-folder-public-ro config "/Dokumente/snorga-data/insurance-test/instrument/0186c752-c413-81f3-8534-8b8a377ca644" "testintclj"))
  (def result (get-shares config "/Dokumente/snorga-data/insurance-test/instrument/0186c752-c413-81f3-8534-8b8a377ca644"))

  (prn result)

  (->> result
       xml-seq
       (filter (comp #{:ocs} :tag))
       (map :content)
       flatten
       (filter #(= :data (:tag %)))
       (map :content)
       flatten
       (filter #(some? (:tag %)))
       (map (fn [{:keys [tag content]}]
              [tag (first content)]))
       (into {}))

  (list-users config)

  (get-user config "casey")

  ;;
  )
