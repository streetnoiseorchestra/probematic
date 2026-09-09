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

(defn start! [system]
  (drip/start-worker!
   {:client         (get-in system [:job-queue :client])
    :registry       {"refresh-play-stats" (partial handle! system)}
    :queues         ["play-stats"]
    :concurrency    1
    :retry-policies {"refresh-play-stats" (drip/constant-retry-policy 5000)}}))

(defn stop! [worker]
  (when-not (drip/stop-worker! worker :drain true)
    (throw (ex-info "Play statistics worker did not stop" {}))))
