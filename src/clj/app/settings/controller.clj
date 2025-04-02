(ns app.settings.controller
  (:require
   [clojure.set :as set]
   [clojure.data :as clojure.data]
   [clojure.string :as str]
   [app.queries :as q]
   [com.yetanalytics.squuid :as sq]
   [app.util.http :as common]
   [app.datomic :as d]
   [app.datomic.shim :as datomic]
   [app.settings.domain :as domain]
   [app.util :as util]))

(defn create-discount-type! [{:keys [datomic-conn db] :as req}]
  (let [discount-type-name (-> req :parameters :body :discount-type-name)
        valid?             (and discount-type-name (not (str/blank? discount-type-name)))
        tx-data            [{:travel.discount.type/discount-type-id   (sq/generate-squuid)
                             :travel.discount.type/enabled?           true
                             :travel.discount.type/discount-type-name discount-type-name}]]

    (if valid?
      (try
        (d/transact-wrapper! req {:tx-data tx-data})
        (catch java.util.concurrent.ExecutionException e
          (if (= :db.error/unique-conflict (:db/error (ex-data (.getCause e))))
            {:error (format "Discount type named '%s' already exists." discount-type-name)}
            (throw e))))
      {:error "Discount type name is required."})))

(defn update-discount-type [{:keys [datomic-conn db] :as req}]
  (let [{:keys [discount-type-name discount-type-id discount-type-enabled]} (-> req :parameters :body :discount-type)
        tx-data                                                             [{:travel.discount.type/discount-type-id   discount-type-id
                                                                              :travel.discount.type/enabled?           discount-type-enabled
                                                                              :travel.discount.type/discount-type-name discount-type-name}]
        {:keys [db-after]}                                                  (datomic/transact datomic-conn {:tx-data tx-data})]

    (q/retrieve-discount-type db-after discount-type-id)))

(defn create-section [{:keys [datomic-conn] :as req}]
  (let [section-name       (-> req :params :section-name)
        {:keys [db-after]} (datomic/transact datomic-conn {:tx-data [{:section/active? true
                                                                      :section/name    section-name}]})]

    db-after))

(defn update-section [{:keys [datomic-conn] :as req}]
  (let [{:keys [old-section-name section-name active?]} (:params req)
        tx-data [[:db/add [:section/name old-section-name] :section/name section-name]
                 [:db/add [:section/name old-section-name] :section/active? (common/check->bool active?)]]
        {:keys [db-after]} (datomic/transact datomic-conn {:tx-data tx-data})]

    (q/retrieve-section-by-name db-after section-name)))

(defn order-sections [{:keys [datomic-conn] :as req}]
  (tap> {:p (:params req)
         :u (common/unwrap-params req)})
  (let [sections (-> req common/unwrap-params :settings-page :sections :section-single)
        tx-data (map (fn [{:keys [section-name position]}]
                       [:db/add [:section/name section-name] :section/position (common/parse-long position)]) sections)]
    (:db-after (datomic/transact datomic-conn {:tx-data tx-data}))))

(defn create-team! [{:keys [datomic-conn] :as req}]
  (let [team-name (-> req :body-params :team-name)
        valid?    (and team-name (not (str/blank? team-name)))
        tx-data   [{:team/team-id (sq/generate-squuid)
                    :team/name    team-name}]]
    (if valid?
      (try
        (d/transact-wrapper! req {:tx-data tx-data})
        (catch java.util.concurrent.ExecutionException e
          (if (= :db.error/unique-conflict (:db/error (ex-data (.getCause e))))
            {:error (format "Team named '%s' already exists." team-name)}
            (throw e))))

      {:error "Team name is required."})))

