(ns app.insurance.actions
  (:require
   [app.insurance.coverage.edit.actions :as coverage-edit.actions]
   [app.insurance.policy.review.actions :as policy-review.actions]
   [app.insurance.index.queries :as queries]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [com.yetanalytics.squuid :as sq]
   [tick.core :as t]))

(defn- coverage-type-tx
  [idx {:insurance.coverage.type/keys [name description premium-factor]}]
  (util/remove-nils
   {:db/id                                  (str "coverage_type_" idx)
    :insurance.coverage.type/name          name
    :insurance.coverage.type/type-id       (sq/generate-squuid)
    :insurance.coverage.type/description   description
    :insurance.coverage.type/premium-factor premium-factor}))

(defn- category-factor-tx
  [idx {:insurance.category.factor/keys [category factor]}]
  {:db/id                                                (str "category_factor_" idx)
   :insurance.category.factor/category-factor-id         (sq/generate-squuid)
   :insurance.category.factor/category                   [:instrument.category/category-id (:instrument.category/category-id category)]
   :insurance.category.factor/factor                     factor})

(defn- coverage-tx
  [idx coverage-type-old->new {:instrument.coverage/keys [status instrument types private? value change]}]
  (util/remove-nils
   {:db/id                           (str "coverage_" idx)
    :instrument.coverage/coverage-id (sq/generate-squuid)
    :instrument.coverage/instrument  [:instrument/instrument-id (:instrument/instrument-id instrument)]
    :instrument.coverage/types       (mapv (fn [{:insurance.coverage.type/keys [type-id]}]
                                             (get coverage-type-old->new type-id))
                                           types)
    :instrument.coverage/private?    private?
    :instrument.coverage/status      status
    :instrument.coverage/change      change
    :instrument.coverage/value       value}))

(defn duplicate-policy-tx-data
  [duplicate-label new-policy-id {:insurance.policy/keys [name effective-at effective-until premium-factor category-factors coverage-types covered-instruments currency]}]
  (let [category-factor-txs      (mapv category-factor-tx (range) category-factors)
        category-factor-tempids  (mapv :db/id category-factor-txs)
        coverage-type-txs        (mapv coverage-type-tx (range) coverage-types)
        coverage-type-tempids    (mapv :db/id coverage-type-txs)
        coverage-type-old->new   (zipmap (map :insurance.coverage.type/type-id coverage-types)
                                         coverage-type-tempids)
        covered-instrument-txs   (mapv (fn [idx coverage]
                                         (coverage-tx idx coverage-type-old->new coverage))
                                       (range)
                                       covered-instruments)
        covered-instrument-ids   (mapv :db/id covered-instrument-txs)]
    (vec
     (concat
      category-factor-txs
      coverage-type-txs
      covered-instrument-txs
      [(util/remove-nils
        {:insurance.policy/policy-id           new-policy-id
         :insurance.policy/name                (str duplicate-label " " name)
         :insurance.policy/effective-at        (some-> effective-at t/inst)
         :insurance.policy/effective-until     (some-> effective-until t/inst)
         :insurance.policy/currency            currency
         :insurance.policy/status              :insurance.policy.status/draft
         :insurance.policy/premium-factor      premium-factor
         :insurance.policy/coverage-types      coverage-type-tempids
         :insurance.policy/category-factors    category-factor-tempids
         :insurance.policy/covered-instruments covered-instrument-ids})]))))

(defn- target-policy-id [{:keys [targetid]}]
  (util/ensure-uuid! targetid))

(defn delete-policy-action
  [{:keys [current-member-id db]} signals]
  (let [policy-id (target-policy-id signals)]
    (if (q/policy-has-open-surveys? db policy-id)
      [support/clear-loading]
      [[:db/transact
        (support/with-audit [[:db/retractEntity [:insurance.policy/policy-id policy-id]]]
          current-member-id)
        {}]
       support/clear-loading])))

(defn duplicate-policy-action
  [{:keys [current-member-id db tr]} signals]
  (let [policy-id       (target-policy-id signals)
        policy          (queries/retrieve-policy db policy-id)
        new-policy-id   (sq/generate-squuid)
        duplicate-label (tr [:action/duplicate])
        duplicate-txes  (support/with-audit (duplicate-policy-tx-data duplicate-label new-policy-id policy)
                          current-member-id)]
    [[:db/transact duplicate-txes {}]
     support/clear-loading
     [:app.datastar/redirect (urls/link-policy new-policy-id)]]))

(def actions
  (merge
   {::delete-policy    #'delete-policy-action
    ::duplicate-policy #'duplicate-policy-action}
   coverage-edit.actions/actions
   policy-review.actions/actions))
