(ns app.insurance.coverage.edit.api
  (:require
   [app.datomic :as d]
   [app.file-utils :as fs]
   [app.filestore.controller :as filestore]
   [app.insurance.domain :as domain]
   [app.util :as util]
   [babashka.fs :as bfs]
   [clojure.string :as str]))

(defn- content-type-extension [content-type]
  (case content-type
    "image/jpeg" ".jpg"
    "image/jpg" ".jpg"
    "image/png" ".png"
    "image/gif" ".gif"
    "image/webp" ".webp"
    "image/heic" ".heic"
    "image/svg+xml" ".svg"
    nil))

(defn- filename-extension [filename]
  (when-let [ext (second (bfs/split-ext (str filename)))]
    (when (re-matches #"[A-Za-z0-9]+" ext)
      (str "." (str/lower-case ext)))))

(defn- temp-file-suffix [filename content-type]
  (or (filename-extension filename)
      (content-type-extension content-type)
      ".img"))

(defn- upload-working-file! [filename content-type tempfile]
  (let [working-file (bfs/create-temp-file {:prefix "snorga.upload."
                                            :suffix (temp-file-suffix filename content-type)})]
    (bfs/copy tempfile working-file {:replace-existing true})
    (bfs/file working-file)))

(defn upload-instrument-image!
  [req instrument-id {:keys [filename tempfile content-type]}]
  (let [working-file_ (volatile! nil)]
    (try
      (let [working-file                    (upload-working-file! filename content-type tempfile)
            _                               (vreset! working-file_ working-file)
            {:keys [image-tempid tx-data]} (filestore/store-image! req {:file-name filename
                                                                        :file      working-file
                                                                        :mime-type content-type})
            instrument-txs                 (domain/txs-add-instrument-image req instrument-id image-tempid)
            tx-data                        (concat tx-data instrument-txs)]
        (d/transact-wrapper! req {:tx-data tx-data}))
      (finally
        (when-let [working-file @working-file_]
          (fs/delete-if-exists working-file))
        (when tempfile
          (fs/delete-if-exists tempfile))))))

(defn image-upload-handler
  [{:keys [parameters] :as req}]
  (let [instrument-id (util/ensure-uuid! (-> parameters :path :instrument-id))
        file          (-> parameters :multipart :file)]
    (upload-instrument-image! req instrument-id file)
    {:status 201}))
