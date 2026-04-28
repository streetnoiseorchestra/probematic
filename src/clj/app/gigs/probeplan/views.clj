(ns app.gigs.probeplan.views
  (:require
   [app.datastar :as d*]
   [app.gigs.domain :as gig.domain]
   [app.gigs.probeplan.actions :as actions]
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn- pop-helper-script []
  (html/squint-inline
   (let [pop-class     "pop"
         pending-class "pending-pop"
         confirm-class "confirm-pop"
         duration      300]
     (set! js/window.snoProbeplanPop (new Object))
     (set! js/window.snoProbeplanPop.pending
           (fn [target]
             (when target
               (.add target.classList pending-class))))
     (set! js/window.snoProbeplanPop.finish
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

(defn- selected-song-ids [songs]
  (set (map :song/song-id songs)))

(defn- repertoire-song? [repertoire-filter song]
  (case repertoire-filter
    "current" (:song/active? song)
    "old" (not (:song/active? song))
    "all" true))

(defn- visible-song-choices [songs repertoire-filter]
  (filter #(repertoire-song? repertoire-filter %) songs))

(defn- intensive? [{:keys [emphasis]}]
  (= :probeplan.emphasis/intensive emphasis))

(defn- page-summary [{:keys [tr]} gig]
  [:header {:class "gigs-probeplan-editor-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item {:href (urls/link-gig gig)}
     (gigs.ui/gig-breadcrumb-label gig)]
    [:wa-breadcrumb-item (tr [:gig/probeplan])]]
   [:div {:class "wa-flank:end wa-align-items-start"}
    [:div {:class "wa-stack wa-gap-2xs"}
     [:h1 (tr [:gig/probeplan])]]
    [:wa-button {:appearance "outlined"
                 :href       (urls/link-gig gig)}
     (tr [:action/back])]]])

(defn- error-callout [error]
  (when-let [message (:error error)]
    [:wa-callout {:variant "danger"}
     message]))

(defn- selected-count [songs]
  [:span {:class "gigs-probeplan-editor-count"}
   (str (count songs) " selected")])

(defn- selected-song-signals [songs]
  (mapv (fn [idx {:song/keys [song-id] :keys [emphasis]}]
          {:song-id  (str song-id)
           :position idx
           :emphasis (if (intensive? {:emphasis emphasis}) "intensive" "none")})
        (range)
        songs))

(defn- toggle-song-client-js [req gig-id song-id]
  (->expr
   (.pending js/window.snoProbeplanPop evt.target)
   (set! $gig-probeplan.gig-id ~(str gig-id))
   (set! $gig-probeplan.song-id ~(str song-id))
   (set! $gig-probeplan.selected evt.target.checked)
   (@post ~(d*/act req ::actions/toggle-probeplan-song))))

(defn- song-choice-pop-effect-js [song-id]
  (->expr
   (let [selected (if (.isArray Array $gig-probeplan.songs)
                    (.some $gig-probeplan.songs
                           (fn [song]
                             (=== (aget song "song-id") ~(str song-id))))
                    false)
         selected-value (if selected "true" "false")]
     (when (and el.dataset.lastSelected
                (!== el.dataset.lastSelected selected-value))
       (.finish js/window.snoProbeplanPop el))
     (set! el.dataset.lastSelected selected-value))))

(defn- intensive-pop-effect-js [song-id]
  (->expr
   (let [song (if (.isArray Array $gig-probeplan.songs)
                (.find $gig-probeplan.songs
                       (fn [song]
                         (= (aget song "song-id") ~(str song-id))))
                nil)
         emphasis (if song song.emphasis "none")]
     (when (and el.dataset.lastEmphasis
                (!== el.dataset.lastEmphasis emphasis))
       (let [icon (.querySelector el "wa-icon[name='fist-punch']")]
         (when icon
           (.finish js/window.snoProbeplanPop icon))))
     (set! el.dataset.lastEmphasis emphasis))))

(defn- repertoire-filter-button [req current-filter value label]
  [:wa-button {:appearance      (if (= current-filter value) "filled" "outlined")
               :variant         (when (= current-filter value) "brand")
               :size            "small"
               :aria-pressed    (if (= current-filter value) "true" "false")
               :data-on:click   (->expr
                                 (set! $gig-probeplan.repertoire-filter ~value)
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
  [:wa-checkbox (cond-> {:id                 (str "gig-probeplan-choice-" (ui2/safe-dom-id song-id))
                         :data-effect        (song-choice-pop-effect-js song-id)
                         :data-on:change     (toggle-song-client-js req gig-id song-id)
                         :data-preserve-attr "class data-last-selected"}
                  (selected-ids song-id) (assoc :checked true))
   [:span title]])

(defn- song-choices [req gig-id songs repertoire-filter selected-songs]
  (let [selected-ids (selected-song-ids selected-songs)
        songs        (visible-song-choices songs repertoire-filter)]
    (ui2/section-card
     {:title    ((:tr req) [:gig/probeplan-choose])
      :divider? true
      :actions  [(selected-count selected-songs)]}
     [:p {:class "gigs-probeplan-editor-guidance"}
      ((:tr req) [:gig/probeplan-guidance])]
     (repertoire-filter-control req repertoire-filter)
     [:div {:class "gigs-probeplan-editor-choices"}
      (for [song songs]
        (song-choice req gig-id selected-ids song))])))

(defn- intensive-button [req gig-id {:song/keys [song-id]}]
  [:wa-button {:appearance    "plain"
               :size          "small"
               :aria-label    "Toggle intensive"
               :data-on:click (->expr
                               (let [icon (.querySelector evt.currentTarget "wa-icon[name='fist-punch']")]
                                 (when icon
                                   (.pending js/window.snoProbeplanPop icon)))
                               (set! $gig-probeplan.gig-id ~(str gig-id))
                               (set! $gig-probeplan.song-id ~(str song-id))
                               (@post ~(d*/act req ::actions/toggle-probeplan-intensive)))}
   [:wa-icon {:library            "snoico"
              :name               "fist-punch"
              :data-preserve-attr "class"}]])

(defn- selected-song-row [req gig-id {:song/keys [song-id title] :as song}]
  [:li (cond-> {:id                 (str "gig-probeplan-selected-" (ui2/safe-dom-id song-id))
                :data-song-id       (str song-id)
                :data-intensive     "false"
                :data-effect        (intensive-pop-effect-js song-id)
                :data-preserve-attr "data-last-emphasis"}
         (intensive? song) (assoc :data-intensive "true"))
   [:div {:data-drag-zone true}
    [:button {:type       "button"
              :aria-label "Drag to reorder"}
     [:wa-icon {:library "snoico"
                :name    "bars"}]]
    [:span title]]
   [:div
    (intensive-button req gig-id song)]])

(defn- selected-songs-list [req gig-id selected-songs]
  (ui2/section-card
   {:title    ((:tr req) [:gig/probeplan-sort])
    :divider? true}
   [:p {:class "gigs-probeplan-editor-guidance"}
    ((:tr req) [:gig/probeplan-order-guidance])]
   (if (seq selected-songs)
     (list
      [:ol {:id                          "gig-probeplan-selected-songs"
            :data-on:probeplan-reordered (->expr
                                          (set! $gig-probeplan.gig-id ~(str gig-id))
                                          (set! $gig-probeplan.order evt.detail.order)
                                          (@post ~(d*/act req ::actions/reorder-probeplan-songs)))}
       (for [song selected-songs]
         (selected-song-row req gig-id song))]
      (html/squint-inline
       (require '["sortable" :as s])
       (let [sort-container (.getElementById js/document "gig-probeplan-selected-songs")]
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
                                     (new js/CustomEvent "probeplan-reordered"
                                          {:bubbles true
                                           :detail  {:order order}}))))})))))
     [:div {:class "gigs-empty"} "—"])))

(defn page [{:keys [db page-state] :as req}]
  (let [gig-id            (http.util/path-param-uuid! req :gig/gig-id)
        gig               (q/retrieve-gig db gig-id)
        songs             (q/retrieve-all-songs db)
        repertoire-filter (actions/normalize-repertoire-filter
                           (get-in page-state [:gig-probeplan :repertoire-filter]))
        selected-songs    (actions/selected-songs-for-page db page-state gig-id)
        error             (get-in page-state [:gig-probeplan :_error])]
    (cond
      (nil? gig)
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id}))

      (not (gig.domain/probe? gig))
      (throw (ex-info "Probeplan is only available for probes" {:app/error-type :app.error.type/not-found
                                                                :gig/gig-id     gig-id}))

      :else
      (ui2/datastar-page
       [:div {:class        "wa-stack wa-gap-xl gigs-probeplan-editor-page"
              :data-signals (d*/->signals {:gig-probeplan {:gig-id            (str gig-id)
                                                           :repertoire-filter repertoire-filter
                                                           :songs             (selected-song-signals selected-songs)
                                                           :order             []}})}
        (page-summary req gig)
        (error-callout error)
        (pop-helper-script)
        (song-choices req gig-id songs repertoire-filter selected-songs)
        (selected-songs-list req gig-id selected-songs)]))))

(d*/refresh-all!)
