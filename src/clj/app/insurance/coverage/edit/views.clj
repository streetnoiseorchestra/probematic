(ns app.insurance.coverage.edit.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.coverage.edit.actions :as actions]
   [app.insurance.queries :as queries]
   [app.insurance.coverage.upload :as upload]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- option [value label selected-value]
  [:wa-option {:value    value
               :selected (= value selected-value)}
   label])

(defn- validate-field-action [req field]
  (str "$coverage-edit.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-coverage-field)
       "')"))

(defn- validate-field-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint           error
     :data-invalid   (when error "true")
     :data-bind      (str "coverage-edit." (name field))
     :data-on:change (validate-field-action req field)}))

(defn- validate-select-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint           error
     :data-invalid   (when error "true")
     :data-bind      (str "coverage-edit." (name field))
     :data-on:change (validate-field-action req field)}))

(defn- input [label name value attrs]
  [:wa-input (merge {:label      label
                     :name       name
                     :value      (form/text-value value)
                     :appearance "outlined"}
                    attrs)])

(defn- textarea [label name value attrs]
  (let [error         (:hint attrs)
        wrapper-attrs (:wrapper-attrs attrs)
        attrs         (dissoc attrs :hint :wrapper-attrs)]
    [:div (merge {:class "insurance-coverage-edit-textarea-field"} wrapper-attrs)
     [:label {:for name} label]
     [:textarea (merge {:id             name
                        :name           name
                        :class          "insurance-coverage-edit-textarea"
                        :rows           5
                        :data-auto-size "true"}
                       attrs)
      (form/text-value value)]
     (when error
       [:span {:class "wa-caption-s text-danger"}
        error])]))

(defn- member-option [selected-member-id member]
  (let [member-id (:member/member-id member)]
    (option (str member-id)
            (ui2/member-nick member)
            (some-> selected-member-id str))))

(defn- member-select [{:keys [db tr] :as req} form-state]
  (let [selected (:owner-member-id form-state)
        members  (q/members-for-select db)]
    (into
     [:wa-select (merge {:label      (tr [:instrument/owner])
                         :name       "owner-member-id"
                         :value      (form/text-value selected)
                         :required   true
                         :appearance "outlined"}
                        (validate-select-attrs req form-state :owner-member-id))
      [:wa-option {:value ""} " - "]]
     (for [member members]
       (member-option selected member)))))

(defn- category-option [selected-category-id category]
  (let [category-id (:instrument.category/category-id category)]
    (option (str category-id)
            (:instrument.category/name category)
            (some-> selected-category-id str))))

(defn- category-select [{:keys [db tr] :as req} form-state]
  (let [selected   (:category-id form-state)
        categories (queries/instrument-categories db)]
    (into
     [:wa-select (merge {:label      (tr [:instrument/category])
                         :name       "category-id"
                         :value      (form/text-value selected)
                         :required   true
                         :appearance "outlined"}
                        (validate-select-attrs req form-state :category-id))
      [:wa-option {:value ""} " - "]]
     (for [category categories]
       (category-option selected category)))))

(defn- private-band-field [{:keys [tr] :as req} form-state]
  (let [attrs (assoc (merge {:label      (tr [:band-private])
                             :name       "private-band"
                             :value      (:private-band form-state)
                             :required   true
                             :appearance "outlined"}
                            (validate-select-attrs req form-state :private-band))
                     :data-on:change
                     (str "$coverage-edit.private-band = evt.target.value; "
                          (validate-field-action req :private-band)))]
    [:wa-radio-group attrs
     [:wa-radio {:value "band"}
      [:span {:class "wa-stack wa-gap-3xs"}
       [:span (tr [:band-instrument])]
       [:small {:class "wa-color-text-quiet"} (tr [:band-instrument-description])]]]
     [:wa-radio {:value "private"}
      [:span {:class "wa-stack wa-gap-3xs"}
       [:span (tr [:private-instrument])]
       [:small {:class "wa-color-text-quiet"} (tr [:private-instrument-description])]]]]))

(defn- coverage-type-change-action [type-id]
  (let [type-id (str type-id)]
    (str "if (evt.target.checked) { "
         "$coverage-edit.coverage-types = Array.from(new Set([...$coverage-edit.coverage-types, '" type-id "'])); "
         "} else { "
         "$coverage-edit.coverage-types = $coverage-edit.coverage-types.filter((id) => id !== '" type-id "'); "
         "}")))

