(ns app.insurance.policy.settings.views
  (:require
   [app.datastar :as d*]
   [app.icons :as icons]
   [app.insurance.policy.settings.actions :as actions]
   [app.insurance.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]))

(defn- settings-card
  [{:keys [actions subtitle title]} & children]
  (into [card/Card {:appearance "plain"
                    :style      "background: var(--wa-color-surface-default);"}
         [:div {:slot  "header"
                :class "wa-stack wa-gap-2xs"}
          [:h2 {:class "wa-heading-l"
                :style "margin: 0;"}
           title]
          (when subtitle
            [:p {:class "wa-caption-s"
                 :style "margin: 0;"}
             subtitle])]
         (when (seq actions)
           (into [:div {:slot  "header-actions"
                        :class "wa-cluster wa-gap-xs"}]
                 actions))]
        children))

(defn- page-state-map
  [req key]
  (let [value (get-in req [:page-state actions/form-key key])]
    (when (map? value)
      value)))

(defn- policy-form-state
  [req {:keys [policy-details]}]
  (let [submitted (page-state-map req :policy)
        details   (merge policy-details (dissoc submitted :_error))]
    {:policy-id       (:policy-id details)
     :name            (:name details)
     :effective-at    (:effective-at details)
     :effective-until (:effective-until details)
     :premium-factor  (:premium-factor details)
     :currency        (:currency details)
     :status          (:status policy-details)
     :_error          (:_error submitted)}))

(defn- signal-string
  [value]
  (if (some? value)
    (str value)
    ""))

(defn- keyword-signal
  [value]
  (if (keyword? value)
    (subs (str value) 1)
    (signal-string value)))

(defn- policy-signal
  [{:keys [currency effective-at effective-until name policy-id premium-factor]}]
  {:policyId       (signal-string policy-id)
   :name           (signal-string name)
   :effectiveAt    (or (ui2/date-input-value effective-at) "")
   :effectiveUntil (or (ui2/date-input-value effective-until) "")
   :premiumFactor  (signal-string premium-factor)
   :currency       (clojure.core/name currency)})

(defn- coverage-type-create-state
  [req {{:keys [policy-id]} :policy-details}]
  (let [submitted (page-state-map req :coverage-type-create)
        details   (merge {:policy-id      policy-id
                          :name           ""
                          :description    ""
                          :premium-factor ""
                          :icon           ""
                          :required?      false
                          :add-to-band-instruments? false
                          :confirmation-count ""}
                         (dissoc submitted :_error))]
    {:open           (:open details)
     :policy-id      (:policy-id details)
     :name           (:name details)
     :description    (:description details)
     :premium-factor (:premium-factor details)
     :icon           (:icon details)
     :required?      (boolean (:required? details))
     :add-to-band-instruments?
     (boolean (:add-to-band-instruments? details))
     :impact-count   (:impact-count details)
     :confirmation-count (:confirmation-count details)
     :_error         (:_error submitted)}))

(defn- coverage-type-edit-state
  [req]
  (let [submitted (page-state-map req :coverage-type)]
    {:policy-id      (:policy-id submitted)
     :type-id        (:type-id submitted)
     :name           (:name submitted)
     :description    (:description submitted)
     :premium-factor (:premium-factor submitted)
     :icon           (:icon submitted)
     :required?      (boolean (:required? submitted))
     :impact-count   (:impact-count submitted)
     :confirmation-count (:confirmation-count submitted)
     :_error         (:_error submitted)}))

(defn- active-coverage-type-state
  [req settings]
  (let [create-state (coverage-type-create-state req settings)
        edit-state   (coverage-type-edit-state req)]
    (if (:open create-state)
      create-state
      edit-state)))

(defn- icon-value
  [icon]
  (keyword-signal icon))

(defn- coverage-type-signal
  [{:keys [add-to-band-instruments? confirmation-count description icon name
           policy-id premium-factor required? type-id]}]
  (cond-> {:policyId      (signal-string policy-id)
           :name          (signal-string name)
           :description   (signal-string description)
           :premiumFactor (signal-string premium-factor)
           :icon          (icon-value icon)
           :required      (boolean required?)
           :addToBandInstruments (boolean add-to-band-instruments?)
           :confirmationCount (signal-string confirmation-count)}
    type-id (assoc :typeId (signal-string type-id))))

(defn- category-factor-create-state
  [req {{:keys [policy-id]} :policy-details}]
  (let [submitted (page-state-map req :category-factor-create)
        details   (merge {:policy-id   policy-id
                          :category-id ""
                          :factor      ""}
                         (dissoc submitted :_error))]
    {:open        (:open details)
     :policy-id   (:policy-id details)
     :category-id (:category-id details)
     :factor      (:factor details)
     :_error      (:_error submitted)}))

(defn- category-factor-edit-state
  [req]
  (let [submitted (page-state-map req :category-factor)]
    {:policy-id          (:policy-id submitted)
     :category-factor-id (:category-factor-id submitted)
     :category-id        (:category-id submitted)
     :category-name      (:category-name submitted)
     :factor             (:factor submitted)
     :_error             (:_error submitted)}))

(defn- active-category-factor-state
  [req settings]
  (let [create-state (category-factor-create-state req settings)
        edit-state   (category-factor-edit-state req)]
    (if (:open create-state)
      create-state
      edit-state)))

