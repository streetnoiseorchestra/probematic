(ns app.insurance.coverage.create.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.coverage.create.actions :as actions]
   [app.insurance.queries :as queries]
   [app.insurance.coverage.upload :as upload]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.ui2.step-circles :as step-circles]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- query-param [req k]
  (or (get-in req [:params k])
      (get-in req [:params (name k)])
      (get-in req [:query-params k])
      (get-in req [:query-params (name k)])))

(defn- validate-field-action [req field]
  (str "$coverage-create.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-instrument-field)
       "')"))

(defn- field-error [form-state field]
  (form/field-error form-state field))

(defn- hint-id [field]
  (str (name field) "-hint"))

(defn- error-id [field]
  (str (name field) "-error"))

(defn- described-by [field hint error]
  (when-let [ids (seq (remove nil?
                              [(when hint (hint-id field))
                               (when error (error-id field))]))]
    (str/join " " ids)))

(defn- field-attrs [req form-state field hint]
  (let [error (field-error form-state field)]
    {:data-bind        (str "coverage-create." (name field))
     :data-invalid     (when error "true")
     :aria-invalid     (when error "true")
     :aria-describedby (described-by field hint error)
     :data-on:change   (validate-field-action req field)}))

(defn- required-marker []
  [:span {:aria-hidden "true"} " *"])

(defn- field-wrapper [label field form-state hint required? & children]
  (let [error (field-error form-state field)]
    (into
     [:div {:class "insurance-coverage-create-field wa-stack wa-gap-2xs"}
      [:label {:class "insurance-coverage-edit-field-label"
               :for   (name field)}
       label
       (when required?
         (required-marker))]]
     (concat
      children
      [(when hint
         [:small {:id    (hint-id field)
                  :class "wa-caption-s wa-color-text-quiet"}
          hint])
       (when error
         [:span {:id    (error-id field)
                 :class "wa-caption-s text-danger"}
          error])]))))

(defn- input-field [req form-state field label value attrs hint]
  (field-wrapper
   label
   field
   form-state
   hint
   (:required attrs)
   [:input (merge {:id    (name field)
                   :name  (name field)
                   :type  "text"
                   :value (form/text-value value)}
                  (field-attrs req form-state field hint)
                  attrs)]))

(defn- textarea-field [req form-state field label value attrs hint]
  (field-wrapper
   label
   field
   form-state
   hint
   false
   [:textarea (merge {:id             (name field)
                      :name           (name field)
                      :class          "insurance-coverage-edit-textarea"
                      :rows           5
                      :data-auto-size "true"}
                     (field-attrs req form-state field hint)
                     attrs)
    (form/text-value value)]))

(defn- option [value label selected-value]
  [:option (cond-> {:value value}
             (= value selected-value) (assoc :selected true))
   label])

(defn- member-option [selected-member-id member]
  (let [member-id (:member/member-id member)]
    (option (str member-id)
            (ui2/member-nick member)
            (some-> selected-member-id str))))

(defn- member-select [{:keys [db] :as req} form-state]
  (let [selected (:owner-member-id form-state)
        members  (q/members-for-select db)]
    (field-wrapper
     [:i18n/tr :instrument/owner]
     :owner-member-id
     form-state
     nil
     true
     (into
      [:select (merge {:id       "owner-member-id"
                       :name     "owner-member-id"
                       :required true}
                      (field-attrs req form-state :owner-member-id nil))
       (option "" "—" (form/text-value selected))]
      (for [member members]
        (member-option selected member))))))

(defn- category-option [selected-category-id category]
  (let [category-id (:instrument.category/category-id category)]
    (option (str category-id)
            (:instrument.category/name category)
            (some-> selected-category-id str))))

(defn- category-select [{:keys [db] :as req} form-state]
  (let [selected   (:category-id form-state)
        categories (queries/instrument-categories db)
        hint       [:i18n/tr :instrument/category-hint]]
    (field-wrapper
     [:i18n/tr :instrument/category]
     :category-id
     form-state
     hint
     true
     (into
      [:select (merge {:id       "category-id"
                       :name     "category-id"
                       :required true}
                      (field-attrs req form-state :category-id hint))
       (option "" "—" (form/text-value selected))]
      (for [category categories]
        (category-option selected category))))))

