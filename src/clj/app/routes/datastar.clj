(ns app.routes.datastar
  (:require
   [app.datastar :as d*]
   [app.layout :as layout]
   [app.nexus :as nexus]))

(defn shim [req]
  (layout/app-shell req nil))

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

(defn- action-query-params [req]
  (or (get-in req [:parameters :query])
      (:query-params req)
      (:params req)))

(defn- action-body [req]
  (or (:body-params req)
      (get-in req [:parameters :body])
      {}))

(defn act-handler [req]
  (let [action-key   (d*/action-key (action-query-params req))
        nexus-config (get-in req [:system :nexus])
        action       (get-in nexus-config [:nexus/actions action-key])]
    (cond
      (nil? action-key)
      {:status 400
       :headers {}
       :body "Missing or invalid act query params: ns and kw"}

      (nil? action)
      {:status 404
       :headers {}
       :body (str "No such action registered for " action-key)}

      :else
      [[action-key (action-body req)]])))

(defn act-route [system]
  ["/act" {:name       ::act
           :interceptors [(nexus/nexus-interceptor (:nexus system) system)]
           :post       {:handler act-handler}}])

(defn page-routes
  [{:keys [path page-name route-data view-ns]}]
  (assert path "path is required")
  (let [render-fn    (resolve-from-kw view-ns :page)
        route-data   (merge {:name page-name} route-data)
        child-routes (into [["" {:get  shim
                                 :post (d*/render-handler render-fn)}]])]
    (assert render-fn (str "Page render function not found for " page-name " in ns " view-ns))
    (into [path route-data] child-routes)))
