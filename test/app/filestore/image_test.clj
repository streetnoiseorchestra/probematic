(ns app.filestore.image-test
  (:require
   [app.filestore.image :as image]
   [babashka.fs :as bfs]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io FileInputStream]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")
(def png-path "resources/public/img/default-avatar.png")

(def metadata-marker-keys
  ["Properties:exif:Software"
   "Profiles:Profile-exif"
   "exif:Software"
   "exif-data"
   "icc-profile-data"
   "xmp-data"])

(defn- copy-to-temp-file [source suffix]
  (let [target (bfs/create-temp-file {:prefix "probematic.image-test." :suffix suffix})]
    (bfs/copy source target {:replace-existing true})
    target))

(defn- delete-result-file! [{:keys [out-file]}]
  (when out-file
    (bfs/delete-if-exists out-file)))

(defn- has-metadata-marker? [info]
  (boolean (some #(contains? info %) metadata-marker-keys)))

(deftest identify-test
  (is (= {:format    :jpeg
          :mime-type "image/jpeg"
          :width     1000
          :height    849}
         (select-keys (image/identify jpeg-path)
                      [:format :mime-type :width :height]))))

(deftest process-thumbnail-down-from-path-test
  (let [result (image/process-thumbnail-down {:input   {:path jpeg-path}
                                              :quality 70
                                              :width   20
                                              :height  20
                                              :format  :jpeg})]
    (try
      (is (= {:ext       ".jpeg"
              :format    :jpeg
              :mime-type "image/jpeg"}
             (select-keys result [:ext :format :mime-type])))
      (is (pos? (:size result)))
      (is (= {:format    :jpeg
              :mime-type "image/jpeg"
              :width     20
              :height    17}
             (select-keys (image/identify (:out-file result))
                          [:format :mime-type :width :height])))
      (finally
        (delete-result-file! result)))))

(deftest process-thumbnail-down-does-not-upscale-test
  (let [result (image/process-thumbnail {:thumbnail-mode :thumbnail-down
                                         :input          {:path png-path}
                                         :quality        70
                                         :width          1000
                                         :height         1000
                                         :format         :webp})]
    (try
      (is (= {:format    :webp
              :mime-type "image/webp"
              :width     300
              :height    300}
             (select-keys (image/identify (:out-file result))
                          [:format :mime-type :width :height])))
      (finally
        (delete-result-file! result)))))

(deftest process-thumbnail-down-from-stream-test
  (let [result (image/process-thumbnail {:thumbnail-mode :thumbnail-down
                                         :input          {:content-thunk #(FileInputStream. jpeg-path)}
                                         :quality        75
                                         :width          30
                                         :height         30
                                         :format         :webp})]
    (try
      (testing "returns a WebP thumbnail bounded by the requested dimensions"
        (let [info (image/identify (:out-file result))]
          (is (= {:format    :webp
                  :mime-type "image/webp"}
                 (select-keys info [:format :mime-type])))
          (is (and (pos? (:width info))
                   (pos? (:height info))
                   (<= (:width info) 30)
                   (<= (:height info) 30)))))
      (finally
        (delete-result-file! result)))))

(deftest process-avatar-square-center-crops-and-upscales-to-the-exact-size
  (let [process-avatar-square
        (some-> (ns-resolve 'app.filestore.image 'process-avatar-square) deref)]
    (is (fn? process-avatar-square)
        "app.filestore.image/process-avatar-square should exist")
    (when process-avatar-square
      (doseq [size [40 80 160 320]]
        (let [result (process-avatar-square
                      {:input {:path jpeg-path}
                       :size size
                       :quality 85
                       :format :webp})]
          (try
            (is (= {:format :webp
                    :mime-type "image/webp"
                    :width size
                    :height size}
                   (select-keys
                    (merge result (image/identify (:out-file result)))
                    [:format :mime-type :width :height])))
            (finally
              (delete-result-file! result))))))))

(deftest process-avatar-square-cleans-its-output-after-processing-fails
  (let [temp-dir (bfs/temp-dir)
        pattern "snorga.avatar.*.webp"
        before (set (bfs/glob temp-dir pattern))]
    (is (thrown? Throwable
                 (image/process-avatar-square
                  {:input {:path "/path/that/does/not/exist/avatar.jpg"}
                   :size 80
                   :quality 85
                   :format :webp})))
    (let [after (set (bfs/glob temp-dir pattern))]
      (try
        (is (= before after))
        (finally
          (doseq [path after
                  :when (not (contains? before path))]
            (bfs/delete-if-exists path)))))))

(deftest strip-metadata-in-place-test
  (let [copy (copy-to-temp-file jpeg-path ".tmp")]
    (try
      (let [before (image/identify-detailed copy)]
        (is (has-metadata-marker? before))
        (image/strip-metadata-in-place! {:input {:path copy}})
        (let [after (image/identify-detailed copy)]
          (is (= (select-keys before [:format :mime-type :width :height])
                 (select-keys after [:format :mime-type :width :height])))
          (is (not (has-metadata-marker? after)))))
      (finally
        (bfs/delete-if-exists copy)))))
