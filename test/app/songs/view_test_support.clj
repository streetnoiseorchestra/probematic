(ns app.songs.view-test-support
  (:require
   [app.test-common :as tc]
   [datomic.api :as d]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(defn new-system [prefix]
  (tc/new-system prefix))

(defn request
  ([conn]
   (request conn {}))
  ([conn extra]
   (merge {::r/router       router
           :current-locale :en
           :db             (d/db conn)
           :page-state     {}
           :system         {:env {}}
           :tr             tr}
          extra)))

(defn seed-song! [conn song-id]
  @(d/transact conn [{:song/song-id song-id
                      :song/title   "Watermelon Man"
                      :song/active? true}]))
