(ns app.insurance.policy.surveys.actions
  (:require
   [app.form :as form]
   [app.insurance.domain :as domain]
   [app.insurance.policy.surveys.queries :as queries]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]
   [tick.core :as t]))

(def form-key :insurance-survey-admin)
(def signal-key :insuranceSurveyAdmin)

(defn- safe-uuid
  [value]
  (when-let [value (form/optional-text value)]
    (try
      (util/ensure-uuid! value)
      (catch Exception _
        nil))))

(defn- parse-date-time
  [value]
  (when-let [value (form/optional-text value)]
    (try
      (t/date-time value)
      (catch Exception _
        nil))))

(defn- action-context
  [{:keys [current-member-id db]} signals]
  (let [params    (or (signal-key signals) {})
        policy-id (safe-uuid (:policyId params))
        policy    (when policy-id
                    (try
                      (q/retrieve-policy db policy-id)
                      (catch Exception _
                        nil)))
        member    (when current-member-id
                    (q/retrieve-member db current-member-id))]
    {:authorized? (boolean (and member
                                (q/insurance-team-member? db member)))
     :params      params
     :policy      policy
     :policy-id   policy-id}))

(defn- context-error-key
  [{:keys [authorized? policy policy-id]}]
  (cond
    (or (nil? policy-id) (nil? policy)) :insurance/survey-error-not-found
    (not authorized?)                    :insurance/survey-error-not-allowed))

(defn- form-error-effects
  [{:keys [tr]} form errors]
  [support/clear-loading
   [:app.datastar/assoc-state
    [form-key]
    {:form (assoc form :_error
                  (cond-> errors
                    (and (seq errors) (nil? (:_top errors)))
                    (assoc :_top {:error (tr [:error/form-has-errors])})))}]])

(defn- result-effects
  [result]
  [support/clear-loading
   [:app.datastar/assoc-state [form-key :result] result]])

(defn- survey-form
  [params]
  {:policy-id (safe-uuid (:policyId params))
   :survey-id (safe-uuid (:surveyId params))
   :name       (form/trim-value (:name params))
   :closes-at  (form/trim-value (:closesAt params))})

(defn- survey-form-errors
  [{:keys [now tr]} {:keys [closes-at name]}]
  (let [closes-at (parse-date-time closes-at)]
    (cond-> {}
      (str/blank? name)
      (assoc :name {:error (tr [:insurance/survey-name-required])})

      (nil? closes-at)
      (assoc :closes-at {:error (tr [:insurance/survey-closes-at-invalid])})

      (and closes-at
           (not (domain/survey-open-at?
                 now
                 {:insurance.survey/closes-at closes-at})))
      (assoc :closes-at {:error (tr [:insurance/survey-closes-at-future])}))))

(defn new-survey-tx-data
  [db policy survey-name closes-at now]
  (let [members (queries/members-for-survey db policy)
        member-survey-txs
        (mapv
         (fn [member-index {:keys [coverages] :member/keys [member-id]}]
           (let [report-txs
                 (mapv
                  (fn [report-index
                       {:instrument.coverage/keys [coverage-id]}]
                    {:db/id (str "insurance-survey-report-"
                                 member-index "-" report-index)
                     :insurance.survey.report/report-id
                     [:db/gen-uuid [:insurance-survey-report
                                    member-index report-index]]
                     :insurance.survey.report/coverage
                     [:instrument.coverage/coverage-id coverage-id]})
                  (range)
                  coverages)
                 report-tempids (mapv :db/id report-txs)
                 response-tempid (str "insurance-survey-response-"
                                      member-index)
                 response-tx
                 (cond->
                  {:db/id response-tempid
                   :insurance.survey.response/response-id
                   [:db/gen-uuid [:insurance-survey-response member-index]]
                   :insurance.survey.response/member
                   [:member/member-id member-id]}
                   (seq report-tempids)
                   (assoc :insurance.survey.response/coverage-reports
                          report-tempids))]
             {:response-tempid response-tempid
              :tx-data         (conj report-txs response-tx)}))
         (range)
         members)
        response-tempids (mapv :response-tempid member-survey-txs)]
    (when (seq response-tempids)
      (conj
       (vec (mapcat :tx-data member-survey-txs))
       {:db/id "insurance-survey"
        :insurance.survey/survey-id [:db/gen-uuid :insurance-survey]
        :insurance.survey/survey-name survey-name
        :insurance.survey/policy
        [:insurance.policy/policy-id
         (:insurance.policy/policy-id policy)]
        :insurance.survey/created-at (t/inst now)
        :insurance.survey/closes-at (domain/closes-at-inst closes-at)
        :insurance.survey/responses response-tempids}))))