(defn- category-factor-signal
  [{:keys [category-factor-id category-id factor policy-id]}]
  (cond-> {:policyId   (signal-string policy-id)
           :categoryId (signal-string category-id)
           :factor     (signal-string factor)}
    category-factor-id (assoc :categoryFactorId (signal-string category-factor-id))))

(defn- exporter-form-state
  [req {{:keys [policy-id]} :policy-details
        {:keys [exporter-id role-rows]} :exporter-configuration}]
  (let [submitted (page-state-map req :exporter)
        details   (merge {:policy-id  policy-id
                          :exporter-id exporter-id
                          :mappings    (mapv #(select-keys %
                                                           [:role
                                                            :coverage-type-id])
                                             role-rows)}
                         (dissoc submitted :_error))]
    (assoc details :_error (:_error submitted))))

(defn- exporter-signal
  [{:keys [exporter-id mappings policy-id]}]
  {:policyId  (signal-string policy-id)
   :exporterId (keyword-signal exporter-id)
   :mappings
   (mapv (fn [{:keys [coverage-type-id role]}]
           {:role           (keyword-signal role)
            :coverageTypeId (signal-string coverage-type-id)})
         mappings)})

(defn- initial-signals
  [req settings]
  {:insurancePolicySettings
   {:policy         (policy-signal (policy-form-state req settings))
    :coverageType   (coverage-type-signal (active-coverage-type-state req settings))
    :categoryFactor (category-factor-signal (active-category-factor-state req settings))
    :exporter       (exporter-signal (exporter-form-state req settings))}})

(defn- field-error
  [errors field]
  (get-in errors [field :error]))

(defn- described-by-id
  [id error]
  (when error
    (str id "-error")))

(defn- native-field
  [{:keys [error hint id label]} input]
  [:label {:class "wa-stack wa-gap-2xs"
           :for   id}
   [:span {:class "wa-caption-s wa-font-weight-bold"} label]
   input
   (when hint
     [:small {:class "wa-color-text-quiet"}
      hint])
   (when error
     [:small {:id    (described-by-id id error)
              :class "wa-color-danger-fill-loud"}
      error])])

(defn- text-input
  [{:keys [bind disabled? error id label type value] :or {type "text"} :as attrs}]
  (native-field
   {:error error :hint (:hint attrs) :id id :label label}
   [:input (cond-> (merge {:id        id
                           :type      type
                           :value     (or value "")
                           :data-bind bind}
                          (select-keys attrs [:min :required :step]))
             disabled? (assoc :disabled true)
             error     (assoc :aria-invalid "true"
                              :aria-describedby (described-by-id id error)))]))

(defn- textarea-input
  [{:keys [bind disabled? error id label value]}]
  (native-field
   {:error error :id id :label label}
   [:textarea (cond-> {:id        id
                       :data-bind bind}
                disabled? (assoc :disabled true)
                error     (assoc :aria-invalid "true"
                                 :aria-describedby (described-by-id id error)))
    (or value "")]))

(defn- currency-option
  [selected currency]
  (let [value (name currency)]
    [:option (cond-> {:value value}
               (= selected currency) (assoc :selected true))
     value]))

(defn- currency-select
  [{:keys [disabled? error id label selected supported-currencies]}]
  (native-field
   {:error error :id id :label label}
   (into [:select (cond-> {:id        id
                           :data-bind "insurancePolicySettings.policy.currency"}
                    disabled? (assoc :disabled true)
                    error     (assoc :aria-invalid "true"
                                     :aria-describedby (described-by-id id error)))]
         (map (partial currency-option selected))
         supported-currencies)))

(defn- category-option
  [selected {:keys [category-id category-name]}]
  (let [value (signal-string category-id)]
    [:option (cond-> {:value value}
               (= value (signal-string selected)) (assoc :selected true))
     category-name]))

(defn- category-select
  [{:keys [categories disabled? error id label selected]}]
  (native-field
   {:error error :id id :label label}
   (into [:select (cond-> {:id        id
                           :data-bind "insurancePolicySettings.categoryFactor.categoryId"}
                    disabled? (assoc :disabled true)
                    error     (assoc :aria-invalid "true"
                                     :aria-describedby (described-by-id id error)))]
         (cons [:option (cond-> {:value ""}
                          (str/blank? (signal-string selected)) (assoc :selected true))
                ""]
               (map (partial category-option selected) categories)))))

(def policy-status-variants
  {:insurance.policy.status/active "success"
   :insurance.policy.status/sent   "warning"
   :insurance.policy.status/draft  "neutral"})

(defn- policy-status-badge
  [tr status]
  (if status
    [:wa-badge {:appearance "outlined"
                :variant    (policy-status-variants status)
                :pill       true}
     (tr [status])]
    (ui2/muted nil)))

(defn- top-error-callout
  [error]
  (when error
    [:wa-callout {:appearance "outlined" :variant "danger"}
     [:div {:class "wa-stack wa-gap-2xs"}
      [:strong (:error error)]]]))

(defn- read-only-message-key
  [{:keys [insurance-team-member? policy-editable?]}]
  (cond
    (not insurance-team-member?) [:insurance.policy-settings/error-not-allowed]
    (not policy-editable?)       [:insurance.policy-settings/error-frozen-policy]))

