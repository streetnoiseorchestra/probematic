(ns app.insurance.public.api
  (:require
   [app.filestore.controller :as filestore]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [app.util.zip :as zip]))

(defn build-image-uri [{:keys [system]} {:instrument/keys [instrument-id]} {:image/keys [image-id]}]
  (when image-id
    {:thumbnail (urls/absolute-link-instrument-image-thumbnail (:env system) instrument-id image-id)
     :full (urls/absolute-link-instrument-image-full (:env system) instrument-id image-id)}))

(defn build-image-uris [req {:instrument/keys [images] :as instrument}]
  (map #(build-image-uri req instrument %) images))

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
