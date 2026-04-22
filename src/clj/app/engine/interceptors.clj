(ns app.engine.interceptors
  "Probematic-specific hifi-engine interceptors."
  (:require
   [hifi.engine.context :as context]
   [hifi.engine.interceptors :as interceptors]
   [promesa.core :as p]))

(def do-fx-with-results-interceptor
  {:interceptor/name :app.engine/do-fx-interceptor
   :doc              "Executes effects and retains their results for Ring response extraction."
   :leave            (fn [ctx]
                       (p/let [results (-> (interceptors/do-effects* ctx (context/get-effects ctx))
                                           (p/catch #(throw %)))]
                         (assoc ctx :results
                                {:outcome/results (first results)})))})

(defn interceptors []
  [do-fx-with-results-interceptor])
