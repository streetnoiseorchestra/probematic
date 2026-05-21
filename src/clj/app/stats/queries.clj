(ns app.stats.queries
  (:require
   [app.datomic :as d]
   [app.datomic.shim :as datomic]
   [app.queries :as q]
   [app.util :as util]
   [app.util.http :as http.util]
   [clojure.core.cache.wrapped :as cache]
   [tick.core :as t]))

(defn- safe-div [numerator denominator]
  (if (pos? denominator)
    (/ numerator denominator)
    0))

(defn active-members-count [db]
  (->
   (d/q '[:find (count ?member)
          :in $
          :where
          [?member :member/active? ?active]
          [(= ?active true)]] db)
   ffirst))

(defn gig-attendance-stats [db gig-id]
  (let [gig                 (q/retrieve-gig db gig-id)
        gig-date            (:gig/date gig)
        time-point          (t/inst (t/in (t/at (t/date gig-date) "00:00") (t/zone "Europe/Vienna")))
        db-as-of            (datomic/as-of db time-point)
        active-member-count (or (active-members-count db-as-of) 0)
        attendances         (mapv first (d/q '[:find (pull ?attendance [{:attendance/member [:member/name
                                                                                             :member/avatar-template
                                                                                             :member/member-id
                                                                                             :member/nick
                                                                                             :member/email]}])
                                               :in $ ?gig
                                               :where
                                               [?attendance :attendance/gig ?gig]
                                               [?attendance :attendance/plan ?plan]
                                               [(= ?plan :plan/definitely)]]
                                             db
                                             [:gig/gig-id gig-id]))
        attended-count      (count attendances)]
    {:gig/gig-id     (:gig/gig-id gig)
     :gig/title      (:gig/title gig)
     :gig/date       gig-date
     :gig/gig-type   (:gig/gig-type gig)
     :attendences    attendances
     :active-count   active-member-count
     :attended-count attended-count
     :attendance-rate (safe-div attended-count active-member-count)}))

(defn aggregate-attendance-rate [data]
  (let [total-attended       (reduce + (map :attended-count data))
        total-active-members (reduce + (map :active-count data))]
    (safe-div total-attended total-active-members)))

(defn gigs-between [db instant-start instant-end]
  (->>
   (d/q '[:find ?gig-id
          :in $ ?ref-start ?ref-end
          :where
          [?e :gig/gig-id ?gig-id]
          [?e :gig/date ?date]
          [?e :gig/status ?gig-status]
          [(= ?gig-status :gig.status/confirmed)]
          [(>= ?date ?ref-start)]
          [(<= ?date ?ref-end)]] db
        instant-start instant-end)
   (mapv first)))

(defn update-count [acc attendance {:gig/keys [date gig-type title]}]
  (let [prev-last-seen (get-in acc [(:attendance/member attendance) :last-seen])
        seen?          (or (nil? prev-last-seen) (t/< prev-last-seen date))]
    (-> acc
        (update-in [(:attendance/member attendance) gig-type] (fnil inc 0))
        (update-in [(:attendance/member attendance) :last-seen]
                   (fn [last-seen]
                     (if seen?
                       date
                       last-seen)))
        (update-in [(:attendance/member attendance) :gig-title]
                   (fn [last-title]
                     (if seen?
                       title
                       last-title))))))

(defn process-gig [acc gig]
  (reduce (fn [inner-acc attendance]
            (update-count inner-acc attendance gig))
          acc
          (:attendences gig)))

