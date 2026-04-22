(ns app.settings.core
  (:require [app.auth :as auth]
            [app.routes.datastar :as ds]
            [app.settings.engine]
            [app.settings.views]
            [app.settings.routes :as settings]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (ds/page-routes-nexus settings/page)])
