(ns app.routes.datastar
  (:require
   [app.datastar :as d*]
   [app.layout :as layout]))

(defn shim [req]
  (layout/app-shell req nil))

(defn route->path [route]
  (str "/" (name route)))

(defn command [route & {:keys [handler signal-spec]}]
  (assert handler (str "A command handler is required for a command route. command: " route))
  [(route->path route)
   (let [data {:name route :post {:handler handler}}]
     (cond-> data
       signal-spec (assoc-in [:post :parameters :body]  signal-spec)))])

(defn page-routes [route render-fn & commands]
  [(route->path route) {:name route}
   (into [["" {:get  shim
               :post (d*/render-handler render-fn)}]]
         commands)])

(defn resolve-from-kw
  "Resolves a namespace-qualified keyword to a symbol and then resolves that symbol to a var."
  ([kw]
   (when (and (keyword? kw) (namespace kw))
     (let [ns-str        (namespace kw)
           fn-str        (clojure.core/name kw)
           qualified-sym (symbol ns-str fn-str)]
       (resolve qualified-sym))))
  ([ns kw]
   (when (and (keyword? kw) (symbol? ns))
     (let [qualified-sym (symbol (str ns) (clojure.core/name kw))]
       (resolve qualified-sym)))))

(defn command2 [cmd-ns [cmd-name param-spec]]
  (let [handler-fn (resolve-from-kw cmd-ns cmd-name)]
    (assert handler-fn (str "Command handler function not found for " cmd-name))
    [(route->path cmd-name)
     (let [data {:name cmd-name :post {:handler (var-get handler-fn)}}]
       (cond-> data
         param-spec (assoc-in [:post :parameters :body]  param-spec)))]))

(defn page-routes2 [{:keys [page-name route-data view-ns command-ns cmds]}]
  (let [render-fn (resolve-from-kw view-ns page-name)]
    (assert render-fn (str "Page render function not found for " page-name " in ns " view-ns))
    [(route->path page-name) (merge {:name page-name}
                                    route-data)
     (into [["" {:get  shim
                 :post (d*/render-handler render-fn)}]]
           (mapv (partial command2 command-ns) cmds))]))
