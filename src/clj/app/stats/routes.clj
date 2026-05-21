(ns app.stats.routes
  (:require
   [app.routes.datastar :as ds]
   [app.stats.views]))

(defn routes []
  ["" {:app.route/name :app/stats}
   (ds/page-routes {:page-name  ::index
                    :path       "/stats"
                    :view-ns    'app.stats.views
                    :extra-head app.stats.views/extra-head})])
