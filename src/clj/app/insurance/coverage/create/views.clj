(ns app.insurance.coverage.create.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.coverage.create.actions :as actions]
   [app.insurance.coverage.queries :as queries]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.step-circles :as step-circles]
   [app.urls :as urls]
   [app.util.http :as http.util]))

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

(defn- field-attrs [req form-state field]
  (let [error (field-error form-state field)]
    {:data-bind      (str "coverage-create." (name field))
     :data-invalid   (when error "true")
     :data-on:change (validate-field-action req field)}))

(defn- field-wrapper [label field form-state & children]
  (let [error (field-error form-state field)]
    (into
     [:div {:class "insurance-coverage-create-field wa-stack wa-gap-2xs"}
      [:label {:class "insurance-coverage-edit-field-label"
               :for   (name field)}
       label]]
     (concat
      children
      [(when error
         [:span {:class "wa-caption-s text-danger"}
          error])]))))

(defn- input-field [req form-state field label value attrs]
  (field-wrapper
   label
   field
   form-state
   [:input (merge {:id    (name field)
                   :name  (name field)
                   :type  "text"
                   :value (form/text-value value)}
                  (field-attrs req form-state field)
                  attrs)]))

(defn- textarea-field [req form-state field label value attrs]
  (field-wrapper
   label
   field
   form-state
   [:textarea (merge {:id             (name field)
                      :name           (name field)
                      :class          "insurance-coverage-edit-textarea"
                      :rows           5
                      :data-auto-size "true"}
                     (field-attrs req form-state field)
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

(defn- member-select [{:keys [db tr] :as req} form-state]
  (let [selected (:owner-member-id form-state)
        members  (q/members-for-select db)]
    (field-wrapper
     (tr [:instrument/owner])
     :owner-member-id
     form-state
     (into
      [:select (merge {:id       "owner-member-id"
                       :name     "owner-member-id"
                       :required true}
                      (field-attrs req form-state :owner-member-id))
       (option "" "—" (form/text-value selected))]
      (for [member members]
        (member-option selected member))))))

(defn- category-option [selected-category-id category]
  (let [category-id (:instrument.category/category-id category)]
    (option (str category-id)
            (:instrument.category/name category)
            (some-> selected-category-id str))))

(defn- category-select [{:keys [db tr] :as req} form-state]
  (let [selected   (:category-id form-state)
        categories (queries/instrument-categories db)]
    (field-wrapper
     (tr [:instrument/category])
     :category-id
     form-state
     (into
      [:select (merge {:id       "category-id"
                       :name     "category-id"
                       :required true}
                      (field-attrs req form-state :category-id))
       (option "" "—" (form/text-value selected))]
      (for [category categories]
        (category-option selected category))))))

(defn- instrument-section [req form-state]
  (let [tr (:tr req)]
    (ui2/section-card
     {:title    (tr [:instrument/instrument])
      :subtitle (tr [:instrument/create-subtitle])
      :divider? true}
     [:div {:class "insurance-coverage-edit-form-grid"}
      (input-field req form-state :instrument-name (tr [:instrument/name]) (:instrument-name form-state) {:required true})
      (member-select req form-state)
      (category-select req form-state)
      (input-field req form-state :make (tr [:instrument/make]) (:make form-state) {:required true})
      (input-field req form-state :model (tr [:instrument/model]) (:model form-state) {})
      (input-field req form-state :serial-number (tr [:instrument/serial-number]) (:serial-number form-state) {})
      (input-field req form-state :build-year (tr [:instrument/build-year]) (:build-year form-state) {})
      (textarea-field req form-state :description (tr [:instrument/description]) (:description form-state)
                      {:class "insurance-coverage-edit-textarea insurance-coverage-edit-wide"})])))

(defn- breadcrumb [{:keys [tr]} policy]
  [breadcrumb/Breadcrumb {:class "insurance-coverage-breadcrumb"}
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
    (:insurance.policy/name policy)]
   [breadcrumb/BreadcrumbItem (tr [:instrument.coverage/create-title])]])

(defn- create-steps [tr]
  (step-circles/StepCircles {::step-circles/label        (tr [:instrument.coverage/create-steps])
                             ::step-circles/current-step 1
                             ::step-circles/steps
                             [{:label (tr [:instrument.coverage/create-step-instrument])}
                              {:label (tr [:instrument.coverage/create-step-photos])}
                              {:label (tr [:instrument.coverage/create-step-coverage])}]}))

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

(defn- warning-callout [{:keys [tr]}]
  [:wa-callout {:appearance "outlined"
                :variant    "brand"}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :info
              :slot         "icon"}]
   (tr [:instrument/separate-warning])])

(defn- next-button [tr]
  [button/Button {:appearance         "filled"
                  :variant            "brand"
                  :type               "submit"
                  :form               "coverage-create-instrument-form"
                  :data-attr:disabled "!!$loading && $loading !== 'coverage-create'"
                  :data-attr:loading  "$loading === 'coverage-create'"}
   (tr [:action/next])])

(defn- form-actions [{:keys [tr]} policy]
  (ui2/action-bar
   {:class "insurance-coverage-edit-actions"}
   [[button/Button {:appearance "outlined"
                    :href       (urls/link-policy policy)}
     (tr [:insurance.review/back-to-dashboard])]
    (next-button tr)]))

(defn- instrument-form [req policy form-state]
  [:form {:id             "coverage-create-instrument-form"
          :class          "wa-stack wa-gap-xl"
          :data-id        "coverage-create"
          :data-action    (d*/act req ::actions/save-instrument-step)
          :data-on:submit "evt.preventDefault();"
          :data-signals   (d*/->signals {:coverage-create (dissoc form-state :_error)})}
   (warning-callout req)
   (instrument-section req form-state)
   (top-error-callout form-state)
   (form-actions req policy)])

(defn instrument-page [{:keys [db tr] :as req}]
  (let [policy-id     (http.util/path-param-uuid! req :policy-id)
        policy        (or (:policy req) (q/retrieve-policy db policy-id))
        instrument-id (http.util/query-param-uuid req :instrument-id)
        redirect      (query-param req :redirect)
        instrument    (when instrument-id
                        (q/retrieve-instrument db instrument-id))]
    (cond
      (nil? policy)
      (throw (ex-info "Policy not found" {:app/error-type :app.error.type/not-found
                                          :insurance.policy/policy-id policy-id}))

      (and instrument-id (nil? instrument))
      (throw (ex-info "Instrument not found" {:app/error-type :app.error.type/not-found
                                              :instrument/instrument-id instrument-id}))

      :else
      (let [form-state (form-state req policy-id redirect instrument)]
        (ui2/datastar-page
         [:div {:class "insurance-coverage-edit-page wa-stack wa-gap-2xl"}
          (ui2/page-header {:class      "insurance-coverage-page-header"
                            :breadcrumb (breadcrumb req policy)
                            :title      (tr [:instrument.coverage/create-title])
                            :subtitle   (tr [:instrument.coverage/create-subtitle]
                                            [(:insurance.policy/name policy)])})
          (create-steps tr)
          (instrument-form req policy form-state)])))))

(d*/refresh-all!)
