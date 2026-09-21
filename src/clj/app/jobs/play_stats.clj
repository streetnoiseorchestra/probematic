(ns app.jobs.play-stats
  "Durable refresh requests for derived play statistics."
  (:require [app.probeplan.stats :as stats]
            [app.write-runner :as writer]
            [datomic.api :as d]
            [s-exp.drip :as drip]))

(def job
  ["refresh-play-stats" {} {:queue "play-stats" :max-attempts 25}])

(defn handle! [system client {:keys [id]}]
  (writer/call!
   (get-in system [:frame-loop :write-runner])
   (fn []
     (let [conn (get-in system [:datomic :conn])]
       ;; Calculate against the writer's current database, not a stale job snapshot.
       @(d/transact conn
                    (conj (vec (stats/calc-stats (d/db conn)))
                          {:db/id        "datomic.tx"
                           :audit/action ::refresh
                           :audit/origin :app.origin/job})))))
  (drip/complete-job client id))
