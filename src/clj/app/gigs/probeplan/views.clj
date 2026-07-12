(ns app.gigs.probeplan.views
  (:require
   [app.datastar :as d*]
   [app.gigs.domain :as gig.domain]
   [app.gigs.probeplan.actions :as actions]
   [app.gigs.song-plan.views :as plan.views]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.util.http :as http.util]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn- intensive? [{:keys [emphasis]}]
  (= :probeplan.emphasis/intensive emphasis))

(defn- selected-song-signals [songs]
  (mapv (fn [idx {:song/keys [song-id] :keys [emphasis]}]
          {:song-id  (str song-id)
           :position idx
           :emphasis (if (intensive? {:emphasis emphasis}) "intensive" "none")})
        (range)
        songs))

(defn- toggle-song-client-js [req gig-id song-id]
  (->expr
   (.pending js/window.snoSongPlanPop evt.target)
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
       (.finish js/window.snoSongPlanPop el))
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
       (let [icon (.querySelector el ".gigs-probeplan-intensive-icon")]
         (when icon
           (.finish js/window.snoSongPlanPop icon))))
     (set! el.dataset.lastEmphasis emphasis))))

(defn- repertoire-filter-button [req current-filter value label]
  [button/Button {:appearance      (if (= current-filter value) "filled" "outlined")
                  :variant         (when (= current-filter value) "brand")
                  :size            "s"
                  :aria-pressed    (if (= current-filter value) "true" "false")
                  :data-on:click   (->expr
                                    (set! $gig-probeplan.repertoire-filter ~value)
                                    (@post ~(d*/act req ::actions/set-repertoire-filter)))}
   label])

(defn- song-choice [req gig-id selected-ids {:song/keys [last-played-on song-id title]}]
  (plan.views/song-choice
   {:id-prefix "gig-probeplan-choice-"
    :song-id   song-id
    :title     title
    :subtitle  (plan.views/last-played-subtitle req last-played-on)
    :checked?  (selected-ids song-id)
    :effect    (song-choice-pop-effect-js song-id)
    :on-change (toggle-song-client-js req gig-id song-id)}))

(defn- song-choices [req gig-id songs repertoire-filter selected-songs]
  (plan.views/song-choices
   {:req               req
    :title-kw          [:gig/probeplan-choose]
    :guidance-kw       [:gig/probeplan-guidance]
    :songs             songs
    :repertoire-filter repertoire-filter
    :selected-songs    selected-songs
    :filter-control    (plan.views/repertoire-filter-control
                        {:req            req
                         :current-filter repertoire-filter
                         :button         (partial repertoire-filter-button req)})
    :choice            (partial song-choice req gig-id)}))

(defn- intensive-button [req gig-id {:song/keys [song-id]}]
  [button/Button {:appearance    "plain"
                  :size          "s"
                  :aria-label    "Toggle intensive"
                  :data-on:click (->expr
                                  (let [icon (.querySelector evt.currentTarget ".gigs-probeplan-intensive-icon")]
                                    (when icon
                                      (.pending js/window.snoSongPlanPop icon)))
                                  (set! $gig-probeplan.gig-id ~(str gig-id))
                                  (set! $gig-probeplan.song-id ~(str song-id))
                                  (@post ~(d*/act req ::actions/toggle-probeplan-intensive)))}
   [ico/Icon {::ico/library       :snoico
              ::ico/name          :fist-punch
              :class              "gigs-probeplan-intensive-icon"
              :data-preserve-attr "class"}]])

(defn- selected-song-row [req gig-id {:song/keys [song-id title] :as song}]
  [:li (cond-> {:id                 (str "gig-probeplan-selected-" (ui2/safe-dom-id song-id))
                :data-song-id       (str song-id)
                :data-intensive     "false"
                :data-effect        (intensive-pop-effect-js song-id)
                :data-preserve-attr "data-last-emphasis"}
         (intensive? song) (assoc :data-intensive "true"))
   (plan.views/drag-zone title)
   [:div
    (intensive-button req gig-id song)]])

(defn- selected-songs-list [req gig-id selected-songs]
  (plan.views/selected-songs-list
   {:req            req
    :title-kw       [:gig/probeplan-sort]
    :guidance-kw    [:gig/probeplan-order-guidance]
    :selected-songs selected-songs
    :list-id        "gig-probeplan-selected-songs"
    :list-class     "gigs-song-plan-selected gigs-song-plan-selected--with-actions"
    :on-reordered   (->expr
                     (set! $gig-probeplan.gig-id ~(str gig-id))
                     (set! $gig-probeplan.order evt.detail.order)
                     (@post ~(d*/act req ::actions/reorder-probeplan-songs)))
    :row            (partial selected-song-row req gig-id)}))

(defn page [{:keys [db page-state] :as req}]
  (let [gig-id            (http.util/path-param-uuid! req :gig/gig-id)
        gig               (q/retrieve-gig db gig-id)
        songs             (q/retrieve-all-songs db)
        repertoire-filter (actions/normalize-repertoire-filter
                           (get-in page-state [:gig-probeplan :repertoire-filter]))
        selected-songs    (actions/selected-songs-for-page db gig-id)
        error             (get-in page-state [:gig-probeplan :_error])]
    (cond
      (nil? gig)
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id}))

      (not (gig.domain/probe? gig))
      (throw (ex-info "Probeplan is only available for probes" {:app/error-type :app.error.type/not-found
                                                                :gig/gig-id     gig-id}))

      :else
      (ui2/datastar-page*
       [page-surface/PageSurface
        {::page-surface/width   :wide
         ::page-surface/toolbar (plan.views/page-toolbar req gig :gigs/probeplan)}
        [:div {:class        "wa-stack wa-gap-xl gigs-probeplan-editor-page"
               :data-signals (d*/->signals {:gig-probeplan {:gig-id            (str gig-id)
                                                            :repertoire-filter repertoire-filter
                                                            :songs             (selected-song-signals selected-songs)
                                                            :order             []}})}
         (plan.views/page-summary :gigs/probeplan)
         (plan.views/error-callout error)
         (song-choices req gig-id songs repertoire-filter selected-songs)
         (selected-songs-list req gig-id selected-songs)
         (plan.views/pop-helper-script)]]))))

(d*/refresh-all!)
