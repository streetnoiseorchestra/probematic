(ns app.settings.sections.actions
  (:require
   [app.queries :as q]
   [app.settings.action-support :as support]
   [clojure.string :as str]
   [datomic.api :as d]))

(def clear-section
  [:app.datastar/assoc-state [:section] false])

(def clear-section-create
  [:app.datastar/assoc-state [:section-create] false])

(defn- section-name-taken? [db section-name]
  (boolean
   (when db
     (d/entity db [:section/name section-name]))))

(defn create-section-action
  [{:keys [db current-member-id]} {:keys [section-create]}]
  (let [{:keys [section-name]} section-create]
    (cond
      (str/blank? section-name)
      [support/clear-loading
       [:app.datastar/assoc-state [:section-create :error :section-name]
        {:error "Section name is required."}]]

      (section-name-taken? db section-name)
      [support/clear-loading
       [:app.datastar/merge-state
        [:section-create]
        {:section-name section-name
         :error {:section-name
                 {:error (format "Section named '%s' already exists." section-name)}}}]]

      :else
      (let [tx-data (support/with-audit [{:section/active? true
                                          :section/name    section-name}]
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         support/clear-loading
         clear-section-create]))))

(defn update-section-action
  [{:keys [db current-member-id]} {:keys [section]}]
  (let [{:keys [section-id section-name section-enabled]} section]
    (cond
      (str/blank? section-name)
      [support/clear-loading
       [:app.datastar/assoc-state [:section :error :section-name]
        {:error "Section name is required."}]]

      (support/lookup-taken-by-other? db [:section/name section-name] [:section/name section-id])
      [support/clear-loading
       [:app.datastar/merge-state
        [:section]
        {:section-name section-name
         :error {:section-name
                 {:error (format "Section named '%s' already exists." section-name)}}}]]

      :else
      [[:db/transact
        (support/with-audit [[:db/add [:section/name section-id] :section/name section-name]
                             [:db/add [:section/name section-id] :section/active? section-enabled]]
          current-member-id)
        {:transact-w-nils? false}]
       support/clear-loading
       clear-section])))

(defn delete-section-action
  [{:keys [current-member-id]} {:keys [targetid]}]
  [[:db/transact
    (support/with-audit [[:db/retractEntity [:section/name targetid]]]
      current-member-id)
    {:transact-w-nils? false}]
   support/clear-loading
   clear-section])

(defn open-section-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [section (q/retrieve-section-by-name db targetid)]
    [support/clear-loading
     [:app.datastar/assoc-state
      [:section]
      {:section-id      targetid
       :section-name    (:section/name section)
       :section-enabled (:section/active? section)}]]))

(defn close-section-edit-action [_state _signals]
  [support/clear-loading clear-section])

(defn open-section-create-action
  [_ _]
  [support/clear-loading
   [:app.datastar/assoc-state [:section-create]
    {:open true
     :section-name ""}]])

(defn close-section-create-action [_state _signals]
  [support/clear-loading clear-section-create])

(defn open-section-reorder-action [_state _signals]
  [support/clear-loading
   [:app.datastar/assoc-state [:section-reorder :open] true]])

(defn close-section-reorder-action [_state _signals]
  [[:app.datastar/assoc-state [:section-reorder :open] false]
   [:app.datastar/merge-signals {:section-reorder {:open false}}]])

(defn update-section-order-action
  [{:keys [current-member-id]} {:keys [section]}]
  (let [order (:order section)]
    [[:db/transact
      (support/with-audit
        (mapv (fn [idx section-name]
                [:db/add [:section/name section-name] :section/position idx])
              (range)
              order)
        current-member-id)
      {:transact-w-nils? false}]]))

(def actions
  {::create-section        #'create-section-action
   ::update-section        #'update-section-action
   ::delete-section        #'delete-section-action
   ::open-section-edit     #'open-section-edit-action
   ::close-section-edit    #'close-section-edit-action
   ::open-section-create   #'open-section-create-action
   ::close-section-create  #'close-section-create-action
   ::open-section-reorder  #'open-section-reorder-action
   ::close-section-reorder #'close-section-reorder-action
   ::update-section-order  #'update-section-order-action})
