(ns app.probeplan.routes
  (:require
   [app.probeplan.views :as views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/probeplan}
   (ds/page-routes {:page-name ::index
                    :path      "/probeplan"
                    :page      #'views/page})])
