(ns app.filestore.controller-test
  (:require
   [app.filestore :as filestore]
   [app.filestore.controller :as controller]
   [babashka.fs :as bfs]
   [clojure.test :refer [deftest is testing]])
  (:import [java.util.concurrent ExecutionException]))

(defn- with-image-input [f]
  (let [file (bfs/create-temp-file {:prefix "image-storage-test-" :suffix ".jpg"})]
    (try
      (bfs/copy "resources/public/img/tuba-robot-boat-1000.jpg" file {:replace-existing true})
      (f {:file-name "upload.jpg" :mime-type "image/jpeg" :file (bfs/file file)})
      (finally (bfs/delete-if-exists file)))))

(deftest image-metadata-is-returned-only-after-content-storage-succeeds
  (with-image-input
    (fn [image-input]
      (testing "the content write completes before transaction data is returned"
        (let [entered (promise)
              release (promise)]
          (with-redefs [filestore/put! (fn [_ prepared]
                                         (deliver entered true)
                                         (future @release (:block prepared)))]
            (try
              (let [result (future (controller/store-image! {:filestore ::store} image-input))]
                (is (= true (deref entered 5000 ::timeout)))
                (is (= ::waiting (deref result 250 ::waiting)))
                (deliver release true)
                (is (seq (:tx-data (deref result 5000 ::timeout)))))
              (finally (deliver release true))))))
      (testing "failed content writes cannot produce transaction data"
        (with-redefs [filestore/put! (fn [& _] (future (throw (ex-info "Content write failed" {}))))]
          (is (thrown-with-msg? ExecutionException #"Content write failed"
                                (controller/store-image! {:filestore ::store} image-input))))))))
