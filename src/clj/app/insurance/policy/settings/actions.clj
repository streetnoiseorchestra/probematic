(ns app.insurance.policy.settings.actions
  (:require
   [app.form :as form]
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.policy.settings.queries :as settings.queries]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]
   [datomic.api :as d]
   [tick.core :as t]))

(def form-key
  :insurance-policy-settings)

(def clear-coverage-type-create
  [:app.datastar/assoc-state [form-key :coverage-type-create] false])

(def clear-coverage-type
  [:app.datastar/assoc-state [form-key :coverage-type] false])

(defn- raw-policy-form
  [signals]
  (or (get-in signals [:insurancePolicySettings :policy])
      {}))

(defn- raw-coverage-type-form
  [signals]
  (or (get-in signals [:insurancePolicySettings :coverageType])
      {}))

(defn- decimal-value
  [value]
  (try
    (when-let [value (form/optional-text value)]
      (bigdec value))
    (catch Exception _
      nil)))

(defn- uuid-value
  [value]
  (try
    (some-> value form/optional-text util/ensure-uuid!)
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
    {:policy-id       (uuid-value (:policyId raw))
     :name            (or (form/trim-value (:name raw)) "")
     :effective-at    (or (form/trim-value (:effectiveAt raw)) "")
     :effective-until (or (form/trim-value (:effectiveUntil raw)) "")
     :premium-factor  (or (form/trim-value (:premiumFactor raw)) "")
     :currency        (or (form/trim-value (:currency raw)) "")}))

(defn- coverage-type-form
  [signals]
  (let [raw     (raw-coverage-type-form signals)
        type-id (uuid-value (:typeId raw))]
    (cond-> {:policy-id      (uuid-value (:policyId raw))
             :name           (or (form/trim-value (:name raw)) "")
             :description    (or (form/trim-value (:description raw)) "")
             :premium-factor (or (form/trim-value (:premiumFactor raw)) "")}
      type-id (assoc :type-id type-id))))

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

(defn- mutation-context-error-key
  [{:keys [current-member-id db]} policy]
  (cond
    (nil? policy)
    [:insurance.policy-settings/error-policy-not-found]

    (not (insurance-team-member? db current-member-id))
    [:insurance.policy-settings/error-not-allowed]

    (not (coverage.queries/policy-editable? policy))
    [:insurance.policy-settings/error-frozen-policy]))

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

(defn- coverage-type-id
  [{:insurance.coverage.type/keys [type-id]}]
  type-id)

(defn- policy-coverage-type-ids
  [policy]
  (set (map coverage-type-id (:insurance.policy/coverage-types policy))))

(defn- coverage-type-belongs-to-policy?
  [policy type-id]
  (contains? (policy-coverage-type-ids policy) type-id))

(defn- lower-name
  [value]
  (some-> value form/optional-text str/lower-case))

(defn- duplicate-coverage-type-name?
  [policy {:keys [name type-id]}]
  (let [normalized-name (lower-name name)]
    (boolean
     (when normalized-name
       (some (fn [{:insurance.coverage.type/keys [name] :as coverage-type}]
               (and (= normalized-name (lower-name name))
                    (not= type-id (coverage-type-id coverage-type))))
             (:insurance.policy/coverage-types policy))))))

(defn- coverage-type-field-validation-errors
  [tr policy {:keys [name premium-factor] :as coverage-type-form}]
  (let [premium-factor-value (decimal-value premium-factor)]
    (cond-> {}
      (str/blank? name)
      (assoc :name (required-error tr [:insurance/name]))

      (not (non-negative-decimal? premium-factor-value))
      (assoc :premium-factor (error tr [:insurance.policy-settings/error-invalid-premium-factor]))

      (and (not (str/blank? name))
           (duplicate-coverage-type-name? policy coverage-type-form))
      (assoc :name (error tr [:insurance.policy-settings/error-duplicate-coverage-type-name])))))

(defn- coverage-type-validation-errors
  [{:keys [db tr] :as state} {:keys [policy-id type-id] :as form} mode]
  (let [{:keys [policy]} (policy-context db form)
        context-error-key (cond
                            (not (uuid? policy-id))
                            [:insurance.policy-settings/error-policy-not-found]

                            :else
                            (mutation-context-error-key state policy))]
    (cond
      context-error-key
      {:_top (error tr context-error-key)}

      (and (= :update mode)
           (or (not (uuid? type-id))
               (not (coverage-type-belongs-to-policy? policy type-id))))
      {:_top (error tr [:insurance.policy-settings/error-coverage-type-not-found])}

      :else
      (let [field-errors (coverage-type-field-validation-errors tr policy form)]
        (cond-> field-errors
          (seq field-errors)
          (assoc :_top (error tr [:error/form-has-errors])))))))

(defn- policy-failure-effects
  [form errors]
  [support/clear-loading
   [:app.datastar/assoc-state
    [form-key :policy]
    (assoc form :_error errors)]])

(defn- coverage-type-failure-effects
  [state-key form errors]
  (let [form-state (cond-> form
                     (= :coverage-type-create state-key)
                     (assoc :open true))]
    [support/clear-loading
     [:app.datastar/assoc-state
      [form-key state-key]
      (assoc form-state :_error errors)]]))

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
      (policy-failure-effects form errors)
      [[:db/transact
        (support/with-audit (save-policy-details-tx-data form)
          current-member-id)
        {}]
       support/clear-loading
       [:app.datastar/assoc-state [form-key :policy] false]])))

