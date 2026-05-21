(ns app.sardine
  (:require
   [app.routes.errors :as errors]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   (com.github.sardine DavResource)
   (com.github.sardine.impl SardineException SardineImpl)
   (java.net URLEncoder)
   (org.apache.http.client.utils URIBuilder)))

(defn- strip-leading-slash [path]
  (when path
    (string/replace path #"^/" "")))

(defn- path-join [& paths]
  (let [paths' (remove empty? paths)]
    (if (empty? paths')
      ""
      (str (apply io/file paths')))))

(defn- add-trailing-slash [path]
  (if (string/ends-with? path "/")
    path
    (str path "/")))

(defn- component-paths [path]
  (assert (string/starts-with? path "/") "Path must be absolute")
  (assert (not (string/ends-with? path "/")) "Path must not end with slash")
  (let [sub (string/split path #"/")]
    (map (fn [i]
           (str "/" (string/join "/" (subvec sub 1 (inc i)))))
         (range 1 (inc (count (re-seq #"/" path)))))))

(defn- validate-base-path! [base-path full-path]
  (when-not (string/starts-with? (str (fs/canonicalize full-path)) base-path)
    (throw (ex-info "Path traversal attack detected" {:base-path base-path
                                                      :full-path full-path}))))

(defn build-full-path [{:keys [webdav-base-path]} remote-path]
  (assert (string/starts-with? webdav-base-path "/") "webdav base path must start and end with a slash")
  (path-join webdav-base-path (strip-leading-slash remote-path)))

(defn build-uri [{:keys [host]} full-path]
  (-> (URIBuilder.)
      (.setScheme "https")
      (.setHost host)
      (.setPort 443)
      (.setPath full-path)
      (.toString)))

(defn build-sardine [token]
  (SardineImpl. token))

(defn build-config [{:keys [host webdav-base-path token]}]
  (assert (string/ends-with? webdav-base-path "/") "webdav base path must start and end with a slash")
  (assert (string/starts-with? webdav-base-path "/") "webdav base path must start and end with a slash")
  (assert (not (string/blank? host)) "Webdav host cannot be empty")
  (assert (not (string/blank? token)) "Webdav bearer token cannot be empty")
  {:token token :host host :webdav-base-path webdav-base-path
   :client (build-sardine token)})

(defn shutdown [^SardineImpl sardine]
  (.shutdown sardine))

(defn ->dav-resource [{:keys [webdav-base-path]} ^DavResource r]
  {:full-path (.getPath r)
   :path (subs (.getPath r) (count webdav-base-path))
   :creation-date (.getCreation r)
   :modified-date (.getModified r)
   :content-type (.getContentType r)
   :content-length (.getContentLength r)
   :display-name (.getDisplayName r)
   :resource-types (.getResourceTypes r)
   :directory? (.isDirectory r)
   :file? (not (.isDirectory r))
   :uri (.getHref r)
   :name (.getName r)})

(def excluded-folder-patterns
  [#".DAV"])

(defn list-directory
  ([webdav-config remote-path]
   (list-directory webdav-config remote-path 1))
  ([{:keys [client] :as webdav-config} remote-path depth]
   (let [full-path (build-full-path webdav-config remote-path)
         result (-> client (.list (build-uri webdav-config full-path) depth))]
     (->> (for [r result]
            (->dav-resource webdav-config r))
          (remove (fn [{:keys [path]}] (some #(re-find % path) excluded-folder-patterns)))
          ;; the directory we listed is included in the results, so lets remove it
          (remove #(= (add-trailing-slash full-path) (get % :full-path)))
          (sort-by (juxt :file? :name))))))

(defn dir-exists? [webdav dir]
  (try
    (list-directory webdav dir)
    true
    (catch com.github.sardine.impl.SardineException _e
      false)))

(defn stream-file [{:keys [client] :as webdav-config} remote-path]
  (let [uri (build-uri webdav-config (build-full-path webdav-config remote-path))]
    (-> client (.get uri))))

(defn content-disposition-filename [prefix name]
  (format "%s; filename*=UTF-8''%s" prefix (URLEncoder/encode name "UTF-8")))

(defn fetch-file-response [{:keys [webdav] :as req} path inline?]
  (try
    (let [{:keys [content-type content-length name]} (list-directory webdav path)]
      {:status 200
       :headers {"Content-Disposition" (content-disposition-filename (if inline?
                                                                       "inline" "attachment")
                                                                     name)
                 "Content-Type" content-type
                 "Content-Length" (str content-length)}
       :body (stream-file (:webdav req) path)})
    (catch SardineException e
      (cond
        (string/includes? (ex-message e) "404")
        (errors/not-found-error req (ex-info "Nextcloud file not found" {:file-path path} e))
        :else
        (errors/unknown-error req (ex-info "Error fetching nextcloud file" {:file-path path} e))))))

(defn fetch-file-handler [req inline?]
  (let [path (-> req :params :path)]
    (fetch-file-response req path inline?)))

(defn mkdirs [{:keys [client] :as webdav-config} remote-path]
  (doseq [component (component-paths remote-path)]
    (when-not (dir-exists? webdav-config component)
      (-> client (.createDirectory (build-uri webdav-config (build-full-path webdav-config component)))))))

(defn- upload-file [{:keys [client] :as webdav-config} remote-path in content-type]
  (assert (string/starts-with? remote-path "/") "remote-path must start with a slash")
  (let [parent-path (fs/unixify (fs/parent remote-path))]
    (when-not (dir-exists? webdav-config parent-path)
      (mkdirs webdav-config parent-path)))
  (try
    (-> client (.put (build-uri webdav-config (build-full-path webdav-config remote-path))
                     in content-type))
    (catch Exception _e
      ;; ignoring exceptions until this bug is fixed because an exception is always being throwed even on success
      ;; https://github.com/nextcloud/server/issues/35931
      ))
  :ok)

(defn upload [webdav base-path {:keys [filename tempfile content-type]}]
  (let [remote-path (path-join base-path filename)]
    (validate-base-path! base-path remote-path)
    (upload-file webdav remote-path (io/input-stream tempfile) content-type)))

(defn list-photos [webdav remote-path]
  (try
    (->> (list-directory webdav remote-path)
         (filter #(string/starts-with? (:content-type %) "image/"))
         (map (comp fs/file-name :path)))
    (catch SardineException _e
      [])))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def webdav-config (-> state/system :app.ig/webdav-sardine))) ;; rcf

  (build-full-path webdav-config "/Noten - Scores/aktuelle Stücke/Kingdom Come")
  (list-directory webdav-config "/Noten - Scores/aktuelle Stücke")

  (list-directory webdav-config "/Noten - Scores")
  (map (juxt  :content-type :name)
       (list-directory webdav-config "/Audio"))

  (upload-file webdav-config "/Dokumente/snorga-data/insurance-test/folder/tuba-robot.jpg"
               (io/input-stream (io/file "/var/home/ramblurr/src/sno/probematic/resources/public/img/tuba-robot-boat.jpg"))
               "image/jpg") ;; rcf
  ;;
  )
