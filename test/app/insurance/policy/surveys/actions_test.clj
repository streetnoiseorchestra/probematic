(ns app.insurance.policy.surveys.actions-test
  (:require
   [app.insurance.policy.surveys.actions :as actions]
   [app.insurance.test-support :as insurance-test]
   [app.nexus :as nexus]
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

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-policy-surveys-actions")
        ids                      (insurance-test/seed-page-shell-fixture! conn member-id)]
    (merge ids
           {:conn      conn
            :member-id member-id
            :state     {:current-member-id member-id
                        :db                (d/db conn)
                        :now               #inst "2026-03-20T12:00:00.000-00:00"
                        :tr                tr}})))

(defn survey-signals [policy-id values]
  {actions/signal-key
   (merge {:policyId (str policy-id)} values)})

(deftest start-survey-action-snapshots-current-members
  (let [{:keys [conn policy-id state]} (fixture)
        start-action (some-> (get actions/actions ::actions/start-survey) deref)]
    (is (fn? start-action))
    (when start-action
      (let [effects (start-action
                     state
                     (survey-signals policy-id
                                     {:closesAt "2026-04-20T20:00"}))
            [_ tx-data] (first effects)]
        (is (= {:effect             :db/transact
                :response-count     2
                :report-count       1
                :contains-name?     false
                :contains-generator true
                :audit              [:db/add "datomic.tx" :audit/user
                                     [:member/member-id (:current-member-id state)]]}
               {:effect             (ffirst effects)
                :response-count     (count (filter :insurance.survey.response/response-id
                                                   tx-data))
                :report-count       (count (filter :insurance.survey.report/report-id
                                                   tx-data))
                :contains-name?     (boolean
                                     (some :insurance.survey/survey-name tx-data))
                :contains-generator (boolean
                                     (some #{:db/gen-uuid}
                                           (tree-seq coll? seq tx-data)))
                :audit              (last tx-data)}))
        @(d/transact conn (nexus/batch-transactions [[tx-data {}]]))
        (let [survey (first (q/surveys-for-policy
                             (d/db conn)
                             (q/retrieve-policy (d/db conn) policy-id)))]
          (is (= {:name-present? false
                  :response-count 2
                  :report-counts  [0 1]}
                 {:name-present? (contains? survey :insurance.survey/survey-name)
                  :response-count (count (:insurance.survey/responses survey))
                  :report-counts  (sort
                                   (map
                                    (comp count
                                          :insurance.survey.response/coverage-reports)
                                    (:insurance.survey/responses survey)))})))))))

(deftest active-survey-on-another-policy-blocks-start
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
        other-policy-id (insurance-test/seed-policy! conn (random-uuid))
        _ (insurance-test/seed-member-survey!
           conn
           {:coverage-ids [coverage-id]
            :member-id    member-id
            :policy-id    policy-id})
        effects (actions/start-survey-action
                 (assoc state :db (d/db conn))
                 (survey-signals other-policy-id
                                 {:closesAt "2026-04-20T20:00"}))]
    (is (not-any? #(= :db/transact (first %)) effects))
    (is (= "survey-error-open-exists"
           (get-in effects [1 2 :form :_error :_top :error])))))

