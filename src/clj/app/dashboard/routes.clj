(ns app.dashboard.routes
  (:require
   [app.auth :as auth]
   [app.dashboard.calendar.views]
   [app.dashboard.index.views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/dashboard
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (ds/page-routes {:page-name ::index
                    :path      "/"
                    :view-ns   'app.dashboard.index.views})
   (ds/page-routes {:page-name ::calendar
                    :path      "/calendar"
                    :view-ns   'app.dashboard.calendar.views})
   (ds/page-routes {:page-name ::calendar-trailing-slash
                    :path      "/calendar/"
                    :view-ns   'app.dashboard.calendar.views})])
