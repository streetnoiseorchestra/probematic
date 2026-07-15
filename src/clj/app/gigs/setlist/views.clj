(ns app.gigs.setlist.views
  (:require
   [app.datastar :as d*]
   [app.gigs.domain :as gig.domain]
   [app.gigs.setlist.actions :as actions]
   [app.gigs.song-plan.views :as plan.views]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.page-surface :as page-surface]
   [app.util.http :as http.util]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

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
  [button/Button {:appearance    (if (= current-filter value) "filled" "outlined")
                  :variant       (when (= current-filter value) "brand")
                  :size          "s"
                  :aria-pressed  (if (= current-filter value) "true" "false")
                  :data-on:click (->expr
                                  (set! $gig-setlist.repertoire-filter ~value)
                                  (@post ~(d*/act req ::actions/set-repertoire-filter)))}
   label])

(defn- song-choice [req gig-id selected-ids {:song/keys [last-played-on song-id title]}]
  (plan.views/song-choice
   {:id-prefix "gig-setlist-choice-"
    :song-id   song-id
    :title     title
    :subtitle  (plan.views/last-played-subtitle req last-played-on)
    :checked?  (selected-ids song-id)
    :effect    (song-choice-pop-effect-js song-id)
    :on-change (toggle-song-client-js req gig-id song-id)}))

(defn- song-choices [req gig-id songs repertoire-filter selected-songs]
  (plan.views/song-choices
   {:req               req
    :title-kw          [:gig/setlist-choose]
    :guidance-kw       [:gig/setlist-guidance]
    :songs             songs
    :repertoire-filter repertoire-filter
    :selected-songs    selected-songs
    :filter-control    (plan.views/repertoire-filter-control
                        {:req            req
                         :current-filter repertoire-filter
                         :button         (partial repertoire-filter-button req)})
    :choice            (partial song-choice req gig-id)}))

(defn- selected-song-row [_req _gig-id {:song/keys [song-id title]}]
  [:li {:id             (str "gig-setlist-selected-" (ui2/safe-dom-id song-id))
        :data-song-id   (str song-id)}
   (plan.views/drag-zone title)])

(defn- selected-songs-list [req gig-id selected-songs]
  (plan.views/selected-songs-list
   {:req            req
    :title-kw       [:gig/setlist-sort]
    :guidance-kw    [:gig/setlist-order-guidance]
    :selected-songs selected-songs
    :list-id        "gig-setlist-selected-songs"
    :list-class     "gigs-song-plan-selected"
    :on-reordered   (->expr
                     (set! $gig-setlist.gig-id ~(str gig-id))
                     (set! $gig-setlist.order evt.detail.order)
                     (@post ~(d*/act req ::actions/reorder-setlist-songs)))
    :row            (partial selected-song-row req gig-id)}))

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
      (ui2/datastar-page*
       [page-surface/PageSurface {::page-surface/toolbar
                                  (plan.views/page-toolbar req gig :gigs/setlist)}
        [:div {:class        "wa-stack wa-gap-xl gigs-probeplan-editor-page"
               :data-signals (d*/->signals {:gig-setlist {:gig-id            (str gig-id)
                                                          :repertoire-filter repertoire-filter
                                                          :songs             (selected-song-signals selected-songs)
                                                          :order             []}})}
         (plan.views/page-summary :gigs/setlist)
         (plan.views/error-callout error)
         (song-choices req gig-id songs repertoire-filter selected-songs)
         (selected-songs-list req gig-id selected-songs)
         (plan.views/pop-helper-script)]]))))

(d*/refresh-all!)