(defn- coverage-type-checkbox
  [selected-type-ids
   {:insurance.coverage.type/keys [type-id name description required?]}]
  (let [type-id-str (str type-id)
        checked?    (or required? (contains? selected-type-ids type-id-str))]
    [:wa-checkbox (cond-> {:value             type-id-str
                           :data-attr:checked (str "$coverage-edit.coverage-types.includes('" type-id-str "')")}
                    checked? (assoc :checked true)
                    required? (assoc :disabled true)
                    (not required?) (assoc :data-on:change (coverage-type-change-action type-id)))
     [:span {:class "wa-stack wa-gap-3xs"}
      [:span name]
      (when-not (str/blank? (str description))
        [:small {:class "wa-color-text-quiet"} description])]]))

(defn- coverage-types-field [{:keys [tr]} form-state coverage-types]
  (let [selected-type-ids (set (:coverage-types form-state))
        error             (form/field-error form-state :coverage-types)]
    [:div {:class (ui2/cs "insurance-coverage-edit-wide" "insurance-coverage-edit-choice-list")}
     [:div {:class "insurance-coverage-edit-field-label"}
      [:span (tr [:insurance/coverage-types])]
      (when error
        [:span {:class "wa-caption-s text-danger"} error])]
     (into
      [:div {:class "wa-stack wa-gap-xs"}]
      (map (partial coverage-type-checkbox selected-type-ids)
           coverage-types))]))

(defn- required-coverage-type-ids [policy]
  (->> (:insurance.policy/coverage-types policy)
       (filter :insurance.coverage.type/required?)
       (mapv (comp str :insurance.coverage.type/type-id))))

(defn- coverage->form [{:instrument.coverage/keys [coverage-id instrument item-count private? types value insurer-id]}
                       policy]
  (let [coverage-type-ids (->> (concat (required-coverage-type-ids policy)
                                       (map (comp str :insurance.coverage.type/type-id)
                                            types))
                               distinct
                               vec)]
    {:policy-id       (str (:insurance.policy/policy-id policy))
     :coverage-id     (str coverage-id)
     :instrument-id   (str (:instrument/instrument-id instrument))
     :instrument-name (form/text-value (:instrument/name instrument))
     :owner-member-id (form/text-value (some-> instrument :instrument/owner :member/member-id str))
     :category-id     (form/text-value (some-> instrument :instrument/category :instrument.category/category-id str))
     :make            (form/text-value (:instrument/make instrument))
     :model           (form/text-value (:instrument/model instrument))
     :serial-number   (form/text-value (:instrument/serial-number instrument))
     :build-year      (form/text-value (:instrument/build-year instrument))
     :description     (form/text-value (:instrument/description instrument))
     :item-count      (str (or item-count 1))
     :value           (str value)
     :private-band    (if private? "private" "band")
     :coverage-types  coverage-type-ids
     :insurer-id      (form/text-value insurer-id)
     :_error          {}}))

(defn- form-state [{:keys [page-state]} coverage policy]
  (merge (coverage->form coverage policy) (:coverage-edit page-state)))

(defn- instrument-section [req form-state]
  (ui2/section-card
   {:title    ((:tr req) [:instrument/instrument])
    :subtitle ((:tr req) [:instrument/create-subtitle])
    :divider? true}
   [:div {:class "insurance-coverage-edit-form-grid"}
    (input ((:tr req) [:instrument/name]) "instrument-name" (:instrument-name form-state) (merge {:required true} (validate-field-attrs req form-state :instrument-name)))
    (member-select req form-state)
    (category-select req form-state)
    (input ((:tr req) [:instrument/make]) "make" (:make form-state) (merge {:required true} (validate-field-attrs req form-state :make)))
    (input ((:tr req) [:instrument/model]) "model" (:model form-state) (validate-field-attrs req form-state :model))
    (input ((:tr req) [:instrument/serial-number]) "serial-number" (:serial-number form-state) (validate-field-attrs req form-state :serial-number))
    (input ((:tr req) [:instrument/build-year]) "build-year" (:build-year form-state) (validate-field-attrs req form-state :build-year))
    (textarea ((:tr req) [:instrument/description]) "description" (:description form-state) (merge {:class "insurance-coverage-edit-textarea insurance-coverage-edit-wide"}
                                                                                                   (validate-field-attrs req form-state :description)))]))

