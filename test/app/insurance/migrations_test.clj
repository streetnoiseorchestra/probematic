(ns app.insurance.migrations-test
  (:require
   [app.insurance.migrations :as migrations]
   [app.insurance.test-support :as test-support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def harmonia-exporter-id
  :insurance/exporter-harmonia-v1)

(defn coverage-type
  [name]
  {:type-id        (random-uuid)
   :name           name
   :description    (str name " description")
   :premium-factor 0.25M})

(defn seed-legacy-policy!
  [conn policy-id names]
  (let [coverage-types (mapv coverage-type names)]
    (test-support/seed-policy!
     conn
     policy-id
     {:coverage-types coverage-types})
    coverage-types))

(defn apply-plan!
  [conn]
  (migrations/migrate-legacy-metadata! conn))

(defn coverage-metadata-by-name
  [db policy-id]
  (->> (d/pull db
               '[{:insurance.policy/coverage-types
                  [:insurance.coverage.type/name
                   :insurance.coverage.type/icon
                   :insurance.coverage.type/required?]}]
               [:insurance.policy/policy-id policy-id])
       :insurance.policy/coverage-types
       (map (fn [coverage-type]
              [(:insurance.coverage.type/name coverage-type)
               (select-keys coverage-type
                            [:insurance.coverage.type/icon
                             :insurance.coverage.type/required?])]))
       (into {})))

(defn exporter-summary
  [db policy-id]
  (let [policy (d/pull
                db
                '[:insurance.policy/exporter-id
                  {:insurance.policy/export-mappings
                   [:insurance.export.mapping/role
                    {:insurance.export.mapping/coverage-type
                     [:insurance.coverage.type/name]}]}]
                [:insurance.policy/policy-id policy-id])]
    {:exporter-id (:insurance.policy/exporter-id policy)
     :mappings    (->> (:insurance.policy/export-mappings policy)
                       (map (fn [mapping]
                              [(:insurance.export.mapping/role mapping)
                               (get-in mapping
                                       [:insurance.export.mapping/coverage-type
                                        :insurance.coverage.type/name])]))
                       (into {}))}))

(defn metadata-transaction
  [conn policy-id opts]
  (try
    (test-support/seed-policy! conn policy-id opts)
    {:status :accepted}
    (catch Exception e
      {:status :rejected
       :error  (ex-message e)})))

(deftest fresh-schema-and-enriched-policy-test
  (testing "accepts explicit coverage and exporter metadata and leaves it unchanged"
    (let [{:keys [conn]} (tc/new-system "insurance-migration-fresh")
          policy-id      (random-uuid)
          required-id    (random-uuid)
          optional-id    (random-uuid)
          opts           {:coverage-types
                          [{:type-id        required-id
                            :name           "Required"
                            :description    "Required description"
                            :premium-factor 1.0M
                            :icon           :phosphor/shield
                            :required?      true}
                           {:type-id        optional-id
                            :name           "Optional"
                            :description    "Optional description"
                            :premium-factor 0.25M
                            :icon           :phosphor/star
                            :required?      false}]
                          :exporter-id harmonia-exporter-id
                          :export-mappings
                          [{:role             :overnight-vehicle
                            :coverage-type-id optional-id}]}
          tx-result      (metadata-transaction conn policy-id opts)]
      (is (= :accepted (:status tx-result)) (:error tx-result))
      (when (= :accepted (:status tx-result))
        (is (= {:fresh-plan {:tx-data                     []
                             :migrated-coverage-type-count 0
                             :configured-policy-count      0
                             :incomplete-policies          []}
                :coverage   {"Required"
                             {:insurance.coverage.type/icon      :phosphor/shield
                              :insurance.coverage.type/required? true}
                             "Optional"
                             {:insurance.coverage.type/icon      :phosphor/star
                              :insurance.coverage.type/required? false}}
                :exporter   {:exporter-id harmonia-exporter-id
                             :mappings    {:overnight-vehicle "Optional"}}}
               {:fresh-plan (migrations/plan-legacy-metadata (d/db conn))
                :coverage   (coverage-metadata-by-name (d/db conn) policy-id)
                :exporter   (exporter-summary (d/db conn) policy-id)}))))))

(deftest explicit-metadata-without-exporter-remains-unconfigured-test
  (testing "an intentional no-exporter policy is not mistaken for legacy data"
    (let [{:keys [conn]} (tc/new-system "insurance-migration-no-exporter")
          policy-id      (random-uuid)
          coverage-types
          [{:type-id        (random-uuid)
            :name           "Grundschutz"
            :description    "Configured by an administrator"
            :premium-factor 1.0M
            :icon           :phosphor/star
            :required?      false}]]
      (test-support/seed-policy!
       conn
       policy-id
       {:coverage-types coverage-types})
      (is (= {:plan     {:tx-data                     []
                         :migrated-coverage-type-count 0
                         :configured-policy-count      0
                         :incomplete-policies          []}
              :exporter {:exporter-id nil
                         :mappings    {}}}
             {:plan     (migrations/migrate-legacy-metadata! conn)
              :exporter (exporter-summary (d/db conn) policy-id)})))))

