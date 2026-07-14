(ns app.jobs.avatar-cutover
  "Sequential cutover from Discourse avatar templates to managed avatars."
  (:require
   [app.filestore.controller :as filestore.controller]
   [babashka.fs :as bfs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as μ]
   [datomic.api :as d]
   [ol.jobs-util :as jobs]
   [org.httpkit.client :as http]))

(def max-avatar-size (* 5 1024 1024))
(def allowed-avatar-types #{"image/jpeg" "image/png" "image/webp"})

(defn- content-type [headers]
  (some-> (:content-type headers)
          (str/split #";" 2)
          first
          str/trim
          str/lower-case))

(defn- absolute-avatar-url [forum-url template]
  (let [expanded (str/replace template "{size}" "320")]
    (if (re-find #"(?i)^https?://" expanded)
      expanded
      (str (str/replace (or forum-url "") #"/$" "")
           (when-not (str/starts-with? expanded "/") "/")
           expanded))))

(defn- suffix-for [mime-type]
  (case mime-type
    "image/png" ".png"
    "image/webp" ".webp"
    ".jpg"))

(defn- download-avatar! [url]
  (let [{:keys [status headers body error]}
        @(http/request {:method :get
                        :url url
                        :as :byte-array
                        :follow-redirects true
                        :timeout 20000})
        mime-type (content-type headers)]
    (when error
      (throw (ex-info "Unable to download Discourse avatar"
                      {:url url}
                      error)))
    (when-not (= 200 status)
      (throw (ex-info "Discourse avatar download failed"
                      {:url url :status status})))
    (when-not (contains? allowed-avatar-types mime-type)
      (throw (ex-info "Unsupported Discourse avatar content type"
                      {:url url :mime-type mime-type})))
    (when (< max-avatar-size (alength ^bytes body))
      (throw (ex-info "Discourse avatar exceeds the upload limit"
                      {:url url :size (alength ^bytes body)})))
    (let [tempfile (bfs/create-temp-file
                    {:prefix "probematic.avatar-cutover."
                     :suffix (suffix-for mime-type)})]
      (with-open [out (io/output-stream (bfs/file tempfile))]
        (.write out ^bytes body))
      {:filename (str "discourse-avatar" (suffix-for mime-type))
       :mime-type mime-type
       :size (alength ^bytes body)
       :tempfile (bfs/file tempfile)})))

(defn- cas-failure? [exception]
  (some #(= :db.error/cas-failed (:db/error (ex-data %)))
        (take-while some? (iterate ex-cause exception))))

(defn- candidate-members [db]
  (->> (d/q '[:find ?member-id ?template
              :where
              [?member :member/member-id ?member-id]
              [?member :member/avatar-template ?template]
              (not [?member :member/avatar _])]
            db)
       (sort-by (comp str first))))

(defn- migrate-member! [system member-id template]
  (let [conn (-> system :datomic :conn)
        url (absolute-avatar-url (get-in system [:env :discourse :forum-url])
                                 template)
        upload (download-avatar! url)]
    (try
      (let [{:keys [image-tempid tx-data]}
            (filestore.controller/store-avatar!
             {:filestore (:filestore system)}
             {:file-name (:filename upload)
              :file (:tempfile upload)
              :mime-type (:mime-type upload)})]
        @(d/transact
          conn
          (into (vec tx-data)
                [[:db.fn/cas [:member/member-id member-id]
                  :member/avatar-template template template]
                 [:db.fn/cas [:member/member-id member-id]
                  :member/avatar nil image-tempid]])))
      (finally
        (bfs/delete-if-exists (:tempfile upload))))))

(defn cutover-avatars!
  "Migrates eligible Discourse avatars sequentially, one transaction per member.

  Members that already have a managed avatar are excluded.
  HTTP and image failures are logged and remain eligible for the next startup.
  Compare-and-swap conflicts preserve the newer profile edit.

  System keys:

  | key                    | description
  |------------------------|-------------
  | `[:datomic :conn]`     | Datomic connection
  | `:filestore`           | Managed file block store
  | `[:env :discourse]`    | Discourse `:forum-url` configuration"
  [system]
  (let [conn (-> system :datomic :conn)
        candidates (candidate-members (d/db conn))]
    (reduce
     (fn [result [member-id template]]
       (try
         (migrate-member! system member-id template)
         (update result :migrated inc)
         (catch Throwable exception
           (if (cas-failure? exception)
             (do
               (μ/log ::avatar-cutover-conflict :member-id member-id)
               (update result :conflicted inc))
             (do
               (μ/log ::avatar-cutover-failed
                      :member-id member-id
                      :exception exception)
               (update result :failed inc))))))
     {:processed (count candidates)
      :migrated 0
      :failed 0
      :conflicted 0}
     candidates)))

(defn avatar-cutover-job
  "Runs the managed-avatar cutover for a scheduled invocation."
  [system _time]
  (cutover-avatars! system))

(defn make-avatar-cutover-job
  "Creates the configured one-shot avatar cutover job definition."
  [system]
  (fn [{:job/keys [initial-delay]}]
    (jobs/make-one-shot-job
     (partial #'avatar-cutover-job system)
     (or initial-delay [30 :seconds]))))
