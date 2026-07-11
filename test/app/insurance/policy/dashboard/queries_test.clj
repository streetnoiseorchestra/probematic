(ns app.insurance.policy.dashboard.queries-test
  (:require
   [app.insurance.policy.dashboard.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn seed-dashboard-policy!
  [conn policy-id]
  @(d/transact
    conn
    [{:db/id                                  "basic"
      :insurance.coverage.type/type-id        (random-uuid)
      :insurance.coverage.type/name           "Basic"
      :insurance.coverage.type/description    ""
      :insurance.coverage.type/premium-factor 1.0M}
     {:db/id                           "brass"
      :instrument.category/category-id (random-uuid)
      :instrument.category/name        "Brass"
      :instrument.category/code        "brass"}
     {:db/id                    "band-one-instrument"
      :instrument/instrument-id (random-uuid)
      :instrument/name          "Band One"
      :instrument/category      "brass"}
     {:db/id                    "band-two-instrument"
      :instrument/instrument-id (random-uuid)
      :instrument/name          "Band Two"
      :instrument/category      "brass"}
     {:db/id                    "private-instrument"
      :instrument/instrument-id (random-uuid)
      :instrument/name          "Private One"
      :instrument/category      "brass"}
     {:db/id                           "band-one-coverage"
      :instrument.coverage/coverage-id (random-uuid)
      :instrument.coverage/instrument  "band-one-instrument"
      :instrument.coverage/types       ["basic"]
      :instrument.coverage/private?    false
      :instrument.coverage/value       100M
      :instrument.coverage/item-count  2
      :instrument.coverage/status      :instrument.coverage.status/needs-review
      :instrument.coverage/change      :instrument.coverage.change/none}
     {:db/id                           "band-two-coverage"
      :instrument.coverage/coverage-id (random-uuid)
      :instrument.coverage/instrument  "band-two-instrument"
      :instrument.coverage/types       ["basic"]
      :instrument.coverage/private?    false
      :instrument.coverage/value       50M
      :instrument.coverage/status      :instrument.coverage.status/reviewed
      :instrument.coverage/change      :instrument.coverage.change/none}
     {:db/id                           "private-coverage"
      :instrument.coverage/coverage-id (random-uuid)
      :instrument.coverage/instrument  "private-instrument"
      :instrument.coverage/types       ["basic"]
      :instrument.coverage/private?    true
      :instrument.coverage/value       300M
      :instrument.coverage/item-count  1
      :instrument.coverage/status      :instrument.coverage.status/coverage-active
      :instrument.coverage/change      :instrument.coverage.change/changed}
     {:insurance.policy/policy-id           policy-id
      :insurance.policy/name                "Insurance 2026"
      :insurance.policy/status              :insurance.policy.status/draft
      :insurance.policy/currency            :currency/EUR
      :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
      :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
      :insurance.policy/premium-factor      1.0M
      :insurance.policy/coverage-types      ["basic"]
      :insurance.policy/category-factors    [{:insurance.category.factor/category-factor-id (random-uuid)
                                              :insurance.category.factor/category           "brass"
                                              :insurance.category.factor/factor             0.1M}]
      :insurance.policy/covered-instruments ["band-one-coverage"
                                             "band-two-coverage"
                                             "private-coverage"]}]))

(defn remove-dashboard-category-factor!
  [conn policy-id]
  (let [factor-eid (d/q '[:find ?factor .
                          :in $ ?policy-id
                          :where
                          [?policy :insurance.policy/policy-id ?policy-id]
                          [?policy :insurance.policy/category-factors ?factor]]
                        (d/db conn)
                        policy-id)]
    @(d/transact conn [[:db/retract
                        [:insurance.policy/policy-id policy-id]
                        :insurance.policy/category-factors
                        factor-eid]])))

(defn seed-dashboard-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(deftest policy-dashboard-totals-split-count-and-cost-by-coverage-ownership
  (testing "coverage mix totals include separate band/private counts and costs"
    (let [{:keys [conn]} (tc/new-system "insurance-dashboard-coverage-mix")
          policy-id      (random-uuid)]
      (seed-dashboard-policy! conn policy-id)
      (let [totals (:totals (queries/policy-dashboard (d/db conn) policy-id))]
        (is (= {:band-count    2
                :private-count 1
                :band-cost     25.0M
                :private-cost  30.0M
                :total-cost    55.0M}
               (select-keys totals [:band-count
                                    :private-count
                                    :band-cost
                                    :private-cost
                                    :total-cost])))))))

(deftest policy-dashboard-tolerates-missing-category-factor
  (testing "A covered instrument uses a category without a policy category factor."
    (let [{:keys [conn member-id]} (tc/new-system "insurance-dashboard-missing-category-factor")
          policy-id                (random-uuid)]
      (seed-dashboard-policy! conn policy-id)
      (seed-dashboard-insurance-team! conn member-id)
      (remove-dashboard-category-factor! conn policy-id)
      (testing "The dashboard marks unavailable coverage costs and excludes them from aggregates."
        (let [db                         (d/db conn)
              {:keys [coverages totals] :as dashboard}
              (queries/policy-dashboard db policy-id {:current-member-id member-id})
              ordinary-dashboard         (queries/policy-dashboard db policy-id)]
          (is (= {:totals          {:total-instruments             3
                                    :total-insured-value           550M
                                    :total-cost                    0M
                                    :private-cost                  0M
                                    :band-cost                     0M
                                    :missing-category-factor-count 1}
                  :coverage-costs  #{nil}
                  :missing-factors #{true}
                  :team-member?    true
                  :ordinary-member? false}
                 {:totals          (select-keys totals [:total-instruments
                                                        :total-insured-value
                                                        :total-cost
                                                        :private-cost
                                                        :band-cost
                                                        :missing-category-factor-count])
                  :coverage-costs  (set (map :instrument.coverage/cost coverages))
                  :missing-factors (set (map :instrument.coverage/missing-category-factor? coverages))
                  :team-member?    (:insurance-team-member? dashboard)
                  :ordinary-member? (:insurance-team-member? ordinary-dashboard)})))))))
