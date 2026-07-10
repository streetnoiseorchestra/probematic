(ns app.songs.index.views-test
  (:require
   [app.songs.index.views :as views]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

(def translations
  {[:song/create-title] "Add Song"
   [:song/last-played]  "Last Played"
   [:song/score]        "Score"
   [:song/sync-songs]   "Sync songs"
   [:song/total-plays]  "Total Play Count"
   [:Active]            "Active"
   [:Inactive]          "Inactive"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path _args]
   (tr path)))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def request
  {::r/router       router
   :current-locale :en
   :tr             tr})

(defn action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(deftest sync-action
  (testing "A member is viewing the repertoire toolbar."
    (let [actions (views/toolbar-actions request)
          sync    (some #(when (= "Sync songs" (l/text %)) %)
                        (l/select :app.ui2.button/button actions))]
      (testing "The sync action reports progress and cannot be repeated while it is running."
        (is (= {:label      "Sync songs"
                :action     :app.songs.index.actions/force-sync-songs
                :indicator  "songsIndexSyncing"
                :loading    "$songsIndexSyncing"
                :disabled   "$songsIndexSyncing"}
               {:label      (l/text sync)
                :action     (-> (l/attrs sync) :data-on:click action-keyword)
                :indicator  (:data-indicator (l/attrs sync))
                :loading    (:data-attr:loading (l/attrs sync))
                :disabled   (:data-attr:disabled (l/attrs sync))}))))))

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
