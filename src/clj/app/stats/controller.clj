(ns app.stats.controller
  (:require
   [app.datomic :as d]
   [app.queries :as q]
   [app.util :as util]
   [app.util.http :as http.util]
   [clojure.core.cache.wrapped :as cache]
   [app.datomic.shim :as datomic]
   [tick.core :as t]))

(defn active-members-in-period [db from to]
  (d/q '[:find (pull ?member pat)
         :in $ ?ref-start ?ref-end pat
         :where
         [?gig :gig/date ?date]
         [(>= ?date ?ref-start)]
         [(<= ?date ?ref-end)]
         [?attendance :attendance/gig ?gig]
         [?attendance :attendance/member ?member]
         [?attendance :attendance/plan ?plan]
         [(= ?plan :plan/definitely)]]
       db
       from
       to
       q/member-pattern))

(defn active-members-count [db]
  (->
   (d/q '[:find (count ?member)
          :in $
          :where
          [?member :member/active? ?active]
          [(= ?active true)]] db)
   ffirst))

(defn gig-attendance-stats [db gig-id]
  (let [gig (q/retrieve-gig db gig-id)
        gig-date (:gig/date gig)
        time-point (t/inst (t/in (t/at (t/date gig-date) "00:00") (t/zone "Europe/Vienna")))
        db-as-of (datomic/as-of db time-point)
        active-member-count (active-members-count db-as-of)
        attendences (mapv first (d/q '[;; :find (count ?attendance)
                                       :find (pull ?attendance [{:attendance/member [:member/name  :member/avatar-template :member/member-id :member/nick :member/email]}])
                                       :in $ ?gig
                                       :where
                                       [?attendance :attendance/gig ?gig]
                                       [?attendance :attendance/plan ?plan]
                                       [(= ?plan :plan/definitely)]]
                                     db
                                     [:gig/gig-id gig-id]))
        attended-count (or (count attendences) 0)]
    (when (some? active-member-count)
      #_(tap> {:at attended-count :active-count active-member-count
               :gig/gig-id (:gig/gig-id gig)
               :gig/title (:gig/title gig)
               :gig/date gig-date})
      {:gig/gig-id (:gig/gig-id gig)
       :gig/title (:gig/title gig)
       :gig/date gig-date
       :gig/gig-type (:gig/gig-type gig)
       :attendences attendences
       :active-count active-member-count
       :attended-count attended-count
       ;; :attendances attendances
       :attendance-rate (/ attended-count active-member-count)})))

(defn aggregate-attendance-rate [data]
  (let [total-attended (reduce + (map :attended-count data))
        total-active-members (reduce + (map :active-count data))]
    (if (> total-active-members 0)
      (/ total-attended total-active-members)
      0)))

#_(defn aggregate-attendance-rate [data]
  ;; weighted average formula
    (let [total-weighted-attendance (reduce + (map #(* (:attendance-rate %) (:active-count %)) data))
          total-active-members (reduce + (map :active-count data))]
      (/ total-weighted-attendance total-active-members)))

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
        instant-start  instant-end)
   (mapv first)))

