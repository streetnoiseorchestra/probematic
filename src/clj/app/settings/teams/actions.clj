(ns app.settings.teams.actions
  (:require
   [app.queries :as q]
   [app.nexus.actions :as support]
   [app.settings.domain :as domain]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]))

(def clear-team
  [:app.datastar/assoc-state [:team] false])

(def clear-team-create
  [:app.datastar/assoc-state [:team-create] false])

(defn- team-name-taken? [db team-name]
  (boolean
   (when db
     (d/entity db [:team/name team-name]))))

(defn create-team-action
  [{:keys [db current-member-id]} {:keys [team-create]}]
  (let [{:keys [team-name]} team-create]
    (cond
      (str/blank? team-name)
      [support/clear-loading
       [:app.datastar/assoc-state [:team-create :error :team-name]
        {:error "Team name is required."}]]

      (team-name-taken? db team-name)
      [support/clear-loading
       [:app.datastar/merge-state
        [:team-create]
        {:team-name team-name
         :error {:team-name
                 {:error (format "Team named '%s' already exists." team-name)}}}]]

      :else
      (let [tx-data (support/with-audit [{:team/team-id :db/gen-uuid
                                          :team/name    team-name}]
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         support/clear-loading
         clear-team-create]))))

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
      [support/clear-loading
       [:app.datastar/assoc-state [:team :error :team-name]
        {:error "Team name is required."}]]

      (support/lookup-taken-by-other? db [:team/name team-name] team-ref)
      [support/clear-loading
       [:app.datastar/merge-state
        [:team]
        {:team-name team-name
         :error {:team-name
                 {:error (format "Team named '%s' already exists." team-name)}}}]]

      :else
      (let [tx-data (support/with-audit
                      (concat
                       (when (not= team-name (:team/name current-team))
                         [[:db/add team-ref :team/name team-name]])
                       (when team-type
                         [[:db/add team-ref :team/team-type team-type]])
                       (when (and (nil? team-type) current-team-type)
                         [[:db/retract team-ref :team/team-type current-team-type]]))
                      current-member-id)]
        [[:db/transact tx-data {:transact-w-nils? false}]
         support/clear-loading
         clear-team]))))

(defn delete-team-action
  [{:keys [current-member-id]} {:keys [targetid]}]
  (let [team-id (util/ensure-uuid! targetid)]
    [[:db/transact
      (support/with-audit [[:db/retractEntity [:team/team-id team-id]]]
        current-member-id)
      {:transact-w-nils? false}]
     support/clear-loading
     clear-team]))

(defn remove-team-member-action
  [{:keys [current-member-id]} {:keys [team]}]
  (let [team-id          (some-> (:team-id team) util/ensure-uuid)
        remove-member-id (some-> (:remove-member-id team) util/ensure-uuid)]
    (if (and team-id remove-member-id)
      [[:db/transact
        (support/with-audit [[:db/retract [:team/team-id team-id]
                              :team/members
                              [:member/member-id remove-member-id]]]
          current-member-id)
        {:transact-w-nils? false}]
       support/clear-loading
       [:app.datastar/assoc-state [:team :remove-member-id] nil]]
      [support/clear-loading])))

(defn add-team-member-action
  [{:keys [current-member-id]} {:keys [team]}]
  (let [team-id   (some-> (:team-id team) util/ensure-uuid)
        member-id (some-> (:member-id team) util/ensure-uuid)]
    (if (and team-id member-id)
      [[:db/transact
        (support/with-audit [[:db/add [:team/team-id team-id] :team/members [:member/member-id member-id]]]
          current-member-id)
        {:transact-w-nils? false}]
       support/clear-loading
       [:app.datastar/assoc-state [:team :member-id] ""]]
      [support/clear-loading])))

(defn open-team-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [team-id (util/ensure-uuid! targetid)
        team    (q/retrieve-team db team-id)]
    [support/clear-loading
     [:app.datastar/assoc-state
      [:team]
      {:team-id   team-id
       :team-name (:team/name team)
       :team-type (some-> (:team/team-type team) name)
       :member-id ""}]]))

(defn close-team-edit-action [_state _signals]
  [support/clear-loading clear-team])

(defn open-team-create-action
  [_ _]
  [support/clear-loading
   [:app.datastar/assoc-state [:team-create]
    {:open true
     :team-name ""}]])

(defn close-team-create-action [_state _signals]
  [support/clear-loading clear-team-create])

(def actions
  {::create-team        #'create-team-action
   ::update-team        #'update-team-action
   ::delete-team        #'delete-team-action
   ::remove-team-member #'remove-team-member-action
   ::add-team-member    #'add-team-member-action
   ::open-team-edit     #'open-team-edit-action
   ::close-team-edit    #'close-team-edit-action
   ::open-team-create   #'open-team-create-action
   ::close-team-create  #'close-team-create-action})
