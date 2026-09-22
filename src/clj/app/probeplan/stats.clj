(ns app.probeplan.stats
  (:require
   [app.datomic :as d]
   [app.probeplan.domain :as domain]
   [app.util :as util]
   [tick.core :as t]))

(defn days-since [d as-of]
  (t/days
   (t/between d as-of)))

(defn fetch-plays [db as-of]
  (->>
   (d/find-all db :played/gig [{:played/song [:song/song-id :song/title]} :played/rating :played/emphasis {:played/gig [:gig/gig-id :gig/date :gig/gig-type]}])
   (map first)
   (map #(-> %
             (merge (:played/gig %))
             (dissoc :played/gig)
             (merge (:played/song %))
             (dissoc :played/song)))
   (group-by :song/title)
   (vals)
   (map (fn [gs]
          {:song/song-id (-> gs first :song/song-id)
           :song/title   (-> gs first :song/title)
           :plays
           (->> gs
                (sort-by :gig/date t/>)
                (remove #(= :play-rating/not-played (:played/rating %)))
                (map (fn [g]
                       (-> g
                           (update :gig/date #(t/date-time %))
                           (assoc :days-since (days-since (:gig/date g) as-of))))))}))))

(defn calc-stats [db]
  (let [window-period (t/new-period 6 :months)
        today         (t/at (t/date) (t/midnight))
        window-cutoff (t/<< today window-period)]
    (->> (fetch-plays db today)
         (map (partial domain/calc-play-stat window-cutoff))
         (map util/remove-nils)
         (map domain/stat-tx)
         (map util/remove-nils))))