(deftest known-legacy-policy-migration-test
  (testing "backfills known metadata and a complete v1 exporter configuration"
    (let [{:keys [conn]} (tc/new-system "insurance-migration-known")
          policy-id      (random-uuid)]
      (seed-legacy-policy!
       conn
       policy-id
       ["Grundschutz" "Nachzeit im Auto" "Proberaum"])
      (let [result (apply-plan! conn)]
        (is (= {:result   {:migrated-coverage-type-count 3
                           :configured-policy-count      1
                           :incomplete-policies          []}
                :coverage {"Grundschutz"
                           {:insurance.coverage.type/icon      :phosphor/shield
                            :insurance.coverage.type/required? true}
                           "Nachzeit im Auto"
                           {:insurance.coverage.type/icon      :phosphor/car-profile
                            :insurance.coverage.type/required? false}
                           "Proberaum"
                           {:insurance.coverage.type/icon      :phosphor/warehouse
                            :insurance.coverage.type/required? false}}
                :exporter {:exporter-id harmonia-exporter-id
                           :mappings    {:overnight-vehicle   "Nachzeit im Auto"
                                         :unattended-building "Proberaum"}}}
               {:result   (dissoc result :tx-data)
                :coverage (coverage-metadata-by-name (d/db conn) policy-id)
                :exporter (exporter-summary (d/db conn) policy-id)}))))))

(deftest ambiguous-and-missing-legacy-mappings-test
  (testing "reports ambiguous and missing roles without guessing a mapping"
    (let [{:keys [conn]}  (tc/new-system "insurance-migration-incomplete")
          ambiguous-id   (random-uuid)
          missing-id     (random-uuid)]
      (seed-legacy-policy!
       conn
       ambiguous-id
       ["Grundschutz" "Nachzeit im Auto" "Nachzeit im Auto" "Proberaum"])
      (seed-legacy-policy!
       conn
       missing-id
       ["Grundschutz" "Nachzeit im Auto" "Touring"])
      (let [result (apply-plan! conn)]
        (is (= {:incomplete
                [{:policy-id      ambiguous-id
                  :missing-roles  []
                  :ambiguous-roles [:overnight-vehicle]}
                 {:policy-id      missing-id
                  :missing-roles  [:unattended-building]
                  :ambiguous-roles []}]
                :ambiguous-exporter
                {:exporter-id harmonia-exporter-id
                 :mappings    {:unattended-building "Proberaum"}}
                :missing-exporter
                {:exporter-id harmonia-exporter-id
                 :mappings    {:overnight-vehicle "Nachzeit im Auto"}}
                :unknown-metadata {}}
               {:incomplete         (:incomplete-policies result)
                :ambiguous-exporter (exporter-summary (d/db conn) ambiguous-id)
                :missing-exporter   (exporter-summary (d/db conn) missing-id)
                :unknown-metadata   (get (coverage-metadata-by-name
                                          (d/db conn)
                                          missing-id)
                                         "Touring")}))))))

(deftest legacy-migration-is-idempotent-test
  (testing "a second execution produces no transaction or metadata changes"
    (let [{:keys [conn]} (tc/new-system "insurance-migration-repeat")
          policy-id      (random-uuid)]
      (seed-legacy-policy!
       conn
       policy-id
       ["Grundschutz" "Nachzeit im Auto" "Proberaum"])
      (let [first-result  (apply-plan! conn)
            first-summary {:coverage (coverage-metadata-by-name (d/db conn) policy-id)
                           :exporter (exporter-summary (d/db conn) policy-id)}
            second-result (apply-plan! conn)]
        (is (= {:first-counts  {:migrated-coverage-type-count 3
                                :configured-policy-count      1
                                :incomplete-policies          []}
                :second-result {:tx-data                     []
                                :migrated-coverage-type-count 0
                                :configured-policy-count      0
                                :incomplete-policies          []}
                :unchanged?    true}
               {:first-counts  (dissoc first-result :tx-data)
                :second-result second-result
                :unchanged?    (= first-summary
                                  {:coverage (coverage-metadata-by-name
                                              (d/db conn)
                                              policy-id)
                                   :exporter (exporter-summary
                                              (d/db conn)
                                              policy-id)})}))))))