(defn- read-only-callout
  [{:keys [tr]} {:keys [editable?] :as settings}]
  (when-not editable?
    (when-let [message-key (read-only-message-key settings)]
      [:wa-callout {:appearance "outlined" :variant "warning"}
       [:div {:class "wa-stack wa-gap-2xs"}
        [:strong (tr [:insurance.policy-settings/read-only-title])]
        [:span (tr message-key)]]])))

(defn- policy-details-section
  [{:keys [tr] :as req} {:keys [editable? supported-currencies] :as settings}]
  (let [{:keys [_error currency effective-at effective-until name premium-factor status] :as form}
        (policy-form-state req settings)
        disabled? (not editable?)]
    (settings-card
     {:title    (tr [:insurance.dashboard/policy-details])
      :subtitle (tr [:insurance.policy-settings/policy-details-subtitle])}
     [:div {:class "wa-stack wa-gap-m"}
      (read-only-callout req settings)
      (top-error-callout (:_top _error))
      [:form {:id             "insurance-policy-settings-policy-form"
              :class          "wa-stack wa-gap-m"
              :data-id        "insurance-policy-settings-policy"
              :data-action    (d*/act req ::actions/save-policy-details)
              :data-on:submit "evt.preventDefault();"}
       [:input {:type      "hidden"
                :data-bind "insurancePolicySettings.policy.policyId"
                :value     (:policy-id form)}]
       [:div {:class "leading-none wa-grid" :style "--min-column-size: 18rem;"}
        (text-input {:id        "insurance-policy-settings-name"
                     :label     (tr [:insurance/name])
                     :value     name
                     :bind      "insurancePolicySettings.policy.name"
                     :disabled? disabled?
                     :error     (field-error _error :name)})
        (text-input {:id        "insurance-policy-settings-effective-at"
                     :label     (tr [:insurance/effective-at])
                     :type      "date"
                     :value     (ui2/date-input-value effective-at)
                     :bind      "insurancePolicySettings.policy.effectiveAt"
                     :disabled? disabled?
                     :error     (field-error _error :effective-at)})
        (text-input {:id        "insurance-policy-settings-effective-until"
                     :label     (tr [:insurance/effective-until])
                     :type      "date"
                     :value     (ui2/date-input-value effective-until)
                     :bind      "insurancePolicySettings.policy.effectiveUntil"
                     :disabled? disabled?
                     :error     (field-error _error :effective-until)})
        (text-input {:id        "insurance-policy-settings-premium-factor"
                     :label     (tr [:insurance/premium-base-factor])
                     :type      "number"
                     :value     (str premium-factor)
                     :bind      "insurancePolicySettings.policy.premiumFactor"
                     :min       "0"
                     :step      "any"
                     :disabled? disabled?
                     :error     (field-error _error :premium-factor)})
        (currency-select {:id                   "insurance-policy-settings-currency"
                          :label                (tr [:insurance/currency])
                          :selected             currency
                          :supported-currencies supported-currencies
                          :disabled?            disabled?
                          :error                (field-error _error :currency)})
        [:div {:class "wa-stack wa-gap-2xs"}
         [:span {:class "wa-caption-s wa-font-weight-bold"}
          (tr [:insurance.dashboard/policy-status])]
         [:div {:class "wa-cluster wa-gap-xs"}
          (policy-status-badge tr status)
          [:span {:class "wa-caption-s wa-color-text-quiet"}
           (tr [:insurance.policy-settings/status-read-only])]]]]
       [button/Button {:appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :disabled           disabled?
                       :data-attr:disabled "!!$loading && $loading !== 'insurance-policy-settings-policy'"
                       :data-attr:loading  "$loading === 'insurance-policy-settings-policy'"}
        (tr [:action/save])]]])))

(defn- exporter-id-value
  [exporter-id]
  (keyword-signal exporter-id))

(defn- exporter-version-option
  [tr selected-exporter-id {:keys [exporter-id label-key]}]
  (let [value (exporter-id-value exporter-id)]
    [:option (cond-> {:value value}
               (= exporter-id selected-exporter-id) (assoc :selected true))
     (tr [label-key])]))

(defn- exporter-version-change
  [exporter-options]
  (let [mappings-by-exporter
        (into (array-map)
              (map (fn [{:keys [exporter-id role-rows]}]
                     [(exporter-id-value exporter-id)
                      (mapv (fn [{:keys [role]}]
                              {:role           (keyword-signal role)
                               :coverageTypeId ""})
                            role-rows)]))
              exporter-options)]
    (str "$insurancePolicySettings.exporter.mappings = "
         (d*/->signals mappings-by-exporter)
         "[evt.target.value] || []")))

