(ns app.datomic.migrations.post002-backfill-insurance-metadata
  "Backfills configurable insurance metadata introduced with policy exporters.

  Historical labels are matched only here. Stork runs [[tx-data]] after the
  canonical schema is installed and records the migration atomically with the
  returned transaction data."
  (:require
   [app.insurance.exporters :as exporters]
   [com.brunobonacci.mulog :as μ]
   [datomic.api :as d]))

(def ^:private legacy-coverage-metadata
  {"Grundschutz"
   {:insurance.coverage.type/icon      :phosphor/shield
    :insurance.coverage.type/required? true}
   "Nachzeit im Auto"
   {:insurance.coverage.type/icon      :phosphor/car-profile
    :insurance.coverage.type/required? false}
   "Proberaum"
   {:insurance.coverage.type/icon      :phosphor/warehouse
    :insurance.coverage.type/required? false}})

(def ^:private legacy-role-coverage-names
  {:insurance.exporter.harmonia-v1/overnight-vehicle
   "Nachzeit im Auto"
   :insurance.exporter.harmonia-v1/unattended-building
   "Proberaum"})

(def ^:private policy-pattern
  [:db/id
   :insurance.policy/policy-id
   :insurance.policy/exporter-id
   {:insurance.policy/coverage-types
    [:db/id
     :insurance.coverage.type/type-id
     :insurance.coverage.type/name
     :insurance.coverage.type/icon
     :insurance.coverage.type/required?]}
   {:insurance.policy/covered-instruments
    [:db/id
     :instrument.coverage/coverage-id
     :instrument.coverage/status
     :instrument.coverage/change
     {:instrument.coverage/types
      [:insurance.coverage.type/type-id]}]}])

(defn- policies
  [db]
  (->> (d/q '[:find [?policy ...]
              :where
              [?policy :insurance.policy/policy-id]]
            db)
       (d/pull-many db policy-pattern)
       (sort-by :db/id)
       vec))

(defn- coverage-metadata-tx
  [{:insurance.coverage.type/keys [name] :as coverage-type}]
  (when-let [legacy-metadata (get legacy-coverage-metadata name)]
    (let [tx (reduce-kv (fn [tx attribute value]
                          (cond-> tx
                            (not (contains? coverage-type attribute))
                            (assoc attribute value)))
                        {:db/id (:db/id coverage-type)}
                        legacy-metadata)]
      (when (< 1 (count tx))
        tx))))

(defn- coverage-metadata-txs
  [policies]
  (->> policies
       (mapcat :insurance.policy/coverage-types)
       (reduce (fn [coverage-types coverage-type]
                 (assoc coverage-types (:db/id coverage-type) coverage-type))
               {})
       vals
       (sort-by :db/id)
       (keep coverage-metadata-tx)
       vec))

(defn- newly-required-coverage-type
  [policy]
  (some (fn [coverage-type]
          (when (true? (:insurance.coverage.type/required?
                        (coverage-metadata-tx coverage-type)))
            coverage-type))
        (:insurance.policy/coverage-types policy)))

(defn- coverage-type-ids
  [coverage]
  (set (map :insurance.coverage.type/type-id
            (:instrument.coverage/types coverage))))

(defn- required-coverage-txs
  [policies]
  (into []
        (mapcat
         (fn [policy]
           (when-let [coverage-type (newly-required-coverage-type policy)]
             (let [type-id  (:insurance.coverage.type/type-id coverage-type)
                   type-ref (:db/id coverage-type)]
               (->> (:insurance.policy/covered-instruments policy)
                    (remove #(= :instrument.coverage.change/removed
                                (:instrument.coverage/change %)))
                    (remove #(contains? (coverage-type-ids %) type-id))
                    (mapv (fn [coverage]
                            (let [change (:instrument.coverage/change coverage)]
                              {:db/id (:db/id coverage)
                               :instrument.coverage/types type-ref
                               :instrument.coverage/status
                               :instrument.coverage.status/needs-review
                               :instrument.coverage/change
                               (if (= :instrument.coverage.change/new change)
                                 :instrument.coverage.change/new
                                 :instrument.coverage.change/changed)}))))))))
        policies))

(defn- legacy-policy?
  [policy]
  (boolean
   (some coverage-metadata-tx
         (:insurance.policy/coverage-types policy))))

(defn- role-match
  [coverage-types-by-name {:keys [role]}]
  (let [matches (get coverage-types-by-name
                     (get legacy-role-coverage-names role)
                     [])]
    {:role    role
     :matches matches
     :status  (case (count matches)
                0 :missing
                1 :matched
                :ambiguous)}))

(defn- mapping-tx
  [{:keys [matches role]}]
  {:insurance.export.mapping/role          role
   :insurance.export.mapping/coverage-type (:db/id (first matches))})

(defn- policy-export-plan
  [policy]
  (when (and (nil? (:insurance.policy/exporter-id policy))
             (legacy-policy? policy))
    (let [descriptor (exporters/descriptor exporters/harmonia-v1)
          by-name    (group-by :insurance.coverage.type/name
                               (:insurance.policy/coverage-types policy))
          role-matches (mapv (partial role-match by-name)
                             (:roles descriptor))
          mapping-txs (->> role-matches
                           (filter #(= :matched (:status %)))
                           (mapv mapping-tx))
          missing-roles (->> role-matches
                             (filter #(= :missing (:status %)))
                             (mapv :role))
          ambiguous-roles (->> role-matches
                               (filter #(= :ambiguous (:status %)))
                               (mapv :role))]
      {:tx (cond-> {:db/id                        (:db/id policy)
                    :insurance.policy/exporter-id exporters/harmonia-v1}
             (seq mapping-txs)
             (assoc :insurance.policy/export-mappings mapping-txs))
       :incomplete
       (when (or (seq missing-roles) (seq ambiguous-roles))
         {:policy-id       (:insurance.policy/policy-id policy)
          :missing-roles   missing-roles
          :ambiguous-roles ambiguous-roles})})))

(defn plan-legacy-metadata
  "Plans legacy insurance metadata transactions and reports migration counts."
  [db]
  (let [policies              (policies db)
        coverage-txs          (coverage-metadata-txs policies)
        required-coverage-txs (required-coverage-txs policies)
        policy-plans          (into [] (keep policy-export-plan) policies)]
    {:tx-data (into coverage-txs
                    (concat required-coverage-txs (map :tx policy-plans)))
     :migrated-coverage-type-count (count coverage-txs)
     :configured-policy-count      (count policy-plans)
     :incomplete-policies          (into [] (keep :incomplete) policy-plans)}))

(defn tx-data
  "Returns the legacy insurance metadata transaction data for Stork."
  [conn]
  (let [{:keys [configured-policy-count
                incomplete-policies
                migrated-coverage-type-count
                tx-data]}
        (plan-legacy-metadata (d/db conn))]
    (μ/log ::migration-planned
           :configured-policy-count configured-policy-count
           :incomplete-policy-count (count incomplete-policies)
           :incomplete-policy-ids (mapv :policy-id incomplete-policies)
           :migrated-coverage-type-count migrated-coverage-type-count)
    tx-data))