(defn- coverage-section [{:keys [tr] :as req} form-state policy]
  (ui2/section-card
   {:title    (tr [:insurance/instrument-coverage])
    :subtitle (tr [:insurance/coverage-for] [(:insurance.policy/name policy)])
    :divider? true}
   [:div {:class "insurance-coverage-edit-form-grid"}
    (input (tr [:insurance/item-count]) "item-count" (:item-count form-state) (merge {:type "number" :min 1 :step 1 :required true}
                                                                                     (validate-field-attrs req form-state :item-count)))
    (input (tr [:insurance/value]) "value" (:value form-state) (merge {:type "number" :min 1 :step 1 :required true}
                                                                      (validate-field-attrs req form-state :value)))
    (private-band-field req form-state)
    (input (tr [:instrument.coverage/insurer-id])
           "insurer-id"
           (:insurer-id form-state)
           (update (validate-field-attrs req form-state :insurer-id)
                   :hint
                   #(or % (tr [:instrument.coverage/insurer-id-hint]))))
    (coverage-types-field req form-state (:insurance.policy/coverage-types policy))]))

(defn- remove-dialog-id [{:instrument.coverage/keys [coverage-id]}]
  (ui2/remove-dialog-id "coverage" coverage-id))

(defn- remove-dialog [{:keys [tr] :as req} coverage]
  (ui2/remove-dialog
   {:id            (remove-dialog-id coverage)
    :label         (tr [:action/confirm-generic])
    :cancel-label  (tr [:action/cancel])
    :confirm-label (tr [:action/confirm-delete])
    :confirm-attrs {:data-id     (str (:instrument.coverage/coverage-id coverage))
                    :data-action (d*/act req ::actions/delete-instrument-coverage)}}
   [:p (tr [:action/confirm-delete-instrument]
           [(get-in coverage [:instrument.coverage/instrument :instrument/name])])]))

(defn- insurance-team-member? [{:keys [db] :as req}]
  (q/insurance-team-member? db (get-in req [:session :session/member])))

(defn- edit-form [req coverage policy]
  (let [form-state (form-state req coverage policy)]
    [:form {:id             "coverage-edit-form"
            :class          "wa-stack wa-gap-xl"
            :data-id        "coverage-edit"
            :data-action    (d*/act req ::actions/update-instrument-coverage)
            :data-on:submit "evt.preventDefault();"
            :data-signals   (d*/->signals {:coverage-edit (dissoc form-state :_error)})}
     (instrument-section req form-state)
     (upload/upload-section
      req
      (:instrument.coverage/instrument coverage)
      {:complete-label ((:tr req) [:instrument.coverage/upload-complete])
       :drop-label     ((:tr req) [:instrument.coverage/upload-drop-label])
       :empty-body     ((:tr req) [:insurance/no-photos])
       :empty-title    ((:tr req) [:instrument/images])
       :error-label    ((:tr req) [:instrument.coverage/upload-error])
       :help-label     ((:tr req) [:instrument.coverage/upload-help])
       :input-id       "coverage-edit-photo-upload"
       :progress-label ((:tr req) [:instrument.coverage/upload-progress])
       :subtitle       ((:tr req) [:instrument/photo-upload-subtitle])
       :title          ((:tr req) [:instrument/photo-upload])})
     (coverage-section req form-state policy)
     (when-let [top-error (form/field-error form-state :_top)]
       [:wa-callout {:appearance "outlined"
                     :variant    "danger"}
        top-error])]))

(defn page [{:keys [db] :as req}]
  (let [coverage-id (http.util/path-param-uuid! req :coverage-id)
        coverage    (queries/coverage db coverage-id)
        policy      (:insurance.policy/_covered-instruments coverage)
        instrument  (:instrument.coverage/instrument coverage)]
    (if coverage
      (let [coverage-url (urls/link-coverage coverage)]
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
                                       [breadcrumb/BreadcrumbItem {::breadcrumb/href coverage-url}
                                        (:instrument/name instrument)]
                                       [breadcrumb/BreadcrumbItem [:i18n/tr :action/edit]]]
                                      ::page-toolbar/actions
                                      [[button/Button {:appearance "plain"
                                                       :href       coverage-url}
                                        [:i18n/tr :action/cancel]]
                                       [button/Button {:appearance         "filled"
                                                       :variant            "brand"
                                                       :type               "submit"
                                                       :form               "coverage-edit-form"
                                                       :data-attr:disabled "!!$loading && $loading !== 'coverage-edit'"
                                                       :data-attr:loading  "$loading === 'coverage-edit'"}
                                        [:i18n/tr :action/save]]]
                                      ::page-toolbar/overflow-items
                                      (when (insurance-team-member? req)
                                        [[:wa-dropdown-item {:variant     "danger"
                                                             :data-dialog (str "open " (remove-dialog-id coverage))}
                                          [:i18n/tr :action/delete]]])
                                      ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
                                      :aria-label                    [:i18n/tr :insurance/toolbar-label]}]}
          [:div {:class "insurance-coverage-edit-page wa-stack wa-gap-2xl"}
           [page-header/PageHeader {:class    "insurance-coverage-page-header"
                                    :title    (:instrument/name instrument)
                                    :subtitle [:i18n/tr :insurance/edit-coverage]}]
           (edit-form req coverage policy)]]
         (remove-dialog req coverage)
         (upload/upload-script)))
      (throw (ex-info "Instrument coverage not found" {:app/error-type :app.error.type/not-found
                                                       :instrument.coverage/coverage-id coverage-id})))))

(d*/refresh-all!)