(defn- instrument-section [req form-state]
  (ui2/section-card
   {:title    [:i18n/tr :instrument/instrument]
    :subtitle [:i18n/tr :instrument/create-subtitle]
    :divider? true}
   [:div {:class "insurance-coverage-edit-form-grid"}
    (input-field req form-state :instrument-name [:i18n/tr :instrument/name] (:instrument-name form-state)
                 {:required true}
                 [:i18n/tr :instrument/name-hint])
    (member-select req form-state)
    (category-select req form-state)
    (input-field req form-state :make [:i18n/tr :instrument/make] (:make form-state)
                 {:required true}
                 [:i18n/tr :instrument/make-hint])
    (input-field req form-state :model [:i18n/tr :instrument/model] (:model form-state)
                 {}
                 [:i18n/tr :instrument/model-hint-optional])
    (input-field req form-state :serial-number [:i18n/tr :instrument/serial-number] (:serial-number form-state)
                 {}
                 [:i18n/tr :instrument/serial-number-hint])
    (input-field req form-state :build-year [:i18n/tr :instrument/build-year] (:build-year form-state)
                 {}
                 [:i18n/tr :instrument/build-year-hint])
    (textarea-field req form-state :description [:i18n/tr :instrument/description] (:description form-state)
                    {:class "insurance-coverage-edit-textarea insurance-coverage-edit-wide"}
                    [:i18n/tr :instrument/description-hint])]))

(defn- create-steps [current-step]
  (step-circles/StepCircles {::step-circles/label        [:i18n/tr :insurance/coverage-create-steps]
                             ::step-circles/current-step current-step
                             ::step-circles/steps
                             [{:label [:i18n/tr :insurance/instrument-step]}
                              {:label [:i18n/tr :insurance/photos-step]}
                              {:label [:i18n/tr :insurance/coverage-step]}]}))

(defn- instrument->form [policy-id redirect instrument]
  {:policy-id       (str policy-id)
   :instrument-id   (form/text-value (some-> instrument :instrument/instrument-id str))
   :redirect        (form/text-value redirect)
   :instrument-name (form/text-value (:instrument/name instrument))
   :owner-member-id (form/text-value (some-> instrument :instrument/owner :member/member-id str))
   :category-id     (form/text-value (some-> instrument :instrument/category :instrument.category/category-id str))
   :make            (form/text-value (:instrument/make instrument))
   :model           (form/text-value (:instrument/model instrument))
   :serial-number   (form/text-value (:instrument/serial-number instrument))
   :build-year      (form/text-value (:instrument/build-year instrument))
   :description     (form/text-value (:instrument/description instrument))
   :_error          {}})

(defn- form-state [{:keys [page-state]} policy-id redirect instrument]
  (merge (instrument->form policy-id redirect instrument)
         (:coverage-create page-state)))

(defn- top-error-callout [form-state]
  (when-let [top-error (field-error form-state :_top)]
    [:wa-callout {:appearance "outlined"
                  :variant    "danger"}
     top-error]))

(defn- warning-callout []
  [:wa-callout {:appearance "outlined"
                :variant    "brand"}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :info
              :slot         "icon"}]
   [:i18n/tr :insurance/add-coverage-separate-warning]])

(defn- instrument-form [req form-state]
  [:form {:id             "coverage-create-instrument-form"
          :class          "wa-stack wa-gap-xl"
          :data-id        "coverage-create"
          :data-action    (d*/act req ::actions/save-instrument-step)
          :data-on:submit "evt.preventDefault();"
          :data-signals   (d*/->signals {:coverage-create (dissoc form-state :_error)})}
   (warning-callout)
   (instrument-section req form-state)
   (top-error-callout form-state)])

