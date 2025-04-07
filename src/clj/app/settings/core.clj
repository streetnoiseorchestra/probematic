(ns app.settings.core
  (:require [app.auth :as auth]
            [app.schemas :as s]
            [app.routes.datastar :refer [page-routes command]]
            [app.settings.routes :as settings]
            [app.settings.views :as view]
            [app.settings.commands :as command]
            [malli.experimental.lite :as l]))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   (page-routes settings/index view/settings-page
                ;; Teams
                (command settings/command-create-team
                         :signal-spec {:team-create {:team-name ::s/non-blank-string}}
                         :handler command/teams-create-handler)

                (command settings/command-update-team
                         :handler command/teams-update-handler
                         :signal-spec {:team {:team-name ::s/non-blank-string
                                              :team-type (l/maybe :string)
                                              :team-id   :uuid}})

                (command settings/command-delete-team
                         :handler command/teams-delete-handler
                         :signal-spec {:team {:team-id :uuid}})

                (command settings/command-delete-team-member
                         :handler command/teams-remove-member-handler
                         :signal-spec {:team {:remove-member-id :uuid
                                              :team-id          :uuid}})

                (command settings/command-add-team-member
                         :handler command/teams-add-member-handler
                         :signal-spec {:team {:team-id   :uuid
                                              :member-id :uuid}})

                (command settings/command-open-team-edit-form
                         :handler command/teams-edit-form-handler
                         :signal-spec {:team {:team-id :uuid}})

                (command settings/command-close-team-edit-form
                         :handler command/close-teams-edit-form-handler)

                ;; Travel Discount Types
                (command settings/command-add-discount-type
                         :handler command/discount-type-create-handler
                         :signal-spec {:discount-type-name ::s/non-blank-string})
                (command settings/command-update-discount-type
                         :handler command/discount-type-update-handler
                         :signal-spec {:discount-type {:discount-type-name    ::s/non-blank-string
                                                       :discount-type-id      :uuid
                                                       :discount-type-enabled :boolean}})
                (command settings/command-delete-discount-type
                         :signal-spec {:discount-type-id :uuid}
                         :handler command/discount-type-delete-handler)
                (command settings/command-open-discount-type-edit-form
                         :signal-spec {:discount-current-edit-id :uuid}
                         :handler command/discount-type-edit-form-handler)
                (command settings/command-close-discount-type-edit-form
                         :handler command/discount-type-close-edit-form-handler)

                ;; Sections

                (command settings/command-add-section
                         :handler command/command-add-section
                         :signal-spec {:section-name ::s/non-blank-string})
                (command settings/command-update-section
                         :handler command/command-update-section
                         :signal-spec {:section {:section-name     ::s/non-blank-string
                                                 :section-old-name ::s/non-blank-string
                                                 :section-active   :boolean}})
                #_(command settings/command-delete-section
                           :signal-spec {:section-id :string}
                           :handler command/command-delete-section)

                (command settings/command-open-section-edit-form
                         :signal-spec {:section-current-edit-id :string}
                         :handler command/command-open-section-edit-form)
                (command settings/command-close-section-edit-form
                         :handler command/command-close-section-edit-form)
                (command settings/command-open-section-reorder
                         :handler command/command-open-section-reorder)
                (command settings/command-close-section-reorder
                         :handler command/command-close-section-reorder)
                (command settings/command-update-section-order
                         :handler command/command-update-section-order
                         :signal-spec {:sections-order [:map-of :string :int]}))])
