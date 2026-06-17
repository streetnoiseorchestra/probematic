(ns app.songs.routes
  (:require
   [app.routes.datastar :as ds]
   [app.songs.detail.views]
   [app.songs.edit.api :as edit.api]
   [app.songs.edit.views]
   [app.songs.index.views]
   [reitit.ring.malli :as reitit.ring.malli]))

(defn songs-list-routes []
  (ds/page-routes {:page-name ::index
                   :path      "/songs"
                   :view-ns   'app.songs.index.views}))

(defn songs-new-routes []
  (ds/page-routes {:page-name  ::create
                   :path       "/songs/new"
                   :view-ns 'app.songs.edit.views}))

(defn song-edit-routes []
  (ds/page-routes {:page-name  ::edit
                   :path       "/song/{song-id}/edit"
                   :view-ns 'app.songs.edit.views}))

(defn song-detail-routes []
  (ds/page-routes {:page-name ::detail
                   :path      "/song/{song-id}"
                   :view-ns   'app.songs.detail.views}))

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
