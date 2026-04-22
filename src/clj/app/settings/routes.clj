(ns app.settings.routes
  (:require
   [app.auth :as auth]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (ds/page-routes {:page-name ::band-settings
                    :path      "/band-settings"
                    :view-ns   'app.settings.views})])
