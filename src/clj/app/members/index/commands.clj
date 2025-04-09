(ns app.members.index.commands
  (:require [app.datastar :as d*]
            [app.ui.typeahead :as typeahead]
            [app.members.controller2 :as controller]
            [app.urls :as url]))

(defn search-member [req]
  (let [{:keys [phrase script]} (typeahead/response-data req :member-table)]
    (d*/state-transact! req #(assoc-in % [:phrase] phrase))
    (d*/respond-signals req :execute script)
    {:status 204}))

(defn create-member [req]
  (if  (-> req :parameters :body :member :validate-only)
    (if-let [error (controller/validate-create-member! req)]
      (d*/form-errors req :member error {:only :touched})
      (d*/clear-form-errors req :member))
    (let [[member error] (controller/create-member! req)]
      (if error
        (d*/form-errors req :member error)
        (d*/redirect req (url/link-member member))))))
