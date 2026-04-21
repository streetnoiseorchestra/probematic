(ns app.engine
  "Probematic-specific wrapper around hifi-engine.

  This namespace is intentionally small.
  It owns engine bootstrapping and generic request-to-command dispatch.
  Feature-specific commands should live in their own namespaces."
  (:require
   [app.engine.coeffects :as coeffects]
   [app.engine.effects :as effects]
   [hifi.engine.shell :as shell]))

(defn registrations []
  [effects/effects
   coeffects/coeffects])

(defn build-env
  ([]
   (build-env nil))
  ([ctx]
   (cond-> (shell/register (registrations))
     (some? ctx) (merge ctx))))

(defn request-context [req]
  {:request req})

(defn dispatch-request-sync
  ([env req command]
   (dispatch-request-sync env req command nil))
  ([env req command opts]
   (when-not (map? command)
     (throw (ex-info "Invalid command" {:command command})))
   (shell/dispatch-sync (merge env (request-context req))
                        command
                        (or opts shell/default-opts))))
