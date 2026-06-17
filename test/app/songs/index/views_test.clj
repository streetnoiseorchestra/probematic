(ns app.songs.index.views-test
  (:require
   [app.songs.index.views :as views]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [reitit.core :as r]
   [tick.core :as t]))

(def translations
  {[:action/search]                    "Search"
   [:gig/probeplan-repertoire]         "Repertoire"
   [:gig/probeplan-repertoire-all]     "All"
   [:gig/probeplan-repertoire-current] "Current"
   [:gig/probeplan-repertoire-old]     "Old"
   [:song/create-title]                "Add Song"
   [:song/last-played]                 "Last Played"
   [:song/list-title]                  "Repertoire"
   [:song/score]                       "Score"
   [:song/search]                      "Search Songs"
   [:song/search-empty]                "No songs found"
   [:song/sync-songs]                  "Sync songs"
   [:song/total-plays]                 "Total Play Count"
   [:total]                            "Total"
   [:Active]                           "Active"
   [:Inactive]                         "Inactive"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path _args]
   (tr path)))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn req [conn]
  {::r/router  router
   :db         (d/db conn)
   :tr         tr
   :page-state {}})

(deftest songs-index-renders-force-sync-action
  (let [{:keys [conn]} (tc/new-system "songs-index-view-sync")
        html           (views/page (req conn))]
    (is (str/includes? html "Sync songs"))
    (is (str/includes? html "/act?ns=app.songs.index.actions&amp;kw=force-sync-songs"))
    (is (str/includes? html "data-indicator=\"songsIndexSyncing\""))
    (is (str/includes? html "data-attr:loading=\"$songsIndexSyncing\""))
    (is (str/includes? html "data-attr:disabled=\"$songsIndexSyncing\""))))

(deftest song-row-uses-compact-last-played-date
  (let [html (str (#'views/song-row
                   {:current-locale :en :tr tr}
                   {:song/song-id        (random-uuid)
                    :song/title          "Watermelon Man"
                    :song/active?        true
                    :song/last-played-on (t/date "2026-06-04")}))]
    (is (str/includes? html "Thu 04 Jun 2026"))
    (is (not (str/includes? html "Thursday, June 4, 2026")))))
