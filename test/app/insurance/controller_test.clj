(ns app.insurance.controller-test
  (:require
   [app.insurance.controller :as controller]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn seed-policy-with-covered-instrument!
  [conn member-id policy-id]
  (let [category-id      (random-uuid)
        coverage-type-id (random-uuid)
        instrument-id    (random-uuid)
        coverage-id      (random-uuid)]
    @(d/transact
      conn
      [{:member/member-id member-id
        :member/name      "Covered Member"
        :member/nick      "covered"
        :member/email     "covered@example.test"
        :member/active?   true}
       {:db/id                           "category"
        :instrument.category/category-id category-id
        :instrument.category/name        "Brass"
        :instrument.category/code        "brass"}
       {:db/id                                  "coverage-type"
        :insurance.coverage.type/type-id        coverage-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                         "instrument"
        :instrument/instrument-id      instrument-id
        :instrument/name               "Trumpet"
        :instrument/owner              [:member/member-id member-id]
        :instrument/category           "category"}
       {:db/id                           "coverage"
        :instrument.coverage/coverage-id coverage-id
        :instrument.coverage/instrument  "instrument"
        :instrument.coverage/types       ["coverage-type"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       1000M
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:insurance.policy/policy-id           policy-id
        :insurance.policy/name                "Insurance 2026"
        :insurance.policy/status              :insurance.policy.status/draft
        :insurance.policy/currency            :currency/EUR
        :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
        :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
        :insurance.policy/premium-factor      0.01M
        :insurance.policy/coverage-types      ["coverage-type"]
        :insurance.policy/category-factors    [{:insurance.category.factor/category-factor-id (random-uuid)
                                                :insurance.category.factor/category           "category"
                                                :insurance.category.factor/factor             0.01M}]
        :insurance.policy/covered-instruments ["coverage"]}])
    policy-id))

(defn survey-response-refs
  [db survey-name]
  (d/q '[:find [(pull ?response [:insurance.survey.response/response-id
                                 :insurance.survey.report/report-id]) ...]
         :in $ ?survey-name
         :where
         [?survey :insurance.survey/survey-name ?survey-name]
         [?survey :insurance.survey/responses ?response]]
       db survey-name))

(defn survey-report-refs
  [db survey-name]
  (d/q '[:find [?report ...]
         :in $ ?survey-name
         :where
         [?survey :insurance.survey/survey-name ?survey-name]
         [?survey :insurance.survey/responses ?response]
         [?response :insurance.survey.response/coverage-reports ?report]]
       db survey-name))

(deftest txs-new-survey-links-only-response-entities-test
  (testing "survey responses do not include report entities directly"
    (let [{:keys [conn member-id]} (tc/new-system "insurance-new-survey-response-refs")
          policy-id                (random-uuid)
          survey-name              "Instrument survey"]
      (seed-policy-with-covered-instrument! conn member-id policy-id)
      (let [db      (d/db conn)
            policy  (q/retrieve-policy db policy-id)
            tx-data (controller/txs-new-survey db survey-name (t/date-time "2026-06-01T00:00:00") policy)
            result  @(d/transact conn tx-data)
            db      (:db-after result)
            responses (survey-response-refs db survey-name)
            reports   (survey-report-refs db survey-name)]
        (is (= 1 (count responses)))
        (is (every? :insurance.survey.response/response-id responses))
        (is (not-any? :insurance.survey.report/report-id responses))
        (is (= 1 (count reports)))))))

(defn remove-category-factor-and-make-coverage-private!
  [conn policy-id]
  (let [[factor-eid coverage-eid]
        (d/q '[:find [?factor ?coverage]
               :in $ ?policy-id
               :where
               [?policy :insurance.policy/policy-id ?policy-id]
               [?policy :insurance.policy/category-factors ?factor]
               [?policy :insurance.policy/covered-instruments ?coverage]]
             (d/db conn)
             policy-id)]
    @(d/transact conn [[:db/retract
                        [:insurance.policy/policy-id policy-id]
                        :insurance.policy/category-factors
                        factor-eid]
                       [:db/add coverage-eid :instrument.coverage/private? true]])))

(deftest unavailable-private-costs-are-not-payable
  (testing "A private coverage has no category factor and therefore no payable cost."
    (let [{:keys [conn member-id]} (tc/new-system "insurance-unavailable-private-payment")
          policy-id                (random-uuid)]
      (seed-policy-with-covered-instrument! conn member-id policy-id)
      (remove-category-factor-and-make-coverage-private! conn policy-id)
      (let [member-data (-> (controller/build-data-notification-table
                             {:db          (d/db conn)
                              :path-params {:policy-id policy-id}
                              :session     {:session/member {:member/member-id member-id
                                                             :member/name      "Covered Member"}}})
                            :members-data
                            first)]
        (testing "Notification data preserves the known subtotal and records why payment is unavailable."
          (is (= {:private-cost-total               0
                  :unavailable-private-cost-count  1
                  :private-costs-available?        false
                  :missing-category-names          ["Brass"]}
                 (select-keys member-data
                              [:private-cost-total
                               :unavailable-private-cost-count
                               :private-costs-available?
                               :missing-category-names]))))
        (testing "A forged selection of the unavailable member is rejected server-side."
          (let [select-members (ns-resolve 'app.insurance.controller 'select-notification-members)
                selection      (when select-members
                                 (select-members [member-data] #{member-id}))]
            (is select-members)
            (is (= {:to-send-ids     []
                    :unavailable-ids [member-id]}
                   {:to-send-ids     (mapv #(get-in % [:member :member/member-id])
                                           (:to-send selection))
                    :unavailable-ids (mapv #(get-in % [:member :member/member-id])
                                           (:unavailable selection))}))))))))

(deftest send-notifications-rejects-unavailable-private-costs
  (testing "A forged payment submission selects a member whose private coverage cost is unavailable."
    (let [{:keys [conn member-id]} (tc/new-system "insurance-reject-unavailable-private-payment")
          policy-id                (random-uuid)]
      (seed-policy-with-covered-instrument! conn member-id policy-id)
      (remove-category-factor-and-make-coverage-private! conn policy-id)
      (let [result (controller/send-notifications!
                    {:db          (d/db conn)
                     :tr          (fn [_path [category-names]]
                                    (str "Missing category factors: " category-names))
                     :path-params {:policy-id policy-id}
                     :form-params {:member-ids (str member-id)}
                     :session     {:session/member {:member/member-id member-id
                                                    :member/name      "Covered Member"}}})]
        (testing "The whole request fails before creating a ledger debit or sending notifications."
          (is (= {:error      "Missing category factors: Brass"
                  :count-sent nil
                  :debited?   false}
                 {:error      (:error result)
                  :count-sent (:count-sent result)
                  :debited?   (controller/member-debited-for-policy?
                               (d/db conn)
                               policy-id
                               member-id)})))))))
