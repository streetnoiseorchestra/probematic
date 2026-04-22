(ns app.settings.engine
  (:require
   [app.settings.domain :as domain]
   [app.settings.routes :as routes]
   [clojure.string :as str]
   [datomic.api :as d]))

(defn- form-data [req form-key]
  (get-in req [:parameters :body form-key]))

(defn- lookup-eid [db lookup-ref]
  (:db/id (d/entity db lookup-ref)))

(defn- lookup-taken-by-other? [db lookup-ref current-ref]
  (let [found-eid   (lookup-eid db lookup-ref)
        current-eid (lookup-eid db current-ref)]
    (boolean (and found-eid (not= found-eid current-eid)))))

(defn- with-audit [tx-data member-id]
  (cond-> (vec tx-data)
    member-id (conj [:db/add "datomic.tx" :audit/user [:member/member-id member-id]])))

(defn create-discount-type [req]
  [[::routes/create-discount-type
    (form-data req :discount-type-create)
    [:app/new-squuid]
    [:app/current-member-id]]])

(defn update-discount-type [req]
  [[::routes/update-discount-type
    (form-data req :discount-type)
    [:app/current-member-id]]])

(defn delete-discount-type [req]
  [[::routes/delete-discount-type
    (form-data req :discount-type)
    [:app/current-member-id]]])

(defn open-discount-type-edit [req]
  [[::routes/open-discount-type-edit
    (form-data req :discount-type)]])

(defn close-discount-type-edit [_req]
  [[::routes/close-discount-type-edit]])

(defn create-team [req]
  [[::routes/create-team
    (form-data req :team-create)
    [:app/new-squuid]
    [:app/current-member-id]]])

(defn update-team [req]
  [[::routes/update-team
    (form-data req :team)
    [:app/current-member-id]]])

(defn delete-team [req]
  [[::routes/delete-team
    (form-data req :team)
    [:app/current-member-id]]])

(defn remove-team-member [req]
  [[::routes/remove-team-member
    (form-data req :team)
    [:app/current-member-id]]])

(defn add-team-member [req]
  [[::routes/add-team-member
    (form-data req :team)
    [:app/current-member-id]]])

(defn open-team-edit [req]
  [[::routes/open-team-edit
    (form-data req :team)]])

(defn close-team-edit [_req]
  [[::routes/close-team-edit]])

(defn create-section [req]
  [[::routes/create-section
    (form-data req :section-create)
    [:app/current-member-id]]])

(defn update-section [req]
  [[::routes/update-section
    (form-data req :section)
    [:app/current-member-id]]])

(defn open-section-edit [req]
  [[::routes/open-section-edit
    (form-data req :section)]])

(defn close-section-edit [_req]
  [[::routes/close-section-edit]])

(defn open-section-reorder [_req]
  [[::routes/open-section-reorder]])

(defn close-section-reorder [_req]
  [[::routes/close-section-reorder]])

