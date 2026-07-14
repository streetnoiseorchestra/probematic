(ns app.insurance.actions-test
  (:require
   [app.insurance.actions :as actions]
   [app.insurance.test-support :as insurance-support]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(deftest delete-policy-action-test
  (testing "returns a retract effect when the policy has no open surveys"
    (let [{:keys [conn]} (tc/new-system "insurance-delete-action")
          policy-id      (random-uuid)]
      (insurance-support/seed-policy! conn policy-id)
      (is (= [[:db/transact
               [[:db/retractEntity [:insurance.policy/policy-id policy-id]]]
               {}]
              support/clear-loading]
             (actions/delete-policy-action
              {:db (d/db conn)}
              {:targetid (str policy-id)}))))))

(deftest delete-policy-action-does-not-retract-policy-with-open-survey-test
  (testing "keeps the policy when an open survey still references it"
    (let [{:keys [conn member-id]} (tc/new-system "insurance-delete-action-open-survey")
          policy-id                (random-uuid)]
      (insurance-support/seed-policy! conn policy-id)
      (insurance-support/seed-survey! conn {:member-id member-id
                                            :policy-id policy-id})
      (let [effects (actions/delete-policy-action
                     {:db (d/db conn)}
                     {:targetid (str policy-id)})]
        (is (= [support/clear-loading] effects))
        (is (not-any? #(= :db/transact (first %)) effects))))))

(deftest duplicate-policy-action-uses-targetid-test
  (let [{:keys [conn]} (tc/new-system "insurance-duplicate-action")
        policy-id      (random-uuid)]
    @(d/transact conn [{:insurance.policy/policy-id       policy-id
                        :insurance.policy/name            "2026"
                        :insurance.policy/status          :insurance.policy.status/draft
                        :insurance.policy/currency        :currency/EUR
                        :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
                        :insurance.policy/effective-until #inst "2026-12-31T00:00:00.000-00:00"
                        :insurance.policy/premium-factor  0.01M}])
    (let [[db-effect _clear-loading redirect-effect]
          (actions/duplicate-policy-action
           {:db (d/db conn)
            :tr (constantly "Duplicate")}
           {:targetid (str policy-id)})
          [_ tx-data opts] db-effect
          policy-tx         (last tx-data)
          [_ redirect-url]  redirect-effect]
      (is (= {} opts))
      (is (= "Duplicate 2026" (:insurance.policy/name policy-tx)))
      (is (uuid? (:insurance.policy/policy-id policy-tx)))
      (is (not= policy-id (:insurance.policy/policy-id policy-tx)))
      (is (= [:app.datastar/redirect]
             (subvec redirect-effect 0 1)))
      (is (re-find #"^/insurance-policy/.+/$" redirect-url)))))

(deftest duplicate-policy-tx-data-test
  (let [new-policy-id (random-uuid)
        old-type-id   (random-uuid)
        old-policy    {:insurance.policy/name            "2026"
                       :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
                       :insurance.policy/effective-until #inst "2026-12-31T00:00:00.000-00:00"
                       :insurance.policy/currency        :currency/EUR
                       :insurance.policy/premium-factor  0.01M
                       :insurance.policy/category-factors
                       [{:insurance.category.factor/category {:instrument.category/category-id (random-uuid)}
                         :insurance.category.factor/factor   0.2M}]
                       :insurance.policy/coverage-types
                       [{:insurance.coverage.type/type-id        old-type-id
                         :insurance.coverage.type/name           "Basic"
                         :insurance.coverage.type/description    nil
                         :insurance.coverage.type/premium-factor 1.0M}]
                       :insurance.policy/covered-instruments
                       [{:instrument.coverage/instrument {:instrument/instrument-id (random-uuid)}
                         :instrument.coverage/types      [{:insurance.coverage.type/type-id old-type-id}]
                         :instrument.coverage/private?   true
                         :instrument.coverage/status     :instrument.coverage.status/needs-review
                         :instrument.coverage/change     :instrument.coverage.change/new
                         :instrument.coverage/value      1000M}]}
        tx-data       (actions/duplicate-policy-tx-data "Duplicate" new-policy-id old-policy)
        category-tx   (first tx-data)
        type-tx       (second tx-data)
        coverage-tx   (nth tx-data 2)
        policy-tx     (last tx-data)]
    (is (= 4 (count tx-data)))
    (is (uuid? (:insurance.category.factor/category-factor-id category-tx)))
    (is (uuid? (:insurance.coverage.type/type-id type-tx)))
    (is (not (contains? type-tx :insurance.coverage.type/description)))
    (is (uuid? (:instrument.coverage/coverage-id coverage-tx)))
    (is (= [(:db/id type-tx)]
           (:instrument.coverage/types coverage-tx)))
    (is (= new-policy-id (:insurance.policy/policy-id policy-tx)))
    (is (= "Duplicate 2026" (:insurance.policy/name policy-tx)))
    (is (= :insurance.policy.status/draft (:insurance.policy/status policy-tx)))
    (is (= [(:db/id type-tx)] (:insurance.policy/coverage-types policy-tx)))
    (is (= [(:db/id category-tx)] (:insurance.policy/category-factors policy-tx)))
    (is (= [(:db/id coverage-tx)] (:insurance.policy/covered-instruments policy-tx)))))

(deftest duplicate-policy-tx-data-clones-insurance-metadata-test
  (let [new-policy-id (random-uuid)
        overnight-id  (random-uuid)
        building-id   (random-uuid)
        overnight-type
        {:insurance.coverage.type/type-id        overnight-id
         :insurance.coverage.type/name           "Worldwide touring renamed"
         :insurance.coverage.type/description    "Tour coverage"
         :insurance.coverage.type/premium-factor 0.2M
         :insurance.coverage.type/icon           :phosphor/car-profile
         :insurance.coverage.type/required?      true}
        building-type
        {:insurance.coverage.type/type-id        building-id
         :insurance.coverage.type/name           "Locked storage renamed"
         :insurance.coverage.type/description    "Storage coverage"
         :insurance.coverage.type/premium-factor 0.3M
         :insurance.coverage.type/icon           :phosphor/warehouse
         :insurance.coverage.type/required?      false}
        old-policy
        {:insurance.policy/name            "2026"
         :insurance.policy/currency        :currency/EUR
         :insurance.policy/premium-factor  0.01M
         :insurance.policy/coverage-types  [overnight-type building-type]
         :insurance.policy/exporter-id     :insurance.exporter/inventory-xls-v1
         :insurance.policy/export-mappings
         [{:insurance.export.mapping/role :overnight-vehicle
           :insurance.export.mapping/coverage-type overnight-type}
          {:insurance.export.mapping/role :unattended-building
           :insurance.export.mapping/coverage-type building-type}]}
        tx-data      (actions/duplicate-policy-tx-data
                      "Duplicate"
                      new-policy-id
                      old-policy)
        type-txs     (filterv :insurance.coverage.type/type-id tx-data)
        type-name->tempid
        (into {}
              (map (juxt :insurance.coverage.type/name :db/id))
              type-txs)
        mapping-txs  (filterv :insurance.export.mapping/role tx-data)
        policy-tx    (some #(when (= new-policy-id
                                     (:insurance.policy/policy-id %))
                              %)
                           tx-data)]
    (testing "coverage metadata and policy-owned mappings point to cloned types"
      (is (= {:coverage-types
              #{{:name      "Worldwide touring renamed"
                 :icon      :phosphor/car-profile
                 :required? true}
                {:name      "Locked storage renamed"
                 :icon      :phosphor/warehouse
                 :required? false}}
              :exporter-id :insurance.exporter/inventory-xls-v1
              :mapping-targets
              {:overnight-vehicle
               (get type-name->tempid "Worldwide touring renamed")
               :unattended-building
               (get type-name->tempid "Locked storage renamed")}
              :policy-mapping-refs (set (map :db/id mapping-txs))}
             {:coverage-types
              (set (map (fn [tx]
                          {:name      (:insurance.coverage.type/name tx)
                           :icon      (:insurance.coverage.type/icon tx)
                           :required? (:insurance.coverage.type/required? tx)})
                        type-txs))
              :exporter-id (:insurance.policy/exporter-id policy-tx)
              :mapping-targets
              (into {}
                    (map (juxt :insurance.export.mapping/role
                               :insurance.export.mapping/coverage-type))
                    mapping-txs)
              :policy-mapping-refs
              (set (:insurance.policy/export-mappings policy-tx))})))))

(def insurance-metadata-attributes
  [:insurance.coverage.type/icon
   :insurance.coverage.type/required?
   :insurance.policy/exporter-id
   :insurance.policy/export-mappings
   :insurance.export.mapping/role
   :insurance.export.mapping/coverage-type])

(deftest duplicate-policy-action-persists-cloned-insurance-metadata-test
  (let [{:keys [conn member-id]} (tc/new-system
                                  "insurance-duplicate-metadata-action")
        db            (d/db conn)
        schema-status (if (every? #(d/entid db %)
                                  insurance-metadata-attributes)
                        :available
                        :missing)]
    (is (= :available schema-status))
    (when (= :available schema-status)
      (let [policy-id    (random-uuid)
            overnight-id (random-uuid)
            building-id  (random-uuid)
            coverage-types
            [{:type-id        overnight-id
              :name           "Worldwide touring renamed"
              :description    "Tour coverage"
              :premium-factor 0.2M
              :icon           :phosphor/car-profile
              :required?      true}
             {:type-id        building-id
              :name           "Locked storage renamed"
              :description    "Storage coverage"
              :premium-factor 0.3M
              :icon           :phosphor/warehouse
              :required?      false}]
            _ (insurance-support/seed-policy!
               conn
               policy-id
               {:coverage-types coverage-types
                :exporter-id    :insurance.exporter/inventory-xls-v1
                :export-mappings
                [{:role             :overnight-vehicle
                  :coverage-type-id overnight-id}
                 {:role             :unattended-building
                  :coverage-type-id building-id}]})
            effects (actions/duplicate-policy-action
                     {:current-member-id member-id
                      :db                (d/db conn)
                      :tr                (constantly "Duplicate")}
                     {:targetid (str policy-id)})
            tx-data (second (first effects))
            new-policy-id (some :insurance.policy/policy-id tx-data)]
        @(d/transact conn tx-data)
        (let [cloned-policy (q/retrieve-policy (d/db conn) new-policy-id)
              cloned-types  (:insurance.policy/coverage-types cloned-policy)
              cloned-type-ids
              (set (map :insurance.coverage.type/type-id cloned-types))]
          (is (= {:source-type-ids #{overnight-id building-id}
                  :cloned-type-ids-disjoint? true
                  :cloned-metadata
                  #{{:name      "Worldwide touring renamed"
                     :icon      :phosphor/car-profile
                     :required? true}
                    {:name      "Locked storage renamed"
                     :icon      :phosphor/warehouse
                     :required? false}}
                  :exporter-id :insurance.exporter/inventory-xls-v1
                  :mappings
                  #{[:overnight-vehicle true]
                    [:unattended-building true]}}
                 {:source-type-ids #{overnight-id building-id}
                  :cloned-type-ids-disjoint?
                  (empty? (set/intersection
                           #{overnight-id building-id}
                           cloned-type-ids))
                  :cloned-metadata
                  (set (map (fn [coverage-type]
                              {:name (:insurance.coverage.type/name
                                      coverage-type)
                               :icon (:insurance.coverage.type/icon
                                      coverage-type)
                               :required?
                               (:insurance.coverage.type/required?
                                coverage-type)})
                            cloned-types))
                  :exporter-id
                  (:insurance.policy/exporter-id cloned-policy)
                  :mappings
                  (set (map (fn [mapping]
                              [(:insurance.export.mapping/role mapping)
                               (contains?
                                cloned-type-ids
                                (get-in
                                 mapping
                                 [:insurance.export.mapping/coverage-type
                                  :insurance.coverage.type/type-id]))])
                            (:insurance.policy/export-mappings
                             cloned-policy)))})))))))
