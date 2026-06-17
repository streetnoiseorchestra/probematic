(ns app.gigs.log-plays.views
  (:require
   [app.datastar :as d*]
   [app.gigs.log-plays.actions :as actions]
   [app.gigs.song-plan.views :as plan.views]
   [app.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.util.http :as http.util]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(def rating-options
  [{:rating :play-rating/not-played
    :label  [:play-log/not-played]
    :icon   "circle-xmark-outline"
    :class  "gigs-log-play-icon--not-played"}
   {:rating :play-rating/good
    :label  [:play-log/nice]
    :icon   "smile"
    :class  "gigs-log-play-icon--good"}
   {:rating :play-rating/ok
    :label  [:play-log/okay]
    :icon   "meh"
    :class  "gigs-log-play-icon--ok"}
   {:rating :play-rating/bad
    :label  [:play-log/bad]
    :icon   "sad"
    :class  "gigs-log-play-icon--bad"}])

(defn- rating->str [rating]
  (name (actions/normalize-rating rating)))

(defn- rating-signal [rating]
  (str "play-rating/" (rating->str rating)))

(defn- emphasis-signal [emphasis]
  (if (= actions/intensive-emphasis (actions/normalize-emphasis emphasis))
    "play-emphasis/intensiv"
    "play-emphasis/durch"))

(defn- play-rating [play]
  (actions/normalize-rating (:played/rating play)))

(defn- play-emphasis [play]
  (actions/normalize-emphasis (:played/emphasis play)))

(defn- active-play? [play]
  (not (actions/not-played? (play-rating play))))

(defn- intensive? [play]
  (= actions/intensive-emphasis (play-emphasis play)))

(defn- play-signals [rows]
  (mapv (fn [{:song/keys [song-id] :keys [play]}]
          {:song-id  (str song-id)
           :rating   (rating-signal (play-rating play))
           :emphasis (emphasis-signal (play-emphasis play))})
        rows))

(defn- row-pop-effect-js [song-id]
  (->expr
   (let [play (if (.isArray Array $gig-log-plays.plays)
                (.find $gig-log-plays.plays
                       (fn [play]
                         (= (aget play "song-id") ~(str song-id))))
                nil)
         rating (if play play.rating "play-rating/not-played")
         emphasis (if play play.emphasis "play-emphasis/durch")
         state (str rating "|" emphasis)]
     (when (and el.dataset.lastPlayState
                (!== el.dataset.lastPlayState state))
       (let [pending (.querySelector el ".pending-pop")
             selected (.querySelector el (str "[data-rating='" rating "']"))]
         (.finish js/window.snoSongPlanPop (or pending selected el))))
     (set! el.dataset.lastPlayState state))))

(defn- repertoire-filter-button [req current-filter value label]
  [button/Button {:appearance    (if (= current-filter value) "filled" "outlined")
                  :variant       (when (= current-filter value) "brand")
                  :size          "s"
                  :aria-pressed  (if (= current-filter value) "true" "false")
                  :data-on:click (->expr
                                  (set! $gig-log-plays.repertoire-filter ~value)
                                  (@post ~(d*/act req ::actions/set-repertoire-filter)))}
   label])

(defn- repertoire-filter-control [req repertoire-filter]
  (plan.views/repertoire-filter-control
   {:req            req
    :current-filter repertoire-filter
    :button         (partial repertoire-filter-button req)}))

(defn- legend-item [{:keys [tr]} {:keys [class icon label]}]
  [:div {:class "gigs-log-plays-legend-item"}
   [:wa-icon {:library "snoico"
              :name    icon
              :class   (ui2/cs "gigs-log-play-icon gigs-log-play-icon--checked" class)}]
   [:span (tr label)]])

(defn- intensive-legend-item [{:keys [tr]}]
  [:div {:class "gigs-log-plays-legend-item gigs-log-plays-legend-item--intensive"}
   [:wa-icon {:library "snoico"
              :name    "fist-punch"
              :class   "gigs-log-play-icon gigs-log-play-icon--checked gigs-log-play-icon--intensive"}]
   [:span (tr [:play-log/intensive])]])

(defn- legend [req]
  [:div {:class "gigs-log-plays-legend"}
   (for [option rating-options]
     (legend-item req option))
   (intensive-legend-item req)])

(defn- rating-button [req gig-id {:song/keys [song-id]} play {:keys [class icon label rating]}]
  (let [selected? (= (actions/normalize-rating rating) (play-rating play))]
    [button/Button {:appearance         "plain"
                    :size               "s"
                    :aria-label         ((:tr req) label)
                    :aria-pressed       (if selected? "true" "false")
                    :data-rating        (rating-signal rating)
                    :data-rating-button true
                    :data-preserve-attr "class"
                    :data-on:click      (->expr
                                         (.pending js/window.snoSongPlanPop evt.currentTarget)
                                         (set! $gig-log-plays.gig-id ~(str gig-id))
                                         (set! $gig-log-plays.song-id ~(str song-id))
                                         (set! $gig-log-plays.rating ~(rating-signal rating))
                                         (set! $gig-log-plays.emphasis ~(emphasis-signal (play-emphasis play)))
                                         (@post ~(d*/act req ::actions/update-rating)))}
     [:wa-icon {:library "snoico"
                :name    icon
                :class   (ui2/cs "gigs-log-play-icon" class
                                 (when selected? "gigs-log-play-icon--checked"))}]]))

(defn- intensive-button [req gig-id {:song/keys [song-id]} play]
  (let [disabled? (not (active-play? play))]
    [button/Button {:appearance         "plain"
                    :size               "s"
                    :aria-label         ((:tr req) [:play-log/intensive])
                    :aria-pressed       (if (intensive? play) "true" "false")
                    :disabled           disabled?
                    :data-preserve-attr "class"
                    :data-on:click      (when-not disabled?
                                          (->expr
                                           (.pending js/window.snoSongPlanPop evt.currentTarget)
                                           (set! $gig-log-plays.gig-id ~(str gig-id))
                                           (set! $gig-log-plays.song-id ~(str song-id))
                                           (set! $gig-log-plays.rating ~(rating-signal (play-rating play)))
                                           (set! $gig-log-plays.emphasis ~(emphasis-signal (play-emphasis play)))
                                           (@post ~(d*/act req ::actions/toggle-intensive))))}
     [:wa-icon {:library "snoico"
                :name    "fist-punch"
                :class   (ui2/cs "gigs-log-play-icon gigs-log-play-icon--intensive"
                                 (when (intensive? play) "gigs-log-play-icon--checked"))}]]))

(defn- play-row [req gig-id {:song/keys [song-id title] :keys [play planned?] :as row}]
  [:div {:id                 (str "gig-log-play-row-" (ui2/safe-dom-id song-id))
         :class              "gigs-log-play-row"
         :data-song-id       (str song-id)
         :data-planned       (if planned? "true" "false")
         :data-rating        (rating-signal (play-rating play))
         :data-effect        (row-pop-effect-js song-id)
         :data-preserve-attr "data-last-play-state"}
   [:span {:class "gigs-log-play-title"} title]
   [:div {:class "gigs-log-play-controls"}
    (for [option rating-options]
      (rating-button req gig-id row play option))
    [:div {:class "gigs-log-play-intensive-control"}
     (intensive-button req gig-id row play)]]])

(defn- song-visible? [repertoire-filter song]
  (plan.views/repertoire-song? repertoire-filter song))

(defn- rows [db gig-id]
  (let [plays           (queries/plays-by-gig db gig-id)
        play-by-song-id (into {} (map (juxt (comp :song/song-id :played/song) identity)) plays)
        planned-songs   (queries/planned-songs-for-gig db gig-id)
        planned-order   (into {}
                              (map-indexed (fn [idx {:song/keys [song-id]}]
                                             [song-id idx]))
                              planned-songs)]
    (->> (queries/retrieve-all-songs db)
         (map (fn [{:song/keys [song-id title] :as song}]
                (assoc song
                       :play (get play-by-song-id song-id)
                       :planned? (contains? planned-order song-id)
                       :planned-position (get planned-order song-id)
                       :sort-title title)))
         (sort-by (juxt (comp {true 0 false 1} boolean :planned?)
                        #(or (:planned-position %) 0)
                        :sort-title))
         vec)))

(defn- plays-list [req gig-id rows repertoire-filter]
  (let [{planned true other false} (group-by :planned? rows)
        repertoire                 (filter (partial song-visible? repertoire-filter) other)]
    (ui2/section-card
     {:title    ((:tr req) [:song/log-play])
      :divider? true}
     [:div {:class "wa-stack wa-gap-m"}
      [:div {:class "wa-stack wa-gap-xs"}
       [:p {:class "gigs-log-plays-guidance gigs-log-plays-intro"}
        ((:tr req) [:gig/play-log-subtitle])]
       (legend req)]
      (when (seq planned)
        (list
         [:p {:class "gigs-log-plays-guidance gigs-log-plays-planned-guidance"}
          ((:tr req) [:gig/was-planned-to-play])]
         [:div {:class "gigs-log-plays-items gigs-log-plays-items--planned"}
          (for [row planned]
            (play-row req gig-id row))]
         [:wa-divider {:class "gigs-log-plays-separator"}]))
      (repertoire-filter-control req repertoire-filter)
      (if (seq repertoire)
        [:div {:class "gigs-log-plays-items"}
         (for [row repertoire]
           (play-row req gig-id row))]
        [:div {:class "gigs-empty"} "—"])])))

(defn page [{:keys [db page-state] :as req}]
  (let [gig-id            (http.util/path-param-uuid! req :gig/gig-id)
        gig               (queries/retrieve-gig db gig-id)
        repertoire-filter (actions/normalize-repertoire-filter
                           (get-in page-state [:gig-log-plays :repertoire-filter]))
        rows              (rows db gig-id)]
    (if-not gig
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id}))
      (ui2/datastar-page
       [:div {:class        "wa-stack wa-gap-xl gigs-log-plays-page"
              :data-signals (d*/->signals {:gig-log-plays {:gig-id            (str gig-id)
                                                           :repertoire-filter repertoire-filter
                                                           :plays             (play-signals rows)}})}
        (plan.views/page-summary req gig [:song/log-play])
        (plan.views/pop-helper-script)
        (plays-list req gig-id rows repertoire-filter)]))))

(d*/refresh-all!)
