(ns app.probeplan.routes
  (:require
   [app.probeplan.views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/probeplan}
   (ds/page-routes {:page-name ::index
                    :path      "/probeplan"
                    :view-ns   'app.probeplan.views})])
