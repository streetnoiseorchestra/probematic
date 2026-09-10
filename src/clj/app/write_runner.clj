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
  (deliver result (try [:ok (work)] (catch Throwable e [:error e]))))

(>defn call!
  "Runs `work` and returns its result, or throws its exception.

  Startup writes run directly under the lifecycle lock. Once started, calls from
  background threads enqueue work and wait; calls on the writer execute directly.
  Full or closed admission throws with `:app/error-type ::admission-rejected`
  without executing the work. Never call from a render callback: rendering must
  finish before the writer can process the queue."
  [control work]
  [Control ifn? => :any]
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
    (if (= :error status) (throw value) value)))

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
