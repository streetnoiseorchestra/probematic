(ns app.probeplan.queries
  (:require
   [app.probeplan.domain :as domain]
   [app.queries :as q]))

(defn active-songs [db]
  (q/retrieve-active-songs db))

(defn generate-probeplan [db]
  (domain/generate-probeplan (q/load-play-stats db)))

(defn future-probeplans [db]
  (let [probes    (q/next-probes-with-plan db domain/emphasis-comparator)
        generated (generate-probeplan db)]
    (loop [probes    probes
           generated generated
           result    []]
      (if-let [probe (first probes)]
        (if (empty? (:songs probe))
          (recur (rest probes)
                 (drop domain/MAX-SONGS generated)
                 (conj result (assoc probe :songs (take domain/MAX-SONGS generated))))
          (recur (rest probes)
                 generated
                 (conj result probe)))
        result))))

(defn probeplan-plans [db]
  (domain/future-probeplans (active-songs db)
                            (future-probeplans db)))