(defn instrument-page-content [req policy instrument redirect]
  (let [policy-id  (:insurance.policy/policy-id policy)
        form-state (form-state req policy-id redirect instrument)]
    [:div {:class "insurance-coverage-edit-page wa-stack wa-gap-2xl"}
     [page-header/PageHeader {:class    "insurance-coverage-page-header"
                              :title    [:i18n/tr :insurance/add-coverage-title]
                              :subtitle [:i18n/tr :insurance/add-coverage-subtitle
                                         {:policy-name (:insurance.policy/name policy)}]}]
     (create-steps 1)
     (instrument-form req form-state)]))

(defn instrument-page [{:keys [db] :as req}]
  (let [policy-id     (http.util/path-param-uuid! req :policy-id)
        policy        (or (:policy req) (q/retrieve-policy db policy-id))
        instrument-id (http.util/query-param-uuid req :instrument-id)
        redirect      (query-param req :redirect)
        instrument    (or (:instrument req)
                          (when instrument-id
                            (q/retrieve-instrument db instrument-id)))]
    (cond
      (nil? policy)
      (throw (ex-info "Policy not found" {:app/error-type :app.error.type/not-found
                                          :insurance.policy/policy-id policy-id}))

      (and instrument-id (nil? instrument))
      (throw (ex-info "Instrument not found" {:app/error-type :app.error.type/not-found
                                              :instrument/instrument-id instrument-id}))

      :else
      (ui2/datastar-page*
       [page-surface/PageSurface
        {::page-surface/width :standard
         ::page-surface/toolbar
         [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                    [breadcrumb/Breadcrumb {::breadcrumb/max-items [2 3]}
                                     [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
                                      [:i18n/tr :insurance/title]]
                                     [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
                                      (:insurance.policy/name policy)]
                                     [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/instrument-step]]]
                                    ::page-toolbar/actions
                                    [[button/Button {:appearance "outlined"
                                                     :href       (urls/link-policy policy)}
                                      [:i18n/tr :action/cancel]]
                                     [button/Button {:appearance         "filled"
                                                     :variant            "brand"
                                                     :type               "submit"
                                                     :form               "coverage-create-instrument-form"
                                                     :data-attr:disabled "!!$loading && $loading !== 'coverage-create'"
                                                     :data-attr:loading  "$loading === 'coverage-create'"}
                                      [:i18n/tr :action/next]]]
                                    :aria-label [:i18n/tr :insurance/toolbar-label]}]}
        (instrument-page-content req policy instrument redirect)]))))

(defn photos-page-content
  [req instrument]
  [:div {:class "insurance-coverage-edit-page wa-stack wa-gap-2xl"}
   [page-header/PageHeader {:class    "insurance-coverage-page-header"
                            :title    [:i18n/tr :insurance/add-coverage-title]
                            :subtitle (:instrument/name instrument)}]
   (create-steps 2)
   (upload/upload-section
    req instrument
    {:complete-label [:i18n/tr :insurance/upload-complete]
     :drop-label     [:i18n/tr :insurance/upload-drop-label]
     :empty-body     [:i18n/tr :insurance/no-photos]
     :empty-title    [:i18n/tr :insurance/photos]
     :error-label    [:i18n/tr :insurance/upload-error]
     :help-label     [:i18n/tr :insurance/upload-help]
     :input-id       "coverage-create-photo-upload"
     :progress-label [:i18n/tr :insurance/upload-progress]
     :reload?        true
     :subtitle       [:i18n/tr :insurance/photo-upload-subtitle]
     :title          [:i18n/tr :insurance/photo-upload]})
   (upload/upload-script)])

