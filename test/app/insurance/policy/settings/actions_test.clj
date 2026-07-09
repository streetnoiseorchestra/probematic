(ns app.insurance.policy.settings.actions-test
  (:require
   [app.insurance.actions :as insurance.actions]
   [app.insurance.policy.settings.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr
  ([k] k)
  ([k args] [k args]))

(defn date-inst
  [date]
  (-> date t/date (t/at (t/midnight)) t/inst))

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn seed-policy!
  [conn policy-id status]
  @(d/transact conn [{:insurance.policy/policy-id       policy-id
                      :insurance.policy/name            "Insurance 2026"
                      :insurance.policy/status          status
                      :insurance.policy/currency        :EUR
                      :insurance.policy/effective-at    (date-inst "2026-01-01")
                      :insurance.policy/effective-until (date-inst "2026-12-31")
                      :insurance.policy/premium-factor  0.01M}])
  policy-id)

(defn state
  [{:keys [conn member-id]}]
  {:current-member-id member-id
   :db                (d/db conn)
   :tr                tr})

(defn policy-signals
  [policy-id overrides]
  {:insurancePolicySettings
   {:policy (merge {:policyId       (str policy-id)
                    :name           "Insurance 2027"
                    :effectiveAt    "2027-01-01"
                    :effectiveUntil "2027-12-31"
                    :premiumFactor  "0.025"
                    :currency       "EUR"}
                   overrides)}})

(defn transact-effect?
  [effect]
  (= :db/transact (first effect)))

(defn assoc-state-effect
  [effects]
  (some #(when (= :app.datastar/assoc-state (first %)) %)
        effects))

(defn failure-summary
  [effects]
  (let [[_ path value] (assoc-state-effect effects)]
    {:transact?      (boolean (some transact-effect? effects))
     :clear-loading? (boolean (some #{support/clear-loading} effects))
     :state-path     path
     :submitted      (select-keys value [:name
                                         :effective-at
                                         :effective-until
                                         :premium-factor
                                         :currency])
     :error-keys     (set (keys (:_error value)))
     :top-error      (get-in value [:_error :_top :error])}))

(deftest save-policy-details-action-test
  (testing "returns an audited policy detail transaction for an insurance team member"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-action-save")
          policy-id                           (random-uuid)
          policy-ref                          [:insurance.policy/policy-id policy-id]]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (is (= [[:db/transact
               (support/with-audit
                 [[:db/add policy-ref :insurance.policy/name "Insurance 2027"]
                  [:db/add policy-ref :insurance.policy/effective-at (date-inst "2027-01-01")]
                  [:db/add policy-ref :insurance.policy/effective-until (date-inst "2027-12-31")]
                  [:db/add policy-ref :insurance.policy/premium-factor 0.025M]
                  [:db/add policy-ref :insurance.policy/currency :EUR]]
                 member-id)
               {}]
              support/clear-loading
              [:app.datastar/assoc-state [:insurance-policy-settings :policy :_error] nil]]
             (actions/save-policy-details-action (state system)
                                                 (policy-signals policy-id {})))))))

(deftest save-policy-details-validation-test
  (testing "returns field and top-level errors without a transaction"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-action-validation")
          policy-id                           (random-uuid)
          missing-policy-id                   (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (is (= {:invalid-fields {:transact?      false
                               :clear-loading? true
                               :state-path     [:insurance-policy-settings :policy]
                               :submitted      {:name            ""
                                                :effective-at    "nope"
                                                :effective-until ""
                                                :premium-factor  "not-a-decimal"
                                                :currency        "JPY"}
                               :error-keys     #{:name
                                                 :effective-at
                                                 :effective-until
                                                 :premium-factor
                                                 :currency
                                                 :_top}
                               :top-error      [:error/form-has-errors]}
              :date-order     {:transact?      false
                               :clear-loading? true
                               :state-path     [:insurance-policy-settings :policy]
                               :submitted      {:name            "Insurance 2027"
                                                :effective-at    "2027-12-31"
                                                :effective-until "2027-01-01"
                                                :premium-factor  "0.025"
                                                :currency        "EUR"}
                               :error-keys     #{:effective-until :_top}
                               :top-error      [:error/form-has-errors]}
              :missing-policy {:transact?      false
                               :clear-loading? true
                               :state-path     [:insurance-policy-settings :policy]
                               :submitted      {:name            "Insurance 2027"
                                                :effective-at    "2027-01-01"
                                                :effective-until "2027-12-31"
                                                :premium-factor  "0.025"
                                                :currency        "EUR"}
                               :error-keys     #{:_top}
                               :top-error      [:insurance.policy-settings/error-policy-not-found]}}
             {:invalid-fields (failure-summary
                               (actions/save-policy-details-action
                                (state system)
                                (policy-signals policy-id
                                                {:name           "  "
                                                 :effectiveAt    "nope"
                                                 :effectiveUntil ""
                                                 :premiumFactor  "not-a-decimal"
                                                 :currency       "JPY"})))
              :date-order     (failure-summary
                               (actions/save-policy-details-action
                                (state system)
                                (policy-signals policy-id
                                                {:effectiveAt    "2027-12-31"
                                                 :effectiveUntil "2027-01-01"})))
              :missing-policy (failure-summary
                               (actions/save-policy-details-action
                                (state system)
                                (policy-signals missing-policy-id {})))})))))

(deftest save-policy-details-authorization-test
  (testing "rejects non-team members and frozen policies without a transaction"
    (let [{draft-conn :conn :as draft-system} (tc/new-system "insurance-settings-action-not-team")
          {frozen-conn :conn frozen-member-id :member-id :as frozen-system}
          (tc/new-system "insurance-settings-action-frozen")
          draft-policy-id  (random-uuid)
          frozen-policy-id (random-uuid)]
      (seed-policy! draft-conn draft-policy-id :insurance.policy.status/draft)
      (seed-insurance-team! frozen-conn frozen-member-id)
      (seed-policy! frozen-conn frozen-policy-id :insurance.policy.status/sent)
      (is (= {:not-team {:transact?      false
                         :clear-loading? true
                         :state-path     [:insurance-policy-settings :policy]
                         :submitted      {:name            "Insurance 2027"
                                          :effective-at    "2027-01-01"
                                          :effective-until "2027-12-31"
                                          :premium-factor  "0.025"
                                          :currency        "EUR"}
                         :error-keys     #{:_top}
                         :top-error      [:insurance.policy-settings/error-not-allowed]}
              :frozen   {:transact?      false
                         :clear-loading? true
                         :state-path     [:insurance-policy-settings :policy]
                         :submitted      {:name            "Insurance 2027"
                                          :effective-at    "2027-01-01"
                                          :effective-until "2027-12-31"
                                          :premium-factor  "0.025"
                                          :currency        "EUR"}
                         :error-keys     #{:_top}
                         :top-error      [:insurance.policy-settings/error-frozen-policy]}}
             {:not-team (failure-summary
                         (actions/save-policy-details-action
                          (state draft-system)
                          (policy-signals draft-policy-id {})))
              :frozen   (failure-summary
                         (actions/save-policy-details-action
                          (state frozen-system)
                          (policy-signals frozen-policy-id {})))})))))

(deftest settings-policy-detail-action-is-registered-test
  (is (contains? insurance.actions/actions
                 :app.insurance.policy.settings.actions/save-policy-details)))
