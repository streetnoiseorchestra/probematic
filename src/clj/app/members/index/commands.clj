(ns app.members.index.commands
  (:require [app.datastar :as d*]
            [app.members.controller2 :as controller]
            [app.ui.typeahead :as typeahead]))

(defn search-member [req]
  (let [{:keys [phrase script]} (typeahead/response-data req :member-table)]
    (d*/state-transact! req #(assoc-in % [:phrase] phrase))
    (d*/respond-signals req :execute script)
    {:status 204}))

(defn delete-invitation [req]
  (controller/delete-invitation! req (-> req :parameters :body :invite :code))
  {:status 204})

(defn resend-invitation [req]
  (controller/resend-invitation! req (-> req :parameters :body :invite :code))
  {:status 204})