(defn photos-page [{:keys [db instrument] :as req}]
  (let [policy-id     (http.util/path-param-uuid! req :policy-id)
        instrument-id (http.util/path-param-uuid! req :instrument-id)
        policy        (or (:policy req) (q/retrieve-policy db policy-id))
        instrument    (or instrument (q/retrieve-instrument db instrument-id))
        redirect      (query-param req :redirect)]
    (cond
      (nil? policy)
      (throw (ex-info "Policy not found" {:app/error-type :app.error.type/not-found
                                          :insurance.policy/policy-id policy-id}))

      (nil? instrument)
      (throw (ex-info "Instrument not found" {:app/error-type :app.error.type/not-found
                                              :instrument/instrument-id instrument-id}))

      :else
      (let [previous-url (urls/link-coverage-create-edit policy-id instrument-id redirect)
            next-url     (urls/link-coverage-create3 policy-id instrument-id redirect)]
        (ui2/datastar-page*
         [page-surface/PageSurface
          {::page-surface/width :standard
           ::page-surface/toolbar
           [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                      [breadcrumb/Breadcrumb {::breadcrumb/max-items [2 3]}
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
                                        [:i18n/tr :insurance/title]]
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
                                        (:insurance.policy/name policy)]
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href
                                                                   (urls/link-coverage-create-edit policy-id instrument-id redirect)}
                                        [:i18n/tr :insurance/instrument-step]]
                                       [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/photos-step]]]
                                      ::page-toolbar/actions
                                      [[button/Button {:appearance "outlined"
                                                       :href       previous-url}
                                        [:i18n/tr :action/back]]
                                       [button/Button {:appearance "filled"
                                                       :variant    "brand"
                                                       :href       next-url}
                                        [:i18n/tr :action/next]]]
                                      :aria-label [:i18n/tr :insurance/toolbar-label]}]}
          (photos-page-content req instrument)])))))

(defn- coverage-validate-field-action [req field]
  (str "$coverage-create.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-coverage-field)
       "')"))

(defn- coverage-field-attrs [req form-state field hint]
  (let [error (field-error form-state field)]
    {:data-bind        (str "coverage-create." (name field))
     :data-invalid     (when error "true")
     :aria-invalid     (when error "true")
     :aria-describedby (described-by field hint error)
     :data-on:change   (coverage-validate-field-action req field)}))

(defn- coverage-input-field [req form-state field label value attrs hint]
  (field-wrapper
   label
   field
   form-state
   hint
   (:required attrs)
   [:input (merge {:id    (name field)
                   :name  (name field)
                   :type  "text"
                   :value (form/text-value value)}
                  (coverage-field-attrs req form-state field hint)
                  attrs)]))

(defn- required-coverage-type-ids [coverage-types]
  (->> coverage-types
       (filter :insurance.coverage.type/required?)
       (mapv (comp str :insurance.coverage.type/type-id))))

(defn- coverage->form [policy instrument redirect]
  {:policy-id      (str (:insurance.policy/policy-id policy))
   :instrument-id  (str (:instrument/instrument-id instrument))
   :redirect       (form/text-value redirect)
   :item-count     "1"
   :value          ""
   :private-band   "band"
   :coverage-types (required-coverage-type-ids
                    (:insurance.policy/coverage-types policy))
   :insurer-id     ""
   :_error         {}})

(defn- coverage-form-state [{:keys [page-state]} policy instrument redirect]
  (merge (coverage->form policy instrument redirect)
         (:coverage-create page-state)))

(defn- coverage-type-change-action [type-id]
  (let [type-id (str type-id)]
    (str "if (evt.target.checked) { "
         "$coverage-create.coverage-types = Array.from(new Set([...$coverage-create.coverage-types, '" type-id "'])); "
         "} else { "
         "$coverage-create.coverage-types = $coverage-create.coverage-types.filter((id) => id !== '" type-id "'); "
         "}")))

(defn- coverage-type-checkbox
  [selected-type-ids
   {:insurance.coverage.type/keys [type-id name description required?]}]
  (let [type-id-str (str type-id)
        checked?    (or required? (contains? selected-type-ids type-id-str))]
    [:wa-checkbox (cond-> {:value             type-id-str
                           :data-attr:checked (str "$coverage-create.coverage-types.includes('" type-id-str "')")}
                    checked? (assoc :checked true)
                    required? (assoc :disabled true)
                    (not required?) (assoc :data-on:change (coverage-type-change-action type-id)))
     [:span {:class "wa-stack wa-gap-3xs"}
      [:span name]
      (when-not (str/blank? (str description))
        [:small {:class "wa-color-text-quiet"} description])]]))

