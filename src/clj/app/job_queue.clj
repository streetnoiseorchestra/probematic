(ns app.job-queue
  (:require [app.sqlite :as sqlite]
            [s-exp.drip :as drip]))

(defn start! [config]
  (let [db (sqlite/start config)]
    (try
      (let [client (drip/make-client db)]
        (drip/migrate! client)
        {:db db
         :client client
         :maintenance (drip/start-maintenance-worker! {:client client :queues []})})
      (catch Throwable e
        (sqlite/stop db)
        (throw e)))))

(defn stop! [{:keys [db maintenance]}]
  (when-not (drip/stop-maintenance-worker! maintenance)
    (throw (ex-info "Job queue maintenance did not stop" {})))
  (sqlite/stop db))