(defn- exporter-version-select
  [{:keys [tr]} {:keys [exporter-options]}
   {:keys [_error exporter-id]} disabled?]
  (let [known-exporter-ids (set (map :exporter-id exporter-options))]
    (native-field
     {:error (field-error _error :exporter-id)
      :id    "insurance-policy-settings-exporter-id"
      :label (tr [:insurance/exporter])}
     (into
      [:select
       (cond-> {:id             "insurance-policy-settings-exporter-id"
                :data-bind      "insurancePolicySettings.exporter.exporterId"
                :data-on:change (exporter-version-change exporter-options)}
         disabled? (assoc :disabled true)
         (field-error _error :exporter-id)
         (assoc :aria-invalid "true"
                :aria-describedby
                (described-by-id
                 "insurance-policy-settings-exporter-id"
                 (field-error _error :exporter-id))))
       [:option (cond-> {:value ""}
                  (nil? exporter-id) (assoc :selected true))
        (tr [:insurance/exporter-none])]
       (when (and exporter-id
                  (not (contains? known-exporter-ids exporter-id)))
         [:option {:value    (exporter-id-value exporter-id)
                   :selected true}
          (exporter-id-value exporter-id)])]
      (map (partial exporter-version-option tr exporter-id))
      exporter-options))))

(defn- exporter-coverage-type-option
  [selected-type-id {:keys [name type-id]}]
  (let [value (signal-string type-id)]
    [:option (cond-> {:value value}
               (= value (signal-string selected-type-id))
               (assoc :selected true))
     name]))

(defn- exporter-role-select
  [{:keys [tr]} coverage-type-rows exporter-id idx
   {:keys [label-key required? role]} selected-type-id disabled?]
  (let [id (str "insurance-policy-settings-exporter-"
                (name exporter-id)
                "-"
                (name role))]
    (native-field
     {:id    id
      :label (tr [label-key])}
     (into
      [:select (cond-> {:id                 id
                        :data-exporter-role (name role)
                        :data-bind
                        (str "insurancePolicySettings.exporter.mappings."
                             idx
                             ".coverageTypeId")
                        :aria-required      (boolean required?)}
                 disabled? (assoc :disabled true))
       [:option (cond-> {:value ""}
                  (str/blank? (signal-string selected-type-id))
                  (assoc :selected true))
        (tr [:insurance/exporter-role-unmapped])]]
      (map (partial exporter-coverage-type-option selected-type-id))
      coverage-type-rows))))

(defn- exporter-role-fields
  [req coverage-type-rows selected-exporter-id mappings disabled?
   {:keys [exporter-id role-rows]}]
  (let [selected-by-role (into {} (map (juxt :role :coverage-type-id)) mappings)
        exporter-value  (exporter-id-value exporter-id)]
    (into
     [:div {:class            "wa-stack wa-gap-m"
            :data-exporter-id exporter-value
            :data-show
            (str "$insurancePolicySettings.exporter.exporterId === '"
                 exporter-value
                 "'")}]
     (map-indexed
      (fn [idx {:keys [role] :as role-row}]
        (exporter-role-select
         req
         coverage-type-rows
         exporter-id
         idx
         role-row
         (when (= selected-exporter-id exporter-id)
           (get selected-by-role role))
         disabled?))
      role-rows))))

(defn- exporter-section
  [{:keys [tr] :as req}
   {:keys [coverage-type-rows editable? exporter-options] :as settings}]
  (let [{:keys [_error exporter-id mappings policy-id] :as form}
        (exporter-form-state req settings)
        disabled? (not editable?)]
    (settings-card
     {:title    (tr [:insurance/exporter])
      :subtitle (tr [:insurance/exporter-subtitle])}
     [:form (cond-> {:id             "insurance-policy-settings-exporter-form"
                     :class          "wa-stack wa-gap-m"
                     :data-id        "insurance-policy-settings-exporter"
                     :data-on:submit "evt.preventDefault();"}
              editable? (assoc :data-action
                               (d*/act req ::actions/save-exporter)))
      [:input {:type      "hidden"
               :value     policy-id
               :data-bind "insurancePolicySettings.exporter.policyId"}]
      (top-error-callout (:_top _error))
      (exporter-version-select req settings form disabled?)
      (for [option exporter-options]
        (exporter-role-fields
         req
         coverage-type-rows
         exporter-id
         mappings
         disabled?
         option))
      (when-let [mapping-error (field-error _error :mappings)]
        [:small {:class "wa-color-danger-fill-loud"}
         mapping-error])
      [button/Button {:appearance         "filled"
                      :variant            "brand"
                      :type               "submit"
                      :disabled           disabled?
                      :data-attr:disabled
                      "!!$loading && $loading !== 'insurance-policy-settings-exporter'"
                      :data-attr:loading
                      "$loading === 'insurance-policy-settings-exporter'"}
       (tr [:action/save])]])))

(defn- metric-item
  [label value]
  [:div {:class "wa-stack wa-gap-2xs"}
   [:dt {:class "wa-caption-s wa-color-text-quiet"} label]
   [:dd {:class "wa-heading-m" :style "margin: 0;"} value]])

(defn- current-totals-section
  [{:keys [tr]} {{:keys [currency]} :policy-details :keys [current-totals]}]
  (settings-card
   {:title    (tr [:insurance.policy-settings/current-totals])
    :subtitle (tr [:insurance.policy-settings/current-totals-subtitle])}
   [:dl {:class "leading-none wa-grid" :style "--min-column-size: 12rem;"}
    (metric-item (tr [:insurance.dashboard/total-instruments])
                 (:total-instruments current-totals 0))
    (metric-item (tr [:insurance.dashboard/total-insured-value])
                 (ui2/money (:total-insured-value current-totals 0M) currency))
    (metric-item (tr [:insurance.dashboard/policy-cost])
                 (ui2/money (:total-cost current-totals 0M) currency))]))