(defn reconcile-team-members [eid before-members after-members]
  (let [[removed added] (clojure.data/diff (set before-members) (set after-members))
        ;; _ (tap> {:added added :removed removed})
        add-tx          (map #(-> [:db/add eid :team/members [:member/member-id  %]]) (filter some? added))
        remove-tx       (map #(-> [:db/retract eid :team/members [:member/member-id  %]]) (filter some? removed))]
    (concat add-tx remove-tx)))

(defn update-team!_old [{:keys [db datomic-conn] :as req}]
  (tap> [:update-team :parameters (-> req :parameters :body :team)])
  (let [{:keys [team-name team-id add-member-id remove-members team-type] :as params} (common/unwrap-params req)
        team-name                                                                     (str/trim team-name)
        team-id                                                                       (util/ensure-uuid! team-id)
        team-type                                                                     (domain/str->team-type team-type)
        valid?                                                                        (and team-name (not (str/blank? team-name)))
        team-ref                                                                      [:team/team-id team-id]
        team                                                                          (q/retrieve-team db team-id)
        before-members                                                                (set (->> team :team/members (mapv :member/member-id)))
        remove-members                                                                (set (->> remove-members (util/ensure-coll) (map util/ensure-uuid) (util/remove-dummy-uuid)))
        add-member-id                                                                 (->> add-member-id (util/ensure-coll) (map util/ensure-uuid) set)
        after-members                                                                 (set/union (set/difference before-members remove-members) add-member-id)
        ;; _ (tap> {:before before-members :after after-members})
        member-changes                                                                (reconcile-team-members team-ref before-members after-members)
        tx-data                                                                       (concat (when (not= team-name (:team/name team)) [[:db/add team-ref :team/name team-name]])
                                                                                              (when team-type [[:db/add team-ref :team/team-type team-type]])
                                                                                              member-changes)]
    #_(tap> [:params params :tx-data tx-data])
    (if valid?
      (try
        (d/transact-wrapper! req {:tx-data tx-data})
        (catch Exception e
          (if (= :db.error/unique-conflict (:db/error (ex-data e)))
            {:error (format "Team named '%s' already exists." team-name)}
            (throw e))))

      {:error "Team name is required."})))

(defn remove-member! [{:keys [db parameters] :as req}]
  (let [{:keys [team-id remove-member-id] :as params} (-> parameters :body :team)
        team-id                                       (util/ensure-uuid! team-id)
        member-id                                     (util/ensure-uuid! remove-member-id)
        team-ref                                      [:team/team-id team-id]
        team                                          (q/retrieve-team db team-id)
        tx-data                                       [[:db/retract team-ref :team/members [:member/member-id member-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))

(defn add-member! [{:keys [db parameters] :as req}]
  (let [{:keys [team-id member-id] :as params} (-> parameters :body :team)
        team-id                                (util/ensure-uuid! team-id)
        member-id                              (util/ensure-uuid! member-id)
        team-ref                               [:team/team-id team-id]
        team                                   (q/retrieve-team db team-id)
        tx-data                                [[:db/add team-ref :team/members [:member/member-id member-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))

(defn update-team! [{:keys [db datomic-conn] :as req}]
  (let [{:keys [team-name team-id team-type] :as params} (-> req :parameters :body :team)
        team-name                                        (str/trim team-name)
        team-id                                          (util/ensure-uuid! team-id)
        team-type                                        (domain/str->team-type team-type)
        valid?                                           (and team-name (not (str/blank? team-name)))
        team-ref                                         [:team/team-id team-id]
        team                                             (q/retrieve-team db team-id)
        tx-data                                          (concat (when (not= team-name (:team/name team))
                                                                   [[:db/add team-ref :team/name team-name]])
                                                                 (when team-type
                                                                   [[:db/add team-ref :team/team-type team-type]])
                                                                 (when (and (not team-type) (:team/team-type team))
                                                                   [[:db/retract team-ref :team/team-type (:team/team-type team)]]))]
    (if valid?
      (try
        (d/transact-wrapper! req {:tx-data tx-data})

        (catch Exception e
          (if (= :db.error/unique-conflict (:db/error (ex-data (.getCause e))))
            {:error (format "Team named '%s' already exists." team-name)}
            (throw e))))
      {:error "Team name is required."})))

(defn delete-team! [{:keys [db datomic-conn] :as req}]
  (let [team-id (util/ensure-uuid! (-> req :body-params :team-id))
        tx-data [[:db/retractEntity [:team/team-id team-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))
