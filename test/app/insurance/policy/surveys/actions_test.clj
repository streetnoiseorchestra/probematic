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

(deftest create-and-update-survey-actions
  (let [{:keys [conn policy-id state]} (fixture)
        effects (actions/save-survey-action
                 state
                 (survey-signals policy-id
                                 {:name     "Spring coverage review"
                                  :closesAt "2026-04-20T20:00"}))
        [_ tx-data] (first effects)]
    (testing "creating a survey snapshots every active member and their current coverages"
      (is (= :db/transact (ffirst effects)))
      (is (= 2 (count (filter :insurance.survey.response/response-id tx-data))))
      (is (= 1 (count (filter :insurance.survey.report/report-id tx-data))))
      (is (= [:db/add "datomic.tx" :audit/user
              [:member/member-id (:current-member-id state)]]
             (last tx-data)))
      (is (some #{:db/gen-uuid}
                (tree-seq coll? seq tx-data)))
      @(d/transact conn (nexus/batch-transactions [[tx-data {}]]))
      (let [survey (first (q/surveys-for-policy (d/db conn)
                                                (q/retrieve-policy (d/db conn) policy-id)))]
        (is (= "Spring coverage review" (:insurance.survey/survey-name survey)))
        (is (= 2 (count (:insurance.survey/responses survey))))
        (is (= [0 1]
               (sort (map (comp count :insurance.survey.response/coverage-reports)
                          (:insurance.survey/responses survey)))))))

    (testing "the active survey can be renamed and rescheduled"
      (let [db        (d/db conn)
            survey   (first (q/surveys-for-policy db (q/retrieve-policy db policy-id)))
            survey-id (:insurance.survey/survey-id survey)
            effects  (actions/save-survey-action
                      (assoc state :db db)
                      (survey-signals policy-id
                                      {:surveyId (str survey-id)
                                       :name     "Updated review"
                                       :closesAt "2026-04-25T19:30"}))
            [_ tx-data] (first effects)]
        (is (= :db/transact (ffirst effects)))
        @(d/transact conn tx-data)
        (is (= "Updated review"
               (:insurance.survey/survey-name
                (q/retrieve-survey (d/db conn) survey-id))))))))

(deftest survey-mutations-validate-input-and-membership
  (let [{:keys [outsider-id policy-id state]} (fixture)]
    (testing "invalid form data stays on the page with field errors"
      (let [effects (actions/save-survey-action
                     state
                     (survey-signals policy-id {:name "" :closesAt "not-a-date"}))]
        (is (not-any? #(= :db/transact (first %)) effects))
        (is (some? (get-in effects [1 2 :form :_error :name :error])))
        (is (some? (get-in effects [1 2 :form :_error :closes-at :error])))))

    (testing "a non-insurance-team member cannot create a survey"
      (let [effects (actions/save-survey-action
                     (assoc state :current-member-id outsider-id)
                     (survey-signals policy-id
                                     {:name     "Forged review"
                                      :closesAt "2026-04-20T20:00"}))]
        (is (not-any? #(= :db/transact (first %)) effects))
        (is (some? (get-in effects [1 2 :form :_error :_top :error])))))))

(deftest survey-deadlines-are-enforced
  (let [{:keys [conn coverage-id member-id policy-id state]} (fixture)]
    (testing "a survey cannot be created with a deadline in the past"
      (let [effects (actions/save-survey-action
                     state
                     (survey-signals policy-id
                                     {:name     "Expired review"
                                      :closesAt "2026-03-01T20:00"}))]
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