(defn- warning-callout
  [{:keys [tr]} {:keys [category-names type]}]
  (case type
    :missing-category-factors
    [:wa-callout {:appearance "outlined" :variant "warning"}
     [:div {:class "wa-stack wa-gap-2xs"}
      [:strong (tr [:insurance.policy-settings/missing-category-factors-title])]
      [:span (tr [:insurance.policy-settings/missing-category-factors-body]
                 [(str/join ", " category-names)])]]]
    nil))

(defn- warnings-section
  [req {:keys [warnings]}]
  (when (seq warnings)
    (into [:div {:class "wa-stack wa-gap-s"}]
          (keep (partial warning-callout req))
          warnings)))

(defn- table-head
  [labels]
  [:thead
   (into [:tr]
         (for [label labels]
           [:th {:scope "col"} label]))])

(defn- close-dialog-on-hide
  [req action]
  (str "if (evt.target !== el) return; evt.preventDefault(); @post('"
       (d*/act req action)
       "')"))

(defn- coverage-type-icon-option
  [selected-icon icon]
  (let [value (icon-value icon)]
    [:wa-option
     (cond-> {:value value}
       (= value (icon-value selected-icon)) (assoc :selected true))
     [ico/Icon {::ico/library (keyword (namespace icon))
                ::ico/name    (keyword (clojure.core/name icon))
                :slot         "start"}]
     (icons/display-name icon)]))

(defn- coverage-type-icon-field
  [tr icon error id-prefix]
  (into
   [:wa-combobox
    (cond-> {:id         (str id-prefix "-icon")
             :label      (tr [:insurance/coverage-type-icon])
             :hint       (tr [:insurance/coverage-type-icon-hint])
             :value      (icon-value icon)
             :required   true
             :appearance "outlined"
             :data-bind  "insurancePolicySettings.coverageType.icon"}
      error (assoc :hint error
                   :data-invalid "true"))]
   (map (partial coverage-type-icon-option icon))
   (icons/catalog)))

(defn- coverage-type-impact-confirmation
  [{:keys [tr]} {:keys [_error confirmation-count impact-count]} id-prefix]
  (when (and (number? impact-count) (pos? impact-count))
    [:div {:class "wa-stack wa-gap-xs"}
     [:span
      (tr [:insurance/coverage-type-impact-confirmation]
          {:count impact-count})]
     (text-input
      {:id       (str id-prefix "-confirmation-count")
       :label    (tr [:insurance/coverage-type-confirmation-count]
                     {:count impact-count})
       :hint     (tr [:insurance/coverage-type-confirmation-count-hint])
       :type     "number"
       :value    confirmation-count
       :bind     "insurancePolicySettings.coverageType.confirmationCount"
       :min      "0"
       :required true
       :error    (field-error _error :confirmation-count)})]))

(defn- coverage-type-fields
  [{:keys [tr] :as req}
   {:keys [_error add-to-band-instruments? description icon name premium-factor
           required?] :as form}
   {:keys [create? id-prefix]}]
  [:div {:class "wa-stack wa-gap-m"}
   (top-error-callout (:_top _error))
   (text-input {:id    (str id-prefix "-name")
                :label (tr [:insurance/coverage-name])
                :value name
                :bind  "insurancePolicySettings.coverageType.name"
                :error (field-error _error :name)})
   (textarea-input {:id    (str id-prefix "-description")
                    :label (tr [:insurance/coverage-type-description])
                    :value description
                    :bind  "insurancePolicySettings.coverageType.description"
                    :error (field-error _error :description)})
   (coverage-type-icon-field tr icon (field-error _error :icon) id-prefix)
   [:wa-checkbox
    (cond-> {:id                (str id-prefix "-required")
             :hint              (tr [:insurance/coverage-type-required-hint])
             :data-attr:checked "$insurancePolicySettings.coverageType.required"
             :data-on:change    "$insurancePolicySettings.coverageType.required = evt.target.checked"}
      required? (assoc :checked true))
    (tr [:insurance/coverage-type-required])]
   (when create?
     [:wa-checkbox
      (cond-> {:id                (str id-prefix "-add-to-band-instruments")
               :hint              (tr [:insurance/coverage-type-add-to-band-instruments-hint])
               :data-show         "!$insurancePolicySettings.coverageType.required"
               :data-attr:checked "$insurancePolicySettings.coverageType.addToBandInstruments"
               :data-on:change    "$insurancePolicySettings.coverageType.addToBandInstruments = evt.target.checked"}
        add-to-band-instruments? (assoc :checked true))
      (tr [:insurance/coverage-type-add-to-band-instruments])])
   (coverage-type-impact-confirmation req form id-prefix)
   (text-input {:id    (str id-prefix "-premium-factor")
                :label (tr [:insurance/premium-factor])
                :type  "number"
                :value (str premium-factor)
                :bind  "insurancePolicySettings.coverageType.premiumFactor"
                :min   "0"
                :step  "any"
                :error (field-error _error :premium-factor)})])

