(ns app.engine.effects
  "Probematic-specific hifi-engine effects that delegate to the existing Datastar helpers."
  (:require
   [app.datastar :as datastar]))

(defn- remember-response! [{:keys [app.engine/response_] :as _ctx} response]
  (when (some? response)
    (reset! response_ response))
  response)

(def merge-signals-fx
  {:effect/kind    :app.datastar/merge-signals
   :effect/handler (fn [{:keys [request] :as ctx} merge-signals]
                     (->> (datastar/respond-signals request :merge merge-signals)
                          (remember-response! ctx)))})

(def remove-signals-fx
  {:effect/kind    :app.datastar/remove-signals
   :effect/handler (fn [{:keys [request] :as ctx} remove-signals]
                     (->> (datastar/respond-signals request :remove remove-signals)
                          (remember-response! ctx)))})

(def execute-script-fx
  {:effect/kind    :app.datastar/execute-script
   :effect/handler (fn [{:keys [request] :as ctx} script]
                     (->> (datastar/respond-signals request :execute script)
                          (remember-response! ctx)))})

(def redirect-fx
  {:effect/kind    :app.datastar/redirect
   :effect/handler (fn [{:keys [request] :as ctx} url]
                     (->> (datastar/redirect request url)
                          (remember-response! ctx)))})

(def state-transact-fx
  {:effect/kind    :app.datastar/state-transact
   :effect/handler (fn [{:keys [request]} tx-fn]
                     (datastar/state-transact! request tx-fn))})

(def open-form-fx
  {:effect/kind    :app.datastar/open-form
   :effect/handler (fn [{:keys [request] :as ctx} {:keys [form-name form-id-key]}]
                     (->> (datastar/open-form request form-name form-id-key)
                          (remember-response! ctx)))})

(def close-form-fx
  {:effect/kind    :app.datastar/close-form
   :effect/handler (fn [{:keys [request] :as ctx} {:keys [form-name form-id-key]}]
                     (->> (datastar/close-form request form-name form-id-key)
                          (remember-response! ctx)))})

(defn effects []
  [merge-signals-fx
   remove-signals-fx
   execute-script-fx
   redirect-fx
   state-transact-fx
   open-form-fx
   close-form-fx])
