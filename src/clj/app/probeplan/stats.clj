(ns app.probeplan.stats
  (:import [java.time Instant])
  (:require
   [app.probeplan.domain :as domain]
   [app.datomic :as d]
   [app.util :as util]
   [app.datomic.shim :as datomic]
   [tick.core :as t]
   [chime.core :as chime]))

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
           :song/title (-> gs first :song/title)
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
        today (t/at (t/date) (t/midnight))
        window-cutoff (t/<< today window-period)]
    (->> (fetch-plays db today)
         (map (partial domain/calc-play-stat window-cutoff))
         (map util/remove-nils)
         (map domain/stat-tx)
         (map util/remove-nils))))

(defn calc-and-save-play-stats! [conn]
  (datomic/transact conn {:tx-data
                          (calc-stats (datomic/db conn))}))

(defn calc-play-stats-in-bg! [conn]
  (chime/chime-at [(.plusSeconds (Instant/now) 5)]
                  (fn [_]
                    (calc-and-save-play-stats! conn)))
  nil)

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))) ;; rcf

  (d/find-all db :played/song [{:played/song [:song/title]}])
  (def today (t/at (t/today) (t/midnight)))
  (fetch-plays db today)
  (calc-stats db)

  (calc-and-save-play-stats! conn)
  (calc-play-stats-in-bg! conn)

  ;;
  )
