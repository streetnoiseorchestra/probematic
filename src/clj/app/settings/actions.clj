(ns app.settings.actions
  (:require
   [app.settings.domain :as domain]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]
   [app.queries :as q]))

(defn- lookup-eid [db lookup-ref]
  (:db/id (d/entity db lookup-ref)))

(defn- lookup-taken-by-other? [db lookup-ref current-ref]
  (let [found-eid   (lookup-eid db lookup-ref)
        current-eid (lookup-eid db current-ref)]
    (boolean (and found-eid (not= found-eid current-eid)))))

(defn- with-audit [tx-data member-id]
  (cond-> (vec tx-data)
    member-id (conj [:db/add "datomic.tx" :audit/user [:member/member-id member-id]])))

;; --------------------------------------------------------------------------
;; Discount type actions
;; --------------------------------------------------------------------------

(defn- discount-type-name-taken? [db discount-type-name]
  (boolean
   (when db
     (d/entity db [:travel.discount.type/discount-type-name discount-type-name]))))

(def clear-loading [:app.datastar/merge-signals {:loading false :targetid false}])

(def clear-discount-type
  [:app.datastar/assoc-state [:discount-type] false])

(def clear-discount-type-create
  [:app.datastar/assoc-state [:discount-type-create] false])

(defn create-discount-type-action
  [{:keys [current-member-id db]} {:keys [discount-type-create]}]
  (let [{:keys [discount-type-name]} discount-type-create]
    (cond
      (str/blank? discount-type-name)
      [clear-loading
       [:app.datastar/assoc-state [:discount-type-create :error :discount-type-name] {:error "Discount type name is required."}]]

      (discount-type-name-taken? db discount-type-name)
      [clear-loading
       [:app.datastar/merge-state
        [:discount-type-create]
        {:discount-type-name discount-type-name
         :error {:discount-type-name
                 {:error (format "Discount type named '%s' already exists." discount-type-name)}}}]]

      :else
      (let [tx-data (with-audit [{:travel.discount.type/discount-type-id   :db/gen-uuid
                                  :travel.discount.type/enabled?           true
                                  :travel.discount.type/discount-type-name discount-type-name}]
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         clear-loading
         clear-discount-type-create]))))

(defn update-discount-type-action
  [{:keys [db current-member-id]} {:keys [discount-type] :as p}]
  (let [{:keys [discount-type-name discount-type-enabled]} discount-type
        discount-type-id (util/ensure-uuid! (:discount-type-id discount-type))]
    (cond
      (str/blank? discount-type-name)
      [clear-loading
       [:app.datastar/assoc-state [:discount-type :error :discount-type-name] {:error "Discount type name is required."}]]
      (lookup-taken-by-other? db
                              [:travel.discount.type/discount-type-name discount-type-name]
                              [:travel.discount.type/discount-type-id discount-type-id])

      [clear-loading
       [:app.datastar/merge-state
        [:discount-type]
        {:discount-type-name discount-type-name
         :error {:discount-type-name
                 {:error (format "Discount type named '%s' already exists." discount-type-name)}}}]]

      :else
      [[:db/transact (with-audit [{:travel.discount.type/discount-type-id   discount-type-id
                                   :travel.discount.type/enabled?           discount-type-enabled
                                   :travel.discount.type/discount-type-name discount-type-name}]
                       current-member-id)
        {:transact-w-nils? false}]
       clear-loading
       clear-discount-type])))

(defn delete-discount-type-action
  [{:keys [current-member-id]} {:keys [targetid]}]
  (let [discount-type-id (util/ensure-uuid! targetid)]
    [[:db/transact (with-audit [[:db/retractEntity [:travel.discount.type/discount-type-id discount-type-id]]]
                     current-member-id)
      {:transact-w-nils? false}]
     clear-loading
     clear-discount-type]))

(defn open-discount-type-edit-action
  [{:keys [db]}  {:keys [targetid]}]
  (let [discount-type-id (util/ensure-uuid! targetid)
        dt (q/retrieve-discount-type db discount-type-id)]
    [clear-loading
     [:app.datastar/assoc-state [:discount-type] {:discount-type-id discount-type-id
                                                  :discount-type-name (:travel.discount.type/discount-type-name dt)
                                                  :discount-type-enabled (:travel.discount.type/enabled? dt)}]]))

(defn close-discount-type-edit-action [_state _signals]
  [clear-loading clear-discount-type])

(defn open-discount-type-create-action
  [_ _]
  [clear-loading
   [:app.datastar/assoc-state [:discount-type-create] {:open true
                                                       :discount-type-name ""}]])

(defn close-discount-type-create-action [_state _signals]
  [clear-loading clear-discount-type-create])

;; --------------------------------------------------------------------------
;; Team actions
;; --------------------------------------------------------------------------

(defn- team-create-error [error]
  [[:app.datastar/merge-signals {:team-create {:error error}}]])

(defn- team-name-taken? [db team-name]
  (boolean
   (when db
     (d/entity db [:team/name team-name]))))

(defn create-team-action
  [{:keys [db current-member-id]} {:keys [team-create]}]
  (let [{:keys [team-name]} team-create]
    (cond
      (str/blank? team-name)
      (team-create-error {:team-name "Team name is required."})

      (team-name-taken? db team-name)
      (team-create-error {:team-name (format "Team named '%s' already exists." team-name)})

      :else
      [[:db/transact (with-audit [{:team/team-id :db/gen-uuid
                                   :team/name    team-name}]
                       current-member-id)
        {:transact-w-nils? false}]
       [:app.datastar/remove-signals ["team-create"]]])))

(defn update-team-action
  [{:keys [db current-member-id]} {:keys [team]}]
  (let [team-id           (util/ensure-uuid! (:team-id team))
        team-name         (some-> (:team-name team) str/trim)
        team-type         (domain/str->team-type (:team-type team))
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
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         [:app.datastar/close-form :team :team-id]]))))