(defn- coverage-type-create-dialog
  [{:keys [tr] :as req} settings]
  (let [{:keys [open policy-id] :as form} (coverage-type-create-state req settings)]
    (when open
      [:wa-dialog {:id                    "coverage-type-create-dialog"
                   :label                 (tr [:insurance.policy-settings/add-coverage-type])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (close-dialog-on-hide req ::actions/close-coverage-type-create)}
       [:form {:id             "coverage-type-create-form"
               :data-id        "coverage-type-create"
               :data-action    (d*/act req ::actions/create-coverage-type)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type      "hidden"
                 :value     policy-id
                 :data-bind "insurancePolicySettings.coverageType.policyId"}]
        (coverage-type-fields req form {:create?   true
                                        :id-prefix "coverage-type-create"})]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        (tr [:action/cancel])]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "coverage-type-create-form"
                       :data-attr:disabled "!!$loading && $loading !== 'coverage-type-create'"
                       :data-attr:loading  "$loading === 'coverage-type-create'"}
        (tr [:action/create])]])))

(defn- coverage-type-edit-dialog
  [{:keys [tr] :as req}]
  (let [{:keys [policy-id type-id] :as form} (coverage-type-edit-state req)]
    (when type-id
      [:wa-dialog {:id                    "coverage-type-edit-dialog"
                   :label                 (tr [:insurance.policy-settings/edit-coverage-type])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (close-dialog-on-hide req ::actions/close-coverage-type-edit)}
       [:form {:id             "coverage-type-edit-form"
               :data-id        "coverage-type"
               :data-action    (d*/act req ::actions/update-coverage-type)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type      "hidden"
                 :value     policy-id
                 :data-bind "insurancePolicySettings.coverageType.policyId"}]
        [:input {:type      "hidden"
                 :value     type-id
                 :data-bind "insurancePolicySettings.coverageType.typeId"}]
        (coverage-type-fields req form {:id-prefix "coverage-type-edit"})]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        (tr [:action/cancel])]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "coverage-type-edit-form"
                       :data-attr:disabled "!!$loading && $loading !== 'coverage-type'"
                       :data-attr:loading  "$loading === 'coverage-type'"}
        (tr [:action/save])]])))

(defn- coverage-type-delete-dialog
  [{:keys [tr] :as req} {:keys [name type-id usage-count used?]}]
  (let [dialog-id  (ui2/remove-dialog-id "coverage-type" type-id)
        loading-id (pr-str (str type-id))]
    [:wa-dialog {:id    dialog-id
                 :label (tr [:action/confirm-generic])}
     [:div {:class "wa-stack wa-gap-s"}
      [:p (tr [:insurance.policy-settings/coverage-type-delete-confirm]
              [(str "\"" name "\"")])]
      (when used?
        [:wa-callout {:appearance "outlined" :variant "warning"}
         [:div {:class "wa-stack wa-gap-2xs"}
          [:strong (tr [:insurance.policy-settings/error-coverage-type-in-use])]
          [:span (tr [:insurance.policy-settings/coverage-type-in-use]
                     [usage-count])]]])]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/cancel])]
     [button/Button (cond-> {:slot               "footer"
                             :appearance         "filled"
                             :variant            "danger"
                             :data-dialog        "close"
                             :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                             :data-attr:loading  (str "$loading === " loading-id)
                             :data-id            type-id
                             :data-action        (d*/act req ::actions/delete-coverage-type)}
                      used? (assoc :disabled true))
      (tr [:action/confirm-delete])]]))

(defn- coverage-type-row
  [{:keys [editable? tr] :as req} currency {:keys [current-cost description name premium-factor type-id usage-count used?]}]
  (let [loading-id (pr-str (str type-id))]
    (cond-> [:tr
             [:td name]
             [:td premium-factor]
             [:td (ui2/muted description "")]
             [:td usage-count]
             [:td (ui2/money current-cost currency)]]
      editable?
      (conj [:td {:class "align-top text-right"}
             (ui2/row-action-menu
              {:button-id (str "coverage-type-actions-" type-id)
               :items     [{:label              (tr [:action/update])
                            :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                            :data-attr:loading  (str "$loading === " loading-id)
                            :data-id            type-id
                            :data-action        (d*/act req ::actions/open-coverage-type-edit)}
                           (cond-> {:label       (tr [:action/remove])
                                    :variant     "danger"
                                    :data-dialog (format "open %s"
                                                         (ui2/remove-dialog-id "coverage-type" type-id))}
                             used? (assoc :disabled true))]})]))))

(defn- coverage-type-table-head
  [tr editable?]
  (table-head (cond-> [(tr [:insurance/coverage-name])
                       (tr [:insurance/premium-factor])
                       (tr [:insurance/coverage-type-description])
                       (tr [:insurance.policy-settings/usage])
                       (tr [:insurance.policy-settings/current-cost])]
                editable? (conj (tr [:actions])))))

(defn- coverage-types-section
  [{:keys [tr] :as req} {{:keys [currency policy-id]} :policy-details
                         :keys [coverage-type-rows editable?]}]
  (settings-card
   {:title    (tr [:insurance/coverage-types])
    :subtitle (tr [:insurance.policy-settings/coverage-types-subtitle])
    :actions  (when editable?
                [[button/Button {:appearance  "outlined"
                                 :variant     "brand"
                                 :data-id     policy-id
                                 :data-action (d*/act req ::actions/open-coverage-type-create)}
                  (tr [:insurance.policy-settings/add-coverage-type])]])}
   [:div {:class "wa-stack wa-gap-m"}
    (top-error-callout (get-in req [:page-state actions/form-key :coverage-type-delete :_error :_top]))
    (ui2/table-shell
     (if (seq coverage-type-rows)
       [:table
        (coverage-type-table-head tr editable?)
        (into [:tbody]
              (map (partial coverage-type-row (assoc req :editable? editable?) currency))
              coverage-type-rows)]
       (ui2/empty-state (tr [:insurance.policy-settings/no-coverage-types]) "")))]))

