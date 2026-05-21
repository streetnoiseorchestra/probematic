(ns app.filestore-test
  (:require
   [app.filestore :as filestore]
   [babashka.fs :as bfs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [multiformats.hash :as mhash])
  (:import
   [java.io ByteArrayOutputStream]
   [java.nio.charset StandardCharsets]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")

(defn- utf-8-bytes [s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- stream->byte-vector [stream]
  (with-open [in stream
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    (vec (.toByteArray out))))

(defn- file->byte-vector [file]
  (stream->byte-vector (io/input-stream (bfs/file file))))

(defn- block->byte-vector [block]
  (stream->byte-vector (filestore/as-input-stream block)))

(defn- with-temp-filestore [f]
  (let [dir (bfs/create-temp-dir {:prefix "probematic.filestore-test."})]
    (try
      (let [store (filestore/start! {:store-path dir})]
        (try
          (f store dir)
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(defn- thrown-ex-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      ex)))

(deftest prepare-test
  (testing "prepares string content"
    (let [content  "hello, filestore"
          prepared (filestore/prepare content)
          block    (:block prepared)]
      (is (= {:hash  (mhash/hex (:id block))
              :size  (count (utf-8-bytes content))
              :bytes (vec (utf-8-bytes content))}
             (assoc (select-keys prepared [:hash :size])
                    :bytes (block->byte-vector block))))))
  (testing "prepares byte-array content"
    (let [content  (utf-8-bytes "byte payload")
          prepared (filestore/prepare content)
          block    (:block prepared)]
      (is (= {:hash  (mhash/hex (:id block))
              :size  (count content)
              :bytes (vec content)}
             (assoc (select-keys prepared [:hash :size])
                    :bytes (block->byte-vector block)))))))

(deftest put-and-load-roundtrip-test
  (with-temp-filestore
    (fn [store _dir]
      (let [content  (utf-8-bytes "stored payload\nwith another line")
            prepared (filestore/prepare content)]
        (filestore/put-sync! store prepared)
        (is (= (vec content)
               (stream->byte-vector (filestore/load-as-stream store (:hash prepared)))))))))

(deftest get-block-sync-test
  (with-temp-filestore
    (fn [store _dir]
      (let [content      (utf-8-bytes "get-block-sync payload")
            prepared     (filestore/prepare content)
            stored-block (filestore/put-sync! store prepared)
            loaded-block (filestore/get-block-sync store (mhash/parse (:hash prepared)))]
        (is (= {:id    (:id stored-block)
                :size  (count content)
                :bytes (vec content)}
               {:id    (:id loaded-block)
                :size  (:size loaded-block)
                :bytes (block->byte-vector loaded-block)}))))))

(deftest persists-across-restart-test
  (let [dir     (bfs/create-temp-dir {:prefix "probematic.filestore-restart-test."})
        content (utf-8-bytes "payload that survives restart")]
    (try
      (let [hash (let [store    (filestore/start! {:store-path dir})
                       prepared (filestore/prepare content)]
                   (try
                     (filestore/put-sync! store prepared)
                     (:hash prepared)
                     (finally
                       (filestore/halt! store))))
            store (filestore/start! {:store-path dir})]
        (try
          (is (= (vec content)
                 (stream->byte-vector (filestore/load-as-stream store hash))))
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(deftest start-requires-existing-store-path-test
  (let [missing-path (bfs/path (System/getProperty "java.io.tmpdir")
                               (str "probematic.filestore-missing-" (random-uuid)))
        ex           (thrown-ex-info #(filestore/start! {:store-path missing-path}))]
    (is (= {:message "Filestore path does not exist"
            :data    {:store-path missing-path}}
           {:message (ex-message ex)
            :data    (ex-data ex)}))))

(deftest prepare-image-test
  (let [copy (bfs/create-temp-file {:prefix "probematic.filestore-image-test." :suffix ".jpg"})]
    (try
      (bfs/copy jpeg-path copy {:replace-existing true})
      (let [file (bfs/file copy)
            {:keys [block] :as prepared} (filestore/prepare-image! file)]
        (is (= {:hash      (mhash/hex (:id block))
                :format    :jpeg
                :mime-type "image/jpeg"
                :size      (:size block)
                :width     1000
                :height    849
                :bytes     (file->byte-vector copy)}
               (assoc (select-keys prepared [:hash :format :mime-type :size :width :height])
                      :bytes (block->byte-vector block)))))
      (finally
        (bfs/delete-if-exists copy)))))
