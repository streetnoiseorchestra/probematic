(ns app.members.routes2
  (:require [app.urls :as url]
            [malli.experimental.lite :as l]))

(def index {:page-name  ::members
            :path       "/members"
            :view-ns    'app.members.index.views
            :command-ns 'app.members.index.actions
            :cmds       {::search-member {:member-table {:phrase         :string
                                                         :update-history (l/optional :boolean)}}
                         ::delete-invitation {:invite {:code :string}}
                         ::resend-invitation {:invite {:code :string}}}})

(def create {:page-name  ::new-member
             :path       "/member-create"
             :view-ns    'app.members.create.view
             :command-ns 'app.members.create.commands
             :cmds       {::create-member {:member {:validate-only :boolean
                                                    :name          :string
                                                    :nick          :string
                                                    :email         :string
                                                    :username      :string
                                                    :phone         :string
                                                    :section-name  :string
                                                    :active        :boolean
                                                    :create-sno-id :boolean
                                                    :touched       {:name          :int
                                                                    :nick          :int
                                                                    :email         :int
                                                                    :username      :int
                                                                    :phone         :int
                                                                    :section-name  :int
                                                                    :active        :int
                                                                    :create-sno-id :int}}}}})

(def detail {:page-name  ::member-detail
             :path       "/member/{member-id}"
             :view-ns    'app.members.detail.view
             :command-ns 'app.members.detail.commands
             :cmds       {}})

(defn link-member [req member]
  (url/url-for req ::member-detail {:member-id (:member/member-id member)}))

(comment
  ;; Intentionally disabled. These Datastar members routes are being retired,
  ;; but the namespace still anchors route/action keywords used by old views.
  (defn routes []
    []))
