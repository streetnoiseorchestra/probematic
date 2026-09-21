(ns app.game-loop
  "Schedules serialized batches and parallel frame rendering.

  Callers supply processing, frame capture, and worker resource scopes.
  All work and resource cleanup must finish before those functions return.
  Stop the loop before closing its caller-owned render pool and resources."
  (:require
   [clojure.main :as main]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]])
  (:import
   [java.util ArrayList]
   [java.util.concurrent ArrayBlockingQueue Callable ConcurrentHashMap ExecutionException Future
    LinkedBlockingQueue ThreadPoolExecutor TimeUnit]))

(defn render-callback
  "Returns a per-connection callback that sends only changed render hashes.

  `render-fn` takes the frame context and returns serialized HTML.
  `send-fn` must not block; it returns true when the transport accepts the
  HTML. A rejected send is retried on a later tick. Create one callback
  per connection; this does not wait for browser acknowledgement.

  The callback returns true for unchanged HTML or an accepted send, so
  frame feedback can follow either path. A rejected send returns nil."
  [render-fn send-fn]
  (let [last-hash (volatile! nil)]
    (fn [ctx]
      (let [html (render-fn ctx)
            h    (hash html)]
        (or (= @last-hash h)
            (when (send-fn html)
              (vreset! last-hash h)
              true))))))

(defn render-frame!
  "Captures one frame, runs worker-scoped callbacks, and waits for cleanup.

  `capture-frame` takes `ctx`. `with-render-context` takes the captured value
  and a synchronous callback; it must call that callback once and release
  its resources before returning. It runs once per active worker task."
  [{:keys [::conns ::render-pool] :as ctx} capture-frame with-render-context]
  (let [frame-ctx (capture-frame ctx)
        renders   (vec (.values ^ConcurrentHashMap conns))
        n         (.getCorePoolSize ^ThreadPoolExecutor render-pool)
        tasks     (mapv (fn [i]
                          (reify Callable
                            (call [_]
                              (with-render-context
                                frame-ctx
                                (fn [render-ctx]
                                  (let [size (count renders)
                                        step (long n)]
                                    (loop [idx (long i)]
                                      (when (< idx size)
                                        (try
                                          ((nth renders idx) render-ctx)
                                          (catch Exception e
                                            (main/repl-caught e)))
                                        (recur (unchecked-add idx step))))))))))
                        (range (min n (count renders))))]
    (doseq [^Future result (.invokeAll ^ThreadPoolExecutor render-pool tasks)]
      (try
        (.get result)
        (catch ExecutionException e
          (main/repl-caught (.getCause e)))))))

(>defn start-batch-loop!
  "Starts batch processing followed by parallel rendering on platform threads.

  `ctx` supplies `::conns` (a ConcurrentHashMap of connection IDs to callbacks)
  and `::render-pool`. Other context data is opaque to the loop.

  | Option                 | Meaning                                                                                  |
  |------------------------|------------------------------------------------------------------------------------------|
  | `:process-batch!`      | Required `(ctx batch)` function; finishes all batch writes before returning.             |
  | `:capture-frame`       | Required `(ctx)` function; captures shared frame state after batch processing.           |
  | `:with-render-context` | Required `(frame-ctx render!)` function; scopes worker resources. See [[render-frame!]]. |
  | `:batch-tick-ms`       | Target minimum tick duration; default 50 ms.                                             |
  | `:queue-capacity`      | Maximum waiting actions; default 1024.                                                   |
  | `:batch-size`          | Maximum actions per tick; default 128.                                                   |

  The processor receives an ordered ArrayList each tick, including empty
  batches. Treat it as read-only. It owns transaction boundaries and error
  feedback: per-action processors must isolate action failures themselves.
  An escaping batch failure is reported, not retried or rolled back here.

  Returns `ctx` with `::submit!`, `::stop!`, and `::thread`. Submission returns
  false when full or stopped; an HTTP adapter must then return 503. Stop
  rejects new work, drains accepted batches, and joins the loop. Call stop
  from outside the loop and its render callbacks."
  [ctx {:keys [process-batch! capture-frame with-render-context
               batch-tick-ms queue-capacity batch-size]
        :or   {batch-tick-ms 50 queue-capacity 1024 batch-size 128}}]
  [[:map
    [::conns [:fn #(instance? ConcurrentHashMap %)]]
    [::render-pool [:fn #(instance? ThreadPoolExecutor %)]]]
   [:map
    [:process-batch! [:fn ifn?]]
    [:capture-frame [:fn ifn?]]
    [:with-render-context [:fn ifn?]]
    [:batch-tick-ms {:optional true} pos-int?]
    [:queue-capacity {:optional true} pos-int?]
    [:batch-size {:optional true} pos-int?]]
   =>
   [:map
    [::submit! [:fn ifn?]]
    [::stop! [:fn ifn?]]
    [::thread [:fn #(instance? Thread %)]]]]
  (let [q          (ArrayBlockingQueue. (int queue-capacity))
        accepting? (atom true)
        submit!    (fn [action]
                     (locking accepting?
                       (and @accepting? (.offer q action))))
        ctx        (assoc ctx ::submit! submit!)
        t          (.start (.name (Thread/ofPlatform) "probematic-game-loop")
                           (bound-fn []
                             (try
                               (while (or @accepting? (not (.isEmpty q)))
                                 (let [next-tick (+ (System/nanoTime) (* 1000000 batch-tick-ms))
                                       batch     (ArrayList.)]
                                   (.drainTo q batch batch-size)
                                   (try
                                     (process-batch! ctx batch)
                                     (catch Exception e
                                       (main/repl-caught e)))
                                   (try
                                     (render-frame! ctx capture-frame with-render-context)
                                     (catch Exception e
                                       (main/repl-caught e)))
                                   (when @accepting?
                                     (Thread/sleep
                                      (long (max 0 (quot (- next-tick (System/nanoTime)) 1000000)))))))
                               (finally
                                 (locking accepting?
                                   (reset! accepting? false))))))]
    (assoc ctx
           ::thread t
           ::stop! (fn []
                     (locking accepting?
                       (reset! accepting? false))
                     (.join t)))))

(defn start-render-pool
  "Creates a fixed platform-thread pool. The caller must close it after stopping the loop.

  | Option       | Meaning                   |
  |--------------|---------------------------|
  | `:pool-size` | Number of render workers. |"
  [{:keys [pool-size]}]
  (ThreadPoolExecutor. (int pool-size) (int pool-size)
                       0 TimeUnit/MILLISECONDS
                       (LinkedBlockingQueue.)
                       (.factory (.name (Thread/ofPlatform) "probematic-render-" 0))))
