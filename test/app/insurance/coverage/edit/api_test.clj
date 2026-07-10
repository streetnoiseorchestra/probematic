(ns app.insurance.coverage.edit.api-test
  (:require
   [app.filestore :as filestore]
   [app.insurance.coverage.edit.api :as api]
   [app.test-common :as tc]
   [babashka.fs :as bfs]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")

(defn- with-temp-filestore [f]
  (let [dir (bfs/create-temp-dir {:prefix "probematic.coverage-edit-upload-test."})]
    (try
      (let [store (filestore/start! {:store-path dir})]
        (try
          (f store)
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(defn- seed-instrument! [conn]
  (let [instrument-id (random-uuid)]
    @(d/transact conn [{:instrument/instrument-id instrument-id
                        :instrument/name          "Upload Test Instrument"}])
    instrument-id))

(defn- upload-file []
  (let [copy (bfs/create-temp-file {:prefix "ring-multipart-" :suffix ".tmp"})]
    (bfs/copy jpeg-path copy {:replace-existing true})
    (bfs/file copy)))

(defn- upload-req [{:keys [conn filestore instrument-id member-id tempfile]}]
  {:datomic-conn conn
   :db           (d/db conn)
   :filestore    filestore
   :system       {:env {:app-base-url "https://example.test"}}
   :session      {:session/member {:member/member-id member-id}}
   :parameters   {:path      {:instrument-id instrument-id}
                  :multipart {:file {:filename     "test upload.jpg"
                                     :tempfile     tempfile
                                     :content-type "image/jpeg"}}}})

(deftest image-upload-handler-test
  (testing "uploads an image, attaches it to the instrument, and removes the temporary file"
    (let [{:keys [conn member-id]} (tc/new-system "insurance-coverage-edit-api")
          instrument-id           (seed-instrument! conn)]
      (with-temp-filestore
        (fn [store]
          (let [tempfile (upload-file)
                response (api/image-upload-handler
                          (upload-req {:conn          conn
                                       :filestore     store
                                       :instrument-id instrument-id
                                       :member-id     member-id
                                       :tempfile      tempfile}))
                db-after (d/db conn)
                instrument (d/pull db-after
                                   '[:instrument/instrument-id
                                     :instrument/images-share-url
                                     {:instrument/images [:image/image-id
                                                          :image/width
                                                          :image/height
                                                          {:image/source-file [:filestore.file/file-name
                                                                               :filestore.file/mime-type]}]}]
                                   [:instrument/instrument-id instrument-id])]
            (is (= {:response        {:status  201
                                      :headers {"Content-Type" "application/json"}
                                      :body    "{}"}
                    :temp-deleted?   true
                    :image-count     1
                    :share-url       (str "https://example.test/instrument-public/" instrument-id)
                    :image-file-name "test upload.jpg"
                    :image-mime-type "image/jpeg"}
                   {:response        (select-keys response [:status :headers :body])
                    :temp-deleted?   (not (bfs/exists? tempfile))
                    :image-count     (count (:instrument/images instrument))
                    :share-url       (:instrument/images-share-url instrument)
                    :image-file-name (get-in instrument [:instrument/images 0 :image/source-file :filestore.file/file-name])
                    :image-mime-type (get-in instrument [:instrument/images 0 :image/source-file :filestore.file/mime-type])}))))))))
