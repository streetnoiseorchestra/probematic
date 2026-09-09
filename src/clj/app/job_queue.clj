(ns app.job-queue
  (:require [app.sqlite :as sqlite]
            [app.write-runner :as writer]
            [s-exp.drip :as drip]
            [s-exp.drip-ui :as drip-ui]
            [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
            [starfederation.datastar.clojure.api :as d*])
  (:import [java.util.concurrent ExecutorService Executors RejectedExecutionException]))

(defn start! [{:keys [write-runner] :as config}]
  (let [db              (sqlite/start config)
        opts            (when write-runner {:run-write-transaction! #(writer/call! write-runner %)})
        stream-executor (Executors/newVirtualThreadPerTaskExecutor)]
    (try
      (let [client (if opts (drip/make-client db opts) (drip/make-client db))]
        (drip/migrate! client)
        {:db              db
         :client          client
         :write-runner    write-runner
         :stream-executor stream-executor
         :ui-handler      (drip-ui/handler
                           db (merge opts {:base-path "/admin/jobs"
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
                                                              (throw e))))))))}))})
      (catch Throwable e
        (.close stream-executor)
        (sqlite/stop db)
        (throw e)))))

(defn stop! [{:keys [db stream-executor]}]
  (.shutdownNow ^ExecutorService stream-executor)
  (.close ^ExecutorService stream-executor)
  (sqlite/stop db))
