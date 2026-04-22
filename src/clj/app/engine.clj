(ns app.engine
  "Probematic-specific wrapper around hifi-engine.

  This namespace is intentionally small.
  It owns engine bootstrapping and generic request-to-command dispatch.
  Feature-specific commands should live in their own namespaces."
  (:require
   [app.engine.coeffects :as coeffects]
   [app.engine.effects :as effects]
   [app.engine.interceptors :as interceptors]
   [app.settings.engine :as settings.engine]
   [hifi.engine.shell :as shell]))

(def response-key :app.engine/response_)

(def default-opts
  {:interceptors [:unhandled-error-interceptor
                  :app.engine/do-fx-interceptor]})

(defn registrations []
  [effects/effects
   coeffects/coeffects
   interceptors/interceptors
   settings.engine/commands])

(defn build-env
  ([]
   (build-env nil))
  ([ctx]
   (cond-> (shell/register (registrations))
     (some? ctx) (merge ctx))))

(defn request-context [req response_]
  {:request req
   response-key response_})

(defn- ring-response [result]
  (some->> (get-in result [:results :outcome/results])
           (keep :result/data)
           (filter #(and (map? %) (contains? % :status)))
           last))

(defn dispatch-request-sync
  ([env req command]
   (dispatch-request-sync env req command nil))
  ([env req command opts]
   (when-not (map? command)
     (throw (ex-info "Invalid command" {:command command})))
   (let [response_ (atom nil)
         result    (shell/dispatch-sync (merge env (request-context req response_))
                                        command
                                        (or opts default-opts))]
     (or @response_
         (ring-response result)
         (get-in result [:outcome :outcome/response])
         result))))
