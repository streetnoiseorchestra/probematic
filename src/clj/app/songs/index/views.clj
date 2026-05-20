(ns app.songs.index.views
  (:require
   [app.datastar :as d*]
   [app.songs.index.actions :as actions]
   [app.songs.index.queries :as queries]
   [app.ui2 :as ui2]
   [app.urls :as urls]))

(defn- song-stat [{:keys [icon label value]}]
  [:span {:class "songs-index-stat"}
   [:wa-icon {:library "default"
              :name    icon}]
   [:span label]
   (when (some? value)
     [:span {:class "songs-index-stat-value"} value])])

(defn- song-status-badge [req active? class]
  [:span {:class class}
   (ui2/active-badge (:tr req) active?)])

(defn- song-row [{:keys [tr] :as req} {:song/keys [active? last-played-on score title total-plays]
                                       :as        song}]
  [:a {:href  (urls/link-song song)
       :class "songs-index-row"}
   [:div {:class "songs-index-row-main"}
    [:div {:class "songs-index-title-line"}
     [:span {:class "songs-index-song-title"} title]
     (song-status-badge req active? "songs-index-status songs-index-status--mobile")]
    [:div {:class "songs-index-row-meta"}
     (song-stat {:icon  "hashtag"
                 :label (tr [:song/total-plays])
                 :value (or total-plays 0)})
     (song-stat {:icon  "star"
                 :label (tr [:song/score])
                 :value score})
     (song-status-badge req active? "songs-index-status songs-index-status--desktop")]]
   [:div {:class "songs-index-row-date"}
    (song-stat {:icon  "calendar"
                :label (tr [:song/last-played])
                :value (or (ui2/format-date req :with-weekday last-played-on) "—")})]])

(defn- songs-list [req songs]
  (if (seq songs)
    [:div {:class "songs-index-list"}
     (for [song songs]
       (song-row req song))]
    (ui2/empty-state
     ((:tr req) [:song/search-empty])
     ((:tr req) [:song/search-empty]))))

(defn- search-control [{:keys [tr] :as req} {:keys [search]}]
  [:wa-input {:label                        (tr [:action/search])
              :placeholder                  (tr [:song/search])
              :appearance                   "outlined"
              :size                         "medium"
              :value                        search
              :with-clear                   true
              :data-bind                    "songs-index.search"
              :data-on:input__debounce.250ms
              (str "@post('" (d*/act req ::actions/set-search-phrase) "')")}
   [:wa-icon {:slot    "start"
              :library "default"
              :name    "search"}]])

(defn- repertoire-filter-control [{:keys [tr] :as req} {:keys [repertoire-filter]}]
  [:wa-select {:label          (tr [:gig/probeplan-repertoire])
               :appearance     "outlined"
               :size           "medium"
               :value          repertoire-filter
               :data-bind      "songs-index.repertoire-filter"
               :data-on:change (str "@post('" (d*/act req ::actions/set-repertoire-filter) "')")}
   [:wa-icon {:slot    "start"
              :library "snoico"
              :name    "music-note-outline"}]
   [:wa-option {:value "current"} (tr [:gig/probeplan-repertoire-current])]
   [:wa-option {:value "old"} (tr [:gig/probeplan-repertoire-old])]
   [:wa-option {:value "all"} (tr [:gig/probeplan-repertoire-all])]])

(defn- toolbar-actions [{:keys [tr]}]
  [[:wa-button {:appearance "outlined"
                :type       "button"
                :disabled   true}
    (tr [:action/update])]
   [:wa-button {:appearance "filled"
                :variant    "brand"
                :href       (urls/link-song-create)}
    (tr [:song/create-title])]])

(defn- toolbar [{:keys [tr] :as req} page-state total]
  [:div {:class "songs-index-toolbar"}
   (ui2/title-block {:title    (tr [:song/list-title])
                     :subtitle (str (tr [:total]) ": " total)})
   [:div {:class "songs-index-toolbar-controls"}
    (search-control req page-state)
    (repertoire-filter-control req page-state)
    (ui2/action-bar {:class "songs-index-toolbar-actions"}
                    (toolbar-actions req))]])

(defn page [{:keys [db page-state] :as req}]
  (let [page-state (queries/normalize-page-state (:songs-index page-state))
        songs      (queries/songs db page-state)]
    (ui2/datastar-page
     [:div {:class        "wa-stack wa-gap-l songs-index-page"
            :data-signals (d*/->signals {:songs-index page-state})}
      (toolbar req page-state (count songs))
      (songs-list req songs)])))

(d*/refresh-all!)
