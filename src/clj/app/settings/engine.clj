(ns app.settings.engine
  (:require
   [app.settings.routes :as routes]
   [clojure.string :as str]
   [datomic.api :as d]))

(defn create-discount-type [req]
  [[::routes/create-discount-type
    (get-in req [:parameters :body :discount-type-create])
    [:app/new-squuid]
    [:app/current-member-id]]])

(defn- create-error [error]
  [[:app.datastar/merge-signals {:discount-type-create {:error error}}]])

(defn- discount-type-name-taken? [db discount-type-name]
  (boolean
   (when db
     (d/entity db [:travel.discount.type/discount-type-name discount-type-name]))))

(defn create-discount-type-action
  [{:keys [db]} {:keys [discount-type-name]} discount-type-id member-id]
  (cond
    (str/blank? discount-type-name)
    (create-error {:discount-type-name "Discount type name is required."})

    (discount-type-name-taken? db discount-type-name)
    (create-error {:discount-type-name (format "Discount type named '%s' already exists." discount-type-name)})

    :else
    (let [tx-data (cond-> [{:travel.discount.type/discount-type-id   discount-type-id
                            :travel.discount.type/enabled?           true
                            :travel.discount.type/discount-type-name discount-type-name}]
                    member-id (conj [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]))]
      [[:db/transact tx-data {:transact-w-nils? false}]
       [:app.datastar/remove-signals ["discount-type-create"]]])))

(def actions
  {::routes/create-discount-type #'create-discount-type-action})
