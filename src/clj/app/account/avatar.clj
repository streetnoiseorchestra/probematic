(ns app.account.avatar
  "Authenticated delivery for fixed managed-avatar renditions."
  (:require
   [app.filestore.controller :as filestore.controller]
   [app.queries :as queries]
   [app.util :as util]
   [ring.middleware.not-modified :as not-modified]
   [ring.util.time :as ring-time]))

(def allowed-sizes #{40 80 160 320})

(defn- requested-size [value]
  (cond
    (integer? value) value
    (string? value) (try
                      (Long/parseLong value)
                      (catch NumberFormatException _exception
                        nil))))

(defn- rendition [member size]
  (some #(when (and (= size (:image/width %))
                    (= size (:image/height %)))
           %)
        (get-in member [:member/avatar :image/renditions])))

(defn member-avatar-handler
  "Serves one member-owned managed-avatar rendition.

  Requests are restricted to the fixed rendition sizes generated during upload.
  The member UUID, rather than a free image UUID, owns the public URL.

  Request keys:

  | key                | description
  |--------------------|-------------
  | `:db`              | Current Datomic database value
  | `:filestore`       | Application filestore component
  | `:parameters/path` | Coerced `:member-id` and `:size` values"
  [{:keys [db filestore] :as request}]
  (let [{:keys [member-id size]} (get-in request [:parameters :path])
        size (requested-size size)
        member-id (try
                    (util/ensure-uuid! member-id)
                    (catch Exception _exception
                      nil))
        member (when (and db member-id (allowed-sizes size))
                 (queries/retrieve-member db member-id))
        image (rendition member size)]
    (if-not image
      {:status 404 :headers {} :body ""}
      (let [{:keys [mime-type etag last-modified size content-thunk]}
            (filestore.controller/-load-image filestore image)]
        (not-modified/not-modified-response
         {:status 200
          :headers
          (cond-> {"Content-Type" mime-type
                   "Cache-Control" "private, max-age=3600"
                   "ETag" etag
                   "Content-Length" (str size)}
            last-modified
            (assoc "Last-Modified" (ring-time/format-date last-modified)))
          :body (content-thunk)}
         request)))))
