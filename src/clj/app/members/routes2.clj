(ns app.members.routes2
  (:require [app.routes.datastar :refer [page-routes2]]
            [malli.experimental.lite :as l]))

(def index {:page-name  ::members
            :view-ns    'app.members.index.view
            :command-ns 'app.members.index.commands
            :cmds       {::search-member {:member-table {:phrase         :string
                                                         :update-history (l/optional :boolean)}}}})

(def create {:page-name  ::new-member
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

(defn routes []
  [(page-routes2 index)
   (page-routes2 create)])
