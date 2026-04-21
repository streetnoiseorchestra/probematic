(ns app.engine.effects
  "Probematic-specific hifi-engine effects that delegate to the existing Datastar helpers."
  (:require
   [app.datastar :as datastar]))

(def merge-signals-fx
  {:effect/kind    :app.datastar/merge-signals
   :effect/handler (fn [{:keys [request]} merge-signals]
                     (datastar/respond-signals request :merge merge-signals))})

(def remove-signals-fx
  {:effect/kind    :app.datastar/remove-signals
   :effect/handler (fn [{:keys [request]} remove-signals]
                     (datastar/respond-signals request :remove remove-signals))})

(def execute-script-fx
  {:effect/kind    :app.datastar/execute-script
   :effect/handler (fn [{:keys [request]} script]
                     (datastar/respond-signals request :execute script))})

(def redirect-fx
  {:effect/kind    :app.datastar/redirect
   :effect/handler (fn [{:keys [request]} url]
                     (datastar/redirect request url))})

(def state-transact-fx
  {:effect/kind    :app.datastar/state-transact
   :effect/handler (fn [{:keys [request]} tx-fn]
                     (datastar/state-transact! request tx-fn))})

(defn effects []
  [merge-signals-fx
   remove-signals-fx
   execute-script-fx
   redirect-fx
   state-transact-fx])
