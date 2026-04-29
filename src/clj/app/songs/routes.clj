(ns app.songs.routes
  (:require
   [app.routes.datastar :as ds]
   [app.songs.detail.views]
   [app.songs.edit.views]
   [app.songs.index.views]
   [app.songs.views :as view]
   [reitit.ring.malli :as reitit.ring.malli]))

(defn songs-sync []
  ["/songs-sync" {:app.route/name :app/songs-sync
                  :post (fn [req]
                          (view/songs-sync req))}])

(defn songs-list-routes []
  (ds/page-routes {:page-name ::index
                   :path      "/songs"
                   :view-ns   'app.songs.index.views}))

(defn songs-list-trailing-slash-routes []
  (ds/page-routes {:page-name ::index-trailing-slash
                   :path      "/songs/"
                   :view-ns   'app.songs.index.views}))

(defn songs-new-routes []
  (ds/page-routes {:page-name  ::create
                   :path       "/songs/new"
                   :view-ns    'app.songs.edit.views
                   :extra-head app.songs.edit.views/extra-head}))

(defn song-edit-routes []
  (ds/page-routes {:page-name  ::edit
                   :path       "/song/{song-id}/edit"
                   :view-ns    'app.songs.edit.views
                   :extra-head app.songs.edit.views/extra-head}))

(defn song-detail-routes []
  (ds/page-routes {:page-name ::detail
                   :path      "/song/{song-id}/"
                   :view-ns   'app.songs.detail.views}))

(defn song-detail-no-slash-routes []
  (ds/page-routes {:page-name ::detail-no-slash
                   :path      "/song/{song-id}"
                   :view-ns   'app.songs.detail.views}))

(defn routes []
  ["" {:app.route/name :app/songs}
   (songs-sync)
   (song-detail-routes)
   (song-detail-no-slash-routes)
   (song-edit-routes)
   (songs-list-routes)
   (songs-list-trailing-slash-routes)
   (songs-new-routes)
   ["/song-media/{song-id}"
    {:post {:summary "Upload media for an song"
            :parameters {:multipart [:map [:file reitit.ring.malli/temp-file-part]]
                         :path [:map [:song-id :uuid]]}
            :handler (fn [req] (view/image-upload-handler req))}}]])

(defn unauthenticated-routes []
  [""
   ["/song-media/{song-id}/{filename}"
    {:get {:summary "Get song media"
           :parameters {:path [:map
                               [:song-id :uuid]
                               [:filename :string]]}
           :handler (fn [req]
                      (view/image-fetch-handler req))}}]])