(deftest ended-surveys-on-another-policy-do-not-block-start
  (doseq [[description survey-state]
          [["expired" {:survey-closes-at
                       #inst "2026-03-19T12:00:00.000-00:00"}]
           ["explicitly closed" {:closed-at
                                 #inst "2026-03-19T12:00:00.000-00:00"}]]]
    (testing description
      (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
            other-policy-id (insurance-test/seed-policy! conn (random-uuid))
            _ (insurance-test/seed-member-survey!
               conn
               (merge {:coverage-ids [coverage-id]
                       :member-id    member-id
                       :policy-id    policy-id}
                      survey-state))
            effects (actions/start-survey-action
                     (assoc state :db (d/db conn))
                     (survey-signals other-policy-id
                                     {:closesAt "2026-04-20T20:00"}))]
        (is (= :db/transact (ffirst effects)))))))

(deftest transaction-guard-closes-the-stale-start-race
  (let [{:keys [conn policy-id state]} (fixture)
        other-policy-id (insurance-test/seed-policy! conn (random-uuid))
        stale-state (assoc state :db (d/db conn))
        first-effects (actions/start-survey-action
                       stale-state
                       (survey-signals policy-id
                                       {:closesAt "2026-04-20T20:00"}))
        second-effects (actions/start-survey-action
                        stale-state
                        (survey-signals other-policy-id
                                        {:closesAt "2026-04-20T20:00"}))
        [_ first-tx first-opts] (first first-effects)
        [_ second-tx second-opts] (first second-effects)]
    @(d/transact conn (nexus/batch-transactions [[first-tx first-opts]]))
    (let [error (try
                  @(d/transact
                    conn
                    (nexus/batch-transactions [[second-tx second-opts]]))
                  nil
                  (catch java.util.concurrent.ExecutionException e
                    (ex-cause e)))]
      (is (= {:cognitect.anomalies/category :cognitect.anomalies/conflict
              :insurance.survey/error       :insurance.survey.error/active-exists
              :datomic/cancelled             true}
             (select-keys
              (ex-data error)
              [:cognitect.anomalies/category
               :insurance.survey/error
               :datomic/cancelled]))))
    (let [db (d/db conn)]
      (is (= 1 (count (q/surveys-for-policy
                       db
                       (q/retrieve-policy db policy-id)))))
      (is (empty? (q/surveys-for-policy
                   db
                   (q/retrieve-policy db other-policy-id)))))))

(deftest update-closes-at-action-preserves-legacy-name
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
        {:keys [survey-id]}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})
        _ @(d/transact conn [[:db/add
                              [:insurance.survey/survey-id survey-id]
                              :insurance.survey/survey-name
                              "Legacy survey name"]])
        update-action (some-> (get actions/actions ::actions/update-closes-at)
                              deref)]
    (is (fn? update-action))
    (when update-action
      (let [effects (update-action
                     (assoc state :db (d/db conn))
                     (survey-signals policy-id
                                     {:surveyId (str survey-id)
                                      :closesAt "2026-04-25T19:30"}))
            [_ tx-data opts] (first effects)]
        (is (= :db/transact (ffirst effects)))
        (is (= :insurance.survey/activate (ffirst tx-data)))
        @(d/transact conn tx-data)
        (is (= {:insurance.survey/survey-name "Legacy survey name"
                :insurance.survey/closes-at   #inst "2026-04-25T17:30:00.000-00:00"}
               (d/pull (d/db conn)
                       [:insurance.survey/survey-name
                        :insurance.survey/closes-at]
                       [:insurance.survey/survey-id survey-id])))
        (is (= [[:app.datastar/assoc-state
                 [actions/form-key]
                 {:form {:policy-id policy-id
                         :survey-id survey-id
                         :closes-at "2026-04-25T19:30"}}]
                [:app.datastar/merge-signals
                 {:loading            false
                  :targetid           false
                  actions/signal-key {:saveStatus "saved"}}]]
               (:on-success opts)))
        (is (= "survey-error-open-exists"
               (get-in opts
                       [:on-error
                        :insurance.survey.error/active-exists
                        1 2 :form :_error :_top :error])))))))

(deftest another-active-survey-blocks-deadline-update
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
        other-policy-id (insurance-test/seed-policy! conn (random-uuid))
        {survey-id :survey-id}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})
        _ (insurance-test/seed-member-survey!
           conn
           {:coverage-ids []
            :member-id    member-id
            :policy-id    other-policy-id})
        effects (actions/update-closes-at-action
                 (assoc state :db (d/db conn))
                 (survey-signals policy-id
                                 {:surveyId (str survey-id)
                                  :closesAt "2026-04-25T19:30"}))]
    (is (not-any? #(= :db/transact (first %)) effects))
    (is (= "survey-error-open-exists"
           (get-in effects [1 2 :form :_error :_top :error])))
    (is (= "error"
           (get-in effects [0 1 actions/signal-key :saveStatus])))))