(defn- coverage-types-field [form-state coverage-types]
  (let [selected-type-ids (set (:coverage-types form-state))
        error             (field-error form-state :coverage-types)]
    [:div {:class     (ui2/cs "insurance-coverage-edit-wide"
                              "insurance-coverage-edit-choice-list")
           :data-show "$coverage-create.private-band === 'private'"}
     [:div {:class "insurance-coverage-edit-field-label"}
      [:span [:i18n/tr :insurance/coverage-types]]
      (when error
        [:span {:class "wa-caption-s text-danger"} error])]
     (into
      [:div {:class "wa-stack wa-gap-xs"}]
      (map (partial coverage-type-checkbox selected-type-ids)
           coverage-types))]))

(defn- private-band-field [req form-state]
  (let [error     (field-error form-state :private-band)
        hint      [:i18n/tr :insurance/ownership-hint]
        on-change (str "$coverage-create.private-band = evt.target.value; "
                       (coverage-validate-field-action req :private-band))]
    [:wa-radio-group (cond-> {:name           "private-band"
                              :value          (:private-band form-state)
                              :required       true
                              :with-label     true
                              :with-hint      true
                              :data-bind      "coverage-create.private-band"
                              :data-on:change on-change}
                       error (assoc :data-invalid "true"))
     [:span {:slot "label"}
      [:i18n/tr :insurance/ownership]]
     [:span {:slot "hint" :class "wa-stack wa-gap-3xs"}
      [:span hint]
      (when error
        [:span {:class "text-danger"} error])]
     [:wa-radio {:value "band"}
      [:span {:class "wa-stack wa-gap-3xs"}
       [:span [:i18n/tr :insurance/ownership-band]]
       [:small {:class "wa-color-text-quiet"}
        [:i18n/tr :insurance/ownership-band-description]]]]
     [:wa-radio {:value "private"}
      [:span {:class "wa-stack wa-gap-3xs"}
       [:span [:i18n/tr :insurance/ownership-private]]
       [:small {:class "wa-color-text-quiet"}
        [:i18n/tr :insurance/ownership-private-description]]]]]))

(defn- instrument-summary [instrument]
  (ui2/section-card
   {:title    [:i18n/tr :instrument/instrument]
    :subtitle (:instrument/name instrument)
    :divider? true}
   [:dl {:class "particulars wa-grid wa-gap-m"}
    (ui2/detail-item [:i18n/tr :instrument/owner]
                     (get-in instrument [:instrument/owner :member/name]))
    (ui2/detail-item [:i18n/tr :instrument/category]
                     (get-in instrument [:instrument/category :instrument.category/name]))
    (ui2/detail-item [:i18n/tr :instrument/make]
                     (:instrument/make instrument))
    (ui2/detail-item [:i18n/tr :instrument/model]
                     (:instrument/model instrument))]))

(defn- insurance-team-member? [{:keys [db] :as req}]
  (q/insurance-team-member? db (get-in req [:session :session/member])))

(defn- coverage-section [req policy form-state]
  (ui2/section-card
   {:title    [:i18n/tr :insurance/instrument-coverage]
    :subtitle [:i18n/tr :insurance/coverage-for
               {:policy-name (:insurance.policy/name policy)}]
    :divider? true}
   [:div {:class "insurance-coverage-edit-form-grid"}
    (coverage-input-field req form-state :item-count [:i18n/tr :insurance/item-count] (:item-count form-state)
                          {:type "number" :min 1 :step 1 :required true}
                          [:i18n/tr :insurance/item-count-hint])
    (coverage-input-field req form-state :value [:i18n/tr :insurance/value] (:value form-state)
                          {:type "number" :min 1 :step 1 :required true}
                          [:i18n/tr :insurance/insured-value-hint])
    (private-band-field req form-state)
    (when (insurance-team-member? req)
      (coverage-input-field req form-state :insurer-id [:i18n/tr :insurance/insurer-id] (:insurer-id form-state)
                            {} [:i18n/tr :insurance/insurer-id-hint]))
    (coverage-types-field form-state (:insurance.policy/coverage-types policy))]))