(defn delete-team-action
  [{:keys [current-member-id]} {:keys [team]}]
  (let [team-id (util/ensure-uuid! (:team-id team))]
    [[:db/transact (with-audit [[:db/retractEntity [:team/team-id team-id]]]
                     current-member-id)
      {:transact-w-nils? false}]
     [:app.datastar/close-form :team :team-id]]))

(defn remove-team-member-action
  [{:keys [current-member-id]} {:keys [team]}]
  (let [team-id          (util/ensure-uuid! (:team-id team))
        remove-member-id (util/ensure-uuid! (:remove-member-id team))]
    [[:db/transact (with-audit [[:db/retract [:team/team-id team-id] :team/members [:member/member-id remove-member-id]]]
                     current-member-id)
      {:transact-w-nils? false}]]))

(defn add-team-member-action
  [{:keys [current-member-id]} {:keys [team]}]
  (let [team-id   (util/ensure-uuid! (:team-id team))
        member-id (util/ensure-uuid! (:member-id team))]
    [[:db/transact (with-audit [[:db/add [:team/team-id team-id] :team/members [:member/member-id member-id]]]
                     current-member-id)
      {:transact-w-nils? false}]
     [:app.datastar/merge-signals {:team {:member-id ""}}]]))

(defn open-team-edit-action
  [_state {:keys [team]}]
  (let [team-id (util/ensure-uuid! (:team-id team))]
    [[:app.datastar/open-form :team :team-id team-id]]))

(defn close-team-edit-action [_state _signals]
  [[:app.datastar/close-form :team :team-id]])

;; --------------------------------------------------------------------------
;; Section actions
;; --------------------------------------------------------------------------

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
      [clear-loading
       [:app.datastar/assoc-state [:section-create :error :section-name] {:error "Section name is required."}]]

      (section-name-taken? db section-name)
      [clear-loading
       [:app.datastar/merge-state
        [:section-create]
        {:section-name section-name
         :error {:section-name
                 {:error (format "Section named '%s' already exists." section-name)}}}]]

      :else
      (let [tx-data (with-audit [{:section/active? true
                                  :section/name    section-name}]
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         clear-loading
         clear-section-create]))))