(defn update-count [acc attendance {:gig/keys [date gig-type title]}]
  (let [prev-last-seen (get-in acc [(:attendance/member attendance) :last-seen])
        seen? (or (nil? prev-last-seen) (t/< prev-last-seen date))]
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
  (reduce (fn [inner-acc att]
            (update-count inner-acc att gig))
          acc (:attendences gig)))

(defn member-stats-from-gig-stats [probe-count gig-count per-gig-stats]
  (->> per-gig-stats
       (reduce process-gig {})
       (map (fn [[member v]] (assoc v :member member)))
       (map (fn [{:keys [gig.type/gig gig.type/probe] :as v}]
              (let [probes-attended (or probe 0)
                    gigs-attended (or gig 0)]
                (-> v
                    (assoc :probes-attended probes-attended)
                    (assoc :gigs-attended gigs-attended)
                    (assoc :gig-rate (/ gigs-attended gig-count))
                    (assoc :probe-rate (/ probes-attended probe-count))))))

       (sort-by #(get-in % [:member :member/name]))))

(def gigs-attendance-stats-cache (cache/ttl-cache-factory {} :ttl (* 10 #_hours 60 60 1000)))

(defn -gigs-attendance-stats [db from to]
  (let [gig-ids (gigs-between db from to)
        per-gig-stats (util/remove-nils (map #(gig-attendance-stats db %) gig-ids))
        gigs (filter #(= (:gig/gig-type %) :gig.type/gig) per-gig-stats)
        probes (filter #(= (:gig/gig-type %) :gig.type/probe) per-gig-stats)
        probe-count (count probes)
        gig-count (count gigs)
        member-stats (member-stats-from-gig-stats probe-count gig-count per-gig-stats)]
    {:per-gig per-gig-stats
     :probe-count  probe-count
     :gig-count  gig-count
     :per-member-stats member-stats
     :attendance-rate (aggregate-attendance-rate per-gig-stats)
     :attendance-rate-gigs (aggregate-attendance-rate gigs)
     :attendance-rate-probes (aggregate-attendance-rate probes)
     :mean-attendance (when (> (count per-gig-stats) 0) (/ (reduce #(+ %1 (:attended-count %2)) 0 per-gig-stats) (count per-gig-stats)))
     :mean-attendance-gig (when (> gig-count 0) (/ (reduce #(+ %1 (:attended-count %2)) 0 gigs) (count gigs)))
     :mean-attendance-probe (when (> probe-count 0) (/ (reduce #(+ %1 (:attended-count %2)) 0 probes) (count probes)))
     :active-members-count (when (> (count per-gig-stats) 0)
                             (apply max (map :active-count per-gig-stats)))
     :most-active-gig-count  (when (> (count gigs) 0)
                               (apply max (map :attended-count gigs)))
     :least-active-gig-count  (when (> (count gigs) 0)
                                (apply min (map :attended-count gigs)))

     :most-active-probe-count  (when (> (count probes) 0)
                                 (apply max (map :attended-count probes)))
     :least-active-probe-count  (when (> (count probes) 0)
                                  (apply min (map :attended-count probes)))}))

(defn gigs-attendance-stats [db from to]
  (cache/lookup-or-miss gigs-attendance-stats-cache [from to] (fn [[from to]]
                                                                (-gigs-attendance-stats db from to))))

(defn total-plays [db from to]
  (->> (d/q '[:find (count ?play)
              :in $ ?ref-start ?ref-end
              :where
              [?gig :gig/date ?date]
              [(>= ?date ?ref-start)]
              [(<= ?date ?ref-end)]
              [?play :played/gig ?gig]
              [?play :played/rating ?rating]
              [(not= ?rating :play-rating/not-played)]]
            db from to)
       ffirst))

(defn attendance-rate [db from to]
  ;; (let [active-members-in-period ])
  )

(def percent-bins
  (into {} [{0 0} {10 0} {20 0} {30 0} {40 0} {50 0} {60 0} {70 0} {80 0} {90 0} {100 0}]))

(defn bin-members [rate-key per-member-stats]
  (let [bins percent-bins]
    (reduce (fn [acc m]

              (let [rate (get m rate-key)
                    bin-key (int (* 10 (Math/floor (* 10 rate))))]
                (update acc bin-key (fnil inc 0))))
            bins
            per-member-stats)))

(defn histogram-data [data kw]
  (let [per-member-stats (:per-member-stats data)]
    (into [] (map (fn [[x y]] {:x x :y y}) (sort (bin-members kw per-member-stats))))))

(defn stats-for [db from to sorting]
  (let [stats  (gigs-attendance-stats db from to)
        sorting (or sorting [{:field :member/name :order :asc}])]
    (->  stats
         (merge {:total-plays (total-plays db from to)})
         (assoc :gig-histogram (histogram-data stats :gig-rate))
         (assoc :probe-histogram (histogram-data stats :probe-rate))
         (update :per-member-stats #(http.util/sort-by-spec sorting %)))))

(clojure.core/comment

  (do
    (require '[integrant.repl.state :as state])
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))) ;; rcf

  (def three-months-ago (t/inst (t/<< (t/zoned-date-time) (t/new-period 3 :months))))
  (def four-days-ago (t/inst (t/<< (t/zoned-date-time) (t/new-period 4 :days))))
  (def yesterday (t/inst (t/<<  (t/zoned-date-time) (t/new-period 1 :days))))

  (attendance-rate db
                   (t/<< (t/zoned-date-time) (t/new-period 3 :months))
                   (t/zoned-date-time))
  (let [am (active-members-in-period db
                                     four-days-ago
                                     yesterday)]
    (->> am
         (map first)
         (map :member/name)
         count))

  ;; rcf
  (active-members-count db)
  (gig-attendance-stats db #uuid "018a802e-fba5-8f5f-b630-0c96ac26d87d") ;; rcf
  (gigs-attendance-stats db four-days-ago yesterday)                     ;; rcf
  (gigs-attendance-stats db four-days-ago yesterday)                     ;; rcf
  (stats-for db four-days-ago yesterday nil)                                 ;; rcf
  (stats-for db three-months-ago yesterday nil)                              ;; rcf
  (gigs-between db three-months-ago yesterday)                           ;; rcf
  (stats-for db four-days-ago yesterday nil)                                 ;; rcf
  (total-plays db four-days-ago yesterday)                               ;; rcf
  (total-plays db three-months-ago yesterday)                            ;; rcf
  )
