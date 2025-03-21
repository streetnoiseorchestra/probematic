(ns app.settings.core
  (:require [app.auth :as auth]
            [app.routes.datastar :refer [page-routes command]]
            [app.settings.routes :as settings]
            [app.settings.views :as view]
            [malli.experimental.lite :as l]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (page-routes settings/index view/settings-page
                (command settings/command-create-team
                         :handler view/teams-create-handler)

                (command settings/command-update-team
                         :handler view/teams-update-handler
                         :signal-spec {:team {:team-name :string
                                              :team-type (l/maybe :string)
                                              :team-id   :uuid}})

                (command settings/command-delete-team
                         :handler view/teams-delete-handler)

                (command settings/command-delete-team-member
                         :handler view/teams-remove-member-handler
                         :signal-spec {:team {:remove-member-id :uuid
                                              :team-id          :uuid}})

                (command settings/command-add-team-member
                         :handler view/teams-add-member-handler
                         :signal-spec {:team {:team-id   :uuid
                                              :member-id :uuid}})

                (command settings/command-open-team-edit-form
                         :handler view/teams-edit-form-handler
                         :signal-spec {:current-edit-id :uuid})

                (command settings/command-close-team-edit-form
                         :handler view/close-teams-edit-form-handler
                         :signal-spec {:current-edit-id :uuid}))])
