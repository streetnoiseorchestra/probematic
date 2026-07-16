(ns app.songs.detail.views-test
  (:require
   [app.songs.detail.views :as views]
   [app.songs.view-test-support :as support]
   [app.test-common :as tc]
   [app.ui2.page-shell-test-support :as page-shell]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

(def translations
  {[:action/add]                       "Add"
   [:action/back]                      "Back"
   [:action/cancel]                    "Cancel"
   [:action/confirm-delete]            "Yes, delete it"
   [:action/confirm-generic]           "Are you sure?"
   [:action/download]                  "Download"
   [:action/edit]                      "Edit"
   [:action/remove]                    "Remove"
   [:song/arrangement-credits]         "Arranged By"
   [:song/arrangement-notes]           "Arrangement Info"
   [:song/background-title]            "Background"
   [:song/choose-sheet-music-subtitle] "For section: %1"
   [:song/choose-sheet-music-title]    "Choose Sheet Music File"
   [:song/composition-credits]         "Composition By"
   [:song/gig-count]                   "Gig Count"
   [:repertoire/last-played]           "Last Played"
   [:song/last-played-gig]             "Last Played Gig"
   [:song/last-played-probe]           "Last Played Rehearsal"
   [:song/lyrics]                      "Lyrics"
   [:song/origin]                      "Origin"
   [:song/probe-count]                 "Rehearsal Count"
   [:song/solo-count]                  "# Solos"
   [:song/total-plays]                 "Total Play Count"
   [:Active]                           "Active"
   [:Inactive]                         "Inactive"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (case path
     [:song/choose-sheet-music-subtitle] (str "For section: " (first args))
     (tr path))))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def request
  {::r/router       router
   :current-locale :en
   :tr             tr})

(def song-id
  #uuid "00000000-0000-0000-0000-000000000101")

(def sheet-id
  #uuid "00000000-0000-0000-0000-000000000102")

(def sheet
  {:sheet-music/sheet-id sheet-id
   :sheet-music/title    "Bella Ciao Trumpet.pdf"
   :file/webdav-path     "/songs/Bella Ciao Trumpet.pdf"})

(def sheet-sections
  [{:section/name         "Trumpets"
    :sheet-music/_section [sheet]}])

(defn request-for-section [section-name]
  (cond-> request
    section-name
    (assoc-in [:session :session/member :member/section :section/name]
              section-name)))

(defn details-by-label [view]
  (into {}
        (map (fn [item]
               [(or (some-> (l/select-one :i18n/tr item) l/first-child)
                    (-> (l/select-one 'dt item) l/text))
                (-> (l/select-one 'dd item) l/text)]))
        (l/select '[dl > div] view)))

(defn action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(defn action-keywords [view]
  (->> (l/select '* view)
       (mapcat #(vals (or (l/attrs %) {})))
       (keep action-keyword)
       set))

(deftest song-detail-page-surface
  (testing "A song uses repertoire context and exposes editing as its primary action."
    (let [{:keys [conn]} (support/new-system "song-detail-surface")]
      (support/seed-song! conn song-id)
      (is (= {:width       :standard
              :breadcrumbs [:repertoire/title "Watermelon Man"]
              :mobile      {:label :repertoire/title :href "/songs"}
              :actions     [{:label      :action/edit
                             :href       (str "/song/" song-id "/edit")
                             :appearance "filled"}]
              :overflow    []}
             (-> conn
                 (support/request {:path-params {:song-id (str song-id)}})
                 views/page
                 page-shell/page-contract))))))

(deftest song-information
  (testing "An active song has complete background details and aggregate play counts."
    (let [song       {:song/song-id             song-id
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
                      :song/total-rehearsals    15}
          summary    (views/song-summary song)
          summary    (l/attrs summary)
          background (views/background-section request song)
          stats      (views/play-stats-section request song)]
      (testing "The header identifies the active song and its place in the repertoire."
        (let [title (:title summary)]
          (is (= {:title  "Watermelon Man"
                  :status :status-active}
                 {:title  (nth title 2)
                  :status (page-shell/translation-key title)}))))
      (testing "The background section shows the song's credits, notes, and lyrics."
        (is (= {"# Solos"          "Alto"
                "Composition By"   "Herbie Hancock"
                "Arranged By"      "StreetNoise"
                "Origin"           "Hard bop tune"
                "Arrangement Info" "Watch the break"
                "Lyrics"           "Watermelon"}
               (details-by-label background))))
      (testing "The play statistics show performance and rehearsal totals."
        (is (= {"Total Play Count" "22"
                "Gig Count"        "7"
                "Rehearsal Count"  "15"}
               (select-keys (details-by-label stats)
                            ["Total Play Count" "Gig Count" "Rehearsal Count"])))))))

(deftest compact-dates
  (testing "A song has a last-played date and a dated rehearsal."
    (let [view    (views/play-stats-section
                   request
                   {:song/last-played-on (t/date "2026-06-04")
                    :song/last-rehearsal {:gig/gig-id (random-uuid)
                                          :gig/date   (t/date "2026-06-07")}})
          details (details-by-label view)]
      (testing "Both dates use the compact weekday format."
        (is (= {:repertoire/last-played "Thu 04 Jun 2026"
                "Last Played Rehearsal" "Sun 07 Jun 2026"}
               (select-keys details
                            [:repertoire/last-played "Last Played Rehearsal"])))))))

(deftest sheet-music
  (testing "A Trumpets member views a song with a trumpet PDF."
    (let [view         (views/sheet-music-content
                        (request-for-section "Trumpets")
                        song-id
                        "/Noten - Scores"
                        "/Noten - Scores/aktuelle Stücke"
                        sheet-sections
                        nil)
          section      (l/select-one '.songs-detail-sheet-section view)
          title-link   (l/select-one '.songs-detail-sheet-title view)
          download     (l/select-one '.songs-detail-sheet-download view)
          remove-sheet (l/select-one '.songs-detail-sheet-remove view)]
      (testing "The member's section is identified as the current sheet-music section."
        (is (= {:heading  "Trumpets"
                :current? true}
               {:heading  (-> (l/select-one 'h3 section) l/text)
                :current? (contains? (:class (l/attrs section))
                                     "songs-detail-sheet-section--current")})))
      (testing "The PDF has a download link and accessible download and remove controls."
        (is (= {:title    {:text "Bella Ciao Trumpet.pdf"
                           :href (urls/link-file-download
                                  "/songs/Bella Ciao Trumpet.pdf")}
                :download {:href       (urls/link-file-download
                                        "/songs/Bella Ciao Trumpet.pdf")
                           :aria-label "Download"}
                :remove   {:aria-label "Remove"}}
               {:title    {:text (l/text title-link)
                           :href (:href (l/attrs title-link))}
                :download (select-keys (l/attrs download) [:href :aria-label])
                :remove   (select-keys (l/attrs remove-sheet) [:aria-label])})))
      (testing "The section can open the file picker and remove the existing sheet."
        (is (= #{:app.file-browser.actions/open-picker
                 :app.songs.detail.actions/remove-sheet-music}
               (action-keywords view)))))))

(deftest unmatched-section
  (testing "A Flutes member views sheet music that only has a Trumpets section."
    (let [view    (views/sheet-music-content
                   (request-for-section "Flutes")
                   song-id
                   "/Noten - Scores"
                   "/Noten - Scores/aktuelle Stücke"
                   sheet-sections
                   nil)
          section (l/select-one '.songs-detail-sheet-section view)]
      (testing "The Trumpets section is not marked as the member's current section."
        (is (false? (contains? (:class (l/attrs section))
                               "songs-detail-sheet-section--current")))))))

(deftest empty-section
  (testing "A percussion section does not have any sheet music yet."
    (let [view        (views/sheet-music-content
                       (request-for-section "percussion")
                       song-id
                       "/Noten - Scores"
                       "/Noten - Scores/aktuelle Stücke"
                       [{:section/name "percussion"}]
                       nil)
          add-button  (l/select-one '.songs-detail-sheet-add view)
          empty-state (l/select-one '.songs-detail-sheet-empty-state view)]
      (testing "The section header and empty state both offer the sheet-music picker."
        (is (= {:labels  [:action/add :action/add]
                :actions [:app.file-browser.actions/open-picker
                          :app.file-browser.actions/open-picker]}
               {:labels  (mapv page-shell/translation-key [add-button empty-state])
                :actions (mapv #(-> (l/attrs %) :data-on:click action-keyword)
                               [add-button empty-state])}))))))

(deftest picker
  (testing "The sheet-music picker is open for the percussion section."
    (let [view   (views/sheet-music-content
                  request
                  song-id
                  "/Noten - Scores"
                  "/Noten - Scores/aktuelle Stücke"
                  [{:section/name "percussion"}
                   {:section/name         "Trumpets"
                    :sheet-music/_section [sheet]}]
                  {:open?  true
                   :target {:section-name "percussion"}}
                  (fn [_req {:keys [select-action subtitle title]}]
                    [:div {:class              "fake-file-picker"
                           :data-select-action select-action
                           :data-subtitle      subtitle
                           :data-title         title}]))
          picker (l/select-one '.fake-file-picker view)]
      (testing "The picker replaces the grid and targets the requested section."
        (is (= {:title         "Choose Sheet Music File"
                :subtitle      "For section: percussion"
                :select-action :app.songs.detail.actions/add-sheet-music}
               {:title         (:data-title (l/attrs picker))
                :subtitle      (:data-subtitle (l/attrs picker))
                :select-action (:data-select-action (l/attrs picker))}))))))

(deftest missing-song
  (testing "The requested song does not exist."
    (let [{:keys [conn]} (tc/new-system "song-detail-missing")
          missing-id     (random-uuid)
          page-request   (assoc request
                                :db (d/db conn)
                                :path-params {:song-id (str missing-id)})]
      (testing "The detail page reports the missing song."
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Song not found"
                              (views/page page-request)))))))