(defn update-section-order [req]
  [[::routes/update-section-order
    (form-data req :section)
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
    (let [tx-data (with-audit [{:travel.discount.type/discount-type-id   discount-type-id
                                :travel.discount.type/enabled?           true
                                :travel.discount.type/discount-type-name discount-type-name}]
                    member-id)]
      [[:db/transact tx-data {:transact-w-nils? false}]
       [:app.datastar/remove-signals ["discount-type-create"]]])))

(defn update-discount-type-action
  [{:keys [db]} {:keys [discount-type-name discount-type-id discount-type-enabled]} member-id]
  (cond
    (str/blank? discount-type-name)
    [[:app.datastar/merge-signals {:discount-type {:error {:discount-type-name "Discount type name is required."}}}]]

    (lookup-taken-by-other? db
                            [:travel.discount.type/discount-type-name discount-type-name]
                            [:travel.discount.type/discount-type-id discount-type-id])
    [[:app.datastar/merge-signals {:discount-type {:error {:discount-type-name
                                                           (format "Discount type named '%s' already exists." discount-type-name)}}}]]

    :else
    [[:db/transact (with-audit [{:travel.discount.type/discount-type-id   discount-type-id
                                 :travel.discount.type/enabled?           discount-type-enabled
                                 :travel.discount.type/discount-type-name discount-type-name}]
                     member-id)
      {:transact-w-nils? false}]
     [:app.datastar/close-form :discount-type :discount-type-id]]))

(defn delete-discount-type-action
  [_state {:keys [discount-type-id]} member-id]
  [[:db/transact (with-audit [[:db/retractEntity [:travel.discount.type/discount-type-id discount-type-id]]]
                   member-id)
    {:transact-w-nils? false}]
   [:app.datastar/close-form :discount-type :discount-type-id]])

(defn open-discount-type-edit-action [_state _form]
  [[:app.datastar/open-form :discount-type :discount-type-id]])

(defn close-discount-type-edit-action [_state]
  [[:app.datastar/close-form :discount-type :discount-type-id]])

(defn- team-create-error [error]
  [[:app.datastar/merge-signals {:team-create {:error error}}]])

(defn- team-name-taken? [db team-name]
  (boolean
   (when db
     (d/entity db [:team/name team-name]))))

(defn create-team-action
  [{:keys [db]} {:keys [team-name]} team-id member-id]
  (cond
    (str/blank? team-name)
    (team-create-error {:team-name "Team name is required."})

    (team-name-taken? db team-name)
    (team-create-error {:team-name (format "Team named '%s' already exists." team-name)})

    :else
    [[:db/transact (with-audit [{:team/team-id team-id
                                 :team/name    team-name}]
                     member-id)
      {:transact-w-nils? false}]
     [:app.datastar/remove-signals ["team-create"]]]))

(defn update-team-action
  [{:keys [db]} {:keys [team-name team-id team-type]} member-id]
  (let [team-name         (some-> team-name str/trim)
        team-type         (domain/str->team-type team-type)
        team-ref          [:team/team-id team-id]
        current-team      (d/entity db team-ref)
        current-team-type (:team/team-type current-team)]
    (cond
      (str/blank? team-name)
      [[:app.datastar/merge-signals {:team {:error {:team-name "Team name is required."}}}]]

      (lookup-taken-by-other? db [:team/name team-name] team-ref)
      [[:app.datastar/merge-signals {:team {:error {:team-name (format "Team named '%s' already exists." team-name)}}}]]

      :else
      (let [tx-data (with-audit (concat
                                 (when (not= team-name (:team/name current-team))
                                   [[:db/add team-ref :team/name team-name]])
                                 (when team-type
                                   [[:db/add team-ref :team/team-type team-type]])
                                 (when (and (nil? team-type) current-team-type)
                                   [[:db/retract team-ref :team/team-type current-team-type]]))
                      member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         [:app.datastar/close-form :team :team-id]]))))

(defn delete-team-action
  [_state {:keys [team-id]} member-id]
  [[:db/transact (with-audit [[:db/retractEntity [:team/team-id team-id]]]
                   member-id)
    {:transact-w-nils? false}]
   [:app.datastar/close-form :team :team-id]])

(defn remove-team-member-action
  [_state {:keys [team-id remove-member-id]} member-id]
  [[:db/transact (with-audit [[:db/retract [:team/team-id team-id] :team/members [:member/member-id remove-member-id]]]
                   member-id)
    {:transact-w-nils? false}]])

(defn add-team-member-action
  [_state {:keys [team-id member-id]} audit-member-id]
  [[:db/transact (with-audit [[:db/add [:team/team-id team-id] :team/members [:member/member-id member-id]]]
                   audit-member-id)
    {:transact-w-nils? false}]
   [:app.datastar/merge-signals {:team {:member-id ""}}]])

(defn open-team-edit-action [_state _form]
  [[:app.datastar/open-form :team :team-id]])

(defn close-team-edit-action [_state]
  [[:app.datastar/close-form :team :team-id]])

(defn- section-create-error [error]
  [[:app.datastar/merge-signals {:section-create {:error error}}]])

(defn- section-name-taken? [db section-name]
  (boolean
   (when db
     (d/entity db [:section/name section-name]))))

(defn create-section-action
  [{:keys [db]} {:keys [section-name]} member-id]
  (cond
    (str/blank? section-name)
    (section-create-error {:section-name "Section name is required."})

    (section-name-taken? db section-name)
    (section-create-error {:section-name (format "Section named '%s' already exists." section-name)})

    :else
    [[:db/transact (with-audit [{:section/active? true
                                 :section/name    section-name}]
                     member-id)
      {:transact-w-nils? false}]
     [:app.datastar/remove-signals ["section-create"]]]))

(defn update-section-action
  [{:keys [db]} {:keys [section-old-name section-name section-enabled]} member-id]
  (cond
    (str/blank? section-name)
    [[:app.datastar/merge-signals {:section {:error {:section-name "Section name is required."}}}]]

    (lookup-taken-by-other? db [:section/name section-name] [:section/name section-old-name])
    [[:app.datastar/merge-signals {:section {:error {:section-name
                                                     (format "Section named '%s' already exists." section-name)}}}]]

    :else
    [[:db/transact (with-audit [[:db/add [:section/name section-old-name] :section/name section-name]
                                [:db/add [:section/name section-old-name] :section/active? section-enabled]]
                     member-id)
      {:transact-w-nils? false}]
     [:app.datastar/close-form :section :section-id]]))

(defn open-section-edit-action [_state _form]
  [[:app.datastar/open-form :section :section-id]])

(defn close-section-edit-action [_state]
  [[:app.datastar/close-form :section :section-id]])

(defn open-section-reorder-action [_state]
  [[:app.datastar/assoc-state [:section-reorder :open] true]])

(defn close-section-reorder-action [_state]
  [[:app.datastar/assoc-state [:section-reorder :open] false]
   [:app.datastar/merge-signals {:section-reorder {:open false}}]])

(defn update-section-order-action
  [_state {:keys [order]} member-id]
  [[:db/transact (with-audit (mapv (fn [[section-name position]]
                                     [:db/add [:section/name section-name] :section/position position])
                                   order)
                   member-id)
    {:transact-w-nils? false}]
   [:app.datastar/merge-signals {:section-reorder {:open false}}]])

(def actions
  {::routes/create-discount-type #'create-discount-type-action
   ::routes/update-discount-type #'update-discount-type-action
   ::routes/delete-discount-type #'delete-discount-type-action
   ::routes/open-discount-type-edit #'open-discount-type-edit-action
   ::routes/close-discount-type-edit #'close-discount-type-edit-action
   ::routes/create-team #'create-team-action
   ::routes/update-team #'update-team-action
   ::routes/delete-team #'delete-team-action
   ::routes/remove-team-member #'remove-team-member-action
   ::routes/add-team-member #'add-team-member-action
   ::routes/open-team-edit #'open-team-edit-action
   ::routes/close-team-edit #'close-team-edit-action
   ::routes/create-section #'create-section-action
   ::routes/update-section #'update-section-action
   ::routes/open-section-edit #'open-section-edit-action
   ::routes/close-section-edit #'close-section-edit-action
   ::routes/open-section-reorder #'open-section-reorder-action
   ::routes/close-section-reorder #'close-section-reorder-action
   ::routes/update-section-order #'update-section-order-action})
