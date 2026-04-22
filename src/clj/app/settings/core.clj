(ns app.settings.core
  (:require [app.auth :as auth]
            [app.routes.datastar :refer [page-routes-mixed]]
            [app.settings.commands]
            [app.settings.views]
            [app.settings.routes :as settings]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (page-routes-mixed {:path       (:path settings/page)
                       :page-name  (:page-name settings/page)
                       :view-ns    (:view-ns settings/page)
                       :command-ns (:command-ns settings/page)
                       :cmds       (:direct-cmds settings/page)
                       :engine-cmds (:engine-cmds settings/page)})])
