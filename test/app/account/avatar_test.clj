(ns app.account.avatar-test
  (:require
   [app.account.test-support :as support]
   [app.filestore :as filestore]
   [app.test-common :as tc]
   [babashka.fs :as bfs]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")

(defn with-temp-filestore [f]
  (let [dir (bfs/create-temp-dir {:prefix "probematic.avatar-route-test."})]
    (try
      (let [store (filestore/start! {:store-path dir})]
        (try
          (f store)
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(deftest member-avatar-handler-serves-only-a-members-fixed-renditions
  (let [store-avatar! (support/public-fn
                       'app.filestore.controller/store-avatar!)
        handler (support/public-fn
                 'app.account.avatar/member-avatar-handler)]
    (is (fn? store-avatar!))
    (is (fn? handler) "app.account.avatar/member-avatar-handler should exist")
    (when (and store-avatar! handler)
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn member-id]} (tc/new-system "avatar-route")
                upload (bfs/create-temp-file
                        {:prefix "probematic.avatar-route-upload."
                         :suffix ".jpg"})]
            (try
              (bfs/copy jpeg-path upload {:replace-existing true})
              (let [{:keys [image-tempid tx-data]}
                    (store-avatar!
                     {:filestore store}
                     {:file-name "portrait.jpg"
                      :file (bfs/file upload)
                      :mime-type "image/jpeg"})]
                @(d/transact
                  conn
                  (conj (vec tx-data)
                        {:db/id [:member/member-id member-id]
                         :member/avatar image-tempid}))
                (let [response
                      (handler
                       {:db (d/db conn)
                        :filestore store
                        :headers {}
                        :parameters {:path {:member-id member-id
                                            :size 40}}})]
                  (is (= 200 (:status response)))
                  (is (= "image/webp"
                         (get-in response [:headers "Content-Type"])))
                  (is (string? (get-in response [:headers "ETag"])))
                  (is (string? (get-in response [:headers "Last-Modified"])))
                  (is (= "private, max-age=3600"
                         (get-in response [:headers "Cache-Control"])))
                  (is (instance? java.io.InputStream (:body response))))
                (is (= 404
                       (:status
                        (handler
                         {:db (d/db conn)
                          :filestore store
                          :headers {}
                          :parameters {:path {:member-id member-id
                                              :size 41}}}))))
                (is (= 404
                       (:status
                        (handler
                         {:db (d/db conn)
                          :filestore store
                          :headers {}
                          :parameters {:path {:member-id (random-uuid)
                                              :size 40}}})))))
              (finally
                (bfs/delete-if-exists upload)))))))))
