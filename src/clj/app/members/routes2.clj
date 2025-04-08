(ns app.members.routes2
  (:require [app.routes.datastar :refer [page-routes2]]
            [malli.experimental.lite :as l]))

(def index {:page-name  ::members
            :view-ns    'app.members.index.views
            :command-ns 'app.members.index.commands
            :cmds       {::search-member {:member-table {:phrase         :string
                                                         :update-history (l/optional :boolean)}}}})

(defn routes []
  (page-routes2 index))
