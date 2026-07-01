(ns app.insurance.policy.workbench.actions
  (:require
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.domain :as domain]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]))

(def form-key :insurance-workbench)

(def selection-signal-path
  "insuranceWorkbench.selectedCoverageIds")

(defn- error-effects
  [tr key]
  [support/clear-loading
   [:app.datastar/assoc-state [form-key :error]
    {:error (tr key)}]])

(defn- success-effects
  [current-member-id tx-data]
  [[:db/transact
    (support/with-audit tx-data current-member-id)
    {}]
   support/clear-loading
   [:app.datastar/assoc-state [form-key :error] nil]
   [:app.datastar/remove-signals [selection-signal-path]]])

(def invalid-uuid ::invalid-uuid)

(defn- ensure-uuid-or-invalid
  [value]
  (try
    (util/ensure-uuid! value)
    (catch Exception _
      invalid-uuid)))

(defn- split-id-value
  [value]
  (cond
    (nil? value) nil
    (sequential? value) (mapcat split-id-value value)
    :else (str/split (str value) #",")))

(defn- policy-id
  [signals]
  (some-> (:policyId signals) ensure-uuid-or-invalid))

(defn- selected-coverage-ids
  [signals]
  (->> (:selectedCoverageIds signals)
       split-id-value
       (mapv (comp ensure-uuid-or-invalid str/trim str))))

(defn- target-status
  [signals]
  (get domain/bulk-workflow-target-statuses
       (domain/simple-keyword (:targetWorkflowStatus signals))))

(defn- insurance-team-member?
  [db current-member-id]
  (when-let [member (and current-member-id (q/retrieve-member db current-member-id))]
    (q/insurance-team-member? db member)))

(defn- coverage-policy-id
  [coverage]
  (get-in coverage [:insurance.policy/_covered-instruments :insurance.policy/policy-id]))

(defn- coverage-status-tx
  [status coverage-id]
  [:db/add
   [:instrument.coverage/coverage-id coverage-id]
   :instrument.coverage/status
   status])

(defn- filter-field-key
  [value]
  (domain/simple-keyword value))

(defn- selected-category-ids
  [draft]
  (->> (:categoryIds draft)
       split-id-value
       (keep (fn [value]
               (let [category-id (ensure-uuid-or-invalid (str/trim (str value)))]
                 (when (uuid? category-id)
                   category-id))))
       vec))

(defn- selected-ownership
  [draft]
  (let [ownership (filter-field-key (:ownership draft))]
    (if (contains? domain/coverage-ownership-set ownership)
      ownership
      :all)))

(defn- selected-simple-values
  [supported values]
  (->> values
       split-id-value
       (keep (fn [value]
               (let [value (filter-field-key value)]
                 (when (contains? supported value)
                   value))))
       vec))

(defn- truthy-filter?
  [value]
  (or (true? value)
      (= "true" value)))

(defn- clear-filter-editor-signals
  [field]
  [:app.datastar/merge-signals
   {:insuranceWorkbench {:filterEditor  {:field        ""
                                         :source       nil
                                         :appliedField (some-> field name)}
                         :filterPopover {:open false}}}])

(defn apply-filter-action
  [_state signals]
  (let [signals (:insuranceWorkbench signals)
        field   (filter-field-key (get-in signals [:filterEditor :field]))
        draft   (:filterDraft signals)]
    (case field
      :category
      [[:app.datastar/assoc-state
        [form-key :filters :category-ids]
        (selected-category-ids draft)]
       (clear-filter-editor-signals field)]

      :ownership
      [[:app.datastar/assoc-state
        [form-key :filters :ownership]
        (selected-ownership draft)]
       (clear-filter-editor-signals field)]

      :coverage-types
      [[:app.datastar/assoc-state
        [form-key :filters :coverage-type-ids]
        (selected-category-ids {:categoryIds (:coverageTypeIds draft)})]
       (clear-filter-editor-signals field)]

      :photos
      [[:app.datastar/assoc-state
        [form-key :filters :missing-photos?]
        (truthy-filter? (:missingPhotos draft))]
       (clear-filter-editor-signals field)]

      :harmonia-id
      [[:app.datastar/assoc-state
        [form-key :filters :missing-harmonia-id?]
        (truthy-filter? (:missingHarmoniaId draft))]
       (clear-filter-editor-signals field)]

      :workflow
      [[:app.datastar/assoc-state
        [form-key :filters :workflow-statuses]
        (selected-simple-values domain/simple-instrument-coverage-status-set (:workflowStatuses draft))]
       (clear-filter-editor-signals field)]

      :change
      [[:app.datastar/assoc-state
        [form-key :filters :change-statuses]
        (selected-simple-values domain/simple-instrument-coverage-change-set (:changeStatuses draft))]
       (clear-filter-editor-signals field)]

      [[:app.datastar/assoc-state [form-key :filters :last-applied-field] (some-> field name)]
       (clear-filter-editor-signals field)])))

(defn bulk-update-workflow-status-action
  [{:keys [current-member-id db tr]} signals]
  (let [signals      (:insuranceWorkbench signals)
        policy-id    (policy-id signals)
        selected-ids (selected-coverage-ids signals)
        status       (target-status signals)
        policy       (when (uuid? policy-id) (q/retrieve-policy db policy-id))
        coverages    (mapv #(when (uuid? %) (q/retrieve-coverage db %)) selected-ids)]
    (cond
      (empty? selected-ids)
      (error-effects tr [:insurance.workbench/error-empty-selection])

      (nil? status)
      (error-effects tr [:insurance.workbench/error-invalid-target-status])

      (not (insurance-team-member? db current-member-id))
      (error-effects tr [:insurance.workbench/error-not-allowed])

      (not (coverage.queries/policy-editable? policy))
      (error-effects tr [:insurance.workbench/error-frozen-policy])

      (some nil? coverages)
      (error-effects tr [:insurance.workbench/error-coverage-not-found])

      (not-every? #(= policy-id (coverage-policy-id %)) coverages)
      (error-effects tr [:insurance.workbench/error-coverage-not-in-policy])

      :else
      (success-effects current-member-id
                       (mapv (partial coverage-status-tx status) selected-ids)))))

(def actions
  {::bulk-update-workflow-status #'bulk-update-workflow-status-action
   ::apply-filter                 #'apply-filter-action})
