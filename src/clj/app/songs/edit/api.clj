(ns app.songs.edit.api
  (:require
   [app.config :as config]
   [app.errors :as errors]
   [app.file-utils :as fu]
   [app.sardine :as sardine]
   [app.urls :as urls]
   [jsonista.core :as j]))

(defn song-image-remote-path
  "Return the remote Nextcloud path for the song image upload directory or a specific file."
  ([req song-id]
   (fu/path-join (config/nextcloud-path-song-image-upload (-> req :system :env)) "song" (str song-id)))
  ([req song-id filename]
   (let [base-path (song-image-remote-path req song-id)
         path      (fu/path-join base-path filename)]
     (fu/validate-base-path! base-path path)
     path)))

(defn list-image-uris [{:keys [system webdav] :as req} song-id]
  (->> (sardine/list-photos webdav (song-image-remote-path req song-id))
       (map #(urls/absolute-link-song-image (:env system) song-id %))))

(defn image-fetch-handler [{:keys [parameters] :as req}]
  (let [{:keys [filename song-id]} (:path parameters)]
    (sardine/fetch-file-response req
                                 (song-image-remote-path req song-id filename)
                                 true)))

(defn image-upload-handler [{:keys [webdav parameters] :as req}]
  (try
    (let [song-id  (-> parameters :path :song-id)
          file     (-> parameters :multipart :file)
          filename (:filename file)]
      (assert song-id)
      (sardine/upload webdav
                      (song-image-remote-path req song-id)
                      file)
      {:status  201
       :headers {"content-type" "application/json"}
       :body    (j/write-value-as-string {:file-url (urls/absolute-link-song-image (-> req :system :env) song-id filename)})})
    (catch Exception e
      (errors/report-error! e)
      {:status  500
       :headers {"content-type" "application/json"}
       :body    (j/write-value-as-string {:error (str e)})})))
