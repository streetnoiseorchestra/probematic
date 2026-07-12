(ns app.everything.routes
  (:require
   [app.everything.views :as views]
   [app.routes.datastar :as ds]))

(defn routes []
  (ds/page-routes {:page-name ::index
                   :path      "/everything"
                   :page      #'views/page}))
