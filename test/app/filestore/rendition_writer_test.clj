(ns app.filestore.rendition-writer-test
  (:require
   [app.filestore.avatar-test :as avatars]
   [app.filestore.controller :as controller]
   [app.filestore.domain :as domain]
   [app.game-loop :as game]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest on-demand-rendition-persistence-waits-for-the-writer
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (avatars/with-temp-filestore
        (fn [store]
          (let [upload      (fs/create-temp-file {:suffix ".jpg"})
                entered     (promise)
                release     (promise)
                filter-spec {:thumbnail-mode :thumbnail-down :width 23 :height 23 :quality 70 :format :webp}]
            (try
              (fs/copy avatars/jpeg-path upload {:replace-existing true})
              (let [{:keys [image-id tx-data]} (controller/store-avatar!
                                                {:filestore store} {:file-name "portrait.jpg" :file (fs/file upload) :mime-type "image/jpeg"})]
                (writer/call! runtime #(deref (d/transact conn tx-data)))
                (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                      (fn [_] (deliver entered true) @release))
                (is (= true (deref entered 5000 ::timeout)))
                (let [result (future (controller/create-rendition!
                                      {:datomic-conn conn :filestore store :system {:write-runner (:write-runner runtime)}}
                                      (q/retrieve-image (d/db conn) image-id) filter-spec))]
                  (is (= ::waiting (deref result 2000 ::waiting)))
                  (is (empty? (q/retrieve-renditions-for (d/db conn) image-id (domain/encode-filter-spec filter-spec))))
                  (deliver release true)
                  (let [rendition (deref result 5000 ::timeout)]
                    (is (uuid? (:image/image-id rendition)))
                    (is (= 23 (:width (avatars/identify-stored store (get-in rendition [:image/source-file :filestore.file/hash]))))))))
              (finally
                (deliver release true)
                (fs/delete-if-exists upload)))))))))
