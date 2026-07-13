(ns app.songs.index.views
  (:require
   [app.datastar :as d*]
   [app.songs.index.actions :as actions]
   [app.songs.index.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn- song-stat [{:keys [icon label value]}]
  [:span {:class "songs-index-stat"}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    icon}]
   [:span label]
   (when (some? value)
     [:span {:class "songs-index-stat-value"} value])])

(defn- song-status-badge [req active? class]
  [:span {:class class}
   (ui2/active-badge (:tr req) active?)])

(defn song-row [{:keys [tr] :as req} {:song/keys [active? last-played-on score title total-plays]
                                      :as        song}]
  [:div {:class "songs-index-row"}
   [:div {:class "songs-index-row-main"}
    [:div {:class "songs-index-title-line"}
     [:a {:href  (urls/link-song song)
          :class "songs-index-song-title"}
      [:span title]]
     (song-status-badge req active? "songs-index-status songs-index-status--mobile")]
    [:div {:class "songs-index-row-meta"}
     (song-stat {:icon  "hash"
                 :label (tr [:song/total-plays])
                 :value (or total-plays 0)})
     (song-stat {:icon  "star"
                 :label (tr [:song/score])
                 :value score})
     (song-status-badge req active? "songs-index-status songs-index-status--desktop")]]
   [:a {:class       "wa-link-plain"
        :href        (urls/link-song song)
        :aria-hidden "true"
        :tabindex    "-1"}]
   [:div {:class "songs-index-row-date"}
    (song-stat {:icon  "calendar"
                :label (tr [:song/last-played])
                :value (or (ui2/format-date req :compact-with-weekday last-played-on) "—")})]])

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
              :size                         "m"
              :value                        search
              :with-clear                   true
              :data-bind                    "songs-index.search"
              :data-on:input__debounce.250ms
              (str "@post('" (d*/act req ::actions/set-search-phrase) "')")}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :magnifying-glass
              :slot         "start"}]])

(defn- repertoire-filter-control [{:keys [tr] :as req} {:keys [repertoire-filter]}]
  [:wa-select {:label          (tr [:gig/probeplan-repertoire])
               :appearance     "outlined"
               :size           "m"
               :value          repertoire-filter
               :data-bind      "songs-index.repertoire-filter"
               :data-on:change (str "@post('" (d*/act req ::actions/set-repertoire-filter) "')")}
   [ico/Icon {::ico/library :snoico
              ::ico/name    :music-note-outline
              :slot         "start"}]
   [:wa-option {:value "current"} (tr [:gig/probeplan-repertoire-current])]
   [:wa-option {:value "old"} (tr [:gig/probeplan-repertoire-old])]
   [:wa-option {:value "all"} (tr [:gig/probeplan-repertoire-all])]])

(defn- sync-menu-item [req]
  [:wa-dropdown-item
   {:data-indicator     "songsIndexSyncing"
    :data-attr:loading  "$songsIndexSyncing"
    :data-attr:disabled "$songsIndexSyncing"
    :data-on:click      (str "@post('" (d*/act req ::actions/force-sync-songs) "')")}
   [:i18n/tr :repertoire/sync-songs]])

(defn- page-toolbar [req]
  [page-toolbar/PageToolbar
   {::page-toolbar/breadcrumb
    [breadcrumb/Breadcrumb
     {}
     [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
      [:i18n/tr :home]]
     [breadcrumb/BreadcrumbItem [:i18n/tr :repertoire/title]]]
    ::page-toolbar/mobile-back
    [button/Button {:appearance "plain"
                    :href       (urls/link-dashboard)}
     [ico/Icon {::ico/library :phosphor
                ::ico/name    :arrow-left
                :slot         "start"}]
     [:i18n/tr :home]]
    ::page-toolbar/actions
    [[button/Button {:appearance "filled"
                     :variant    "brand"
                     :href       (urls/link-song-create)}
      [:i18n/tr :repertoire/add-song]]]
    ::page-toolbar/overflow-items [(sync-menu-item req)]
    ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
    :aria-label                    [:i18n/tr :repertoire/index-toolbar-label]}])

(defn- collection-controls [req page-state]
  [:div {:class "songs-index-toolbar-controls"}
   (search-control req page-state)
   (repertoire-filter-control req page-state)])

(defn page [{:keys [db page-state] :as req}]
  (let [page-state (queries/normalize-page-state (:songs-index page-state))
        songs      (queries/songs db page-state)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width   :wide
       ::page-surface/toolbar (page-toolbar req)}
      [:div {:class        "wa-stack wa-gap-l"
             :data-signals (d*/->signals {:songs-index page-state})}
       [page-header/PageHeader
        {:title    [:i18n/tr :repertoire/title]
         :subtitle [:i18n/tr :repertoire/song-count {:count (count songs)}]}]
       (collection-controls req page-state)
       (songs-list req songs)]])))

(d*/refresh-all!)
