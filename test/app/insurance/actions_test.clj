(ns app.insurance.actions-test
  (:require
   [app.insurance.actions :as actions]
   [app.insurance.test-support :as insurance-support]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
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
