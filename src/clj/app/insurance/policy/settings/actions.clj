(ns app.insurance.policy.settings.actions
  (:require
   [app.form :as form]
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.policy.settings.queries :as settings.queries]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]
   [tick.core :as t]))

(def form-key
  :insurance-policy-settings)

(defn- raw-policy-form
  [signals]
  (or (get-in signals [:insurancePolicySettings :policy])
      {}))

(defn- decimal-value
  [value]
  (try
    (when-let [value (form/optional-text value)]
      (bigdec value))
    (catch Exception _
      nil)))

(defn- date-inst
  [value]
  (when-let [date (form/parse-date value)]
    (-> date (t/at (t/midnight)) t/inst)))

(defn- currency-value
  [value]
  (when-let [currency (some-> value form/optional-text keyword)]
    (when (contains? (set settings.queries/supported-currencies) currency)
      currency)))

(defn- policy-form
  [signals]
  (let [raw (raw-policy-form signals)]
    {:policy-id       (try
                        (some-> (:policyId raw) form/optional-text util/ensure-uuid!)
                        (catch Exception _
                          nil))
     :name            (or (form/trim-value (:name raw)) "")
     :effective-at    (or (form/trim-value (:effectiveAt raw)) "")
     :effective-until (or (form/trim-value (:effectiveUntil raw)) "")
     :premium-factor  (or (form/trim-value (:premiumFactor raw)) "")
     :currency        (or (form/trim-value (:currency raw)) "")}))

(defn- retrieve-policy
  [db policy-id]
  (when (d/entid db [:insurance.policy/policy-id policy-id])
    (q/retrieve-policy db policy-id)))

(defn- policy-context
  [db {:keys [policy-id]}]
  {:policy-id policy-id
   :policy    (when (uuid? policy-id)
                (retrieve-policy db policy-id))})

(defn- insurance-team-member?
  [db current-member-id]
  (boolean
   (when-let [member (and current-member-id (q/retrieve-member db current-member-id))]
     (q/insurance-team-member? db member))))

(defn- required-error
  [tr label-key]
  {:error (tr [:error/is-required] [(tr label-key)])})

(defn- error
  [tr key]
  {:error (tr key)})

(defn- non-negative-decimal?
  [value]
  (boolean
   (when value
     (not (neg? (compare value 0M))))))

(defn- field-validation-errors
  [tr {:keys [name effective-at effective-until premium-factor currency]}]
  (let [effective-at-inst    (date-inst effective-at)
        effective-until-inst (date-inst effective-until)
        premium-factor-value (decimal-value premium-factor)
        currency-value       (currency-value currency)]
    (cond-> {}
      (str/blank? name)
      (assoc :name (required-error tr [:insurance/name]))

      (nil? effective-at-inst)
      (assoc :effective-at (error tr [:insurance.policy-settings/error-invalid-date]))

      (nil? effective-until-inst)
      (assoc :effective-until (error tr [:insurance.policy-settings/error-invalid-date]))

      (and effective-at-inst
           effective-until-inst
           (not (pos? (compare effective-until-inst effective-at-inst))))
      (assoc :effective-until (error tr [:insurance.policy-settings/error-effective-until-before-effective-at]))

      (not (non-negative-decimal? premium-factor-value))
      (assoc :premium-factor (error tr [:insurance.policy-settings/error-invalid-premium-factor]))

      (nil? currency-value)
      (assoc :currency (error tr [:insurance.policy-settings/error-invalid-currency])))))

(defn- validation-errors
  [{:keys [current-member-id db tr]} form]
  (let [{:keys [policy-id policy]} (policy-context db form)
        context-error-key (cond
                            (not (uuid? policy-id))
                            [:insurance.policy-settings/error-policy-not-found]

                            (nil? policy)
                            [:insurance.policy-settings/error-policy-not-found]

                            (not (insurance-team-member? db current-member-id))
                            [:insurance.policy-settings/error-not-allowed]

                            (not (coverage.queries/policy-editable? policy))
                            [:insurance.policy-settings/error-frozen-policy])]
    (if context-error-key
      {:_top (error tr context-error-key)}
      (let [field-errors (field-validation-errors tr form)]
        (cond-> field-errors
          (seq field-errors)
          (assoc :_top (error tr [:error/form-has-errors])))))))

(defn- failure-effects
  [form errors]
  [support/clear-loading
   [:app.datastar/assoc-state
    [form-key :policy]
    (assoc form :_error errors)]])

(defn- save-policy-details-tx-data
  [{:keys [policy-id name effective-at effective-until premium-factor currency]}]
  (let [policy-ref [:insurance.policy/policy-id policy-id]]
    [[:db/add policy-ref :insurance.policy/name name]
     [:db/add policy-ref :insurance.policy/effective-at (date-inst effective-at)]
     [:db/add policy-ref :insurance.policy/effective-until (date-inst effective-until)]
     [:db/add policy-ref :insurance.policy/premium-factor (decimal-value premium-factor)]
     [:db/add policy-ref :insurance.policy/currency (currency-value currency)]]))

(defn save-policy-details-action
  [{:keys [current-member-id] :as state} signals]
  (let [form   (policy-form signals)
        errors (validation-errors state form)]
    (if (seq errors)
      (failure-effects form errors)
      [[:db/transact
        (support/with-audit (save-policy-details-tx-data form)
          current-member-id)
        {}]
       support/clear-loading
       [:app.datastar/assoc-state [form-key :policy :_error] nil]])))

(def actions
  {::save-policy-details #'save-policy-details-action})
