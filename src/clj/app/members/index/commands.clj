(ns app.members.index.commands
  (:require [app.datastar :as d*]
            [app.ui.typeahead :as typeahead]))

(defn search-member [req]
  (let [{:keys [phrase script]} (typeahead/response-data req :member-table)]
    (d*/state-transact! req #(assoc-in % [:phrase] phrase))
    (d*/respond-signals req :execute script)
    {:status 204}))