(defn- category-factor-create-fields
  [{:keys [tr]} {:keys [_error category-id factor]} unused-categories id-prefix]
  [:div {:class "wa-stack wa-gap-m"}
   (top-error-callout (:_top _error))
   (category-select {:id         (str id-prefix "-category")
                     :label      (tr [:instrument/category])
                     :selected   category-id
                     :categories unused-categories
                     :error      (field-error _error :category-id)})
   (text-input {:id    (str id-prefix "-factor")
                :label (tr [:insurance/premium-factor])
                :type  "number"
                :value (str factor)
                :bind  "insurancePolicySettings.categoryFactor.factor"
                :min   "0"
                :step  "any"
                :error (field-error _error :factor)})])

(defn- read-only-field
  [label value]
  [:div {:class "wa-stack wa-gap-2xs"}
   [:span {:class "wa-caption-s wa-font-weight-bold"} label]
   [:span value]])

(defn- category-factor-edit-fields
  [{:keys [tr]} {:keys [_error category-id category-name factor]} id-prefix]
  [:div {:class "wa-stack wa-gap-m"}
   (top-error-callout (:_top _error))
   [:input {:type      "hidden"
            :value     category-id
            :data-bind "insurancePolicySettings.categoryFactor.categoryId"}]
   (read-only-field (tr [:instrument/category]) category-name)
   (text-input {:id    (str id-prefix "-factor")
                :label (tr [:insurance/premium-factor])
                :type  "number"
                :value (str factor)
                :bind  "insurancePolicySettings.categoryFactor.factor"
                :min   "0"
                :step  "any"
                :error (field-error _error :factor)})])

(defn- category-factor-create-dialog
  [{:keys [tr] :as req} {:keys [unused-categories] :as settings}]
  (let [{:keys [open policy-id] :as form} (category-factor-create-state req settings)]
    (when open
      [:wa-dialog {:id                    "category-factor-create-dialog"
                   :label                 (tr [:insurance.policy-settings/add-category-factor])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (close-dialog-on-hide req ::actions/close-category-factor-create)}
       [:form {:id             "category-factor-create-form"
               :data-id        "category-factor-create"
               :data-action    (d*/act req ::actions/create-category-factor)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type      "hidden"
                 :value     policy-id
                 :data-bind "insurancePolicySettings.categoryFactor.policyId"}]
        (category-factor-create-fields req form unused-categories "category-factor-create")]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        (tr [:action/cancel])]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "category-factor-create-form"
                       :data-attr:disabled "!!$loading && $loading !== 'category-factor-create'"
                       :data-attr:loading  "$loading === 'category-factor-create'"}
        (tr [:action/create])]])))

(defn- category-factor-edit-dialog
  [{:keys [tr] :as req}]
  (let [{:keys [category-factor-id policy-id] :as form} (category-factor-edit-state req)]
    (when category-factor-id
      [:wa-dialog {:id                    "category-factor-edit-dialog"
                   :label                 (tr [:insurance.policy-settings/edit-category-factor])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (close-dialog-on-hide req ::actions/close-category-factor-edit)}
       [:form {:id             "category-factor-edit-form"
               :data-id        "category-factor"
               :data-action    (d*/act req ::actions/update-category-factor)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type      "hidden"
                 :value     policy-id
                 :data-bind "insurancePolicySettings.categoryFactor.policyId"}]
        [:input {:type      "hidden"
                 :value     category-factor-id
                 :data-bind "insurancePolicySettings.categoryFactor.categoryFactorId"}]
        (category-factor-edit-fields req form "category-factor-edit")]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        (tr [:action/cancel])]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "category-factor-edit-form"
                       :data-attr:disabled "!!$loading && $loading !== 'category-factor'"
                       :data-attr:loading  "$loading === 'category-factor'"}
        (tr [:action/save])]])))

(defn- category-factor-delete-dialog
  [{:keys [tr] :as req} {:keys [category-factor-id category-name usage-count used?]}]
  (let [dialog-id  (ui2/remove-dialog-id "category-factor" category-factor-id)
        loading-id (pr-str (str category-factor-id))]
    [:wa-dialog {:id    dialog-id
                 :label (tr [:action/confirm-generic])}
     [:div {:class "wa-stack wa-gap-s"}
      [:p (tr [:insurance.policy-settings/category-factor-delete-confirm]
              [(str "\"" category-name "\"")])]
      (when used?
        [:wa-callout {:appearance "outlined" :variant "warning"}
         [:div {:class "wa-stack wa-gap-2xs"}
          [:strong (tr [:insurance.policy-settings/error-category-factor-in-use])]
          [:span (tr [:insurance.policy-settings/category-factor-in-use]
                     [usage-count])]]])]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/cancel])]
     [button/Button (cond-> {:slot               "footer"
                             :appearance         "filled"
                             :variant            "danger"
                             :data-dialog        "close"
                             :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                             :data-attr:loading  (str "$loading === " loading-id)
                             :data-id            category-factor-id
                             :data-action        (d*/act req ::actions/delete-category-factor)}
                      used? (assoc :disabled true))
      (tr [:action/confirm-delete])]]))