(defn member-stats-from-gig-stats [probe-count gig-count per-gig-stats]
  (->> per-gig-stats
       (reduce process-gig {})
       (map (fn [[member v]] (assoc v :member member)))
       (map (fn [{:keys [gig.type/gig gig.type/probe] :as v}]
              (let [probes-attended (or probe 0)
                    gigs-attended   (or gig 0)]
                (-> v
                    (assoc :probes-attended probes-attended)
                    (assoc :gigs-attended gigs-attended)
                    (assoc :gig-rate (safe-div gigs-attended gig-count))
                    (assoc :probe-rate (safe-div probes-attended probe-count))))))
       (sort-by #(get-in % [:member :member/name]))))

(def gigs-attendance-stats-cache (cache/ttl-cache-factory {} :ttl (* 10 60 60 1000)))

(defn- mean-attendance [gigs]
  (when (seq gigs)
    (/ (reduce #(+ %1 (:attended-count %2)) 0 gigs)
       (count gigs))))

(defn- max-attended-count [gigs]
  (when (seq gigs)
    (apply max (map :attended-count gigs))))

(defn- min-attended-count [gigs]
  (when (seq gigs)
    (apply min (map :attended-count gigs))))

(defn -gigs-attendance-stats [db from to]
  (let [gig-ids       (gigs-between db from to)
        per-gig-stats (util/remove-nils (map #(gig-attendance-stats db %) gig-ids))
        gigs          (filter #(= (:gig/gig-type %) :gig.type/gig) per-gig-stats)
        probes        (filter #(= (:gig/gig-type %) :gig.type/probe) per-gig-stats)
        probe-count   (count probes)
        gig-count     (count gigs)
        member-stats  (member-stats-from-gig-stats probe-count gig-count per-gig-stats)]
    {:per-gig                  per-gig-stats
     :probe-count              probe-count
     :gig-count                gig-count
     :per-member-stats         member-stats
     :attendance-rate          (aggregate-attendance-rate per-gig-stats)
     :attendance-rate-gigs     (aggregate-attendance-rate gigs)
     :attendance-rate-probes   (aggregate-attendance-rate probes)
     :mean-attendance          (mean-attendance per-gig-stats)
     :mean-attendance-gig      (mean-attendance gigs)
     :mean-attendance-probe    (mean-attendance probes)
     :active-members-count     (when (seq per-gig-stats)
                                 (apply max (map :active-count per-gig-stats)))
     :most-active-gig-count    (max-attended-count gigs)
     :least-active-gig-count   (min-attended-count gigs)
     :most-active-probe-count  (max-attended-count probes)
     :least-active-probe-count (min-attended-count probes)}))

(defn gigs-attendance-stats [db from to]
  (cache/lookup-or-miss gigs-attendance-stats-cache [from to] (fn [[from to]]
                                                                (-gigs-attendance-stats db from to))))

(defn total-plays [db from to]
  (or
   (ffirst
    (d/q '[:find (count ?play)
           :in $ ?ref-start ?ref-end
           :where
           [?gig :gig/date ?date]
           [(>= ?date ?ref-start)]
           [(<= ?date ?ref-end)]
           [?play :played/gig ?gig]
           [?play :played/rating ?rating]
           [(not= ?rating :play-rating/not-played)]]
         db from to))
   0))

(def percent-bins
  (into (sorted-map) (map (fn [n] [n 0]) (range 0 101 10))))

(defn bin-members [rate-key per-member-stats]
  (reduce (fn [acc member-stat]
            (let [rate    (get member-stat rate-key)
                  bin-key (int (* 10 (Math/floor (* 10 rate))))]
              (update acc bin-key (fnil inc 0))))
          percent-bins
          per-member-stats))

(defn histogram-data [data kw]
  (let [per-member-stats (:per-member-stats data)]
    (into [] (map (fn [[x y]] {:x x :y y}) (sort (bin-members kw per-member-stats))))))

(defn stats-for [db from to sorting]
  (let [stats   (gigs-attendance-stats db from to)
        sorting (or sorting [{:field :member/name :order :asc}])]
    (-> stats
        (merge {:total-plays (total-plays db from to)})
        (assoc :gig-histogram (histogram-data stats :gig-rate))
        (assoc :probe-histogram (histogram-data stats :probe-rate))
        (update :per-member-stats #(http.util/sort-by-spec sorting %)))))
