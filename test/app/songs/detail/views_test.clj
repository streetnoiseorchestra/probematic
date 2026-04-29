(ns app.songs.detail.views-test
  (:require
   [app.songs.detail.views :as views]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [hiccup2.core :as h]
   [reitit.core :as r]))

(def translations
  {[:action/add]                     "Add"
   [:action/back]                    "Back"
   [:action/cancel]                  "Cancel"
   [:action/download]                "Download"
   [:action/confirm-delete]          "Yes, delete it"
   [:action/confirm-generic]         "Are you sure?"
   [:action/remove]                  "Remove"
   [:nav/songs]                      "Repertoire"
   [:nav/forum]                      "Forum"
   [:song/active]                    "Active?"
   [:song/arrangement-credits]       "Arranged By"
   [:song/arrangement-notes]         "Arrangement Info"
   [:song/background-title]          "Background"
   [:song/composition-credits]       "Composition By"
   [:song/gig-count]                 "Gig Count"
   [:song/last-played]               "Last Played"
   [:song/last-played-gig]           "Last Played Gig"
   [:song/last-played-probe]         "Last Played Rehearsal"
   [:song/lyrics]                    "Lyrics"
   [:song/origin]                    "Origin"
   [:song/other-sheet-music]         "Musescore, etc"
   [:song/play-stats-title]          "Play Stats"
   [:song/probe-count]               "Rehearsal Count"
   [:song/score]                     "Score"
   [:song/sheet-music-title]         "Sheet Music"
   [:song/choose-sheet-music-title]  "Choose Sheet Music File"
   [:song/choose-sheet-music-subtitle] "For section: %1"
   [:song/solo-count]                "# Solos"
   [:song/total-plays]               "Total Play Count"
   [:song/total-performances]        "Total Performances"
   [:song/total-rehearsals]          "Total Rehearsals"
   [:Active]                         "Active"
   [:Inactive]                       "Inactive"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path _args]
   (tr path)))

(defn seed-song! [conn song]
  @(d/transact conn [song]))

(defn seed-section! [conn section]
  @(d/transact conn [section]))

