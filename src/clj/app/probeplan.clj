(ns app.probeplan
  (:require
   [app.datomic :as d]
   [app.queries :as q]
   [tick.core :as t]))

(defn next-wednesday [from]
  (if (= t/WEDNESDAY (t/day-of-week from))
    from
    (recur (next-wednesday (t/>> from (t/new-period 1 :days))))))

(defn wednesday-sequence
  "Return a lazy infinite sequence of dates that are wednesdays starting on from."
  [from]
  (t/range
   (t/beginning (next-wednesday from))
   ;; ok.. it's not really infinite
   (t/end (t/>> (t/date) (t/new-period 999 :months)))
   (t/new-period 7 :days)))

{:db/ident :probeplan/gig
 :db/doc "The gig this probeplan belongs to"
 :db/valueType :db.type/ref
 :db/unique :db.unique/unique
 :db/cardinality :db.cardinality/one}

{:db/ident :probeplan/format
 :db/doc "The format of the probeplan"
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :probeplan.classic/intensive1
 :db/doc "The first intensive song"
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident :probeplan.classic/intensive2
 :db/doc "The second intensive song"
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident :probeplan.classic/others
 :db/doc "The other songs to be 'durchgespielt'"
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many}

{:db/ident :probeplan.setlist/ordered-songs
 :db/doc "The other songs to be 'durchgespielt'"
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many}

(defn gen-plan-for-gig [_db gig]
  (let [_tx-data [{:probeplan/gig (d/ref gig)
                   :probeplan/format :classic
                   :probeplan.classic/intensive1 nil
                   :probeplan.classic/intensive2 nil
                   :probeplan.classic/others []}]]))

(def classic-num-intensive 2)
(def classic-num-durchspielen 5)

(defn gen-classic-from-last
  [_song-cycle {_intensive1 :probeplan.classic/intensive1
                _intensive2 :probeplan.classic/intensive2
                _others :probeplan.classic/others}])
(defn gen-classic-fresh [song-cycle]
  (let [num-to-take (+ classic-num-durchspielen classic-num-durchspielen)
        songs (take num-to-take song-cycle)
        intensive1 (first songs)
        intensive2 (second songs)
        others (take classic-num-durchspielen (nthrest songs 2))]
    {:probeplan.classic/intensive1 intensive1
     :probeplan.classic/intensive2 intensive2
     :probeplan.classic/others others}))

(defn gen-classic [song-cycle last-plan]
  (if last-plan
    (gen-classic-from-last song-cycle last-plan)
    (gen-classic-fresh song-cycle)))

(defn make-song-cycle
  "Return a lazy, infinite seq of songs, optionally shuffled once."
  ([db]
   (make-song-cycle db false))
  ([db shuffle?]
   (let [songs (q/retrieve-all-songs db)]
     (cycle
      (if shuffle?
        (shuffle songs)
        songs)))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require  '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))) ;; rcf

  (let [song-cycle (make-song-cycle db)]
    (take 7 song-cycle))

  (take 5 (nthrest (make-song-cycle db) 2))

  (gen-classic-fresh (make-song-cycle db))

  ;;
  )