(defn- no-coverage-types-callout []
  [:wa-callout {:appearance "outlined"
                :variant    "warning"}
   [:i18n/tr :insurance/no-coverage-types]])

(defn- coverage-form [req policy instrument redirect]
  (let [form-state      (coverage-form-state req policy instrument redirect)
        coverage-types (:insurance.policy/coverage-types policy)
        disabled?      (empty? coverage-types)]
    [:form {:id             "coverage-create-coverage-form"
            :class          "wa-stack wa-gap-xl"
            :data-id        "coverage-create"
            :data-action    (d*/act req ::actions/create-coverage)
            :data-on:submit "evt.preventDefault();"
            :data-signals   (d*/->signals {:coverage-create (dissoc form-state :_error)})}
     (if disabled?
       (no-coverage-types-callout)
       (coverage-section req policy form-state))
     (top-error-callout form-state)]))

(defn coverage-page-content [req policy instrument redirect]
  [:div {:class "insurance-coverage-edit-page wa-stack wa-gap-2xl"}
   [page-header/PageHeader {:class    "insurance-coverage-page-header"
                            :title    [:i18n/tr :insurance/add-coverage-title]
                            :subtitle [:i18n/tr :insurance/coverage-for
                                       {:policy-name (:insurance.policy/name policy)}]}]
   (create-steps 3)
   (instrument-summary instrument)
   (coverage-form req policy instrument redirect)])

(defn coverage-page [{:keys [db instrument] :as req}]
  (let [policy-id     (http.util/path-param-uuid! req :policy-id)
        instrument-id (http.util/path-param-uuid! req :instrument-id)
        policy        (or (:policy req) (q/retrieve-policy db policy-id))
        instrument    (or instrument (q/retrieve-instrument db instrument-id))
        redirect      (query-param req :redirect)]
    (cond
      (nil? policy)
      (throw (ex-info "Policy not found" {:app/error-type :app.error.type/not-found
                                          :insurance.policy/policy-id policy-id}))

      (nil? instrument)
      (throw (ex-info "Instrument not found" {:app/error-type :app.error.type/not-found
                                              :instrument/instrument-id instrument-id}))

      :else
      (let [previous-url (urls/link-coverage-create2 policy-id instrument-id redirect)
            disabled?   (empty? (:insurance.policy/coverage-types policy))]
        (ui2/datastar-page*
         [page-surface/PageSurface
          {::page-surface/width :standard
           ::page-surface/toolbar
           [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                      [breadcrumb/Breadcrumb {::breadcrumb/max-items [2 3]}
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
                                        [:i18n/tr :insurance/title]]
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
                                        (:insurance.policy/name policy)]
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href
                                                                   (urls/link-coverage-create-edit policy-id instrument-id redirect)}
                                        [:i18n/tr :insurance/instrument-step]]
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href previous-url}
                                        [:i18n/tr :insurance/photos-step]]
                                       [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/coverage-step]]]
                                      ::page-toolbar/actions
                                      [[button/Button {:appearance "outlined"
                                                       :href       previous-url}
                                        [:i18n/tr :action/back]]
                                       [button/Button (cond-> {:appearance         "filled"
                                                               :variant            "brand"
                                                               :type               "submit"
                                                               :form               "coverage-create-coverage-form"
                                                               :data-attr:disabled "!!$loading && $loading !== 'coverage-create'"
                                                               :data-attr:loading  "$loading === 'coverage-create'"}
                                                        disabled? (assoc :disabled true))
                                        [:i18n/tr :action/save]]]
                                      :aria-label [:i18n/tr :insurance/toolbar-label]}]}
          (coverage-page-content req policy instrument redirect)])))))

(d*/refresh-all!)