(deftest survey-mutations-validate-input-and-membership
  (let [{:keys [outsider-id policy-id state]} (fixture)]
    (testing "invalid start data stays on the page with field errors"
      (let [start-action (some-> (get actions/actions ::actions/start-survey) deref)
            effects      (when start-action
                           (start-action
                            state
                            (survey-signals policy-id {:closesAt "not-a-date"})))]
        (is (fn? start-action))
        (is (not-any? #(= :db/transact (first %)) effects))
        (is (some? (get-in effects [1 2 :form :_error :closes-at :error])))))

    (testing "a non-insurance-team member cannot create a survey"
      (let [start-action (some-> (get actions/actions ::actions/start-survey) deref)
            effects      (when start-action
                           (start-action
                            (assoc state :current-member-id outsider-id)
                            (survey-signals policy-id
                                            {:closesAt "2026-04-20T20:00"})))]
        (is (fn? start-action))
        (is (not-any? #(= :db/transact (first %)) effects))
        (is (some? (get-in effects [1 2 :form :_error :_top :error])))))))

(deftest survey-deadlines-are-enforced
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)]
    (testing "a survey cannot be created with a deadline in the past"
      (let [start-action (some-> (get actions/actions ::actions/start-survey) deref)
            effects      (when start-action
                           (start-action
                            state
                            (survey-signals policy-id
                                            {:closesAt "2026-03-01T20:00"})))]
        (is (fn? start-action))
        (is (not-any? #(= :db/transact (first %)) effects))
        (is (some? (get-in effects [1 2 :form :_error :closes-at :error])))))

    (testing "reminders are rejected after the deadline"
      (let [{:keys [survey-id]}
            (insurance-test/seed-member-survey!
             conn
             {:coverage-ids [coverage-id]
              :member-id    member-id
              :policy-id    policy-id})
            effects (actions/send-reminders-action
                     (assoc state
                            :db (d/db conn)
                            :now #inst "2027-01-01T00:00:00.000-00:00")
                     (assoc (survey-signals policy-id {})
                            :targetid (str survey-id)))]
        (is (= support/clear-loading (first effects)))
        (is (= :error (get-in effects [1 2 :status])))
        (is (not-any? #(= :app.insurance/send-survey-notifications (first %))
                      effects))))))

(deftest update-closes-at-rejects-invalid-survey-state
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
        update-action (some-> (get actions/actions ::actions/update-closes-at)
                              deref)
        other-policy-id (random-uuid)
        _ (insurance-test/seed-policy! conn other-policy-id)
        {survey-id :survey-id}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})]
    (is (fn? update-action))
    (when update-action
      (testing "the survey must belong to the submitted policy"
        (let [effects (update-action
                       (assoc state :db (d/db conn))
                       (survey-signals other-policy-id
                                       {:surveyId (str survey-id)
                                        :closesAt "2026-04-25T19:30"}))]
          (is (= {:transacts? false
                  :signal-effects
                  [[:app.datastar/merge-signals
                    {:loading            false
                     :targetid           false
                     actions/signal-key {:saveStatus "error"}}]]}
                 {:transacts? (boolean
                               (some #(= :db/transact (first %)) effects))
                  :signal-effects
                  (filterv #(= :app.datastar/merge-signals (first %))
                           effects)}))))

      (testing "closed surveys cannot be rescheduled"
        @(d/transact conn [[:db/add
                            [:insurance.survey/survey-id survey-id]
                            :insurance.survey/closed-at
                            #inst "2026-03-19T12:00:00.000-00:00"]])
        (let [effects (update-action
                       (assoc state :db (d/db conn))
                       (survey-signals policy-id
                                       {:surveyId (str survey-id)
                                        :closesAt "2026-04-25T19:30"}))]
          (is (not-any? #(= :db/transact (first %)) effects))))

      (testing "expired surveys cannot be rescheduled"
        (let [{expired-survey-id :survey-id}
              (insurance-test/seed-member-survey!
               conn
               {:coverage-ids     [coverage-id]
                :member-id        member-id
                :policy-id        policy-id
                :survey-closes-at #inst "2026-03-01T12:00:00.000-00:00"})
              effects (update-action
                       (assoc state :db (d/db conn))
                       (survey-signals policy-id
                                       {:surveyId (str expired-survey-id)
                                        :closesAt "2026-04-25T19:30"}))]
          (is (not-any? #(= :db/transact (first %)) effects)))))))

(deftest close-and-toggle-response-actions
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
        {:keys [response-id survey-id]}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})
        state (assoc state :db (d/db conn))]
    (testing "an insurance-team member can mark a response complete"
      (let [effects (actions/toggle-response-action
                     state
                     (assoc (survey-signals policy-id {})
                            :targetid (str response-id)))
            [_ tx-data] (first effects)]
        (is (= :db/transact (ffirst effects)))
        @(d/transact conn tx-data)
        (is (some? (:insurance.survey.response/completed-at
                    (q/retrieve-survey-response (d/db conn) response-id))))))

    (testing "an insurance-team member can close the active survey"
      (let [effects (actions/close-survey-action
                     (assoc state :db (d/db conn))
                     (assoc (survey-signals policy-id {})
                            :targetid (str survey-id)))
            [_ tx-data] (first effects)]
        (is (= :db/transact (ffirst effects)))
        @(d/transact conn tx-data)
        (is (some? (:insurance.survey/closed-at
                    (q/retrieve-survey (d/db conn) survey-id))))))))

(deftest send-reminders-action-builds-one-email-effect-for-incomplete-members
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)
        {:keys [survey-id]}
        (insurance-test/seed-member-survey!
         conn
         {:coverage-ids [coverage-id]
          :member-id    member-id
          :policy-id    policy-id})
        [effect] (actions/send-reminders-action
                  (assoc state :db (d/db conn))
                  (assoc (survey-signals policy-id {})
                         :targetid (str survey-id)))
        [_ payload] effect]
    (is (= :app.insurance/send-survey-notifications (first effect)))
    (is (= [member-id]
           (mapv :member/member-id (:members payload))))
    (is (= "Ada" (:sender-name payload)))
    (is (= [:insurance-survey-admin :result] (:result-path payload)))
    (is (= {:status :sent :count-sent 1} (:success payload)))))
