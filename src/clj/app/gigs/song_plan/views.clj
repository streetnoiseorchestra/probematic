(ns app.gigs.song-plan.views
  (:require
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn pop-helper-script []
  (html/squint-inline
   (let [pop-class     "pop"
         pending-class "pending-pop"
         confirm-class "confirm-pop"
         duration      300]
     (set! js/window.snoSongPlanPop (new Object))
     (set! js/window.snoSongPlanPop.pending
           (fn [target]
             (when target
               (.add target.classList pending-class))))
     (set! js/window.snoSongPlanPop.finish
           (fn [target]
             (when target
               (let [pending?        (.contains target.classList pending-class)
                     animation-class (if pending? confirm-class pop-class)]
                 (.remove target.classList pending-class)
                 (.remove target.classList pop-class)
                 (.remove target.classList confirm-class)
                 (void target.offsetWidth)
                 (.add target.classList animation-class)
                 (setTimeout (fn []
                               (.remove target.classList animation-class))
                             duration))))))))

(defn page-toolbar [req gig title-key]
  (let [gig-url (urls/link-gig gig)]
    [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                               [breadcrumb/Breadcrumb {::breadcrumb/max-items [2 3]}
                                [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
                                 [:i18n/tr :gigs/title]]
                                (gigs.ui/gig-breadcrumb req gig)
                                [breadcrumb/BreadcrumbItem [:i18n/tr title-key]]]
                               ::page-toolbar/actions
                               [[button/Button {:appearance "filled"
                                                :variant    "brand"
                                                :href       gig-url}
                                 [:i18n/tr :action/done]]]
                               :aria-label [:i18n/tr :gigs/tool-toolbar-label]}]))

(defn page-summary [title-key]
  [page-header/PageHeader {:title [:i18n/tr title-key]}])

(defn selected-song-ids [songs]
  (set (map :song/song-id songs)))

(defn repertoire-song? [repertoire-filter song]
  (case repertoire-filter
    "current" (:song/active? song)
    "old" (not (:song/active? song))
    "all" true))

(defn visible-song-choices [songs repertoire-filter]
  (filter #(repertoire-song? repertoire-filter %) songs))

(defn error-callout [error]
  (when-let [message (:error error)]
    [:wa-callout {:variant "danger"}
     message]))

(defn selected-count [songs]
  [:span {:class "gigs-probeplan-editor-count"}
   (str (count songs) " selected")])

(defn repertoire-filter-control [{:keys [current-filter button]}]
  [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
   [:span {:class "gigs-probeplan-editor-guidance"}
    [:i18n/tr :repertoire/filter-label]
    ":"]
   [:wa-button-group {:label [:i18n/tr :repertoire/filter-label]}
    (button current-filter "current" [:i18n/tr :repertoire/filter-current])
    (button current-filter "old" [:i18n/tr :repertoire/filter-old])
    (button current-filter "all" [:i18n/tr :repertoire/filter-all])]])

(defn last-played-subtitle [req last-played-on]
  (when last-played-on
    (let [label (ui2/format-date-time req :medium last-played-on)]
      [:span
       [:i18n/tr :repertoire/last-played]
       ": "
       [:time {:datetime   (str last-played-on)
               :title      label
               :aria-label label}
        (ui2/relative-time-value last-played-on)]])))

(defn song-choice [{:keys [checked? id-prefix on-change effect song-id subtitle title]}]
  [:wa-checkbox (cond-> {:id                 (str id-prefix (ui2/safe-dom-id song-id))
                         :data-effect        effect
                         :data-on:change     on-change
                         :data-preserve-attr "class data-last-selected"}
                  checked? (assoc :checked true))
   [:span {:class "gigs-probeplan-editor-choice-label"}
    [:span {:class "gigs-probeplan-editor-choice-title"} title]
    (when subtitle
      [:span {:class "gigs-probeplan-editor-choice-subtitle"}
       subtitle])]])

(defn song-choices [{:keys [title-kw guidance-kw songs repertoire-filter selected-songs filter-control choice]}]
  (let [selected-ids (selected-song-ids selected-songs)
        songs        (visible-song-choices songs repertoire-filter)]
    (ui2/section-card
     {:title    [:i18n/tr title-kw]
      :divider? true
      :actions  [(selected-count selected-songs)]}
     [:p {:class "gigs-probeplan-editor-guidance"}
      [:i18n/tr guidance-kw]]
     filter-control
     [:div {:class "gigs-probeplan-editor-choices"}
      (for [song songs]
        (choice selected-ids song))])))

(defn drag-zone [title]
  [:div {:data-drag-zone true}
   [:button {:type       "button"
             :aria-label "Drag to reorder"}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :bars}]]
   [:span title]])

(def reorder-event-name "song-plan-reordered")

(defn sortable-script [list-id]
  (html/squint-inline
   (require '["sortable" :as s])
   (let [sort-container (.getElementById js/document ~list-id)]
     (when sort-container
       (new s/Sortable
            sort-container
            {:animation  150
             :ghostClass "gigs-probeplan-editor-ghost"
             :handle     "[data-drag-zone]"
             :onEnd      (fn []
                           (let [order (new js/Array)]
                             (.forEach
                              (.querySelectorAll sort-container "[data-song-id]")
                              (fn [row]
                                (.push order (.-songId (.-dataset row)))))
                             (.dispatchEvent sort-container
                                             (new js/CustomEvent ~reorder-event-name
                                                  {:bubbles true
                                                   :detail  {:order order}}))))})))))

(defn selected-songs-list [{:keys [title-kw guidance-kw selected-songs list-id list-class on-reordered row]}]
  (ui2/section-card
   {:title    [:i18n/tr title-kw]
    :divider? true}
   [:p {:class "gigs-probeplan-editor-guidance"}
    [:i18n/tr guidance-kw]]
   (if (seq selected-songs)
     (list
      [:ol {:id                                 list-id
            :class                              list-class
            (str "data-on:" reorder-event-name) on-reordered}
       (for [song selected-songs]
         (row song))]
      (sortable-script list-id))
     [:div {:class "gigs-empty"} "—"])))
