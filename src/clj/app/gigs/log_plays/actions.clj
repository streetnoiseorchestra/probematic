(ns app.gigs.log-plays.actions
  (:require
   [app.queries :as queries]
   [app.util :as util]))

(def log-plays-path [:gig-log-plays])
(def repertoire-filter-path (conj log-plays-path :repertoire-filter))
(def default-repertoire-filter "current")
(def repertoire-filters #{"current" "old" "all"})
(def ratings #{:play-rating/not-played
               :play-rating/good
               :play-rating/ok
               :play-rating/bad})
(def default-rating :play-rating/not-played)
(def default-emphasis :play-emphasis/durch)
(def intensive-emphasis :play-emphasis/intensiv)

(defn normalize-repertoire-filter [filter]
  (let [filter (str filter)]
    (if (repertoire-filters filter)
      filter
      default-repertoire-filter)))

(defn normalize-rating [rating]
  (let [rating (if (keyword? rating)
                 rating
                 (keyword (str rating)))]
    (if (ratings rating)
      rating
      default-rating)))

(defn normalize-emphasis [emphasis]
  (let [emphasis (if (keyword? emphasis)
                   emphasis
                   (keyword (str emphasis)))]
    (if (= intensive-emphasis emphasis)
      intensive-emphasis
      default-emphasis)))

(defn not-played? [rating]
  (= default-rating (normalize-rating rating)))

(defn- play-by-song-id [plays song-id]
  (some #(when (= song-id (get-in % [:played/song :song/song-id])) %) plays))

(defn play-tx-data [db gig-id song-id rating emphasis]
  (let [gig-id       (util/ensure-uuid! gig-id)
        song-id      (util/ensure-uuid! song-id)
        rating       (normalize-rating rating)
        emphasis     (if (not-played? rating)
                       default-emphasis
                       (normalize-emphasis emphasis))
        current-play (play-by-song-id (queries/plays-by-gig db gig-id) song-id)]
    [(cond-> {:played/gig      [:gig/gig-id gig-id]
              :played/song     [:song/song-id song-id]
              :played/rating   rating
              :played/gig+song (pr-str [gig-id song-id])
              :played/emphasis emphasis}
       (:played/play-id current-play) (assoc :played/play-id (:played/play-id current-play))
       (nil? (:played/play-id current-play)) (assoc :played/play-id :db/gen-uuid))]))

(defn persist-play-effect [db gig-id song-id rating emphasis]
  [:db/transact
   (play-tx-data db gig-id song-id rating emphasis)
   {:on-success [[:app.gigs/recalc-play-stats]
                 [:app.gigs/trigger-gig-edited (util/ensure-uuid! gig-id) :plays]]}])

(defn update-rating-action [{:keys [db]} {:keys [gig-log-plays]}]
  (let [{:keys [gig-id song-id rating emphasis]} gig-log-plays]
    [(persist-play-effect db gig-id song-id rating emphasis)]))

(defn toggle-intensive-action [{:keys [db]} {:keys [gig-log-plays]}]
  (let [{:keys [gig-id song-id rating emphasis]} gig-log-plays
        rating    (normalize-rating rating)
        current   (normalize-emphasis emphasis)
        emphasis* (if (= intensive-emphasis current)
                    default-emphasis
                    intensive-emphasis)]
    [(persist-play-effect db gig-id song-id rating emphasis*)]))

(defn set-repertoire-filter-action [_state {:keys [gig-log-plays]}]
  (let [{:keys [repertoire-filter]} gig-log-plays]
    [[:app.datastar/assoc-state repertoire-filter-path (normalize-repertoire-filter repertoire-filter)]]))

(def actions
  {::set-repertoire-filter #'set-repertoire-filter-action
   ::update-rating         #'update-rating-action
   ::toggle-intensive      #'toggle-intensive-action})
