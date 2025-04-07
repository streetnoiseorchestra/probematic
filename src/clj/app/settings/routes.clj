(ns app.settings.routes
  (:require
   [app.schemas :as s]
   [malli.experimental.lite :as l]))

#_(comment
    (def index ::band-settings)
    (def command-delete-team-member ::command-delete-team-member)
    (def command-add-team-member ::command-add-team-member)
    (def command-create-team ::command-create-team)
    (def command-update-team ::command-update-team)
    (def command-delete-team ::command-delete-team)
    (def command-open-team-edit-form ::command-open-team-edit-form)
    (def command-close-team-edit-form ::command-close-team-edit-form)

    (def command-add-discount-type ::command-add-discount-type)
    (def command-update-discount-type ::command-update-discount-type)
    (def command-delete-discount-type ::command-delete-discount-type)
    (def command-open-discount-type-edit-form ::command-open-discount-type-edit-form)
    (def command-close-discount-type-edit-form ::command-close-discount-type-edit-form)

    (def command-add-section ::command-add-section)
    (def command-update-section ::command-update-section)
    (def command-delete-section ::command-delete-section)
    (def command-open-section-edit-form ::command-open-section-edit-form)
    (def command-close-section-edit-form ::command-close-section-edit-form)
    (def command-open-section-reorder ::command-open-section-reorder)
    (def command-close-section-reorder ::command-close-section-reorder)
    (def command-update-section-order ::command-update-section-order))

(def page {:page-name  ::band-settings
           :view-ns    'app.settings.views
           :command-ns 'app.settings.commands
           :cmds       {;; Teams
                        ::create-team              {:team-create {:team-name ::s/non-blank-string}}
                        ::update-team              {:team {:team-name ::s/non-blank-string
                                                           :team-type (l/maybe :string)
                                                           :team-id   :uuid}}
                        ::delete-team              {:team {:team-id :uuid}}
                        ::remove-team-member       {:team {:remove-member-id :uuid
                                                           :team-id          :uuid}}
                        ::add-team-member          {:team {:team-id   :uuid
                                                           :member-id :uuid}}
                        ::open-team-edit           {:team {:team-id :uuid}}
                        ::close-team-edit          {}
                        ;; Discount Types
                        ::create-discount-type     {:discount-type-create {:discount-type-name ::s/non-blank-string}}
                        ::update-discount-type     {:discount-type {:discount-type-name    ::s/non-blank-string
                                                                    :discount-type-id      :uuid
                                                                    :discount-type-enabled :boolean}}
                        ::delete-discount-type     {:discount-type {:discount-type-id :uuid}}
                        ::open-discount-type-edit  {:discount-type {:discount-type-id :uuid}}
                        ::close-discount-type-edit {}
                        ;; Sections
                        ::create-section           {:section-create {:section-name ::s/non-blank-string}}
                        ::update-section           {:section {:section-name     ::s/non-blank-string
                                                              :section-old-name ::s/non-blank-string
                                                              :section-enabled  :boolean}}
                        ::open-section-edit        {:section {:section-id :string}}
                        ::close-section-edit       {}
                        ::open-section-reorder     {}
                        ::close-section-reorder    {}
                        ::update-section-order     {:section {:order [:map-of :string :int]}}}})
