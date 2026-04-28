(ns app.gigs.song-plan.views
  (:require
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [app.ui2 :as ui2]
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

(defn page-summary [{:keys [tr]} gig title-kw]
  [:header {:class "gigs-probeplan-editor-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item {:href (urls/link-gig gig)}
     (gigs.ui/gig-breadcrumb-label gig)]
    [:wa-breadcrumb-item (tr title-kw)]]
   [:div {:class "wa-flank:end wa-align-items-start"}
    [:div {:class "wa-stack wa-gap-2xs"}
     [:h1 (tr title-kw)]]
    [:wa-button {:appearance "outlined"
                 :href       (urls/link-gig gig)}
     (tr [:action/back])]]])

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

(defn repertoire-filter-control [{:keys [req current-filter button]}]
  [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
   [:span {:class "gigs-probeplan-editor-guidance"}
    (str ((:tr req) [:gig/probeplan-repertoire]) ":")]
   [:wa-button-group {:label ((:tr req) [:gig/probeplan-repertoire])}
    (button current-filter "current" ((:tr req) [:gig/probeplan-repertoire-current]))
    (button current-filter "old" ((:tr req) [:gig/probeplan-repertoire-old]))
    (button current-filter "all" ((:tr req) [:gig/probeplan-repertoire-all]))]])

(defn song-choice [{:keys [checked? id-prefix on-change effect song-id title]}]
  [:wa-checkbox (cond-> {:id                 (str id-prefix (ui2/safe-dom-id song-id))
                         :data-effect        effect
                         :data-on:change     on-change
                         :data-preserve-attr "class data-last-selected"}
                  checked? (assoc :checked true))
   [:span title]])

(defn song-choices [{:keys [req title-kw guidance-kw songs repertoire-filter selected-songs filter-control choice]}]
  (let [selected-ids (selected-song-ids selected-songs)
        songs        (visible-song-choices songs repertoire-filter)]
    (ui2/section-card
     {:title    ((:tr req) title-kw)
      :divider? true
      :actions  [(selected-count selected-songs)]}
     [:p {:class "gigs-probeplan-editor-guidance"}
      ((:tr req) guidance-kw)]
     filter-control
     [:div {:class "gigs-probeplan-editor-choices"}
      (for [song songs]
        (choice selected-ids song))])))

(defn drag-zone [title]
  [:div {:data-drag-zone true}
   [:button {:type       "button"
             :aria-label "Drag to reorder"}
    [:wa-icon {:library "snoico"
               :name    "bars"}]]
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

(defn selected-songs-list [{:keys [req title-kw guidance-kw selected-songs list-id list-class on-reordered row]}]
  (ui2/section-card
   {:title    ((:tr req) title-kw)
    :divider? true}
   [:p {:class "gigs-probeplan-editor-guidance"}
    ((:tr req) guidance-kw)]
   (if (seq selected-songs)
     (list
      [:ol {:id                                 list-id
            :class                              list-class
            (str "data-on:" reorder-event-name) on-reordered}
       (for [song selected-songs]
         (row song))]
      (sortable-script list-id))
     [:div {:class "gigs-empty"} "—"])))
