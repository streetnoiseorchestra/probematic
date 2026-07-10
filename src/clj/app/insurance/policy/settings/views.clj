(ns app.insurance.policy.settings.views
  (:require
   [app.datastar :as d*]
   [app.insurance.policy.settings.actions :as actions]
   [app.insurance.policy.settings.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]))

(defn- page-breadcrumb
  [{:keys [tr]} policy]
  [breadcrumb/Breadcrumb
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
    (:insurance.policy/name policy)]
   [breadcrumb/BreadcrumbItem (tr [:insurance.dashboard/policy-settings])]])

(defn- page-header
  [{:keys [tr] :as req} {:insurance.policy/keys [name] :as policy}]
  [:div
   [page-header/PageHeader
    {:breadcrumb (page-breadcrumb req policy)
     :title      (tr [:insurance.dashboard/policy-settings])
     :subtitle   (tr [:insurance.policy-settings/subtitle] [name])
     :actions    [[button/BackButton {:tr tr :href (urls/link-policy policy)}]]}]
   [divider/Divider]])

(defn- settings-card
  [{:keys [actions subtitle title]} & children]
  (into [:wa-card (cond-> {:appearance  "plain"
                           :with-header true
                           :style       "background: var(--wa-color-surface-default);"}
                    (seq actions) (assoc :with-header-actions true))
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
                          :premium-factor ""}
                         (dissoc submitted :_error))]
    {:open           (:open details)
     :policy-id      (:policy-id details)
     :name           (:name details)
     :description    (:description details)
     :premium-factor (:premium-factor details)
     :_error         (:_error submitted)}))

(defn- coverage-type-edit-state
  [req]
  (let [submitted (page-state-map req :coverage-type)]
    {:policy-id      (:policy-id submitted)
     :type-id        (:type-id submitted)
     :name           (:name submitted)
     :description    (:description submitted)
     :premium-factor (:premium-factor submitted)
     :_error         (:_error submitted)}))

(defn- active-coverage-type-state
  [req settings]
  (let [create-state (coverage-type-create-state req settings)
        edit-state   (coverage-type-edit-state req)]
    (if (:open create-state)
      create-state
      edit-state)))

(defn- coverage-type-signal
  [{:keys [description name policy-id premium-factor type-id]}]
  (cond-> {:policyId      (signal-string policy-id)
           :name          (signal-string name)
           :description   (signal-string description)
           :premiumFactor (signal-string premium-factor)}
    type-id (assoc :typeId (signal-string type-id))))

(defn- initial-signals
  [req settings]
  {:insurancePolicySettings
   {:policy       (policy-signal (policy-form-state req settings))
    :coverageType (coverage-type-signal (active-coverage-type-state req settings))}})

(defn- field-error
  [errors field]
  (get-in errors [field :error]))

(defn- described-by-id
  [id error]
  (when error
    (str id "-error")))

(defn- native-field
  [{:keys [error id label]} input]
  [:label {:class "wa-stack wa-gap-2xs"
           :for   id}
   [:span {:class "wa-caption-s wa-font-weight-bold"} label]
   input
   (when error
     [:small {:id    (described-by-id id error)
              :class "wa-color-danger-fill-loud"}
      error])])

(defn- text-input
  [{:keys [bind disabled? error id label type value] :or {type "text"} :as attrs}]
  (native-field
   {:error error :id id :label label}
   [:input (cond-> (merge {:id        id
                           :type      type
                           :value     (or value "")
                           :data-bind bind}
                          (select-keys attrs [:min :step]))
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

(defn- coverage-type-fields
  [{:keys [tr]} {:keys [_error description name premium-factor]} id-prefix]
  [:div {:class "wa-stack wa-gap-m"}
   (top-error-callout (:_top _error))
   (text-input {:id    (str id-prefix "-name")
                :label (tr [:insurance/name])
                :value name
                :bind  "insurancePolicySettings.coverageType.name"
                :error (field-error _error :name)})
   (textarea-input {:id    (str id-prefix "-description")
                    :label (tr [:insurance/coverage-type-description])
                    :value description
                    :bind  "insurancePolicySettings.coverageType.description"
                    :error (field-error _error :description)})
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
        (coverage-type-fields req form "coverage-type-create")]
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
        (coverage-type-fields req form "coverage-type-edit")]
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
  (table-head (cond-> [(tr [:insurance/name])
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

(defn- category-factor-row
  [currency {:keys [category-name current-cost factor usage-count]}]
  [:tr
   [:td category-name]
   [:td factor]
   [:td usage-count]
   [:td (ui2/money current-cost currency)]])

(defn- category-factors-section
  [{:keys [tr]} {{:keys [currency]} :policy-details :keys [category-factor-rows]}]
  (settings-card
   {:title    (tr [:insurance/category-factors])
    :subtitle (tr [:insurance.policy-settings/category-factors-subtitle])}
   (ui2/table-shell
    (if (seq category-factor-rows)
      [:table
       (table-head [(tr [:instrument/category])
                    (tr [:insurance/premium-factor])
                    (tr [:insurance.policy-settings/usage])
                    (tr [:insurance.policy-settings/current-cost])])
       (into [:tbody]
             (map (partial category-factor-row currency))
             category-factor-rows)]
      (ui2/empty-state (tr [:insurance.policy-settings/no-category-factors]) "")))))

(defn- settings-page-content
  [req {:keys [coverage-type-rows editable? policy] :as settings}]
  [:div {:id           "insurance-policy-settings"
         :class        "wa-stack"
         :data-signals (d*/->signals (initial-signals req settings))}
   (coverage-type-create-dialog req settings)
   (coverage-type-edit-dialog req)
   (when editable?
     (for [row coverage-type-rows]
       (coverage-type-delete-dialog req row)))
   (page-header req policy)
   (warnings-section req settings)
   [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 34ch;"}
    [:div {:class "wa-stack"}
     (policy-details-section req settings)
     (coverage-types-section req settings)
     (category-factors-section req settings)]
    [:aside {:class "wa-stack"}
     (current-totals-section req settings)]]])

(defn page
  [{:keys [db] :as req}]
  (let [settings (queries/policy-settings
                  db
                  (util/ensure-uuid! (get-in req [:path-params :policy-id]))
                  {:current-member-id (get-in req [:session :session/member :member/member-id])})]
    (ui2/datastar-page
     (settings-page-content req settings))))

(d*/refresh-all!)
