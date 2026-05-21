(ns app.filestore.controller
  (:require
   [app.datomic :as d]
   [app.datomic.shim :as datomic]
   [app.filestore :as filestore]
   [app.filestore.domain :as domain]
   [app.filestore.image :as img]
   [app.queries :as q]
   [app.util :as util]
   [babashka.fs :as bfs]
   [com.yetanalytics.squuid :as sq]))

"
We rarely want to add a file to the store without also adding some reference to it in another identity.
So here we provide functions to store the content and generate datoms for use in a transaction
"

#_(defn store-file!
    "Stores the file in the content store, returns :file-tempid and :tx-data datoms for the filestore entity.
  If the file is already stored, it will not be stored again, but the tx-data will still be returned.
  If the tx-data is not transacted, then the content might be garbage collected at some point."
    [{:keys [filestore] :as sys} file-name file-source mime-type]
    (let [file-block (filestore/put! filestore file-source)
          tempid (d/tempid)
          txs (domain/txs-new-file tempid file-name mime-type file-block)]
      {:file-tempid tempid
       :tx-data txs}))

(defn fix-mime-type [reported-mime actual-mime]
  (if (and (= reported-mime "application/octet-stream") (some? actual-mime))
    actual-mime
    (if (not= reported-mime actual-mime)
      (throw (ex-info
              "Seems like you are uploading a file whose content does not match the extension."
              {:expected-mime-type reported-mime  :actual-mime-type actual-mime}))
      actual-mime)))

(defn store-image!
  "Stores the image in the content store, returns :image-tempid, :file-tempid, and :tx-data datoms for the filestore and image entities.
  If the image is already stored, it will not be stored again, but the tx-data will still be returned.
  If the tx-data is not transacted, then the content might be garbage collected at some point.
  "
  [{:keys [filestore]} {:keys [file-name file mime-type]}]
  (assert filestore "filestore service required")
  (assert file-name "file-name required")
  (assert mime-type "mime-type required")
  (assert file "file required")
  (let [{:keys [size hash width height] :as prepared actual-mime-type :mime-type} (filestore/prepare-image! file)
        image-tempid (d/tempid)
        file-tempid (d/tempid)
        file-txs (domain/txs-new-file file-tempid file-name (fix-mime-type mime-type actual-mime-type) size hash)
        image-txs (domain/txs-new-image image-tempid file-tempid width height)]

    (filestore/put! filestore prepared)
    {:image-tempid image-tempid
     :file-tempid file-tempid
     :tx-data (concat file-txs image-txs)}))

(defn -load-image
  [filestore {:image/keys [source-file]}]
  (let [{:filestore.file/keys [size hash mime-type file-name mtime]} source-file]
    {:mime-type mime-type
     :file-name file-name
     :etag hash
     :last-modified mtime
     :size size
     :content-thunk (fn [] (filestore/load-as-stream filestore hash))}))

(defn load-image
  "Load the image from the store
  The image content can be lazily loaded by calling the content-thunk function.
  "
  [{:keys [db filestore]} image-id]
  (let [image (q/retrieve-image db (util/ensure-uuid! image-id))]
    (-load-image filestore image)))

(defn build-rendition-filename [{:image/keys [source-file image-id]} {:keys [width height thumbnail-mode]} ext]
  (let [base (or (some-> (:filestore.file/file-name source-file) bfs/strip-ext str) image-id)
        suffix (format "-%s-%dx%d" (name thumbnail-mode) width height)]
    (str base suffix ext)))

(defn create-rendition! [{:keys [datomic-conn filestore]} parent-image filter-spec]
  (assert parent-image "parent-image required")
  (let [rendition-tempid                              (d/tempid)
        rendition-file-tempid                         (d/tempid)
        {:keys [out-file ext mime-type]}              (img/process-thumbnail (assoc filter-spec :input (-load-image filestore parent-image)))
        _ (assert mime-type "mime-type required")
        rendition-filename                            (build-rendition-filename parent-image filter-spec ext)
        {:keys [size hash width height] :as prepared} (filestore/prepare-image! out-file)
        file-txs                                      (domain/txs-new-file rendition-file-tempid rendition-filename mime-type size hash)
        rendition-id (sq/generate-squuid)
        rendition-txs                                 (domain/txs-new-rendition rendition-id rendition-tempid rendition-file-tempid parent-image width height filter-spec)
        txs                                           (concat file-txs rendition-txs)
        {:keys [db-after]} (datomic/transact datomic-conn {:tx-data txs})]
    (filestore/put-sync! filestore prepared)
    (bfs/delete-if-exists out-file)
    (q/retrieve-image db-after rendition-id)))

(defn load-image-rendition
  "Load the rendition matching the filter spec for the image, will create the rendition on-the-fly if it doesn't exist yet."
  [{:keys [db filestore] :as req} image-id filter-spec]
  (let [filter-spec-encoded (domain/encode-filter-spec filter-spec)
        image-id (util/ensure-uuid! image-id)
        renditions (q/retrieve-renditions-for db image-id filter-spec-encoded)
        rendition (first renditions)]
    (if rendition
      (load-image req (:image/image-id rendition))
      (let [rendition (create-rendition! req (q/retrieve-image db image-id) filter-spec)]
        (-load-image filestore rendition)))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def filestore (:app.ig/filestore state/system))
    (def full-opts (-> state/system :app.ig/env :insurance :images  :full-opts))
    (def thumbnail-opts (-> state/system :app.ig/env :insurance :images  :thumbnail-opts))
    (def db (datomic/db conn))) ;; rcf

  (q/retrieve-image db #uuid "018f70ec-eda8-8bd8-b37c-6e08724959db")
  (load-image-rendition {:datomic-conn conn :db (datomic/db conn) :filestore filestore} #uuid "018f70ec-eda8-8bd8-b37c-6e08724959db" thumbnail-opts)

  (filestore/load filestore "1220b5c1284f300ead3e8b523b9d42ec282fd75ded9e23f407c82599d44e78683b8c")

  ;;
  )
