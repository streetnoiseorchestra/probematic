(ns app.insurance.public.api
  (:require
   [app.config :as config]
   [app.filestore.controller :as filestore]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [app.util.zip :as zip]
   [ring.middleware.not-modified :as not-modified]
   [ring.util.time :as ring.time]))

(defn build-image-uri [{:keys [system]} {:instrument/keys [instrument-id]} {:image/keys [image-id]}]
  (when image-id
    {:thumbnail (urls/absolute-link-instrument-image-thumbnail (:env system) instrument-id image-id)
     :full (urls/absolute-link-instrument-image-full (:env system) instrument-id image-id)}))

(defn build-image-uris [req {:instrument/keys [images] :as instrument}]
  (map #(build-image-uri req instrument %) images))

(defn image-response
  [{:keys [parameters system] :as req}]
  (let [image-id  (get-in parameters [:path :image-id])
        mode      (get-in parameters [:query :mode])
        settings  (config/insurance-image-settings (:env system))
        rendition (filestore/load-image-rendition
                   req
                   image-id
                   (if (= mode "thumbnail")
                     (:thumbnail-opts settings)
                     (:full-opts settings)))
        response  {:status  200
                   :headers (cond->
                             {"Content-Disposition"
                              (util/content-disposition-filename
                               (:file-name rendition))}
                              (:last-modified rendition)
                              (assoc "Last-Modified"
                                     (ring.time/format-date
                                      (:last-modified rendition)))

                              (:mime-type rendition)
                              (assoc "Content-Type" (:mime-type rendition))

                              (:etag rendition)
                              (assoc "ETag" (:etag rendition)))
                   :body    ((:content-thunk rendition))}]
    (not-modified/not-modified-response response req)))

(defn- append-images-to-zip! [zip attachments file-name-prefix]
  (doseq [{:keys [content file-name]} attachments]
    (when (and content file-name)
      (zip/open-and-append! zip (str file-name-prefix "_" file-name) content))))

(defn get-all-images-as-input-stream! [{:keys [db] :as req} instrument-id]
  (let [{:instrument/keys [name images]} (q/retrieve-instrument db
                                                                instrument-id
                                                                [:instrument/name
                                                                 {:instrument/images [:image/image-id {:image/source-file [:filestore.file/file-id :filestore.file/hash :filestore.file/filename]}]}])
        file-name                        (str name ".zip")
        attachments                      (->> images
                                              (map :image/image-id)
                                              (map (partial filestore/load-image req))
                                              (map (fn [{:keys [content-thunk file-name]}]
                                                     {:content   content-thunk
                                                      :file-name file-name})))]
    {:status  200
     :headers {"Content-Type"        "application/octet-stream"
               "Content-Disposition" (util/content-disposition-filename file-name false)}
     :body    (zip/piped-zip-input-stream
               (fn [zip]
                 (append-images-to-zip! zip attachments "instrument")))}))
