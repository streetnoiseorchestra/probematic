(ns app.songs.routes
  (:require
   [app.routes.datastar :as ds]
   [app.songs.detail.views :as detail.views]
   [app.songs.edit.api :as edit.api]
   [app.songs.edit.views :as edit.views]
   [app.songs.index.views :as index.views]
   [reitit.ring.malli :as reitit.ring.malli]))

(defn songs-list-routes []
  (ds/page-routes {:page-name ::index
                   :path      "/songs"
                   :page      #'index.views/page}))

(defn songs-new-routes []
  (ds/page-routes {:page-name ::create
                   :path      "/songs/new"
                   :page      #'edit.views/page}))

(defn song-edit-routes []
  (ds/page-routes {:page-name ::edit
                   :path      "/song/{song-id}/edit"
                   :page      #'edit.views/page}))

(defn song-detail-routes []
  (ds/page-routes {:page-name ::detail
                   :path      "/song/{song-id}"
                   :page      #'detail.views/page}))

(defn routes []
  ["" {:app.route/name :app/songs}
   (song-detail-routes)
   (song-edit-routes)
   (songs-list-routes)
   (songs-new-routes)
   ["/song-media/{song-id}"
    {:post {:summary "Upload media for an song"
            :parameters {:multipart [:map [:file reitit.ring.malli/temp-file-part]]
                         :path [:map [:song-id :uuid]]}
            :handler (fn [req] (edit.api/image-upload-handler req))}}]])

(defn unauthenticated-routes []
  [""
   ["/song-media/{song-id}/{filename}"
    {:get {:summary "Get song media"
           :parameters {:path [:map
                               [:song-id :uuid]
                               [:filename :string]]}
           :handler (fn [req]
                      (edit.api/image-fetch-handler req))}}]])
