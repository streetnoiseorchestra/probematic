(ns app.settings.commands
  (:require
   [app.datastar :as d*]
   [app.settings.controller :as controller]))

(defn teams-edit-form-handler [req]
  (let [team-id (-> req :parameters :body :current-edit-id)]
    (d*/state-transact! req #(assoc % :current-edit-id team-id))
    {:status 204}))

(defn close-teams-edit-form-handler [req]
  (d*/state-transact! req #(dissoc % :current-edit-id))
  (d*/respond-signals req :merge {:team-update-error false} :remove ["team"])
  {:status 204})

(defn teams-create-handler [req]
  (let [{:keys [error]} (controller/create-team! req)]
    (if error
      (d*/respond-signals req :merge {:team-create-error error})
      (d*/respond-signals req :merge {:team-create-form-open false :team-name ""}))))

(defn teams-update-handler [req]
  (let [{:keys [error]} (controller/update-team! req)]
    (if error
      (d*/respond-signals req :merge {:team-update-error error})
      (do
        (d*/state-transact! req #(dissoc % :current-edit-id))
        (d*/respond-signals req
                            :merge {:team-update-error false}
                            :remove ["team"])))))

(defn teams-remove-member-handler [req]
  (controller/remove-member! req)
  {:status 204})

(defn teams-add-member-handler [req]
  (controller/add-member! req)
  {:status 204})

(defn teams-delete-handler [req]
  (let [{:keys [error]} (controller/delete-team! req)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      (do
        (d*/state-transact! req #(dissoc % :current-edit-id))
        {:status 204}))))

;; --------------------------------------------------------------------------------------------
;; Discount type commands

(defn discount-type-edit-form-handler [req]
  (let [discount-type-id (-> req :parameters :body :discount-current-edit-id)]
    (d*/state-transact! req #(assoc % :discount-current-edit-id discount-type-id))
    {:status 204}))

(defn discount-type-close-edit-form-handler [req]
  (d*/state-transact! req #(dissoc % :discount-current-edit-id))
  (d*/respond-signals req :merge {:discount-update-error false} :remove ["discount-type"])
  {:status 204})

(defn discount-type-create-handler [req]
  (let [{:keys [error]} (controller/create-discount-type! req)]
    (if error
      (d*/respond-signals req :merge {:discount-create-error error})
      (d*/respond-signals req :merge {:discount-create-form-open false :discount-type-name ""}))))

(defn discount-type-update-handler [req]
  (let [{:keys [error]} (controller/update-discount-type req)]
    (if error
      (d*/respond-signals req :merge {:discount-update-error error})
      (do
        (d*/state-transact! req #(dissoc % :discount-current-edit-id))
        (d*/respond-signals req
                            :merge {:discount-update-error false}
                            :remove ["discount-type"])))))

(defn discount-type-delete-handler [req]
  (let [{:keys [error]} (controller/delete-discount-type! req)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      (do
        (d*/state-transact! req #(dissoc % :discount-current-edit-id))
        {:status 204}))))
;; --------------------------------------------------------------------------------------------
;; Section commands

(defn command-open-section-edit-form [req]
  (let [section-id (-> req :parameters :body :section-current-edit-id)]
    (d*/state-transact! req #(assoc % :section-current-edit-id section-id))
    {:status 204}))

(defn command-close-section-edit-form [req]
  (d*/state-transact! req #(dissoc % :section-current-edit-id))
  (d*/respond-signals req :merge {:section-update-error false} :remove ["section"])
  {:status 204})

(defn command-open-section-reorder [req]
  (d*/state-transact! req #(assoc % :section-reorder-open true))
  {:status 204})

(defn command-close-section-reorder [req]
  (d*/state-transact! req #(assoc % :section-reorder-open false))
  (d*/respond-signals req :merge {:section-reorder-open false})
  {:status 204})

(defn command-update-section-order [req]
  (controller/order-sections! req)
  (d*/respond-signals req :merge {:sections-order false})
  {:status 204})

(defn command-add-section [req]
  (let [{:keys [error]} (controller/create-section! req)]
    (if error
      (d*/respond-signals req :merge {:section-create-error error})
      (d*/respond-signals req :merge {:section-create-form-open false :section-name ""}))))

(defn command-update-section [req]
  (let [{:keys [error]} (controller/update-section! req)]
    (if error
      (d*/respond-signals req :merge {:section-update-error error})
      (do
        (d*/state-transact! req #(dissoc % :section-current-edit-id))
        (d*/respond-signals req
                            :merge {:section-update-error false}
                            :remove ["section"])))))

#_(defn command-delete-section [req]
    (let [{:keys [error]} nil]
      (if error
        (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
        (do
          (d*/state-transact! req #(dissoc % :section-current-edit-id))
          {:status 204}))))
