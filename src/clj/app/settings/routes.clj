(ns app.settings.routes
  (:require [app.schemas :as s]
            [malli.experimental.lite :as l]))

(def team-cmds
  {::create-team {:team-create {:team-name ::s/non-blank-string}}
   ::update-team {:team {:team-name ::s/non-blank-string
                         :team-type (l/maybe :string)
                         :team-id   :uuid}}
   ::delete-team {:team {:team-id :uuid}}
   ::remove-team-member {:team {:remove-member-id :uuid
                                :team-id          :uuid}}
   ::add-team-member {:team {:team-id   :uuid
                             :member-id :uuid}}
   ::open-team-edit {:team {:team-id :uuid}}
   ::close-team-edit {}})

(def discount-type-cmds
  {::create-discount-type {:discount-type-create {:discount-type-name ::s/non-blank-string}}
   ::update-discount-type {:discount-type {:discount-type-name    ::s/non-blank-string
                                           :discount-type-id      :uuid
                                           :discount-type-enabled :boolean}}
   ::delete-discount-type {:discount-type {:discount-type-id :uuid}}
   ::open-discount-type-edit {:discount-type {:discount-type-id :uuid}}
   ::close-discount-type-edit {}})

(def section-cmds
  {::create-section {:section-create {:section-name ::s/non-blank-string}}
   ::update-section {:section {:section-name     ::s/non-blank-string
                               :section-old-name ::s/non-blank-string
                               :section-enabled  :boolean}}
   ::open-section-edit {:section {:section-id :string}}
   ::close-section-edit {}
   ::open-section-reorder {}
   ::close-section-reorder {}
   ::update-section-order {:section {:order [:map-of :string :int]}}})

(def cmds
  (merge team-cmds discount-type-cmds section-cmds))

(def page {:page-name        ::band-settings
           :path             "/band-settings"
           :view-ns          'app.settings.views
           :nexus-command-ns 'app.settings.engine
           :cmds             cmds})
