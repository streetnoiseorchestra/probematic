(ns app.filestore.image
  (:require
   [babashka.fs :as bfs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [medley.core :as m]
   [ol.vips :as v]
   [ol.vips.operations :as ops]))

(def supported-formats [{:format :gif :ext ".gif" :mime-type "image/gif" :im-tag "GIF"}
                        {:format :jpeg :ext ".jpeg" :mime-type "image/jpeg" :im-tag "JPEG"}
                        {:format :png :ext ".png" :mime-type "image/png" :im-tag "PNG"}
                        {:format :svg :ext ".svg" :mime-type "image/svg+xml" :im-tag "SVG"}
                        {:format :heic :ext ".heic" :mime-type "image/heic" :im-tag "HEIC"}
                        {:format :webp :ext ".webp" :mime-type "image/webp" :im-tag "WEBP"}])

(def format-lookup (m/index-by :format supported-formats))
(def ext-lookup (m/index-by :ext supported-formats))
(def mime-lookup (m/index-by :mime-type supported-formats))
(def format->extension #(-> % format-lookup :ext))
(def format->mime #(or (-> % format-lookup :mime-type) "application/octet-stream"))

(def supported-mime-types (set (map :mime-type supported-formats)))

(defn initialize! []
  (v/init!))

#_(defn mime->extension [mime-type]
    (case mime-type
      "application/pdf"    ".pdf"
      "application/zip"    ".zip"
      "image/apng"         ".apng"
      "image/avif"         ".avif"
      "image/gif"          ".gif"
      "image/jpeg"         ".jpg"
      "image/png"          ".png"
      "image/svg+xml"      ".svg"
      "image/webp"         ".webp"
      "text/plain"         ".txt"
      nil))

(defn- prepare-input [{:keys [path content-thunk]}]
  (if path
    [path nil]
    (let [tmp (bfs/file (bfs/create-temp-file {:prefix "probematic." :suffix ".tmp"}))]
      (assert content-thunk "input path or content-thunk required")
      (with-open [stream (content-thunk)]
        (io/copy stream tmp))
      [(str tmp) tmp])))

(def quality-option-formats #{:jpeg :png :heic :webp})

(defn- thumbnail-save-options [format quality]
  (cond-> {:strip true}
    (and quality (quality-option-formats format))
    (assoc :Q (int quality))))

(defn- write-thumbnail-to-file! [image path format quality]
  (v/write-to-file image path (thumbnail-save-options format quality)))

(defn- generic-process
  [{:keys [input format quality width height] :as params}]
  (let [[path input-temp] (prepare-input input)
        _ (assert path "input path required")
        _ (assert format "output format required")
        ext (format->extension format)
        tmp (bfs/file (bfs/create-temp-file {:prefix "snorga." :suffix ext}))]
    (try
      (with-open [thumbnail (ops/thumbnail path
                                           (int width)
                                           {:height      (int height)
                                            :size        :down
                                            :auto-rotate true})]
        (write-thumbnail-to-file! thumbnail tmp format quality))
      (finally
        (when input-temp
          (bfs/delete-if-exists input-temp))))

    (assoc params
           :ext ext
           :format format
           :mime-type (format->mime format)
           :size (bfs/size tmp)
           :out-file tmp)))

(defn process-thumbnail-down
  "Create a thumbnail of the image, scaling down to fit within the specified dimensions preserving the aspect ratio, will not upscale."
  [{:keys [quality width height] :as params}]
  (assert (and width height) "width and height required")
  (assert quality "quality required")
  (generic-process params))

(defn- loader->format [loader]
  (when-let [loader (some-> loader str str/lower-case)]
    (cond
      (str/includes? loader "jpeg") :jpeg
      (str/includes? loader "png") :png
      (str/includes? loader "gif") :gif
      (str/includes? loader "svg") :svg
      (or (str/includes? loader "heif")
          (str/includes? loader "heic")) :heic
      (str/includes? loader "webp") :webp
      :else nil)))

(def ext-format-overrides
  {"jpg" :jpeg
   "jpe" :jpeg
   "heif" :heic})

(defn- path->format [path]
  (let [[_ ext] (bfs/split-ext path)
        ext (some-> ext str/lower-case)]
    (or (get ext-format-overrides ext)
        (when ext
          (-> (str "." ext) ext-lookup :format)))))

(defn- image->format [image path]
  (or (loader->format (or (v/field image "vips-loader")
                          (v/field image "loader")))
      (path->format path)))

(defn- move-file! [source target]
  (bfs/move source target {:replace-existing true}))

(defn strip-metadata-in-place!
  "Strip all metadata from the image in place."
  [{:keys [input]}]
  (let [path (:path input)
        _ (assert path "In place operations require a path on disk, not a stream")
        [_ suffix] (bfs/split-ext path)
        tmp (bfs/file (bfs/create-temp-file {:prefix "snorga.strip." :suffix (if suffix (str "." suffix) ".img")}))]
    (try
      (with-open [image (v/from-file path {:access :sequential})
                  oriented (ops/autorot image)]
        (v/write-to-file oriented tmp {:strip true}))
      (move-file! tmp path)
      (finally
        (bfs/delete-if-exists tmp)))))

(defn process-thumbnail [{:keys [thumbnail-mode] :as params}]
  (condp = thumbnail-mode
    :thumbnail-down (process-thumbnail-down params)
    nil))

(defn- identify* [path]
  (with-open [image (v/from-file path {:access :sequential})]
    (let [headers (v/headers image)
          metadata (v/metadata image)
          format (image->format image path)]
      (assoc headers
             :format format
             :mime-type (format->mime format)
             :width (:width metadata)
             :height (:height metadata)))))

(defn identify
  ([path]
   (identify* path)))

(defn identify-detailed
  ([path]
   (identify* path)))

(comment
  (process-thumbnail-down {:input {:path "resources/public/img/tuba-robot-boat-1000.jpg"}
                           :quality 70
                           :width 10
                           :height 10
                           :format :jpeg})

  (process-thumbnail {:thumbnail-mode :thumbnail-down
                      :input {:path "resources/public/img/tuba-robot-boat-1000.jpg"}
                      :quality 70
                      :width 10
                      :height 10
                      :format :jpeg})
  (identify-detailed "resources/public/img/tuba-robot-boat-1000.jpg")
  (identify "/home/ramblurr/downloads/Test/IMG_3635.HEIC")
  (identify-detailed "/home/ramblurr/downloads/Test/test-stripped.heic")

  ;; rcf
  ;;
  )
