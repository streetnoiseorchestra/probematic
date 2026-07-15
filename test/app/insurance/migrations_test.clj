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
                          [{:role             :insurance.exporter.harmonia-v1/overnight-vehicle
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
                :exporter
                {:exporter-id harmonia-exporter-id
                 :mappings
                 {:insurance.exporter.harmonia-v1/overnight-vehicle
                  "Optional"}}}
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
                :exporter
                {:exporter-id harmonia-exporter-id
                 :mappings
                 {:insurance.exporter.harmonia-v1/overnight-vehicle
                  "Nachzeit im Auto"
                  :insurance.exporter.harmonia-v1/unattended-building
                  "Proberaum"}}}
               {:result   (dissoc result :tx-data)
                :coverage (coverage-metadata-by-name (d/db conn) policy-id)
                :exporter (exporter-summary (d/db conn) policy-id)}))))))

(deftest legacy-required-type-migration-updates-active-coverages-test
  (testing "required metadata and coverage membership migrate together"
    (let [{:keys [conn]} (tc/new-system
                          "insurance-migration-required-coverages")
          policy-id      (random-uuid)
          coverage-types (seed-legacy-policy!
                          conn
                          policy-id
                          ["Grundschutz" "Nachzeit im Auto" "Proberaum"])
          required-id    (:type-id
                          (some #(when (= "Grundschutz" (:name %)) %)
                                coverage-types))
          coverage-ids   {:unchanged (random-uuid)
                          :new       (random-uuid)
                          :removed   (random-uuid)
                          :present   (random-uuid)}]
      @(d/transact
        conn
        (into
         [{:db/id                           "legacy-unchanged"
           :instrument.coverage/coverage-id (:unchanged coverage-ids)
           :instrument.coverage/private?    false
           :instrument.coverage/status
           :instrument.coverage.status/reviewed
           :instrument.coverage/change
           :instrument.coverage.change/none}
          {:db/id                           "legacy-new"
           :instrument.coverage/coverage-id (:new coverage-ids)
           :instrument.coverage/private?    true
           :instrument.coverage/status
           :instrument.coverage.status/reviewed
           :instrument.coverage/change
           :instrument.coverage.change/new}
          {:db/id                           "legacy-removed"
           :instrument.coverage/coverage-id (:removed coverage-ids)
           :instrument.coverage/private?    false
           :instrument.coverage/status
           :instrument.coverage.status/reviewed
           :instrument.coverage/change
           :instrument.coverage.change/removed}
          {:db/id                           "legacy-present"
           :instrument.coverage/coverage-id (:present coverage-ids)
           :instrument.coverage/private?    true
           :instrument.coverage/types
           [[:insurance.coverage.type/type-id required-id]]
           :instrument.coverage/status
           :instrument.coverage.status/reviewed
           :instrument.coverage/change
           :instrument.coverage.change/none}]
         (mapv (fn [[state _coverage-id]]
                 [:db/add
                  [:insurance.policy/policy-id policy-id]
                  :insurance.policy/covered-instruments
                  (str "legacy-" (name state))])
               coverage-ids)))
      (apply-plan! conn)
      (let [db        (d/db conn)
            summaries
            (into
             {}
             (map (fn [[state coverage-id]]
                    (let [coverage
                          (d/pull
                           db
                           '[:instrument.coverage/status
                             :instrument.coverage/change
                             {:instrument.coverage/types
                              [:insurance.coverage.type/type-id]}]
                           [:instrument.coverage/coverage-id coverage-id])]
                      [state
                       {:status (:instrument.coverage/status coverage)
                        :change (:instrument.coverage/change coverage)
                        :required?
                        (contains?
                         (set (map :insurance.coverage.type/type-id
                                   (:instrument.coverage/types coverage)))
                         required-id)}])))
             coverage-ids)]
        (is (= {:unchanged
                {:status    :instrument.coverage.status/needs-review
                 :change    :instrument.coverage.change/changed
                 :required? true}
                :new
                {:status    :instrument.coverage.status/needs-review
                 :change    :instrument.coverage.change/new
                 :required? true}
                :removed
                {:status    :instrument.coverage.status/reviewed
                 :change    :instrument.coverage.change/removed
                 :required? false}
                :present
                {:status    :instrument.coverage.status/reviewed
                 :change    :instrument.coverage.change/none
                 :required? true}}
               summaries))))))

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
                  :ambiguous-roles
                  [:insurance.exporter.harmonia-v1/overnight-vehicle]}
                 {:policy-id      missing-id
                  :missing-roles
                  [:insurance.exporter.harmonia-v1/unattended-building]
                  :ambiguous-roles []}]
                :ambiguous-exporter
                {:exporter-id harmonia-exporter-id
                 :mappings
                 {:insurance.exporter.harmonia-v1/unattended-building
                  "Proberaum"}}
                :missing-exporter
                {:exporter-id harmonia-exporter-id
                 :mappings
                 {:insurance.exporter.harmonia-v1/overnight-vehicle
                  "Nachzeit im Auto"}}
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