(defn save-survey-action
  [{:keys [current-member-id db now tr] :as state} signals]
  (let [{:keys [params policy policy-id] :as context}
        (action-context state signals)
        form          (survey-form params)
        survey-id     (:survey-id form)
        survey        (when survey-id
                        (try
                          (q/retrieve-survey db survey-id)
                          (catch Exception _
                            nil)))
        context-error (context-error-key context)
        errors        (merge
                       (survey-form-errors state form)
                       (when context-error
                         {:_top {:error (tr [context-error])}})
                       (when (and survey-id
                                  (or (nil? survey)
                                      (not (queries/survey-belongs-to-policy?
                                            survey policy-id))))
                         {:_top {:error (tr [:insurance/survey-error-not-found])}})
                       (when (and survey (:insurance.survey/closed-at survey))
                         {:_top {:error (tr [:insurance/survey-error-closed])}})
                       (when (and survey
                                  (not (domain/survey-open-at? now survey)))
                         {:_top {:error (tr [:insurance/survey-error-expired])}})
                       (when (and (nil? survey-id)
                                  policy-id
                                  policy
                                  (queries/policy-has-open-survey-at?
                                   db policy now))
                         {:_top {:error (tr [:insurance/survey-error-open-exists])}}))]
    (if (seq errors)
      (form-error-effects state form errors)
      (let [tx-data (if survey-id
                      (domain/txs-update-survey
                       survey-id
                       (:name form)
                       (parse-date-time (:closes-at form)))
                      (new-survey-tx-data
                       db
                       policy
                       (:name form)
                       (parse-date-time (:closes-at form))
                       now))]
        (if (seq tx-data)
          [[:db/transact
            (support/with-audit tx-data current-member-id)
            {}]
           [:app.datastar/assoc-state
            [form-key]
            {:result {:status (if survey-id :updated :created)}}]
           support/clear-loading]
          (form-error-effects
           state
           form
           {:_top {:error (tr [:insurance/survey-error-no-members])}}))))))

(defn- survey-target
  [db signals]
  (when-let [survey-id (safe-uuid (:targetid signals))]
    (try
      (q/retrieve-survey db survey-id)
      (catch Exception _
        nil))))

(defn- mutation-error-key
  [context entity belongs? closed? expired?]
  (or (context-error-key context)
      (when (or (nil? entity) (not belongs?))
        :insurance/survey-error-not-found)
      (when closed?
        :insurance/survey-error-closed)
      (when expired?
        :insurance/survey-error-expired)))

(defn close-survey-action
  [{:keys [current-member-id db now tr] :as state} signals]
  (let [{:keys [policy-id] :as context} (action-context state signals)
        survey    (survey-target db signals)
        error-key (mutation-error-key
                   context
                   survey
                   (queries/survey-belongs-to-policy? survey policy-id)
                   (:insurance.survey/closed-at survey)
                   false)]
    (if error-key
      (result-effects {:status :error :message (tr [error-key])})
      [[:db/transact
        (support/with-audit
          [[:db/add
            [:insurance.survey/survey-id
             (:insurance.survey/survey-id survey)]
            :insurance.survey/closed-at
            (t/inst now)]]
          current-member-id)
        {}]
       [:app.datastar/remove-signals [(name signal-key)]]
       [:app.datastar/assoc-state [form-key :result] {:status :closed}]
       support/clear-loading])))

(defn toggle-response-action
  [{:keys [current-member-id db now tr] :as state} signals]
  (let [{:keys [policy-id] :as context} (action-context state signals)
        response-id (safe-uuid (:targetid signals))
        response    (when response-id
                      (try
                        (q/retrieve-survey-response db response-id)
                        (catch Exception _
                          nil)))
        error-key   (mutation-error-key
                     context
                     response
                     (queries/response-belongs-to-policy? response policy-id)
                     (get-in response [:survey :insurance.survey/closed-at])
                     (not (domain/survey-open-at? now (:survey response))))]
    (if error-key
      (result-effects {:status :error :message (tr [error-key])})
      [[:db/transact
        (support/with-audit
          (domain/txs-toggle-response-completion response now)
          current-member-id)
        {}]
       support/clear-loading])))

(defn send-reminders-action
  [{:keys [current-member-id db now tr] :as state} signals]
  (let [{:keys [policy policy-id] :as context} (action-context state signals)
        survey    (survey-target db signals)
        error-key (mutation-error-key
                   context
                   survey
                   (queries/survey-belongs-to-policy? survey policy-id)
                   (:insurance.survey/closed-at survey)
                   (not (domain/survey-open-at? now survey)))]
    (if error-key
      (result-effects {:status :error :message (tr [error-key])})
      (let [responses   (:insurance.survey/responses survey)
            incomplete (filterv #(nil? (:insurance.survey.response/completed-at %))
                                responses)
            most-items (when (seq responses)
                         (apply max-key
                                (comp count
                                      :insurance.survey.response/coverage-reports)
                                responses))]
        (if (empty? incomplete)
          (result-effects {:status :empty})
          [[:app.insurance/send-survey-notifications
            {:email-data      {:closes-at
                               (:insurance.survey/closes-at survey)
                               :member-most-instruments
                               (:insurance.survey.response/member most-items)
                               :member-most-instrument-count
                               (count (:insurance.survey.response/coverage-reports
                                       most-items))}
             :failure-message (tr [:insurance/survey-reminders-failed])
             :members         (mapv :insurance.survey.response/member incomplete)
             :policy          policy
             :result-path     [form-key :result]
             :sender-name     (:member/name
                               (q/retrieve-member db current-member-id))
             :success         {:status     :sent
                               :count-sent (count incomplete)}}]])))))

(def actions
  {::close-survey    #'close-survey-action
   ::save-survey     #'save-survey-action
   ::send-reminders  #'send-reminders-action
   ::toggle-response #'toggle-response-action})
