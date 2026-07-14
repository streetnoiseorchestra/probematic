(ns app.filestore.avatar-test
  (:require
   [app.account.test-support :as support]
   [app.filestore :as filestore]
   [app.filestore.image :as image]
   [app.test-common :as tc]
   [babashka.fs :as bfs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")

(defn with-temp-filestore [f]
  (let [dir (bfs/create-temp-dir {:prefix "probematic.avatar-store-test."})]
    (try
      (let [store (filestore/start! {:store-path dir})]
        (try
          (f store)
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(defn identify-stored [store hash]
  (let [path (bfs/create-temp-file
              {:prefix "probematic.avatar-rendition-test."
               :suffix ".webp"})]
    (try
      (with-open [stream (filestore/load-as-stream store hash)]
        (io/copy stream (bfs/file path)))
      (image/identify path)
      (finally
        (bfs/delete-if-exists path)))))

(deftest store-avatar-prepares-one-parent-and-four-stored-renditions
  (let [store-avatar! (support/public-fn
                       'app.filestore.controller/store-avatar!)]
    (is (fn? store-avatar!)
        "app.filestore.controller/store-avatar! should exist")
    (when store-avatar!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "stored-avatar")
                upload (bfs/create-temp-file
                        {:prefix "probematic.avatar-upload."
                         :suffix ".jpg"})]
            (try
              (bfs/copy jpeg-path upload {:replace-existing true})
              (let [{:keys [image-id image-tempid renditions tx-data]}
                    (store-avatar!
                     {:filestore store}
                     {:file-name "portrait.jpg"
                      :file (bfs/file upload)
                      :mime-type "image/jpeg"})]
                (is (uuid? image-id))
                (is (= #{40 80 160 320}
                       (set (map :size renditions))))
                (doseq [{:keys [size hash mime-type]} renditions]
                  (is (= "image/webp" mime-type))
                  (is (= {:width size :height size :mime-type "image/webp"}
                         (select-keys (identify-stored store hash)
                                      [:width :height :mime-type]))))
                @(d/transact
                  conn
                  (conj (vec tx-data)
                        {:db/id [:member/member-id member-id]
                         :member/avatar image-tempid}))
                (let [avatar (d/pull
                              (d/db conn)
                              [:image/image-id
                               {:image/renditions
                                [:image/image-id
                                 :image/width
                                 :image/height
                                 :image/filter-spec]}]
                              [:image/image-id image-id])]
                  (is (= image-id (:image/image-id avatar)))
                  (is (= #{40 80 160 320}
                         (set (map :image/width
                                   (:image/renditions avatar)))))
                  (is (every? #(= (:image/width %) (:image/height %))
                              (:image/renditions avatar)))))
              (finally
                (bfs/delete-if-exists upload)))))))))
