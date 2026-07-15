(ns app.insurance.policy.settings.queries-test
  (:require
   [app.insurance.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def omit-currency ::omit-currency)

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn category-factor-tx
  [category-tempid factor]
  {:insurance.category.factor/category-factor-id (random-uuid)
   :insurance.category.factor/category           category-tempid
   :insurance.category.factor/factor             factor})

(defn seed-settings-policy!
  [conn policy-id {:keys [category-factors currency]
                   :or   {category-factors {"brass"    0.10M
                                            "woodwind" 0.20M}
                          currency         :currency/EUR}}]
  (let [brass-id      (random-uuid)
        woodwind-id   (random-uuid)
        percussion-id (random-uuid)
        basic-type-id (random-uuid)
        extended-id   (random-uuid)
        unused-id     (random-uuid)
        policy-tx     (cond-> {:insurance.policy/policy-id           policy-id
                               :insurance.policy/name                "Insurance 2026"
                               :insurance.policy/status              :insurance.policy.status/draft
                               :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
                               :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
                               :insurance.policy/premium-factor      0.01M
                               :insurance.policy/coverage-types      ["basic" "extended" "unused"]
                               :insurance.policy/category-factors    (mapv (fn [[category-tempid factor]]
                                                                             (category-factor-tx category-tempid factor))
                                                                           category-factors)
                               :insurance.policy/covered-instruments ["alto-coverage"
                                                                      "bass-coverage"
                                                                      "trumpet-coverage"]}
                        (not= omit-currency currency)
                        (assoc :insurance.policy/currency currency))]
    @(d/transact
      conn
      [{:db/id                           "brass"
        :instrument.category/category-id brass-id
        :instrument.category/name        "Brass"
        :instrument.category/code        (str "brass-" policy-id)}
       {:db/id                           "woodwind"
        :instrument.category/category-id woodwind-id
        :instrument.category/name        "Woodwind"
        :instrument.category/code        (str "woodwind-" policy-id)}
       {:db/id                           "percussion"
        :instrument.category/category-id percussion-id
        :instrument.category/name        "Percussion"
        :instrument.category/code        (str "percussion-" policy-id)}
       {:db/id                                  "basic"
        :insurance.coverage.type/type-id        basic-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                                  "extended"
        :insurance.coverage.type/type-id        extended-id
        :insurance.coverage.type/name           "Extended"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 0.5M}
       {:db/id                                  "unused"
        :insurance.coverage.type/type-id        unused-id
        :insurance.coverage.type/name           "Unused"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 0.25M}
       {:db/id                    "alto-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Alto Horn"
        :instrument/category      "brass"}
       {:db/id                    "bass-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Bass Clarinet"
        :instrument/category      "woodwind"}
       {:db/id                    "trumpet-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Trumpet"
        :instrument/category      "brass"}
       {:db/id                           "alto-coverage"
        :instrument.coverage/coverage-id (random-uuid)
        :instrument.coverage/instrument  "alto-instrument"
        :instrument.coverage/types       ["basic"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       1000M
        :instrument.coverage/item-count  2
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:db/id                           "bass-coverage"
        :instrument.coverage/coverage-id (random-uuid)
        :instrument.coverage/instrument  "bass-instrument"
        :instrument.coverage/types       ["basic" "extended"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       2000M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:db/id                           "trumpet-coverage"
        :instrument.coverage/coverage-id (random-uuid)
        :instrument.coverage/instrument  "trumpet-instrument"
        :instrument.coverage/types       ["extended"]
        :instrument.coverage/private?    true
        :instrument.coverage/value       3000M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      :instrument.coverage.status/coverage-active
        :instrument.coverage/change      :instrument.coverage.change/none}
       policy-tx])
    {:brass-id      brass-id
     :woodwind-id   woodwind-id
     :percussion-id percussion-id
     :basic-type-id basic-type-id
     :extended-id   extended-id
     :unused-id     unused-id}))

(defn configure-exporter!
  [conn policy-id mappings]
  (try
    @(d/transact
      conn
      [{:db/id                            [:insurance.policy/policy-id policy-id]
        :insurance.policy/exporter-id     :insurance/exporter-harmonia-v1
        :insurance.policy/export-mappings
        (mapv (fn [[role coverage-type-id]]
                {:insurance.export.mapping/role role
                 :insurance.export.mapping/coverage-type
                 [:insurance.coverage.type/type-id coverage-type-id]})
              mappings)}])
    :accepted
    (catch Exception _
      :rejected)))

(defn coverage-type-summary
  [settings]
  (mapv #(select-keys % [:name :usage-count :current-cost])
        (:coverage-type-rows settings)))

(defn coverage-impact-summary
  [settings]
  {:coverage-counts (:coverage-counts settings)
   :coverage-type-rows
   (mapv #(select-keys % [:name :missing-coverage-counts])
         (:coverage-type-rows settings))})

(defn exporter-summary
  [settings]
  {:exporter-options       (:exporter-options settings)
   :exporter-configuration (:exporter-configuration settings)})

(defn category-factor-summary
  [settings]
  (mapv #(select-keys % [:category-name :usage-count :current-cost])
        (:category-factor-rows settings)))

(defn category-names
  [rows]
  (mapv :category-name rows))

(defn settings-summary
  [settings]
  {:editable?            (:editable? settings)
   :supported-currencies (:supported-currencies settings)
   :policy-details       (select-keys (:policy-details settings)
                                      [:name :currency :premium-factor :status])
   :coverage-type-rows   (coverage-type-summary settings)
   :category-factor-rows (category-factor-summary settings)
   :available-categories (category-names (:available-categories settings))
   :unused-categories    (category-names (:unused-categories settings))
   :current-totals       (:current-totals settings)
   :warnings             (:warnings settings)})

(deftest policy-settings-complete-factor-read-model-test
  (testing "returns setup rows, totals, and editability for a draft policy with complete used factors"
    (let [{:keys [conn member-id]} (tc/new-system "insurance-settings-query-complete")
          policy-id                (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-settings-policy! conn policy-id {})
      (let [result (queries/policy-settings (d/db conn)
                                            policy-id
                                            {:current-member-id member-id})]
        (is (= {:editable?            true
                :supported-currencies [:EUR :USD]
                :policy-details       {:name           "Insurance 2026"
                                       :currency       :EUR
                                       :premium-factor 0.01M
                                       :status         :insurance.policy.status/draft}
                :coverage-type-rows   [{:name "Basic" :usage-count 2 :current-cost 6.0M}
                                       {:name "Extended" :usage-count 2 :current-cost 3.5M}
                                       {:name "Unused" :usage-count 0 :current-cost 0M}]
                :category-factor-rows [{:category-name "Brass" :usage-count 2 :current-cost 3.5M}
                                       {:category-name "Woodwind" :usage-count 1 :current-cost 6.0M}]
                :available-categories ["Brass" "Percussion" "Woodwind"]
                :unused-categories    ["Percussion"]
                :current-totals       {:total-instruments   3
                                       :total-insured-value 7000M
                                       :total-cost          9.5M}
                :warnings             []}
               (settings-summary result)))))))

(deftest policy-settings-missing-factor-read-model-test
  (testing "skips costs for used categories without a factor and reports one warning"
    (let [{:keys [conn member-id]} (tc/new-system "insurance-settings-query-missing-factor")
          policy-id                (random-uuid)
          {:keys [woodwind-id]}    (do
                                     (seed-insurance-team! conn member-id)
                                     (seed-settings-policy! conn
                                                            policy-id
                                                            {:category-factors {"brass" 0.10M}}))
          result                   (queries/policy-settings (d/db conn)
                                                            policy-id
                                                            {:current-member-id member-id})]
      (is (= {:coverage-type-rows [{:name "Basic" :usage-count 2 :current-cost 2.0M}
                                   {:name "Extended" :usage-count 2 :current-cost 1.5M}
                                   {:name "Unused" :usage-count 0 :current-cost 0M}]
              :category-factor-rows [{:category-name "Brass" :usage-count 2 :current-cost 3.5M}]
              :unused-categories ["Percussion" "Woodwind"]
              :current-totals    {:total-instruments   3
                                  :total-insured-value 7000M
                                  :total-cost          3.5M}
              :warnings          [{:type           :missing-category-factors
                                   :category-ids   [woodwind-id]
                                   :category-names ["Woodwind"]}]}
             {:coverage-type-rows   (coverage-type-summary result)
              :category-factor-rows (category-factor-summary result)
              :unused-categories    (category-names (:unused-categories result))
              :current-totals       (:current-totals result)
              :warnings             (:warnings result)})))))

(deftest policy-settings-nil-currency-falls-back-test
  (testing "returns EUR in policy details when a policy has no currency"
    (let [{:keys [conn]} (tc/new-system "insurance-settings-query-nil-currency")
          policy-id      (random-uuid)]
      (seed-settings-policy! conn policy-id {:currency omit-currency})
      (is (= :EUR
             (get-in (queries/policy-settings (d/db conn) policy-id)
                     [:policy-details :currency]))))))

(deftest policy-settings-coverage-impact-read-model-test
  (testing "counts private and band coverages affected by type backfills"
    (let [{:keys [conn]} (tc/new-system "insurance-settings-query-impact")
          policy-id      (random-uuid)]
      (seed-settings-policy! conn policy-id {})
      (is (= {:coverage-counts {:total 3
                                :private 1
                                :band 2}
              :coverage-type-rows
              [{:name "Basic"
                :missing-coverage-counts {:total 1
                                          :private 1
                                          :band 0}}
               {:name "Extended"
                :missing-coverage-counts {:total 1
                                          :private 0
                                          :band 1}}
               {:name "Unused"
                :missing-coverage-counts {:total 3
                                          :private 1
                                          :band 2}}]}
             (coverage-impact-summary
              (queries/policy-settings (d/db conn) policy-id)))))))

(deftest policy-settings-unconfigured-exporter-read-model-test
  (testing "offers registered exporter versions when the policy has none"
    (let [{:keys [conn]} (tc/new-system "insurance-settings-query-no-exporter")
          policy-id      (random-uuid)]
      (seed-settings-policy! conn policy-id {})
      (is (= {:exporter-options
              [{:exporter-id :insurance/exporter-harmonia-v1
                :label-key
                :insurance/exporter-harmonia-v1
                :role-rows
                [{:role
                  :insurance.exporter.harmonia-v1/overnight-vehicle
                  :label-key :insurance/exporter-role-overnight-vehicle
                  :required? true}
                 {:role
                  :insurance.exporter.harmonia-v1/unattended-building
                  :label-key :insurance/exporter-role-unattended-building
                  :required? true}]}]
              :exporter-configuration
              {:exporter-id nil
               :status      :not-configured
               :role-rows   []}}
             (exporter-summary
              (queries/policy-settings (d/db conn) policy-id)))))))

(deftest policy-settings-configured-exporter-read-model-test
  (testing "resolves exporter roles to policy coverage types without using names"
    (let [{:keys [conn]} (tc/new-system "insurance-settings-query-exporter")
          policy-id      (random-uuid)
          {:keys [basic-type-id extended-id]}
          (seed-settings-policy! conn policy-id {})
          seed-status
          (configure-exporter!
           conn
           policy-id
           [[:insurance.exporter.harmonia-v1/overnight-vehicle extended-id]
            [:insurance.exporter.harmonia-v1/unattended-building
             basic-type-id]])]
      (is (= {:seed-status :accepted
              :read-model
              {:exporter-options
               [{:exporter-id :insurance/exporter-harmonia-v1
                 :label-key
                 :insurance/exporter-harmonia-v1
                 :role-rows
                 [{:role
                   :insurance.exporter.harmonia-v1/overnight-vehicle
                   :label-key :insurance/exporter-role-overnight-vehicle
                   :required? true}
                  {:role
                   :insurance.exporter.harmonia-v1/unattended-building
                   :label-key :insurance/exporter-role-unattended-building
                   :required? true}]}]
               :exporter-configuration
               {:exporter-id :insurance/exporter-harmonia-v1
                :status      :complete
                :role-rows
                [{:role
                  :insurance.exporter.harmonia-v1/overnight-vehicle
                  :label-key
                  :insurance/exporter-role-overnight-vehicle
                  :required?        true
                  :coverage-type-id extended-id
                  :coverage-type-name "Extended"}
                 {:role
                  :insurance.exporter.harmonia-v1/unattended-building
                  :label-key
                  :insurance/exporter-role-unattended-building
                  :required?        true
                  :coverage-type-id basic-type-id
                  :coverage-type-name "Basic"}]}}}
             {:seed-status seed-status
              :read-model  (when (= :accepted seed-status)
                             (exporter-summary
                              (queries/policy-settings
                               (d/db conn)
                               policy-id)))})))))
