(ns app.insurance.survey.actions-test
  (:require
   [app.insurance.survey.actions :as actions]
   [app.insurance.survey.queries :as queries]
   [app.insurance.test-support :as insurance-test]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn tr
  ([path]
   (name (last path)))
  ([path _vars]
   (tr path)))

(defn fixture
  ([]
   (fixture true))
  ([with-report?]
   (let [{:keys [conn member-id]} (tc/new-system "insurance-survey-actions")
         {:keys [coverage-id policy-id] :as ids}
         (insurance-test/seed-page-shell-fixture! conn member-id)
         survey-ids (insurance-test/seed-member-survey!
                     conn
                     {:coverage-ids (cond-> [] with-report? (conj coverage-id))
                      :member-id    member-id
                      :policy-id    policy-id})]
     (merge ids
            survey-ids
            {:conn conn
             :member-id member-id
             :state {:current-member-id member-id
                     :db                (d/db conn)
                     :now               #inst "2026-03-20T00:00:00.000-00:00"
                     :page-state        {}
                     :tr                tr}}))))

(defn signals [policy-id values]
  {:insuranceSurvey (merge {:policyId (str policy-id)} values)})

(deftest transition-action-validates-and-advances-the-server-owned-flow
  (let [{:keys [policy-id report-ids state]} (fixture)
        report-id (first report-ids)]
    (testing "a valid answer advances the current report and records its decision"
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [actions/form-key]
               {:current-flow-key :keep-insured
                :decisions        [:confirm-band]
                :mode             :question
                :report-id        report-id}]]
             (actions/transition-action
              state
              (signals policy-id {:answer "yes"})))))

    (testing "an answer that does not belong to the current question is rejected"
      (let [effects (actions/transition-action
                     state
                     (signals policy-id {:answer "remove"}))]
        (is (= support/clear-loading (first effects)))
        (is (= :app.datastar/assoc-state (first (second effects))))
        (is (some? (get-in effects [1 2 :error])))
        (is (not-any? #(= :db/transact (first %)) effects))))))

(deftest completing-a-report-applies-decisions-and-finishes-the-response
  (let [{:keys [conn member-id policy-id report-ids response-id state]} (fixture)
        report-id (first report-ids)
        state     (assoc state :page-state
                         {actions/form-key
                          {:current-flow-key :data-check
                           :decisions        [:confirm-band]
                           :mode             :question
                           :report-id        report-id}})
        effects   (actions/transition-action
                   state
                   (signals policy-id {:answer "yes"}))
        [_ tx-data] (first effects)]
    (is (= :db/transact (ffirst effects)))
    (is (= support/clear-loading (second effects)))
    @(d/transact conn tx-data)
    (let [db       (d/db conn)
          report   (q/retrieve-survey-report db report-id)
          response (q/retrieve-survey-response db response-id)]
      (is (some? (:insurance.survey.report/completed-at report)))
      (is (some? (:insurance.survey.response/completed-at response)))
      (is (= false (get-in report [:insurance.survey.report/coverage
                                   :instrument.coverage/private?])))
      (is (some #{[:db/add "datomic.tx" :audit/user
                   [:member/member-id member-id]]}
                tx-data)))))

(deftest dismissal-only-finishes-a-response-without-open-reports
  (let [{:keys [conn policy-id response-id state]} (fixture false)
        effects (actions/dismiss-action state (signals policy-id {}))
        [_ tx-data] (first effects)]
    (is (= :db/transact (ffirst effects)))
    @(d/transact conn tx-data)
    (is (some? (:insurance.survey.response/completed-at
                (q/retrieve-survey-response (d/db conn) response-id)))))

  (let [{:keys [policy-id state]} (fixture true)
        effects (actions/dismiss-action state (signals policy-id {}))]
    (is (not-any? #(= :db/transact (first %)) effects))
    (is (some? (get-in effects [1 2 :error])))))

(deftest survey-edit-reuses-coverage-validation-and-completes-the-report
  (let [{:keys [conn coverage-id member-id policy-id report-ids state]} (fixture)
        _         @(d/transact conn [[:db/add
                                      [:insurance.policy/policy-id policy-id]
                                      :insurance.policy/status
                                      :insurance.policy.status/active]])
        state     (assoc state :db (d/db conn))
        report-id (first report-ids)
        data      (queries/survey-data (:db state) policy-id member-id)
        coverage  (:insurance.survey.report/coverage (:active-report data))
        type-id   (get-in coverage [:instrument.coverage/types 0
                                    :insurance.coverage.type/type-id])
        category-id (get-in coverage [:instrument.coverage/instrument
                                      :instrument/category
                                      :instrument.category/category-id])
        state     (assoc state :page-state
                         {actions/form-key
                          {:current-flow-key :data-edit
                           :decisions        [:confirm-band]
                           :mode             :edit
                           :report-id        report-id}})
        edit      {:buildYear      "1988"
                   :categoryId     (str category-id)
                   :coverageTypes  [(str type-id)]
                   :description    "Recently serviced"
                   :insurerId      "H-999"
                   :instrumentName "Updated Trumpet"
                   :itemCount      "1"
                   :make           "Yamaha"
                   :model          "Xeno"
                   :serialNumber   "ABC"
                   :value          "150"}]
    (testing "invalid edits stay in the edit state with field errors"
      (let [effects (actions/save-edit-action
                     state
                     (signals policy-id {:edit (assoc edit :make "")}))]
        (is (not-any? #(= :db/transact (first %)) effects))
        (is (some? (get-in effects [1 2 :edit :_error :make :error])))))

    (testing "a valid edit updates coverage data and completes the report atomically"
      (let [effects (actions/save-edit-action
                     state
                     (signals policy-id {:edit edit}))
            [_ tx-data] (first effects)]
        (is (= :db/transact (ffirst effects)))
        @(d/transact conn tx-data)
        (let [db       (d/db conn)
              coverage (q/retrieve-coverage db coverage-id)]
          (is (= "Updated Trumpet"
                 (get-in coverage [:instrument.coverage/instrument :instrument/name])))
          (is (= 150M (:instrument.coverage/value coverage)))
          (is (some? (:insurance.survey.report/completed-at
                      (q/retrieve-survey-report db report-id)))))))))
