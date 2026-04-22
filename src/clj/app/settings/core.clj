(ns app.settings.core
  (:require [app.auth :as auth]
            [app.routes.datastar :refer [page-routes-nexus]]
            [app.settings.engine]
            [app.settings.views]
            [app.settings.routes :as settings]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (page-routes-nexus settings/page)])
