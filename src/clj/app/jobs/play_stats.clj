(ns app.jobs.play-stats
  "Durable refresh requests for derived play statistics."
  (:require [app.probeplan.stats :as stats]
            [app.datomic :as datomic]
            [app.write-runner :as writer]
            [datomic.api :as d]
            [s-exp.drip :as drip]))

(def job
  ["refresh-play-stats" {} {:queue "start-within-15m" :max-attempts 25}])

(defn handle! [system client {:keys [id args]}]
  (writer/call!
   (get-in system [:frame-loop :write-runner])
   (fn []
     (let [conn       (get-in system [:datomic :conn])
           audit-user (datomic/source-audit-user conn (:source-t args))]
       ;; Calculate against the writer's current database, not a stale job snapshot.
       (datomic/transact conn
                         {:tx-data (vec (stats/calc-stats (d/db conn)))
                          :audit   (cond-> {:audit/action ::refresh
                                            :audit/origin :app.origin/job}
                                     audit-user (assoc :audit/user audit-user))}))))
  (drip/complete-job client id))
