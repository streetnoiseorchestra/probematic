(ns app.routes.datastar
  (:require

   [app.datastar :as d*]
   [app.engine :as engine]
   [app.layout :as layout]
   [clojure.string :as str]))

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
  (let [handler-fn   (resolve-from-kw cmd-ns cmd-name)
        path         (route->path cmd-name)
        post-handler (var-get handler-fn)
        route-data   (cond-> {:name cmd-name :post {:handler post-handler}}
                       param-spec (assoc-in [:post :parameters :body] param-spec))]
    (assert handler-fn (str "Command handler function not found for " cmd-name " in ns " cmd-ns))
    [path route-data]))

(defn engine-command-handler [cmd-name]
  (fn [req]
    (let [env (get-in req [:system :engine])]
      (assert env "Engine not found in request system")
      (engine/dispatch-request-sync env req {:command/kind cmd-name}))))

(defn command-engine [[cmd-name param-spec]]
  (let [path       (route->path cmd-name)
        route-data (cond-> {:name cmd-name
                            :post {:handler (engine-command-handler cmd-name)}}
                     param-spec (assoc-in [:post :parameters :body] param-spec))]
    [path route-data]))

(def CommandOpt
  [:map-of :keyword :map])

(def PageOpts
  [:map
   [:path [:and :string [:fn {:error/message "should start with a /"} #(str/starts-with? % "/")]]]
   [:page-name :qualified-keyword]
   [:route-data {:optional true} :map]
   [:view-ns :symbol]
   [:command-ns :symbol]
   [:cmds [:map-of :qualified-keyword CommandOpt]]])

(defn page-routes2
  [{:keys [path page-name route-data view-ns command-ns cmds]}]
  (assert path "path is required")
  (let [render-fn  (resolve-from-kw view-ns :page)
        route-data (merge {:name page-name} route-data)]
    (assert render-fn (str "Page render function not found for " page-name " in ns " view-ns))
    [path route-data
     (into [["" {:get  shim
                  :post (d*/render-handler render-fn)}]]
           (mapv (partial command2 command-ns) cmds))]))

(defn page-routes-engine
  [{:keys [path page-name route-data view-ns cmds]}]
  (assert path "path is required")
  (let [render-fn    (resolve-from-kw view-ns :page)
        route-data   (merge {:name page-name} route-data)
        child-routes (into [["" {:get  shim
                                 :post (d*/render-handler render-fn)}]]
                           (mapv command-engine cmds))]
    (assert render-fn (str "Page render function not found for " page-name " in ns " view-ns))
    (into [path route-data] child-routes)))

(defn page-routes-mixed
  [{:keys [path page-name route-data view-ns command-ns cmds engine-cmds]}]
  (assert path "path is required")
  (let [render-fn     (resolve-from-kw view-ns :page)
        route-data    (merge {:name page-name} route-data)
        direct-routes (if (seq cmds)
                        (mapv (partial command2 command-ns) cmds)
                        [])
        engine-routes (if (seq engine-cmds)
                        (mapv command-engine engine-cmds)
                        [])
        child-routes  (into [["" {:get  shim
                                  :post (d*/render-handler render-fn)}]]
                            (concat direct-routes engine-routes))]
    (assert render-fn (str "Page render function not found for " page-name " in ns " view-ns))
    (into [path route-data] child-routes)))
