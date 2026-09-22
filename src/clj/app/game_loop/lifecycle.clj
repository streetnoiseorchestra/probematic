(ns app.game-loop.lifecycle
  "Owns the application frame-loop lifecycle and its resources."
  (:require
   [app.datastar :as datastar]
   [app.game-loop :as game]
   [app.game-loop.storage :as storage]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.nexus :as nexus]
   [app.write-runner :as writer])
  (:import
   java.util.concurrent.ConcurrentHashMap
   java.util.concurrent.ExecutorService))

(defn start!
  "Starts the writer-owned frame loop and returns its runtime.

  The supplied system must contain `:write-runner`, `:job-queue`, `:datomic`,
  `:nexus`, and `:cutover-t`. A startup failure closes the render pool."
  [{:keys [write-runner job-queue cutover-t] :as system}]
  (assert write-runner "Frame loop requires a write runner")
  (assert job-queue "Frame loop requires a job queue")
  (let [hooks   (storage/render-hooks (get-in system [:datomic :conn])
                                      {:jobs (:db job-queue)})
        clients (atom {})
        pool    (game/start-render-pool {:pool-size 2})]
    (try
      (log-dispatch/initialize! (get-in system [:datomic :conn])
                                (:client job-queue)
                                cutover-t)
      (writer/start!
       write-runner
       #(game/start-batch-loop!
         {::game/conns       (ConcurrentHashMap.)
          ::game/render-pool pool
          :write-runner      write-runner
          :clients           clients
          :stopped?          (atom false)}
         (assoc
          (merge hooks
                 (select-keys system
                              [:queue-capacity :batch-size :batch-tick-ms]))
          :capture-frame
          (fn [ctx]
            (locking clients
              (assoc ((:capture-frame hooks) ctx)
                     :clients @clients
                     :page-state @datastar/!page-state)))
          :process-batch!
          (fn [runtime batch]
            (doseq [action batch]
              (if (::writer/work action)
                (writer/execute! action)
                (nexus/process-queued! (:nexus system)
                                       system
                                       runtime
                                       action)))
            (log-dispatch/dispatch-pending! (get-in system [:datomic :conn])
                                            (:client job-queue)
                                            128)))))
      (catch Exception exception
        (.close ^ExecutorService pool)
        (throw exception)))))

(defn stop!
  "Stops `runtime`, closes its clients, and releases its render pool."
  [runtime]
  (when runtime
    (let [clients (:clients runtime)]
      (locking clients
        (reset! (:stopped? runtime) true)))
    (writer/close! (:write-runner runtime))
    ((::game/stop! runtime))
    (try
      (doseq [client (vals @(:clients runtime))]
        ((:close! client)))
      (finally
        (.close ^ExecutorService (::game/render-pool runtime))))))
