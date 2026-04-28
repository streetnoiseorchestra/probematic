(ns app.gigs.setlist.views
  (:require
   [app.datastar :as d*]
   [app.gigs.domain :as gig.domain]
   [app.gigs.setlist.actions :as actions]
   [app.gigs.song-plan.views :as plan.views]
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn- page-summary [{:keys [tr]} gig]
  [:header {:class "gigs-probeplan-editor-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item {:href (urls/link-gig gig)}
     (gigs.ui/gig-breadcrumb-label gig)]
    [:wa-breadcrumb-item (tr [:gig/setlist])]]
   [:div {:class "wa-flank:end wa-align-items-start"}
    [:div {:class "wa-stack wa-gap-2xs"}
     [:h1 (tr [:gig/setlist])]]
    [:wa-button {:appearance "outlined"
                 :href       (urls/link-gig gig)}
     (tr [:action/back])]]])

(defn- selected-song-signals [songs]
  (mapv (fn [idx {:song/keys [song-id]}]
          {:song-id  (str song-id)
           :position idx})
        (range)
        songs))

(defn- toggle-song-client-js [req gig-id song-id]
  (->expr
   (.pending js/window.snoSongPlanPop evt.target)
   (set! $gig-setlist.gig-id ~(str gig-id))
   (set! $gig-setlist.song-id ~(str song-id))
   (set! $gig-setlist.selected evt.target.checked)
   (@post ~(d*/act req ::actions/toggle-setlist-song))))

(defn- song-choice-pop-effect-js [song-id]
  (->expr
   (let [selected (if (.isArray Array $gig-setlist.songs)
                    (.some $gig-setlist.songs
                           (fn [song]
                             (=== (aget song "song-id") ~(str song-id))))
                    false)
         selected-value (if selected "true" "false")]
     (when (and el.dataset.lastSelected
                (!== el.dataset.lastSelected selected-value))
       (.finish js/window.snoSongPlanPop el))
     (set! el.dataset.lastSelected selected-value))))

(defn- repertoire-filter-button [req current-filter value label]
  [:wa-button {:appearance    (if (= current-filter value) "filled" "outlined")
               :variant       (when (= current-filter value) "brand")
               :size          "small"
               :aria-pressed  (if (= current-filter value) "true" "false")
               :data-on:click (->expr
                               (set! $gig-setlist.repertoire-filter ~value)
                               (@post ~(d*/act req ::actions/set-repertoire-filter)))}
   label])

(defn- repertoire-filter-control [req current-filter]
  [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
   [:span {:class "gigs-probeplan-editor-guidance"}
    (str ((:tr req) [:gig/probeplan-repertoire]) ":")]
   [:wa-button-group {:label ((:tr req) [:gig/probeplan-repertoire])}
    (repertoire-filter-button req current-filter "current" ((:tr req) [:gig/probeplan-repertoire-current]))
    (repertoire-filter-button req current-filter "old" ((:tr req) [:gig/probeplan-repertoire-old]))
    (repertoire-filter-button req current-filter "all" ((:tr req) [:gig/probeplan-repertoire-all]))]])

(defn- song-choice [req gig-id selected-ids {:song/keys [song-id title]}]
  [:wa-checkbox (cond-> {:id                 (str "gig-setlist-choice-" (ui2/safe-dom-id song-id))
                         :data-effect        (song-choice-pop-effect-js song-id)
                         :data-on:change     (toggle-song-client-js req gig-id song-id)
                         :data-preserve-attr "class data-last-selected"}
                  (selected-ids song-id) (assoc :checked true))
   [:span title]])

(defn- song-choices [req gig-id songs repertoire-filter selected-songs]
  (let [selected-ids (plan.views/selected-song-ids selected-songs)
        songs        (plan.views/visible-song-choices songs repertoire-filter)]
    (ui2/section-card
     {:title    ((:tr req) [:gig/setlist-choose])
      :divider? true
      :actions  [(plan.views/selected-count selected-songs)]}
     [:p {:class "gigs-probeplan-editor-guidance"}
      ((:tr req) [:gig/setlist-guidance])]
     (repertoire-filter-control req repertoire-filter)
     [:div {:class "gigs-probeplan-editor-choices"}
      (for [song songs]
        (song-choice req gig-id selected-ids song))])))

(defn- selected-song-row [_req _gig-id {:song/keys [song-id title]}]
  [:li {:id             (str "gig-setlist-selected-" (ui2/safe-dom-id song-id))
        :data-song-id   (str song-id)}
   [:div {:data-drag-zone true}
    [:button {:type       "button"
              :aria-label "Drag to reorder"}
     [:wa-icon {:library "snoico"
                :name    "bars"}]]
    [:span title]]])

(defn- selected-songs-list [req gig-id selected-songs]
  (ui2/section-card
   {:title    ((:tr req) [:gig/setlist-sort])
    :divider? true}
   [:p {:class "gigs-probeplan-editor-guidance"}
    ((:tr req) [:gig/setlist-order-guidance])]
   (if (seq selected-songs)
     (list
      [:ol {:id                         "gig-setlist-selected-songs"
            :class                      "gigs-song-plan-selected"
            :data-on:setlist-reordered (->expr
                                        (set! $gig-setlist.gig-id ~(str gig-id))
                                        (set! $gig-setlist.order evt.detail.order)
                                        (@post ~(d*/act req ::actions/reorder-setlist-songs)))}
       (for [song selected-songs]
         (selected-song-row req gig-id song))]
      (html/squint-inline
       (require '["sortable" :as s])
       (let [sort-container (.getElementById js/document "gig-setlist-selected-songs")]
         (when sort-container
           (new s/Sortable
                sort-container
                {:animation  150
                 :ghostClass "gigs-probeplan-editor-ghost"
                 :handle     "[data-drag-zone]"
                 :onEnd
                 (fn []
                   (let [order (new js/Array)]
                     (.forEach
                      (.querySelectorAll sort-container "[data-song-id]")
                      (fn [row]
                        (.push order (.-songId (.-dataset row)))))
                     (.dispatchEvent sort-container
                                     (new js/CustomEvent "setlist-reordered"
                                          {:bubbles true
                                           :detail  {:order order}}))))})))))
     [:div {:class "gigs-empty"} "—"])))

(defn page [{:keys [db page-state] :as req}]
  (let [gig-id            (http.util/path-param-uuid! req :gig/gig-id)
        gig               (q/retrieve-gig db gig-id)
        songs             (q/retrieve-all-songs db)
        repertoire-filter (actions/normalize-repertoire-filter
                           (get-in page-state [:gig-setlist :repertoire-filter]))
        selected-songs    (actions/selected-songs-for-page db gig-id)
        error             (get-in page-state [:gig-setlist :_error])]
    (cond
      (nil? gig)
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id}))

      (not (gig.domain/gig? gig))
      (throw (ex-info "Set list is only available for gigs" {:app/error-type :app.error.type/not-found
                                                             :gig/gig-id     gig-id}))

      :else
      (ui2/datastar-page
       [:div {:class        "wa-stack wa-gap-xl gigs-probeplan-editor-page"
              :data-signals (d*/->signals {:gig-setlist {:gig-id            (str gig-id)
                                                         :repertoire-filter repertoire-filter
                                                         :songs             (selected-song-signals selected-songs)
                                                         :order             []}})}
        (page-summary req gig)
        (plan.views/error-callout error)
        (plan.views/pop-helper-script)
        (song-choices req gig-id songs repertoire-filter selected-songs)
        (selected-songs-list req gig-id selected-songs)]))))
