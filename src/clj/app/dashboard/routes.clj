(ns app.dashboard.routes
  (:require
   [app.auth :as auth]
   [app.dashboard.calendar.views :as calendar.views]
   [app.dashboard.index.views :as index.views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/dashboard
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (ds/page-routes {:page-name ::index
                    :path      "/"
                    :page      #'index.views/page})
   (ds/page-routes {:page-name ::calendar
                    :path      "/calendar"
                    :page      #'calendar.views/page})])