(defn- category-factor-row
  [{:keys [editable? tr] :as req} currency {:keys [category-factor-id category-name current-cost factor usage-count used?]}]
  (let [loading-id (pr-str (str category-factor-id))]
    (cond-> [:tr
             [:td category-name]
             [:td factor]
             [:td usage-count]
             [:td (ui2/money current-cost currency)]]
      editable?
      (conj [:td {:class "align-top text-right"}
             (ui2/row-action-menu
              {:button-id (str "category-factor-actions-" category-factor-id)
               :items     [{:label              (tr [:action/update])
                            :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                            :data-attr:loading  (str "$loading === " loading-id)
                            :data-id            category-factor-id
                            :data-action        (d*/act req ::actions/open-category-factor-edit)}
                           (cond-> {:label       (tr [:action/remove])
                                    :variant     "danger"
                                    :data-dialog (format "open %s"
                                                         (ui2/remove-dialog-id "category-factor" category-factor-id))}
                             used? (assoc :disabled true))]})]))))

(defn- category-factor-table-head
  [tr editable?]
  (table-head (cond-> [(tr [:instrument/category])
                       (tr [:insurance/premium-factor])
                       (tr [:insurance.policy-settings/usage])
                       (tr [:insurance.policy-settings/current-cost])]
                editable? (conj (tr [:actions])))))

(def category-factor-create-disabled-id
  "category-factor-create-disabled")

(defn- category-factor-create-actions
  [{:keys [tr] :as req} policy-id unused-categories]
  (if (seq unused-categories)
    [[button/Button {:appearance  "outlined"
                     :variant     "brand"
                     :data-id     policy-id
                     :data-action (d*/act req ::actions/open-category-factor-create)}
      (tr [:insurance.policy-settings/add-category-factor])]]
    [[:span {:id    category-factor-create-disabled-id
             :style "display: inline-block;"}
      [button/Button {:appearance "outlined"
                      :variant    "brand"
                      :disabled   true}
       (tr [:insurance.policy-settings/add-category-factor])]]
     [:wa-tooltip {:for category-factor-create-disabled-id}
      (tr [:insurance.policy-settings/category-factor-create-disabled-tooltip])]]))

(defn- category-factors-section
  [{:keys [tr] :as req} {{:keys [currency policy-id]} :policy-details
                         :keys [category-factor-rows editable? unused-categories]}]
  (settings-card
   {:title    (tr [:insurance/category-factors])
    :subtitle (tr [:insurance.policy-settings/category-factors-subtitle])
    :actions  (when editable?
                (category-factor-create-actions req policy-id unused-categories))}
   [:div {:class "wa-stack wa-gap-m"}
    (top-error-callout (get-in req [:page-state actions/form-key :category-factor-delete :_error :_top]))
    (ui2/table-shell
     (if (seq category-factor-rows)
       [:table
        (category-factor-table-head tr editable?)
        (into [:tbody]
              (map (partial category-factor-row (assoc req :editable? editable?) currency))
              category-factor-rows)]
       (ui2/empty-state (tr [:insurance.policy-settings/no-category-factors]) "")))]))

(defn settings-page-content
  [{:keys [tr] :as req} {:keys [category-factor-rows coverage-type-rows editable? policy] :as settings}]
  [:div {:id           "insurance-policy-settings"
         :class        "wa-stack"
         :data-signals (d*/->signals (initial-signals req settings))}
   [page-header/PageHeader
    {:title    (tr [:insurance.dashboard/policy-settings])
     :subtitle (tr [:insurance.policy-settings/subtitle]
                   [(:insurance.policy/name policy)])}]
   [divider/Divider]
   (warnings-section req settings)
   [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 34ch;"}
    [:div {:class "wa-stack"}
     (policy-details-section req settings)
     (coverage-types-section req settings)
     (category-factors-section req settings)
     (exporter-section req settings)]
    [:aside {:class "wa-stack"}
     (current-totals-section req settings)]]
   (coverage-type-create-dialog req settings)
   (coverage-type-edit-dialog req)
   (category-factor-create-dialog req settings)
   (category-factor-edit-dialog req)
   (when editable?
     (for [row coverage-type-rows]
       (coverage-type-delete-dialog req row)))
   (when editable?
     (for [row category-factor-rows]
       (category-factor-delete-dialog req row)))])

(defn page
  [{:keys [db] :as req}]
  (let [settings (queries/policy-settings
                  db
                  (util/ensure-uuid! (get-in req [:path-params :policy-id]))
                  {:current-member-id (get-in req [:session :session/member :member/member-id])})]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :wide
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
           [:i18n/tr :insurance/title]]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy (:policy settings))}
           (get-in settings [:policy :insurance.policy/name])]
          [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/policy-settings]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy (:policy settings))
                             :label (get-in settings [:policy :insurance.policy/name])}]
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      (settings-page-content req settings)])))

(d*/refresh-all!)
