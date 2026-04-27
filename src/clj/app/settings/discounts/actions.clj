(ns app.settings.discounts.actions
  (:require
   [app.queries :as q]
   [app.nexus.actions :as support]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]))

(def clear-discount-type
  [:app.datastar/assoc-state [:discount-type] false])

(def clear-discount-type-create
  [:app.datastar/assoc-state [:discount-type-create] false])

(defn- discount-type-name-taken? [db discount-type-name]
  (boolean
   (when db
     (d/entity db [:travel.discount.type/discount-type-name discount-type-name]))))

(defn create-discount-type-action
  [{:keys [current-member-id db]} {:keys [discount-type-create]}]
  (let [{:keys [discount-type-name]} discount-type-create]
    (cond
      (str/blank? discount-type-name)
      [support/clear-loading
       [:app.datastar/assoc-state [:discount-type-create :error :discount-type-name]
        {:error "Discount type name is required."}]]

      (discount-type-name-taken? db discount-type-name)
      [support/clear-loading
       [:app.datastar/merge-state
        [:discount-type-create]
        {:discount-type-name discount-type-name
         :error {:discount-type-name
                 {:error (format "Discount type named '%s' already exists." discount-type-name)}}}]]

      :else
      (let [tx-data (support/with-audit [{:travel.discount.type/discount-type-id   :db/gen-uuid
                                          :travel.discount.type/enabled?           true
                                          :travel.discount.type/discount-type-name discount-type-name}]
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         support/clear-loading
         clear-discount-type-create]))))

(defn update-discount-type-action
  [{:keys [db current-member-id]} {:keys [discount-type]}]
  (let [{:keys [discount-type-name discount-type-enabled]} discount-type
        discount-type-id (util/ensure-uuid! (:discount-type-id discount-type))]
    (cond
      (str/blank? discount-type-name)
      [support/clear-loading
       [:app.datastar/assoc-state [:discount-type :error :discount-type-name]
        {:error "Discount type name is required."}]]

      (support/lookup-taken-by-other?
       db
       [:travel.discount.type/discount-type-name discount-type-name]
       [:travel.discount.type/discount-type-id discount-type-id])
      [support/clear-loading
       [:app.datastar/merge-state
        [:discount-type]
        {:discount-type-name discount-type-name
         :error {:discount-type-name
                 {:error (format "Discount type named '%s' already exists." discount-type-name)}}}]]

      :else
      [[:db/transact
        (support/with-audit [{:travel.discount.type/discount-type-id   discount-type-id
                              :travel.discount.type/enabled?           discount-type-enabled
                              :travel.discount.type/discount-type-name discount-type-name}]
          current-member-id)
        {:transact-w-nils? false}]
       support/clear-loading
       clear-discount-type])))

(defn delete-discount-type-action
  [{:keys [current-member-id]} {:keys [targetid]}]
  (let [discount-type-id (util/ensure-uuid! targetid)]
    [[:db/transact
      (support/with-audit [[:db/retractEntity [:travel.discount.type/discount-type-id discount-type-id]]]
        current-member-id)
      {:transact-w-nils? false}]
     support/clear-loading
     clear-discount-type]))

(defn open-discount-type-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [discount-type-id (util/ensure-uuid! targetid)
        discount-type    (q/retrieve-discount-type db discount-type-id)]
    [support/clear-loading
     [:app.datastar/assoc-state
      [:discount-type]
      {:discount-type-id      discount-type-id
       :discount-type-name    (:travel.discount.type/discount-type-name discount-type)
       :discount-type-enabled (:travel.discount.type/enabled? discount-type)}]]))

(defn close-discount-type-edit-action [_state _signals]
  [support/clear-loading clear-discount-type])

(defn open-discount-type-create-action
  [_ _]
  [support/clear-loading
   [:app.datastar/assoc-state [:discount-type-create]
    {:open true
     :discount-type-name ""}]])

(defn close-discount-type-create-action [_state _signals]
  [support/clear-loading clear-discount-type-create])

(def actions
  {::create-discount-type       #'create-discount-type-action
   ::update-discount-type       #'update-discount-type-action
   ::delete-discount-type       #'delete-discount-type-action
   ::open-discount-type-edit    #'open-discount-type-edit-action
   ::close-discount-type-edit   #'close-discount-type-edit-action
   ::open-discount-type-create  #'open-discount-type-create-action
   ::close-discount-type-create #'close-discount-type-create-action})
