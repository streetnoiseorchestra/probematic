(ns app.settings.controller
  (:require [app.datomic :as d]
            [app.datomic.shim :as datomic]
            [app.queries :as q]
            [app.settings.domain :as domain]
            [app.util :as util]
            [clojure.string :as str]
            [com.yetanalytics.squuid :as sq]))

(defn create-discount-type! [req]
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

(defn update-discount-type [{:keys [datomic-conn] :as req}]
  (let [{:keys [discount-type-name discount-type-id discount-type-enabled]} (-> req :parameters :body :discount-type)
        tx-data                                                             [{:travel.discount.type/discount-type-id   discount-type-id
                                                                              :travel.discount.type/enabled?           discount-type-enabled
                                                                              :travel.discount.type/discount-type-name discount-type-name}]
        {:keys [db-after]}                                                  (datomic/transact datomic-conn {:tx-data tx-data})]

    (q/retrieve-discount-type db-after discount-type-id)))

(defn delete-discount-type! [req]
  (let [discount-type-id (util/ensure-uuid! (-> req :body-params :discount-type-id))
        tx-data          [[:db/retractEntity [:travel.discount.type/discount-type-id discount-type-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))

(defn create-section! [{:keys [datomic-conn] :as req}]
  (let [section-name       (-> req :parameters :body :section-name)
        {:keys [db-after]} (datomic/transact datomic-conn {:tx-data [{:section/active? true
                                                                      :section/name    section-name}]})]

    db-after))

(defn update-section! [{:keys [datomic-conn] :as req}]
  (let [{:keys [section-old-name section-name section-active]} (-> req :parameters :body :section)
        tx-data                                                [[:db/add [:section/name section-old-name] :section/name section-name]
                                                                [:db/add [:section/name section-old-name] :section/active? section-active]]
        {:keys [db-after]}                                     (datomic/transact datomic-conn {:tx-data tx-data})]

    (q/retrieve-section-by-name db-after section-name)))

(defn order-sections! [req]
  (let [sections-order (-> req :parameters :body :sections-order)
        tx-data        (map (fn [[section-name position]]
                              [:db/add [:section/name section-name] :section/position position]) sections-order)]
    (d/transact-wrapper! req {:tx-data tx-data})))

#_(defn reconcile-team-members [eid before-members after-members]
    (let [[removed added] (clojure.data/diff (set before-members) (set after-members))
          ;; _ (tap> {:added added :removed removed})
          add-tx          (map #(-> [:db/add eid :team/members [:member/member-id  %]]) (filter some? added))
          remove-tx       (map #(-> [:db/retract eid :team/members [:member/member-id  %]]) (filter some? removed))]
      (concat add-tx remove-tx)))

(defn remove-member! [{:keys [parameters] :as req}]
  (let [{:keys [team-id remove-member-id]} (-> parameters :body :team)
        team-id                            (util/ensure-uuid! team-id)
        member-id                          (util/ensure-uuid! remove-member-id)
        team-ref                           [:team/team-id team-id]
        #_#_team                           (q/retrieve-team db team-id)
        tx-data                            [[:db/retract team-ref :team/members [:member/member-id member-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))

(defn add-member! [{:keys [parameters] :as req}]
  (let [{:keys [team-id member-id]} (-> parameters :body :team)
        team-id                     (util/ensure-uuid! team-id)
        member-id                   (util/ensure-uuid! member-id)
        team-ref                    [:team/team-id team-id]
        #_#_team                    (q/retrieve-team db team-id)
        tx-data                     [[:db/add team-ref :team/members [:member/member-id member-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))

(defn create-team! [req]
  (tap> [:create-team (-> req :parameters)])
  (let [team-name (-> req :parameters :body :team-create :team-name)
        valid?    (and team-name (not (str/blank? team-name)))
        tx-data   [{:team/team-id (sq/generate-squuid)
                    :team/name    team-name}]]
    (if valid?
      (try
        (d/transact-wrapper! req {:tx-data tx-data})
        (catch java.util.concurrent.ExecutionException e
          (if (= :db.error/unique-conflict (:db/error (ex-data (.getCause e))))
            {:error {:team-name (format "Team named '%s' already exists." team-name)}}
            (throw e))))
      {:error {:team-name "Team name is required."}})))

(defn update-team! [{:keys [db] :as req}]
  (let [{:keys [team-name team-id team-type]} (-> req :parameters :body :team)
        team-name                             (str/trim team-name)
        team-id                               (util/ensure-uuid! team-id)
        team-type                             (domain/str->team-type team-type)
        valid?                                (and team-name (not (str/blank? team-name)))
        team-ref                              [:team/team-id team-id]
        team                                  (q/retrieve-team db team-id)
        tx-data                               (concat (when (not= team-name (:team/name team))
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
            {:error {:team-name (format "Team named '%s' already exists." team-name)}}
            (throw e))))
      {:error {:team-name "Team name is required."}})))

(defn delete-team! [req]
  (let [team-id (-> req :parameters :body :team :team-id)
        tx-data [[:db/retractEntity [:team/team-id team-id]]]]
    (d/transact-wrapper! req {:tx-data tx-data})))
