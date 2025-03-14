(ns app.probeplan.controller
  (:require
   [app.gigs.service :as gig.service]
   [app.probeplan.domain :as domain]
   [app.queries :as q]
   [app.util :as util]
   [app.datomic.shim :as datomic]))

(defn generate-probeplan! [db]
  (let [play-stats (q/load-play-stats db)]
    (domain/generate-probeplan play-stats)))

(defn future-probeplans [db]
  (let [probes  (q/next-probes-with-plan db domain/emphasis-comparator)
        probeplan (generate-probeplan! db)]
    (loop [probes probes
           probeplan probeplan
           result []]
      (if-let [probe (first probes)]
        (if (empty? (:songs probe))
          (recur (rest probes)
                 (drop domain/MAX-SONGS probeplan)
                 (conj result (assoc probe :songs (take domain/MAX-SONGS probeplan))))
          (recur (rest probes)
                 probeplan
                 (conj result probe)))
        result))))
(defn probeplan-song-tx [{:keys [song-id emphasis position]}]
  [[:song/song-id (util/ensure-uuid song-id)]
   (Integer/parseInt position)
   (domain/str->play-emphasis emphasis)])

(defn update-probeplan-tx [db idx {:keys  [date gig-id probeplan-probe-song-col-rw]}]
  (let [gig-id (util/ensure-uuid! gig-id)
        song-tuples (map probeplan-song-tx probeplan-probe-song-col-rw)
        current  (q/probeplan-song-tuples-for-gig db gig-id)
        tmpid (str "probeplan-" idx)
        song-txs (gig.service/reconcile-probeplan tmpid song-tuples current)

        tx  {:probeplan/gig [:gig/gig-id gig-id]
             :db/id tmpid
             :probeplan/version :probeplan.version/classic}
        txs (concat [tx] song-txs)]
    (if (seq song-txs)
      txs
      nil)))

(defn probeplan-plans [db]
  (domain/future-probeplans (q/retrieve-active-songs db)
                            (future-probeplans db)))

(defn update-probeplans! [{:keys [datomic-conn db] :as req}]
  (let [params (util/unwrap-params req)
        txs
        (->> params
             (map-indexed (partial update-probeplan-tx db))
             (remove nil?)
             (apply concat))
        result (datomic/transact datomic-conn {:tx-data txs})]
    (probeplan-plans (:db-after result))))

#_(defn update-probeplan2!
    "Updates the probeplan for the current gig. song-ids are ordered. Returns the songs in the setlist."
    [{:keys [datomic-conn db] :as req} song-id-emphases]
    (let [gig-id (common/path-param req :gig/gig-id)
          song-tuples (map probeplan-song-tx song-id-emphases)
          current (q/probeplan-song-tuples-for-gig db  gig-id)
          ;; _ (tap> {:sides song-id-emphases :song-tups song-tuples :current current})
          txs (reconcile-probeplan "probeplan" song-tuples current)
          tx  {:probeplan/gig [:gig/gig-id gig-id]
               :db/id "probeplan"
               :probeplan/version :probeplan.version/classic}
          txs (concat [tx] txs)
          ;; _ (tap> txs)
          result (datomic/transact datomic-conn {:tx-data txs})]
      (q/probeplan-songs-for-gig (:db-after result) gig-id)))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))) ;; rcf
  (generate-probeplan! db)
  (future-probeplans db)

  ;;
  )
