(ns app.settings.routes
  (:require
   [app.auth :as auth]
   [app.routes.datastar :as ds]
   [app.settings.discounts.views]
   [app.settings.index.views]
   [app.settings.sections.views]
   [app.settings.teams.views]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (ds/page-routes {:page-name ::index
                    :path      "/band-settings"
                    :view-ns   'app.settings.index.views})
   (ds/page-routes {:page-name ::teams
                    :path      "/band-settings/teams"
                    :view-ns   'app.settings.teams.views})
   (ds/page-routes {:page-name ::travel-discounts
                    :path      "/band-settings/travel-discounts"
                    :view-ns   'app.settings.discounts.views})
   (ds/page-routes {:page-name ::sections
                    :path      "/band-settings/sections"
                    :view-ns   'app.settings.sections.views})])
