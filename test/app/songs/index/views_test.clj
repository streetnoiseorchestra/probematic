(ns app.songs.index.views-test
  (:require
   [app.songs.index.views :as views]
   [app.songs.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def request
  {::r/router       router
   :current-locale :en})

(deftest repertoire-page-surface
  (testing "The repertoire has collection context and keeps synchronization secondary."
    (let [{:keys [conn]} (support/new-system "songs-index-surface")]
      (is (= {:width       :standard
              :breadcrumbs [:home :repertoire/title]
              :mobile      {:label :home :href "/"}
              :actions     [{:label      :repertoire/add-song
                             :href       "/songs/new"
                             :appearance "filled"
                             :variant    "brand"}]
              :overflow    [{:label              :repertoire/sync-songs
                             :action             :app.songs.index.actions/force-sync-songs
                             :data-indicator     "songsIndexSyncing"
                             :data-attr:loading  "$songsIndexSyncing"
                             :data-attr:disabled "$songsIndexSyncing"}]}
             (-> conn support/request views/page page-shell/page-contract))))))

(deftest last-played
  (testing "An active song was last played on 4 June 2026."
    (let [view (views/song-row
                request
                {:song/song-id        (random-uuid)
                 :song/title          "Watermelon Man"
                 :song/active?        true
                 :song/last-played-on (t/date "2026-06-04")})]
      (testing "The row shows the last-played date in compact weekday format."
        (is (= "Thu 04 Jun 2026"
               (-> (l/select-one
                    '[.songs-index-row-date .songs-index-stat-value]
                    view)
                   l/text)))))))
