(ns app.routes.datastar
  (:require
   [app.datastar :as d*]
   #_[app.layout :as layout]
   [app.layout2 :as layout2]
   [app.nexus :as nexus]))

(defn shim [req]
  #_(layout/app-shell req nil)
  (layout2/app-shell req nil (select-keys (-> req :reitit.core/match :data) [:extra-head])))

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
  (let [body         (or (:body-params req)
                         (get-in req [:parameters :body])
                         {})
        query-params (not-empty (apply dissoc
                                       (action-query-params req)
                                       [:ns :kw "ns" "kw"]))]
    (cond-> body
      query-params (assoc :query-params query-params))))

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
  [{:keys [extra-head path page-name route-data view-ns]}]
  (assert path "path is required")
  (let [render-fn    (resolve-from-kw view-ns :page)
        route-data   (cond-> (merge {:name page-name} route-data)
                       extra-head (assoc :extra-head extra-head))
        child-routes (into [["" {:get  shim
                                 :post (d*/render-handler render-fn)}]])]
    (assert render-fn (str "Page render function not found for " page-name " in ns " view-ns))
    (into [path route-data] child-routes)))
