(ns app.filestore.image-test
  (:require
   [app.file-utils :as fs]
   [app.filestore.image :as image]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io FileInputStream]
   [java.nio.file Files StandardCopyOption]))

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
  (let [target (fs/tempfile :prefix "probematic.image-test." :suffix suffix)]
    (Files/copy (.toPath (io/file source))
                (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    target))

(defn- delete-result-file! [{:keys [out-file]}]
  (when out-file
    (fs/delete-if-exists out-file)))

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

(deftest strip-metadata-in-place-test
  (let [copy (copy-to-temp-file jpeg-path ".jpg")]
    (try
      (let [before (image/identify-detailed copy)]
        (is (has-metadata-marker? before))
        (image/strip-metadata-in-place! {:input {:path copy}})
        (let [after (image/identify-detailed copy)]
          (is (= (select-keys before [:format :mime-type :width :height])
                 (select-keys after [:format :mime-type :width :height])))
          (is (not (has-metadata-marker? after)))))
      (finally
        (fs/delete-if-exists copy)))))