(defn update-section-action
  [{:keys [db current-member-id]} {:keys [section]}]
  (let [{:keys [section-id section-name section-enabled]} section]
    (cond
      (str/blank? section-name)
      [clear-loading
       [:app.datastar/assoc-state [:section :error :section-name] {:error "Section name is required."}]]

      (lookup-taken-by-other? db [:section/name section-name] [:section/name section-id])
      [clear-loading
       [:app.datastar/merge-state
        [:section]
        {:section-name section-name
         :error {:section-name
                 {:error (format "Section named '%s' already exists." section-name)}}}]]

      :else
      [[:db/transact (with-audit [[:db/add [:section/name section-id] :section/name section-name]
                                  [:db/add [:section/name section-id] :section/active? section-enabled]]
                       current-member-id)
        {:transact-w-nils? false}]
       clear-loading
       clear-section])))

(defn delete-section-action
  [{:keys [current-member-id]} {:keys [targetid]}]
  (let [section-id targetid]
    [[:db/transact (with-audit [[:db/retractEntity [:section/name section-id]]]
                     current-member-id)
      {:transact-w-nils? false}]
     clear-loading
     clear-section]))

(defn open-section-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [section-id targetid
        section    (q/retrieve-section-by-name db section-id)]
    [clear-loading
     [:app.datastar/assoc-state [:section] {:section-id      section-id
                                            :section-name    (:section/name section)
                                            :section-enabled (:section/active? section)}]]))

(defn close-section-edit-action [_state _signals]
  [clear-loading clear-section])

(defn open-section-create-action
  [_ _]
  [clear-loading
   [:app.datastar/assoc-state [:section-create] {:open true
                                                 :section-name ""}]])

(defn close-section-create-action [_state _signals]
  [clear-loading clear-section-create])

(defn open-section-reorder-action [_state _signals]
  [clear-loading
   [:app.datastar/assoc-state [:section-reorder :open] true]])

(defn close-section-reorder-action [_state _signals]
  [[:app.datastar/assoc-state [:section-reorder :open] false]
   [:app.datastar/merge-signals {:section-reorder {:open false}}]])

(defn update-section-order-action
  [{:keys [current-member-id]} {:keys [section]}]
  (let [order (:order section)]
    [[:db/transact (with-audit (mapv (fn [idx section-name]
                                       [:db/add [:section/name section-name]
                                        :section/position idx])
                                     (range)
                                     order)
                     current-member-id)
      {:transact-w-nils? false}]]))

(def actions
  {::create-discount-type     #'create-discount-type-action
   ::update-discount-type     #'update-discount-type-action
   ::delete-discount-type     #'delete-discount-type-action
   ::open-discount-type-edit  #'open-discount-type-edit-action
   ::close-discount-type-edit #'close-discount-type-edit-action
   ::open-discount-type-create  #'open-discount-type-create-action
   ::close-discount-type-create #'close-discount-type-create-action
   ::create-team              #'create-team-action
   ::update-team              #'update-team-action
   ::delete-team              #'delete-team-action
   ::remove-team-member       #'remove-team-member-action
   ::add-team-member          #'add-team-member-action
   ::open-team-edit           #'open-team-edit-action
   ::close-team-edit          #'close-team-edit-action
   ::create-section           #'create-section-action
   ::update-section           #'update-section-action
   ::delete-section           #'delete-section-action
   ::open-section-edit        #'open-section-edit-action
   ::close-section-edit       #'close-section-edit-action
   ::open-section-create      #'open-section-create-action
   ::close-section-create     #'close-section-create-action
   ::open-section-reorder     #'open-section-reorder-action
   ::close-section-reorder    #'close-section-reorder-action
   ::update-section-order     #'update-section-order-action})
