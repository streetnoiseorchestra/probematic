(ns app.job-queue
  (:require [app.sqlite :as sqlite]
            [s-exp.drip :as drip]
            [s-exp.drip-ui :as drip-ui]
            [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
            [starfederation.datastar.clojure.api :as d*])
  (:import [java.util.concurrent ExecutorService Executors RejectedExecutionException]))

(defn start! [config]
  (let [db              (sqlite/start config)
        stream-executor (Executors/newVirtualThreadPerTaskExecutor)]
    (try
      (let [client (drip/make-client db)]
        (drip/migrate! client)
        {:db              db
         :client          client
         :stream-executor stream-executor
         :ui-handler      (drip-ui/handler
                           db {:base-path "/admin/jobs"
                               :->sse-response
                               (fn [request opts]
                                 (hk-gen/->sse-response
                                  request
                                  (update opts :d*.sse/on-open
                                          (fn [on-open]
                                            (fn [sse]
                                              (try
                                                (.execute stream-executor #(on-open sse))
                                                (catch RejectedExecutionException e
                                                  (d*/close-sse! sse)
                                                  (throw e))))))))})
         :maintenance     (drip/start-maintenance-worker! {:client client :queues []})})
      (catch Throwable e
        (.close stream-executor)
        (sqlite/stop db)
        (throw e)))))

(defn stop! [{:keys [db maintenance stream-executor]}]
  (.shutdownNow ^ExecutorService stream-executor)
  (.close ^ExecutorService stream-executor)
  (when-not (drip/stop-maintenance-worker! maintenance)
    (throw (ex-info "Job queue maintenance did not stop" {})))
  (sqlite/stop db))
