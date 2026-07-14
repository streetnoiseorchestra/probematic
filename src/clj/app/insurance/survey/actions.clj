(ns app.insurance.survey.actions
  (:require
   [app.form :as form]
   [app.insurance.coverage.edit.actions :as coverage-edit.actions]
   [app.insurance.domain :as domain]
   [app.insurance.survey.flow :as flow]
   [app.insurance.survey.queries :as queries]
   [app.nexus.actions :as support]
   [app.util :as util]))

(def form-key :insurance-survey)
(def signal-key :insuranceSurvey)

(defn- data-for [{:keys [current-member-id db now]} signals]
  (let [policy-id (some-> (get-in signals [signal-key :policyId])
                          form/optional-text
                          util/ensure-uuid!)]
    (queries/survey-data db policy-id current-member-id now)))

(defn- report-id [report]
  (:insurance.survey.report/report-id report))

(defn- initial-state [{:keys [active-report]}]
  {:answered-count   0
   :current-flow-key flow/start-key
   :decisions        []
   :mode             :question
   :report-id        (report-id active-report)
   :transition-kind  :none})

(defn page-state [{:keys [page-state]} data]
  (let [stored (get page-state form-key)
        fresh  (initial-state data)]
    (if (= (:report-id fresh) (:report-id stored))
      (merge fresh stored)
      fresh)))

(defn- state-effect [value]
  [:app.datastar/assoc-state [form-key] value])

(defn- error-effects [{:keys [tr] :as state} data message-key]
  (let [current (page-state state data)]
    [support/clear-loading
     (state-effect (assoc current :error (tr [message-key])))]))

(defn- next-page-state [{:keys [current-index todo-reports total-todo]}]
  (let [next-report       (second todo-reports)
        next-index        (inc current-index)
        remaining         (dec total-todo)
        milestone?        (and next-report
                               (queries/show-milestone? next-index remaining))]
    {:answered-count   0
     :current-flow-key flow/start-key
     :decisions        []
     :milestone?       (boolean milestone?)
     :mode             :question
     :report-id        (report-id next-report)
     :transition-kind  :item}))

(defn- complete-report-tx [state data decisions extra-tx]
  (let [{:keys [active-report response]} data]
    (support/with-audit
      (concat extra-tx
              (mapcat #(domain/txs-for-decision active-report %) decisions)
              (domain/txs-complete-survey-report active-report (:now state))
              (domain/txs-maybe-survey-response-complete
               active-report response (:now state)))
      (:current-member-id state))))

(defn transition-action
  [state signals]
  (let [data       (data-for state signals)
        page-state (page-state state data)
        answer-id  (domain/simple-keyword (get-in signals [signal-key :answer]))
        transition (flow/transition (:current-flow-key page-state) answer-id)]
    (cond
      (not= :active (:status data))
      (error-effects state data :insurance/review-not-available)

      (nil? transition)
      (error-effects state data :insurance/review-invalid-transition)

      :else
      (let [next-step (:next transition)
            answered-count (inc (or (:answered-count page-state) 0))
            decisions (->> (concat (:decisions page-state)
                                   (:decisions transition))
                           distinct
                           vec)]
        (case next-step
          :complete
          [[:db/transact (complete-report-tx state data decisions []) {}]
           support/clear-loading
           (state-effect (next-page-state data))]

          :data-edit
          [support/clear-loading
           (state-effect (-> page-state
                             (assoc :current-flow-key :data-edit
                                    :answered-count answered-count
                                    :decisions decisions
                                    :mode :edit
                                    :transition-kind :question)
                             (dissoc :error :milestone?)))]

          [support/clear-loading
           (state-effect (-> page-state
                             (assoc :current-flow-key next-step
                                    :answered-count answered-count
                                    :decisions decisions
                                    :mode :question
                                    :transition-kind :question)
                             (dissoc :error :milestone?)))])))))

(defn dismiss-action [state signals]
  (let [{:keys [response status total-todo] :as data} (data-for state signals)]
    (if (and (= :empty status) (zero? total-todo))
      [[:db/transact
        (support/with-audit
          [[:db/add (domain/response-ref response)
            :insurance.survey.response/completed-at
            (:now state)]]
          (:current-member-id state))
        {}]
       support/clear-loading
       (state-effect {})]
      (error-effects state data :insurance/review-cannot-finish))))

(defn- edit-params [data signals]
  (let [coverage   (:insurance.survey.report/coverage (:active-report data))
        instrument (:instrument.coverage/instrument coverage)
        edit       (get-in signals [signal-key :edit])]
    (coverage-edit.actions/normalize-form
     {:policy-id       (str (get-in data [:policy :insurance.policy/policy-id]))
      :coverage-id     (str (:instrument.coverage/coverage-id coverage))
      :instrument-id   (str (:instrument/instrument-id instrument))
      :instrument-name (:instrumentName edit)
      :owner-member-id (str (get-in instrument [:instrument/owner :member/member-id]))
      :category-id     (:categoryId edit)
      :make            (:make edit)
      :model           (:model edit)
      :serial-number   (:serialNumber edit)
      :build-year      (:buildYear edit)
      :description     (:description edit)
      :item-count      (:itemCount edit)
      :value           (:value edit)
      :private-band    (if (:instrument.coverage/private? coverage) "private" "band")
      :coverage-types  (:coverageTypes edit)
      :insurer-id      (:insurerId edit)})))

(defn- coverage-context [data params]
  (let [coverage   (:insurance.survey.report/coverage (:active-report data))
        instrument (:instrument.coverage/instrument coverage)]
    {:coverage      coverage
     :coverage-id   (:instrument.coverage/coverage-id coverage)
     :instrument    instrument
     :instrument-id (:instrument/instrument-id instrument)
     :policy        (:policy data)
     :policy-id     (:insurance.policy/policy-id (:policy data))
     :params        params}))

(defn save-edit-action
  [{:keys [tr] :as state} signals]
  (let [data       (data-for state signals)
        page-state (page-state state data)]
    (if (or (not= :active (:status data))
            (not= :edit (:mode page-state)))
      (error-effects state data :insurance/review-invalid-transition)
      (let [params (edit-params data signals)
            errors (coverage-edit.actions/validation-errors
                    state params {:allow-frozen-policy? true})
            errors (cond-> errors
                     (seq errors)
                     (assoc :_top {:error (tr [:error/form-has-errors])}))]
        (if (seq errors)
          [support/clear-loading
           (state-effect (assoc page-state
                                :edit (assoc params :_error errors)
                                :error nil))]
          (let [coverage-tx (coverage-edit.actions/update-instrument-coverage-tx-data
                             state
                             (coverage-context data params)
                             params)]
            [[:db/transact
              (complete-report-tx state data (:decisions page-state) coverage-tx)
              {}]
             support/clear-loading
             (state-effect (next-page-state data))]))))))

(def actions
  {::dismiss-review  #'dismiss-action
   ::save-edit       #'save-edit-action
   ::transition      #'transition-action})
