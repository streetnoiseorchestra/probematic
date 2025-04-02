(ns app.settings.commands
  (:require
   [app.datastar :as d*]
   [app.settings.controller :as controller]))

(defn teams-edit-form-handler [{:keys [db tr] :as req}]
  (let [team-id (-> req :parameters :body :current-edit-id)]
    (d*/state-transact! req #(assoc % :current-edit-id team-id))
    {:status 204}))

(defn close-teams-edit-form-handler [{:keys [db tr] :as req}]
  (d*/state-transact! req #(dissoc % :current-edit-id))
  (d*/respond-signals req :merge {:team-update-error false} :remove ["team"])
  {:status 204})

(defn teams-create-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/create-team! req)]
    (if error
      (d*/respond-signals req :merge {:team-create-error error})
      (d*/respond-signals req :merge {:team-create-form-open false :team-name ""}))))

(defn teams-update-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/update-team! req)]
    (if error
      (d*/respond-signals req :merge {:team-update-error error})
      (do
        (d*/state-transact! req #(dissoc % :current-edit-id))
        (d*/respond-signals req
                            :merge {:team-update-error false}
                            :remove ["team"])))))

(defn teams-remove-member-handler [{:keys [db tr] :as req}]
  (controller/remove-member! req)
  {:status 204})

(defn teams-add-member-handler [{:keys [db tr] :as req}]
  (controller/add-member! req)
  {:status 204})

(defn teams-delete-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/delete-team! req)
        tab-id          (-> req :body-params :tab-id)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      (do
        (swap! d*/!page-state update tab-id assoc :current-edit-id nil)
        {:status 204}))))

;; --------------------------------------------------------------------------------------------
;; Discount type commands

(defn discount-type-edit-form-handler [{:keys [db tr] :as req}]
  (let [discount-type-id (-> req :parameters :body :discount-current-edit-id)]
    (tap> [:discount-type-edit-form discount-type-id])
    (d*/state-transact! req #(assoc % :discount-current-edit-id discount-type-id))
    {:status 204}))

(defn discount-type-close-edit-form-handler [{:keys [db tr] :as req}]
  (tap> [:discount-type-close-edit-form-handler])
  (d*/state-transact! req #(dissoc % :discount-current-edit-id))
  (d*/respond-signals req :merge {:discount-update-error false} :remove ["discount-type"])
  {:status 204})

(defn discount-type-create-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/create-discount-type! req)]
    (if error
      (d*/respond-signals req :merge {:discount-create-error error})
      (d*/respond-signals req :merge {:discount-create-form-open false :discount-type-name ""}))))

(defn discount-type-update-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/update-discount-type req)]
    (if error
      (d*/respond-signals req :merge {:discount-update-error error})
      (do
        (d*/state-transact! req #(dissoc % :discount-current-edit-id))
        (d*/respond-signals req
                            :merge {:discount-update-error false}
                            :remove ["discount-type"])))))

(defn discount-type-remove-member-handler [{:keys [db tr] :as req}]
  (controller/remove-member! req)
  {:status 204})

(defn discount-type-add-member-handler [{:keys [db tr] :as req}]
  (controller/add-member! req)
  {:status 204})

(defn discount-type-delete-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} nil
        tab-id          (-> req :body-params :tab-id)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      (do
        (swap! d*/!page-state update tab-id assoc :discount-current-edit-id nil)
        {:status 204}))))
