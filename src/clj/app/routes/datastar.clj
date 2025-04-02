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
