(ns app.stats.routes
  (:require
   [app.routes.datastar :as ds]
   [app.stats.views :as views]))

(defn routes []
  ["" {:app.route/name :app/stats}
   (ds/page-routes {:page-name ::index
                    :path      "/stats"
                    :page      #'views/page})])
