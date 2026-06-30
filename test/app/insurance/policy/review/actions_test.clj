(ns app.insurance.policy.review.actions-test
  (:require
   [app.insurance.policy.review.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn tr
  ([k] k)
  ([k args] [k args]))

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn seed-review-coverage!
  [conn {:keys [coverage-id policy-id policy-status]}]
  (let [category-id      (random-uuid)
        coverage-type-id (random-uuid)
        owner-id         (random-uuid)]
    @(d/transact
      conn
      [{:db/id            "owner"
        :member/member-id owner-id
        :member/name      "Owner"}
       {:db/id                           "category"
        :instrument.category/category-id category-id
        :instrument.category/name        "Brass"
        :instrument.category/code        "brass"}
       {:db/id                                  "coverage-type"
        :insurance.coverage.type/type-id        coverage-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                    "instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Trumpet"
        :instrument/owner         "owner"
        :instrument/category      "category"}
       {:db/id                           "coverage"
        :instrument.coverage/coverage-id coverage-id
        :instrument.coverage/instrument  "instrument"
        :instrument.coverage/types       ["coverage-type"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       1000M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:insurance.policy/policy-id           policy-id
        :insurance.policy/name                "Insurance 2026"
        :insurance.policy/status              (or policy-status :insurance.policy.status/draft)
        :insurance.policy/currency            :currency/EUR
        :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
        :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
        :insurance.policy/premium-factor      0.01M
        :insurance.policy/coverage-types      ["coverage-type"]
        :insurance.policy/category-factors    [{:insurance.category.factor/category-factor-id (random-uuid)
                                                :insurance.category.factor/category           "category"
                                                :insurance.category.factor/factor             0.01M}]
        :insurance.policy/covered-instruments ["coverage"]}]))
  coverage-id)

(defn state
  [{:keys [conn member-id]}]
  {:current-member-id member-id
   :db                (d/db conn)
   :tr                tr})

(defn has-db-transact?
  [effects]
  (boolean (some #(and (vector? %) (= :db/transact (first %))) effects)))

(deftest mark-coverage-reviewed-action-test
  (testing "returns a transaction effect for an insurance team member on a draft policy"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-review-action-reviewed")
          policy-id                           (random-uuid)
          coverage-id                         (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-review-coverage! conn {:policy-id policy-id :coverage-id coverage-id})
      (is (= [[:db/transact
               [[:db/add
                 [:instrument.coverage/coverage-id coverage-id]
                 :instrument.coverage/status
                 :instrument.coverage.status/reviewed]
                [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {}]
              support/clear-loading
              [:app.datastar/assoc-state [:insurance-review :error] nil]]
             (actions/mark-coverage-reviewed-action
              (state system)
              {:targetid (str coverage-id)}))))))

(deftest update-insurer-id-action-test
  (testing "returns a transaction effect that stores a trimmed Harmonia ID"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-review-action-insurer-id")
          policy-id                           (random-uuid)
          coverage-id                         (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-review-coverage! conn {:policy-id policy-id :coverage-id coverage-id})
      (is (= [[:db/transact
               [[:db/add
                 [:instrument.coverage/coverage-id coverage-id]
                 :instrument.coverage/insurer-id
                 "H-123"]
                [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {}]
              support/clear-loading
              [:app.datastar/assoc-state [:insurance-review :error] nil]
              [:app.datastar/remove-signals ["insuranceReview.insurerId"
                                             "insurance-review.insurer-id"
                                             (str "insurance-review.insurer-id-" coverage-id)]]]
             (actions/update-insurer-id-action
              (state system)
              {:targetid         (str coverage-id)
               :insurance-review {:insurer-id " H-123 "}})))))

  (testing "uses the camelCase save-click signal posted by the review input"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-review-action-insurer-id-camel")
          policy-id                           (random-uuid)
          coverage-id                         (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-review-coverage! conn {:policy-id policy-id :coverage-id coverage-id})
      (let [effects (actions/update-insurer-id-action
                     (state system)
                     {:targetid        (str coverage-id)
                      :insuranceReview {:insurerId " H-456 "}})]
        (is (= "H-456" (get-in effects [0 1 0 3])))))))

(deftest mark-coverage-action-rejections-test
  (testing "rejects a non-insurance-team member"
    (let [{:keys [conn] :as system} (tc/new-system "insurance-review-action-non-team")
          policy-id                 (random-uuid)
          coverage-id               (random-uuid)]
      (seed-review-coverage! conn {:policy-id policy-id :coverage-id coverage-id})
      (is (not (has-db-transact?
                (actions/mark-coverage-reviewed-action
                 (state system)
                 {:targetid (str coverage-id)}))))))

  (testing "rejects a frozen policy"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-review-action-frozen")
          policy-id                           (random-uuid)
          coverage-id                         (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-review-coverage! conn {:policy-id      policy-id
                                   :coverage-id    coverage-id
                                   :policy-status  :insurance.policy.status/active})
      (is (not (has-db-transact?
                (actions/mark-coverage-reviewed-action
                 (state system)
                 {:targetid (str coverage-id)}))))))

  (testing "rejects a missing coverage"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-review-action-missing")]
      (seed-insurance-team! conn member-id)
      (is (not (has-db-transact?
                (actions/mark-coverage-reviewed-action
                 (state system)
                 {:targetid (str (random-uuid))})))))))

(deftest update-insurer-id-action-rejections-test
  (testing "rejects a blank Harmonia ID"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-review-action-insurer-id-blank")
          policy-id                           (random-uuid)
          coverage-id                         (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-review-coverage! conn {:policy-id policy-id :coverage-id coverage-id})
      (is (not (has-db-transact?
                (actions/update-insurer-id-action
                 (state system)
                 {:targetid         (str coverage-id)
                  :insurance-review {:insurer-id " "}})))))))