(defn seed-sheet-music! [conn sheet]
  @(d/transact conn [sheet]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn req
  ([conn song-id]
   (req conn song-id nil))
  ([conn song-id current-section]
   (cond-> {::r/router   router
            :db          (d/db conn)
            :tr          tr
            :system      {:env {:nextcloud {:sheet-music-path   "/Noten - Scores"
                                            :current-songs-path "/Noten - Scores/aktuelle Stücke"}}}
            :page-state  {}
            :path-params {:song-id (str song-id)}}
     current-section
     (assoc-in [:session :session/member :member/section :section/name]
               current-section))))

(deftest song-detail-page-renders-read-only-song-information
  (let [{:keys [conn]} (tc/new-system "song-detail-page")
        song-id        (random-uuid)]
    (seed-song! conn {:song/song-id             song-id
                      :song/title               "Watermelon Man"
                      :song/active?             true
                      :song/solo-info           "Alto"
                      :song/composition-credits "Herbie Hancock"
                      :song/arrangement-credits "StreetNoise"
                      :song/origin              "Hard bop tune"
                      :song/arrangement-notes   "Watch the break"
                      :song/lyrics              "Watermelon"
                      :song/total-plays         22
                      :song/total-performances  7
                      :song/total-rehearsals    15})
    (let [html (views/page (req conn song-id))]
      (testing "renders the song summary"
        (is (str/includes? html "Watermelon Man"))
        (is (str/includes? html "Active"))
        (is (str/includes? html "Repertoire")))

      (testing "renders background details"
        (is (str/includes? html "Background"))
        (is (str/includes? html "Herbie Hancock"))
        (is (str/includes? html "StreetNoise"))
        (is (str/includes? html "Hard bop tune"))
        (is (str/includes? html "Watch the break"))
        (is (str/includes? html "Watermelon")))

      (testing "renders play stats"
        (is (str/includes? html "Play Stats"))
        (is (str/includes? html "22"))
        (is (str/includes? html "7"))
        (is (str/includes? html "15"))))))

(deftest song-detail-page-renders-sheet-music-grid
  (let [{:keys [conn]} (tc/new-system "song-detail-sheet-music")
        song-id        (random-uuid)
        sheet-id       (random-uuid)]
    (seed-song! conn {:song/song-id song-id
                      :song/title   "Bella Ciao"
                      :song/active? false})
    (seed-section! conn {:section/name     "Trumpets"
                         :section/active?  true
                         :section/position 1})
    (seed-sheet-music! conn {:sheet-music/sheet-id sheet-id
                             :sheet-music/song     [:song/song-id song-id]
                             :sheet-music/section  [:section/name "Trumpets"]
                             :sheet-music/title    "Bella Ciao Trumpet.pdf"
                             :file/webdav-path     "/songs/Bella Ciao Trumpet.pdf"})
    (let [html (views/page (req conn song-id "Trumpets"))]
      (is (str/includes? html "Sheet Music"))
      (is (str/includes? html "wa-grid"))
      (is (str/includes? html "songs-detail-sheet-section"))
      (is (str/includes? html "songs-detail-sheet-section--current"))
      (is (str/includes? html "songs-detail-sheet-row"))
      (is (str/includes? html "library=\"snoico\""))
      (is (str/includes? html "name=\"file-pdf-solid\""))
      (is (str/includes? html "<a class=\"songs-detail-sheet-title\""))
      (is (str/includes? html "appearance=\"plain\""))
      (is (str/includes? html "library=\"default\" name=\"download\""))
      (is (not (str/includes? html ">Download<")))
      (is (not (str/includes? html "songs-detail-sheet-card")))
      (is (not (str/includes? html "<wa-card")))
      (is (str/includes? html "Trumpets"))
      (is (str/includes? html "Bella Ciao Trumpet.pdf"))
      (is (str/includes? html "songs-detail-sheet-add"))
      (is (str/includes? html "data-on:click"))
      (is (str/includes? html "/act?ns=app.file-browser.actions&amp;kw=open-picker"))
      (is (str/includes? html "songs-detail-sheet-remove"))
      (is (str/includes? html "variant=\"danger\""))
      (is (str/includes? html "data-dialog=\"open sheet-music-remove-"))
      (is (str/includes? html "<wa-dialog"))
      (is (str/includes? html "/act?ns=app.songs.detail.actions&amp;kw=remove-sheet-music")))))

(deftest song-detail-page-does-not-highlight-when-current-section-has-no-box
  (let [{:keys [conn]} (tc/new-system "song-detail-sheet-music-no-current")
        song-id        (random-uuid)
        sheet-id       (random-uuid)]
    (seed-song! conn {:song/song-id song-id
                      :song/title   "Bella Ciao"
                      :song/active? true})
    (seed-section! conn {:section/name     "Trumpets"
                         :section/active?  true
                         :section/position 1})
    (seed-sheet-music! conn {:sheet-music/sheet-id sheet-id
                             :sheet-music/song     [:song/song-id song-id]
                             :sheet-music/section  [:section/name "Trumpets"]
                             :sheet-music/title    "Bella Ciao Trumpet.pdf"
                             :file/webdav-path     "/songs/Bella Ciao Trumpet.pdf"})
    (let [html (views/page (req conn song-id "Flutes"))]
      (is (str/includes? html "songs-detail-sheet-section"))
      (is (not (str/includes? html "songs-detail-sheet-section--current"))))))

(deftest sheet-section-empty-state-test
  (let [{:keys [conn]} (tc/new-system "song-detail-sheet-music-empty")
        song-id        (random-uuid)]
    (seed-song! conn {:song/song-id song-id
                      :song/title   "Bella Ciao"
                      :song/active? true})
    (seed-section! conn {:section/name     "percussion"
                         :section/active?  true
                         :section/position 1})
    (let [html (views/page (req conn song-id "percussion"))]
      (is (str/includes? html "songs-detail-sheet-empty-state"))
      (is (str/includes? html "songs-detail-sheet-add"))
      (is (str/includes? html "/act?ns=app.file-browser.actions&amp;kw=open-picker"))
      (is (not (str/includes? html "&gt;—&lt;"))))))

(deftest song-detail-picker-targeting-test
  (testing "open picker replaces the sheet music grid"
    (let [song-id (random-uuid)
          picker  {:open? true
                   :target {:section-name "percussion"}}
          content ((ns-resolve 'app.songs.detail.views 'sheet-music-content)
                   {:tr tr}
                   song-id
                   "/Noten - Scores"
                   "/Noten - Scores/1_aktuelle Stücke"
                   [{:section/name "percussion"}
                    {:section/name "Trumpets"
                     :sheet-music/_section [{:sheet-music/title "Trumpet.pdf"}]}]
                   picker
                   (fn [_req {:keys [subtitle]}]
                     [:div {:class "fake-file-picker"} subtitle]))
          html    (str (h/html content))]
      (is (str/includes? html "fake-file-picker"))
      (is (str/includes? html "For section"))
      (is (not (str/includes? html "songs-detail-sheet-section")))
      (is (not (str/includes? html "Trumpet.pdf"))))))

(deftest song-detail-page-throws-for-missing-song
  (let [{:keys [conn]} (tc/new-system "song-detail-missing")
        song-id        (random-uuid)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Song not found"
                          (views/page (req conn song-id))))))
