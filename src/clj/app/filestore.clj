(ns app.filestore
  "Stores uploaded and generated file content outside the database.

  The application keeps durable file bytes in this filestore and stores domain
  metadata such as filenames, MIME types, image dimensions, and ownership in Datomic.
  Files are addressed by a hash of their bytes, so the same content has the same
  identifier and metadata can reference stored content without copying it.

  ## Preparation

  Preparation is the boundary between an incoming source and durable storage.
  [[prepare]] reads ordinary file content, calculates its hash and byte size, and
  returns a prepared source map accepted by [[put!]] and [[put-sync!]].
  [[prepare-image!]] does the same for images after stripping metadata in place,
  then adds detected image format, MIME type, width, and height.

  Stripping image metadata avoids storing private EXIF/XMP data and makes image
  content safer to serve and compare.

  Use [[load-as-stream]] to open stored content by hash.

  ## Related Namespaces

  - [[app.filestore.image]] - Image identification, metadata stripping, and thumbnail generation.
  - [[app.filestore.controller]] - Higher-level Datomic metadata and image rendition workflows."
  (:require
   [app.filestore.image :as im]
   [babashka.fs :as bfs]
   [blocks.core :as block]
   [blocks.store.file :as blocks.store.file]
   [com.stuartsierra.component :as component]
   [multiformats.hash :as mhash]))

(defn system-check!
  "Checks that `store-path` can host the filestore and that image processing is available.

  Throws `ExceptionInfo` when `store-path` does not exist, is not writable, or
  when the libvips runtime cannot be initialized."
  [store-path]
  (when-not (bfs/exists? store-path)
    (throw (ex-info "Filestore path does not exist" {:store-path store-path})))
  (when-not (bfs/writable? store-path)
    (throw (ex-info "Filestore path is not writeable" {:store-path store-path})))
  (try
    (im/initialize!)
    (catch Throwable t
      (throw (ex-info "libvips image processing runtime unavailable" {} t)))))

(defn start!
  "Starts a file-backed block store rooted at `:store-path`.

  The path must already exist and be writable.
  This also verifies that image processing can initialize before the component is returned.

  Options:

  | key           | description
  |---------------|-------------
  | `:store-path` | Existing writable directory used as the block store root"
  [{:keys [store-path]}]
  (system-check! store-path)
  (-> store-path
      (blocks.store.file/file-block-store)
      (component/start)))

(defn halt!
  "Stops `store` and returns the stopped component."
  [store]
  (component/stop store))

(defn put!
  "Stores `prepared-source` in `filestore` asynchronously.

  `prepared-source` must be the map returned by [[prepare]] or [[prepare-image!]].
  Returns the deferred result from `blocks.core/put!`."
  [filestore prepared-source]
  (block/put! filestore (:block prepared-source)))

(defn put-sync!
  "Stores `prepared-source` in `filestore` and waits for completion.

  `prepared-source` must be the map returned by [[prepare]] or [[prepare-image!]].
  Returns the stored block."
  [filestore prepared-source]
  @(put! filestore prepared-source))

(defn get-block-sync
  "Loads the block identified by multihash `hash` from `filestore`.

  `hash` must be a parsed `multiformats.hash.Multihash`, not the hex string
  returned by [[prepare]].
  Returns the loaded block or `nil` when the store does not contain it."
  [filestore hash]
  @(block/get filestore hash))

(defn as-input-stream
  "Opens block `b` as a `java.io.InputStream`.

  The caller owns and must close the returned stream."
  ^java.io.InputStream [b]
  (block/open b))

(defn load-as-stream
  "Loads the block identified by hex string `hash` and opens it as a stream.

  The caller owns and must close the returned `java.io.InputStream`."
  [filestore hash]
  (as-input-stream (get-block-sync filestore (mhash/parse hash))))

(defn prepare
  "Prepares `source` for storage in the filestore.

  `source` may be an `InputStream`, `Reader`, `File`, `byte[]`, `char[]`, or `String`.
  Returns a map with:

  | key      | description
  |----------|-------------
  | `:hash`  | Hex-encoded multihash of the block content
  | `:block` | Prepared block value to pass to [[put!]] or [[put-sync!]]
  | `:size`  | Content size in bytes"
  [source]
  (let [{:keys [id size] :as block} (block/read! source)]
    {:hash (mhash/hex id)
     :block block
     :size size}))

(defn prepare-image!
  "Strips metadata from image `file` in place and prepares it for storage.

  `file` must be a path on disk because metadata stripping mutates the file before hashing it.
  Returns a prepared source map with image metadata:

  | key          | description
  |--------------|-------------
  | `:hash`      | Hex-encoded multihash of the stripped image content
  | `:block`     | Prepared block value to pass to [[put!]] or [[put-sync!]]
  | `:format`    | Detected image format keyword
  | `:mime-type` | Detected image MIME type
  | `:size`      | Stripped image size in bytes
  | `:width`     | Image width in pixels
  | `:height`    | Image height in pixels"
  [file]
  (im/strip-metadata-in-place! {:input {:path file}})
  (let [{:keys [width height format mime-type]} (im/identify-detailed file)
        {:keys [id size] :as block} (block/read! file)]
    {:hash (mhash/hex id)
     :block block
     :format format
     :mime-type mime-type
     :size size
     :width width
     :height height}))

(comment
  (def store (start! {:store-path "/home/ramblurr/src/sno/probematic/data.dev/filestore"}))
  (def hello (block/read! "hello world"))
  (str (:id hello))

  @(block/put! store hello)
  @(block/stat store (:id hello))
  (def hello2 (first (block/list-seq store)))

  ;; TODO how to load string hash
  @(block/get store (str (:id hello)))

  (mhash/hex (:id hello))
  (mhash/parse (mhash/hex (:id hello)))

  (slurp (block/open hello))

;;
  )
