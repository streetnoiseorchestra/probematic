(ns app.write-runner
  "Routes background database work through the application's writer thread."
  (:require [app.game-loop :as game]
            [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]))

(def Control [:fn #(instance? clojure.lang.Atom %)])

(>defn create
  "Returns a runner control in startup mode. Startup writes execute directly."
  []
  [=> Control]
  (atom {:phase :starting}))

(>defn execute!
  "Executes one accepted background operation on the writer and releases its caller."
  [{::keys [work result]}]
  [[:map [::work ifn?] [::result :any]] => :any]
  (deliver result (try [:ok (work)] (catch Exception e [:error e]))))

(>defn call!
  "Runs `work` through the writer in `ctx` and returns its result.

  `ctx` must contain a top-level `:write-runner` control from [[create]].
  Missing or invalid controls throw before work runs. Work exceptions propagate.
  Startup writes run under the lifecycle lock. Once started, background calls
  enqueue work and wait; calls on the writer execute directly.
  Full or closed admission throws with `:app/error-type ::admission-rejected`.
  Never call from a render callback: rendering must finish before queued writes."
  [ctx work]
  [:map ifn? => :any]
  (let [control (:write-runner ctx)]
    (when-not (instance? clojure.lang.Atom control)
      (throw (ex-info "Writer context requires :write-runner" {:app/error-type ::invalid-context})))
    (let [[status value]
          (locking control
            (let [{:keys [phase thread submit!]} @control]
              (cond
                (identical? thread (Thread/currentThread)) [:ok (work)]
                (= :starting phase) [:ok (work)]
                (= :running phase)
                (let [result (promise)]
                  (when-not (submit! {::work work ::result result})
                    (throw (ex-info "Writer admission is full or closed" {:app/error-type ::admission-rejected})))
                  [:pending result])
                :else (throw (ex-info "Writer is stopped" {:app/error-type ::admission-rejected})))))
          [status value] (if (= :pending status) @value [status value])]
      (if (= :error status) (throw value) value))))

(>defn start!
  "Starts the loop with `start-loop!` after all direct startup writes finish.

  Returns the loop runtime and switches future background calls to its queue.
  `start-loop!` must return the runtime from app.game-loop/start-batch-loop!."
  [control start-loop!]
  [Control ifn? => :map]
  (locking control
    (when-not (= :starting (:phase @control))
      (throw (ex-info "Writer control has already been started or stopped" {})))
    (let [runtime (start-loop!)]
      (reset! control {:phase   :running
                       :thread  (::game/thread runtime)
                       :submit! (::game/submit! runtime)})
      runtime)))

(>defn close!
  "Rejects new background calls. Stop workers before closing, then drain the loop."
  [control]
  [Control => :map]
  (locking control
    (swap! control assoc :phase :closed)))
