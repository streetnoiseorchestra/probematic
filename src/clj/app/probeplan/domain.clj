(ns app.probeplan.domain
  (:require
   [app.probeplan :as pp]
   [app.schemas :as s]
   [clojure.set :as set]
   [medley.core :as m]
   [tick.core :as t]))

(def MAX-INTENSIVE 2)
(def MAX-SONGS 5)

(def probeplan-versions #{:probeplan.version/classic})

(def probeplan-classic-emphases #{:probeplan.emphasis/intensive :probeplan.emphasis/none})
(def probeplan-classic-default-emphasis :probeplan.emphasis/none)

(def str->play-emphasis (zipmap (map name probeplan-classic-emphases) probeplan-classic-emphases))

(defn emphasis-comparator
  "Comparator to sort intensive emphasis before non-intensive"
  [a b]
  (cond (and (= :probeplan.emphasis/intensive a)
             (= :probeplan.emphasis/intensive b))
        false
        (and (= :probeplan.emphasis/none a)
             (= :probeplan.emphasis/intensive b))
        false
        (and (= :probeplan.emphasis/intensive a)
             (= :probeplan.emphasis/none b))
        true
        :else false))

(defn probeplan-version? [v]
  (contains? probeplan-versions v))

(defn probeplan-classic-emphases? [v]
  (contains? probeplan-classic-emphases v))

(defn probeplan-classic-ordered-song? [v]
  (and (= 3 (count v))
       (int? (second v))
       (probeplan-classic-emphases? v)))

(def ProbeplanClassicEntity
  [:map {:name :app.entity/probeplan-classic}
   [:probeplan/gig ::s/datomic-ref]
   [:probeplan/version (s/enum-from probeplan-versions)]
   [:probeplan.classic/ordered-songs [:sequential [:tuple ::s/datomic-ref :int (s/enum-from probeplan-classic-emphases)]]]])

(def ProbeplanTableRow
  [:map {:name :app.entity/probeplan-table-row}
   [:date ::s/date]
   [:gig-id {:optional true} :uuid]
   [:fixed? :boolean]
   [:last-fixed? :boolean]
   [:num-gigs integer?]
   [:idx integer?]])

(defn future-probeplans [all-songs future-probes]
  (let [num-probes        20
        num-songs-p-probe MAX-SONGS
        num-songs-needed  (* 20 MAX-SONGS)
        num-fixed         4
        probe-dates       (mapv t/date (take num-probes (drop num-fixed (pp/wednesday-sequence (t/date)))))
        songs             (partition num-songs-p-probe (take num-songs-needed (cycle all-songs)))
        fixed-probes (->> (take num-fixed future-probes)
                          (map-indexed (fn [idx gig]
                                         (-> gig
                                             (set/rename-keys {:gig/date   :date
                                                               :gig/gig-id :gig-id})
                                             (select-keys [:date :gig-id :songs])
                                             (assoc :fixed? true)
                                             (assoc :idx idx)
                                             (assoc :num-gigs 0)
                                             (assoc :last-fixed? (= (inc idx)  num-fixed))))))
        future-probes (map (fn [idx songs date] {:songs       songs
                                                 :date        date
                                                 :idx         (+ num-fixed idx)
                                                 :last-fixed? false
                                                 :num-gigs    (rand-int 3)
                                                 :fixed?      false})
                           (range) songs probe-dates)

        all-probes (concat fixed-probes future-probes)]
    (assert (every? (partial s/valid? ProbeplanTableRow) all-probes))
    all-probes))

(def stat-weights {:song/days-since-performed 50
                   :song/days-since-rehearsed 100
                   :song/days-since-intensive 50})

;; score = a_rank * a_weight + b_rank * b_weight + c_rank * c_weight.
(defn score-song [{:song/keys [days-since-performed days-since-rehearsed days-since-intensive]
                   :or        {days-since-performed 999
                               days-since-rehearsed 999
                               days-since-intensive 999}}]
  (reduce +
          [(* days-since-performed (:song/days-since-performed stat-weights))
           (* days-since-rehearsed (:song/days-since-rehearsed stat-weights))
           (* days-since-intensive (:song/days-since-intensive stat-weights))]))

(defn score-and-sort [s]
  (->> s
       (mapv #(assoc % :score (score-song %)))
       (mapv #(select-keys % [:song/song-id :song/title :score]))
       (sort-by :score >)))

(defn generate-probeplan
  "A pure function that takes the historical play-stats and outputs a sequence of probe plans in priority order"
  [play-stats]
  (->> play-stats
       score-and-sort)

;;
  )

(defn days-since-when [pred plays]
  (when-let [play (m/find-first pred plays)]
    (:days-since play)))

(defn calc-play-stat [window-cutoff {:keys [plays] :as p}]
  (let [last-play (first plays)
        reversed (reverse plays)
        is-gig? #(= :gig.type/gig (:gig/gig-type %))
        is-probe? #(#{:gig.type/probe :gig.type/extra-probe} (:gig/gig-type %))
        is-intensive? #(= :play-emphasis/intensiv (:played/emphasis %))
        is-good? #(= :play-rating/good (:played/rating %))
        is-bad? #(= :play-rating/bad (:played/rating %))
        is-ok? #(= :play-rating/ok (:played/rating %))
        windowed-plays (filter #(t/>= (:gig/date %) window-cutoff) plays)]
    (-> p
        (assoc :last-performance (:gig/gig-id (m/find-first is-gig? plays)))
        (assoc :last-rehearsal (:gig/gig-id (m/find-first is-probe? plays)))
        (assoc :last-intensive (:gig/gig-id (m/find-first is-intensive? plays)))
        (assoc :first-performance (:gig/gig-id (m/find-first is-gig? reversed)))
        (assoc :first-rehearsal (:gig/gig-id (m/find-first is-probe? reversed)))
        (assoc :total-plays (count plays))
        (assoc :total-performances (count (filter is-gig? plays)))
        (assoc :total-rehearsals (count (filter is-probe? plays)))
        (assoc :total-rating-good (count (filter is-good? plays)))
        (assoc :total-rating-bad (count (filter is-bad? plays)))
        (assoc :total-rating-ok (count (filter is-ok? plays)))
        (assoc :windowed-total-rating-good (count (filter is-good? windowed-plays)))
        (assoc :windowed-total-rating-bad (count (filter is-bad? windowed-plays)))
        (assoc :windowed-total-rating-ok (count (filter is-ok? windowed-plays)))
        (assoc :last-played-on (:gig/date last-play))
        (assoc :days-since-last-played (:days-since last-play))
        (assoc :days-since-performed (days-since-when is-gig? plays))
        (assoc :days-since-rehearsed (days-since-when is-probe? plays))
        (assoc :days-since-intensive (days-since-when is-intensive? plays))
        ;;
        )))

(defn stat-tx [stat]
  {:song/song-id                     (:song/song-id stat)
   :song/total-plays                 (:total-plays stat)
   :song/total-performances          (:total-performances stat)
   :song/total-rehearsals            (:total-rehearsals stat)
   :song/total-rating-good           (:total-rating-good stat)
   :song/total-rating-bad            (:total-rating-bad stat)
   :song/total-rating-ok             (:total-rating-ok stat)
   :song/six-month-total-rating-good (:windowed-total-rating-good stat)
   :song/six-month-total-rating-bad  (:windowed-total-rating-bad stat)
   :song/six-month-total-rating-ok   (:windowed-total-rating-ok stat)
   :song/days-since-performed        (:days-since-performed stat)
   :song/days-since-rehearsed        (:days-since-rehearsed stat)
   :song/days-since-intensive        (:days-since-intensive stat)
   :song/last-played-on              (when-let [v (:last-played-on stat)] (t/inst v))
   :song/last-performance            (when-let [v (:last-performance stat)] [:gig/gig-id v])
   :song/last-rehearsal              (when-let [v (:last-rehearsal stat)] [:gig/gig-id v])
   :song/last-intensive              (when-let [v (:last-intensive stat)] [:gig/gig-id v])
   :song/first-performance           (when-let [v (:first-performance stat)] [:gig/gig-id v])
   :song/first-rehearsal             (when-let [v (:first-rehearsal stat)] [:gig/gig-id v])})