(defn- create-coverage-type-tx-data
  [{:keys [policy-id name description premium-factor]}]
  (let [tempid "coverage-type-create"]
    [{:db/id                                  tempid
      :insurance.coverage.type/type-id        (sq/generate-squuid)
      :insurance.coverage.type/name           name
      :insurance.coverage.type/description    description
      :insurance.coverage.type/premium-factor (decimal-value premium-factor)}
     [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/coverage-types tempid]]))

(defn create-coverage-type-action
  [{:keys [current-member-id] :as state} signals]
  (let [form   (coverage-type-form signals)
        errors (coverage-type-validation-errors state form :create)]
    (if (seq errors)
      (coverage-type-failure-effects :coverage-type-create form errors)
      [[:db/transact
        (support/with-audit (create-coverage-type-tx-data form)
          current-member-id)
        {}]
       support/clear-loading
       clear-coverage-type-create])))

(defn- update-coverage-type-tx-data
  [{:keys [type-id name description premium-factor]}]
  (let [type-ref [:insurance.coverage.type/type-id type-id]]
    [[:db/add type-ref :insurance.coverage.type/name name]
     [:db/add type-ref :insurance.coverage.type/description description]
     [:db/add type-ref :insurance.coverage.type/premium-factor (decimal-value premium-factor)]]))

(defn update-coverage-type-action
  [{:keys [current-member-id] :as state} signals]
  (let [form   (coverage-type-form signals)
        errors (coverage-type-validation-errors state form :update)]
    (if (seq errors)
      (coverage-type-failure-effects :coverage-type form errors)
      [[:db/transact
        (support/with-audit (update-coverage-type-tx-data form)
          current-member-id)
        {}]
       support/clear-loading
       clear-coverage-type])))

(defn- policy-id-for-coverage-type
  [db type-id]
  (when (uuid? type-id)
    (d/q '[:find ?policy-id .
           :in $ ?type-id
           :where
           [?type :insurance.coverage.type/type-id ?type-id]
           [?policy :insurance.policy/coverage-types ?type]
           [?policy :insurance.policy/policy-id ?policy-id]]
         db
         type-id)))

(defn- coverage-type-usage-count
  [policy type-id]
  (count
   (filter (fn [coverage]
             (contains? (set (map coverage-type-id (:instrument.coverage/types coverage)))
                        type-id))
           (:insurance.policy/covered-instruments policy))))

(defn- delete-coverage-type-form
  [{:keys [targetid]}]
  {:type-id (uuid-value targetid)})

(defn- delete-coverage-type-validation-errors
  [{:keys [db tr] :as state} {:keys [type-id]}]
  (let [policy-id (policy-id-for-coverage-type db type-id)
        policy    (when policy-id (retrieve-policy db policy-id))
        context-error-key (mutation-context-error-key state policy)]
    (cond
      (not (uuid? type-id))
      {:_top (error tr [:insurance.policy-settings/error-coverage-type-not-found])}

      (nil? policy-id)
      {:_top (error tr [:insurance.policy-settings/error-coverage-type-not-found])}

      context-error-key
      {:_top (error tr context-error-key)}

      (pos? (coverage-type-usage-count policy type-id))
      {:_top (error tr [:insurance.policy-settings/error-coverage-type-in-use])})))

(defn delete-coverage-type-action
  [{:keys [current-member-id db] :as state} signals]
  (let [form      (delete-coverage-type-form signals)
        policy-id (policy-id-for-coverage-type db (:type-id form))
        errors    (delete-coverage-type-validation-errors state form)]
    (if (seq errors)
      (coverage-type-failure-effects :coverage-type-delete form errors)
      [[:db/transact
        (support/with-audit
          [[:db/retract [:insurance.policy/policy-id policy-id]
            :insurance.policy/coverage-types
            [:insurance.coverage.type/type-id (:type-id form)]]
           [:db/retractEntity [:insurance.coverage.type/type-id (:type-id form)]]]
          current-member-id)
        {}]
       support/clear-loading
       [:app.datastar/assoc-state [form-key :coverage-type-delete] false]])))

(defn open-coverage-type-create-action
  [_state {:keys [targetid]}]
  [support/clear-loading
   [:app.datastar/assoc-state
    [form-key :coverage-type-create]
    {:open           true
     :policy-id      (uuid-value targetid)
     :name           ""
     :description    ""
     :premium-factor ""}]])

(defn close-coverage-type-create-action
  [_state _signals]
  [support/clear-loading clear-coverage-type-create])

(defn- coverage-type-entity
  [db type-id]
  (when (uuid? type-id)
    (d/entity db [:insurance.coverage.type/type-id type-id])))

(defn open-coverage-type-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [type-id       (uuid-value targetid)
        coverage-type (coverage-type-entity db type-id)]
    [support/clear-loading
     [:app.datastar/assoc-state
      [form-key :coverage-type]
      {:policy-id      (policy-id-for-coverage-type db type-id)
       :type-id        type-id
       :name           (:insurance.coverage.type/name coverage-type)
       :description    (or (:insurance.coverage.type/description coverage-type) "")
       :premium-factor (:insurance.coverage.type/premium-factor coverage-type)}]]))

(defn close-coverage-type-edit-action
  [_state _signals]
  [support/clear-loading clear-coverage-type])

(def actions
  {::save-policy-details          #'save-policy-details-action
   ::open-coverage-type-create    #'open-coverage-type-create-action
   ::close-coverage-type-create   #'close-coverage-type-create-action
   ::create-coverage-type         #'create-coverage-type-action
   ::open-coverage-type-edit      #'open-coverage-type-edit-action
   ::close-coverage-type-edit     #'close-coverage-type-edit-action
   ::update-coverage-type         #'update-coverage-type-action
   ::delete-coverage-type         #'delete-coverage-type-action})
