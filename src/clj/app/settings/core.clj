(ns app.settings.core
  (:require [app.auth :as auth]
            [app.settings.views]
            [app.settings.commands]
            [app.routes.datastar :refer [page-routes2]]
            [app.settings.routes :as settings]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (page-routes2 settings/page)])
