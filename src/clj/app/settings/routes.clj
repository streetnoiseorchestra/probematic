(ns app.settings.routes
  (:require
   [app.auth :as auth]
   [app.routes.datastar :as ds]
   [app.settings.discounts.views :as discounts.views]
   [app.settings.index.views :as index.views]
   [app.settings.sections.views :as sections.views]
   [app.settings.teams.views :as teams.views]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (ds/page-routes {:page-name ::index
                    :path      "/band-settings"
                    :page      #'index.views/page})
   (ds/page-routes {:page-name ::teams
                    :path      "/band-settings/teams"
                    :page      #'teams.views/page})
   (ds/page-routes {:page-name ::travel-discounts
                    :path      "/band-settings/travel-discounts"
                    :page      #'discounts.views/page})
   (ds/page-routes {:page-name ::sections
                    :path      "/band-settings/sections"
                    :page      #'sections.views/page})])
