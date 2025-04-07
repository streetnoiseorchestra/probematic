(ns app.settings.commands
  (:require
   [app.datastar :as d*]
   [app.settings.controller :as controller]))

(def open-team-edit (d*/open-form-handler :team :team-id))
(def close-team-edit (d*/close-form-handler :team :team-id))

(defn create-team [req]
  (let [{:keys [error]} (controller/create-team! req)]
    (if error
      (d*/respond-signals req :merge {:team-create {:error error}})
      (d*/respond-signals req :remove ["team-create"]))))

(defn update-team [req]
  (let [{:keys [error]} (controller/update-team! req)]
    (if error
      (d*/respond-signals req :merge {:team {:error error}})
      (d*/close-form req :team :team-id))))

(defn remove-team-member [req]
  (controller/remove-member! req)
  {:status 204})

(defn add-team-member [req]
  (controller/add-member! req)
  (d*/respond-signals req :merge {:team {:member-id ""}})
  {:status 204})

(defn delete-team [req]
  (let [{:keys [error]} (controller/delete-team! req)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      (do
        (d*/close-form req :team :team-id)
        {:status 204}))))

;; --------------------------------------------------------------------------------------------
;; Discount type commands

(def open-discount-type-edit (d*/open-form-handler :discount-type :discount-type-id))
(def close-discount-type-edit (d*/close-form-handler :discount-type :discount-type-id))

(defn create-discount-type [req]
  (let [{:keys [error]} (controller/create-discount-type! req)]
    (if error
      (d*/respond-signals req :merge {:discount-type-create {:error error}})
      (d*/respond-signals req :remove ["discount-type-create"]))))

(defn update-discount-type [req]
  (let [{:keys [error]} (controller/update-discount-type req)]
    (if error
      (d*/respond-signals req :merge {:discount-type {:error error}})
      (d*/close-form req :discount-type :discount-type-id))))

(defn delete-discount-type [req]
  (let [{:keys [error]} (controller/delete-discount-type! req)]
    (if error
      (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
      (do
        (d*/close-form req :discount-type :discount-type-id)
        {:status 204}))))
;; --------------------------------------------------------------------------------------------
;; Section commands

(def open-section-edit (d*/open-form-handler :section :section-id))
(def close-section-edit (d*/close-form-handler :section :section-id))

(defn open-section-reorder [req]
  (d*/state-transact! req #(assoc-in % [:section-reorder :open] true))
  {:status 204})

(defn close-section-reorder [req]
  (d*/state-transact! req #(assoc-in % [:section-reorder :open] false))
  (d*/respond-signals req :merge {:section-reorder {:open false}})
  {:status 204})

(defn update-section-order [req]
  (controller/order-sections! req)
  (d*/respond-signals req :merge {:section-reorder {:open false}})
  {:status 204})

(defn create-section [req]
  (let [{:keys [error]} (controller/create-section! req)]
    (if error
      (d*/respond-signals req :merge {:section-create {:error error}})
      (d*/respond-signals req :remove ["section-create"]))))

(defn update-section [req]
  (let [{:keys [error]} (controller/update-section! req)]
    (if error
      (d*/respond-signals req :merge {:section {:error error}})
      (d*/close-form req :section :section-id))))

#_(defn delete-section [req]
    (let [{:keys [error]} nil]
      (if error
        (throw (ex-info (str "TODO implement delete failure " error) {:status 500}))
        (do
          (d*/state-transact! req #(dissoc % :section-current-edit-id))
          {:status 204}))))
