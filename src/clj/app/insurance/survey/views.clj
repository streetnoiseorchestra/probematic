(ns app.insurance.survey.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.coverage.upload :as upload]
   [app.insurance.survey.actions :as actions]
   [app.insurance.survey.flow :as flow]
   [app.insurance.survey.queries :as queries]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.ui2.step-circles :as step-circles]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(def edit-signal-names
  {:build-year      "buildYear"
   :category-id     "categoryId"
   :coverage-types  "coverageTypes"
   :description     "description"
   :insurer-id      "insurerId"
   :instrument-name "instrumentName"
   :item-count      "itemCount"
   :make            "make"
   :model           "model"
   :serial-number   "serialNumber"
   :value           "value"})

(defn- coverage->edit
  [{:instrument.coverage/keys [instrument item-count types value insurer-id]}]
  {:build-year      (form/text-value (:instrument/build-year instrument))
   :category-id     (form/text-value
                     (some-> instrument :instrument/category
                             :instrument.category/category-id str))
   :coverage-types  (mapv (comp str :insurance.coverage.type/type-id) types)
   :description     (form/text-value (:instrument/description instrument))
   :insurer-id      (form/text-value insurer-id)
   :instrument-name (form/text-value (:instrument/name instrument))
   :item-count      (str (or item-count 1))
   :make            (form/text-value (:instrument/make instrument))
   :model           (form/text-value (:instrument/model instrument))
   :serial-number   (form/text-value (:instrument/serial-number instrument))
   :value           (str value)})

(defn- edit->signals [edit]
  (reduce-kv (fn [signals key signal-name]
               (assoc signals (keyword signal-name) (get edit key)))
             {}
             edit-signal-names))

(defn- field-hint [form-state field hint]
  (or (form/field-error form-state field) hint))

(defn- input-field [form-state field label attrs hint]
  (let [name        (name field)
        signal-name (edit-signal-names field)
        hint-id     (str "insurance-survey-edit-" name "-hint")
        hint        (field-hint form-state field hint)
        error?      (some? (form/field-error form-state field))]
    [:label {:class "wa-stack wa-gap-2xs"
             :for   name}
     [:span label]
     [:input (merge {:id               name
                     :name             name
                     :value            (form/text-value (get form-state field))
                     :data-bind        (str "insuranceSurvey.edit." signal-name)
                     :aria-describedby (when hint hint-id)
                     :aria-invalid     (when error? "true")}
                    attrs)]
     (when hint
       [:small {:id    hint-id
                :class (if error? "text-danger" "wa-color-text-quiet")}
        hint])]))

(defn- textarea-field [form-state]
  (let [field  :description
        hint   (field-hint form-state field [:i18n/tr :instrument/description-hint])
        error? (some? (form/field-error form-state field))]
    [:label {:class "insurance-coverage-edit-textarea-field"
             :for   "description"}
     [:span [:i18n/tr :instrument/description]]
     [:textarea {:id               "description"
                 :name             "description"
                 :class            "insurance-coverage-edit-textarea"
                 :rows             5
                 :data-auto-size   "true"
                 :data-bind        "insuranceSurvey.edit.description"
                 :aria-describedby "insurance-survey-edit-description-hint"
                 :aria-invalid     (when error? "true")}
      (form/text-value (:description form-state))]
     [:small {:id    "insurance-survey-edit-description-hint"
              :class (if error? "text-danger" "wa-color-text-quiet")}
      hint]]))

(defn- category-field [db form-state]
  (let [error  (form/field-error form-state :category-id)
        hint   (or error [:i18n/tr :instrument/category-hint])
        current (some-> (:category-id form-state) str)]
    [:label {:class "wa-stack wa-gap-2xs"
             :for   "category-id"}
     [:span [:i18n/tr :instrument/category]]
     (into
      [:select {:id               "category-id"
                :name             "category-id"
                :required         true
                :value            current
                :data-bind        "insuranceSurvey.edit.categoryId"
                :aria-describedby "insurance-survey-edit-category-id-hint"
                :aria-invalid     (when error "true")}
       [:option {:value ""} "—"]]
      (for [{:instrument.category/keys [category-id name]}
            (coverage.queries/instrument-categories db)]
        [:option {:value    (str category-id)
                  :selected (= current (str category-id))}
         name]))
     [:small {:id    "insurance-survey-edit-category-id-hint"
              :class (if error "text-danger" "wa-color-text-quiet")}
      hint]]))

(defn- coverage-types-field [form-state coverage policy]
  (let [selected (set (:coverage-types form-state))
        private? (:instrument.coverage/private? coverage)
        error    (form/field-error form-state :coverage-types)]
    [:fieldset {:class "insurance-coverage-edit-wide wa-stack wa-gap-xs"}
     [:legend [:i18n/tr :insurance/coverage-types]]
     (when error
       [:small {:class "text-danger"} error])
     (for [[idx {:insurance.coverage.type/keys [type-id name description]}]
           (map-indexed vector (:insurance.policy/coverage-types policy))
           :let [type-id (str type-id)
                 checked? (or (not private?)
                              (zero? idx)
                              (contains? selected type-id))]]
       [:label {:class "wa-flank wa-gap-xs"}
        [:input (cond-> {:type              "checkbox"
                         :value             type-id
                         :checked           checked?
                         :data-attr:checked (str "$insuranceSurvey.edit.coverageTypes.includes('" type-id "')")}
                  (or (not private?) (zero? idx)) (assoc :disabled true)
                  (and private? (pos? idx))
                  (assoc :data-on:change
                         (str "if (evt.target.checked) { "
                              "$insuranceSurvey.edit.coverageTypes = Array.from(new Set([...$insuranceSurvey.edit.coverageTypes, '" type-id "'])); "
                              "} else { $insuranceSurvey.edit.coverageTypes = $insuranceSurvey.edit.coverageTypes.filter((id) => id !== '" type-id "'); }")))]
        [:span {:class "wa-stack wa-gap-3xs"}
         [:span name]
         (when-not (str/blank? (str description))
           [:small {:class "wa-color-text-quiet"} description])]])]))

(defn- instrument-card [coverage decisions]
  (let [{:instrument.coverage/keys [cost instrument item-count private? types value]}
        coverage
        private? (cond
                   (some #{:confirm-not-band} decisions) true
                   (some #{:confirm-band} decisions) false
                   :else private?)
        details  [[[:i18n/tr :instrument/category]
                   (get-in instrument [:instrument/category :instrument.category/name])]
                  [[:i18n/tr :insurance/ownership]
                   [:i18n/tr (if private?
                               :insurance/ownership-private
                               :insurance/ownership-band)]]
                  (when private?
                    [[:i18n/tr :insurance/annual-cost]
                     (if cost
                       (ui2/money cost :EUR)
                       [:i18n/tr :insurance/cost-unavailable])])
                  [[:i18n/tr :insurance/value] (ui2/money value :EUR)]
                  [[:i18n/tr :insurance/item-count] (or item-count 1)]
                  [[:i18n/tr :insurance/coverage-types]
                   (str/join ", " (map :insurance.coverage.type/name types))]
                  [[:i18n/tr :instrument/make] (:instrument/make instrument)]
                  [[:i18n/tr :instrument/model] (:instrument/model instrument)]
                  [[:i18n/tr :instrument/serial-number] (:instrument/serial-number instrument)]
                  [[:i18n/tr :instrument/build-year] (:instrument/build-year instrument)]]]
    [card/Card {:id         "insurance-survey-instrument"
                :appearance "outlined"}
     [:h2 {:slot "header" :class "wa-heading-l"}
      (:instrument/name instrument)]
     (when-not (str/blank? (:instrument/description instrument))
       [:p (:instrument/description instrument)])
     (into [:dl {:class "particulars"}]
           (for [[label value] (keep identity details)
                 :when (some? value)]
             (ui2/detail-item label value)))]))

(defn- question-node [step-key question-key coverage]
  (if (= step-key :confirm-go-private)
    [:i18n/tr question-key
     {:cost (or (ui2/money-format (:instrument.coverage/cost coverage) :EUR)
                "—")}]
    [:i18n/tr question-key]))

(defn- question-panel [req page-state coverage]
  (let [step-key              (:current-flow-key page-state)
        {:keys [question secondary answers]} (get flow/steps step-key)
        action-url            (d*/act req ::actions/transition)]
    [card/Card {:id         "insurance-survey-question"
                :appearance "filled-outlined"}
     [:form {:class          "wa-stack wa-gap-l"
             :data-on:submit (str "evt.preventDefault(); "
                                  "$insuranceSurvey.answer = evt.submitter.value; "
                                  "$loading = 'insurance-survey-transition'; "
                                  "@post('" action-url "')")}
      [:div {:class "wa-stack wa-gap-xs"}
       [:h2 {:class "wa-heading-l"}
        (question-node step-key question coverage)]
       (when secondary
         [:p {:class "wa-color-text-quiet"} [:i18n/tr secondary]])
       (when (= step-key :go-private)
         [:p {:class "wa-color-text-quiet"}
          [:i18n/tr :insurance/review-private-cost
           {:cost (or (ui2/money-format (:instrument.coverage/cost coverage) :EUR)
                      "—")}]])]
      (into
       [:div {:class "wa-cluster wa-gap-s"}]
       (for [{:keys [id label]} answers]
         [button/Button {:appearance "outlined"
                         :type       "submit"
                         :name       "answer"
                         :value      (name id)}
          [:i18n/tr label]]))]]))

(defn- progress [total-reports current-index]
  (step-circles/StepCircles
   {::step-circles/label [:i18n/tr :insurance/review-progress]
    ::step-circles/current-step current-index
    ::step-circles/steps
    (mapv (fn [number]
            {:label [:i18n/tr :insurance/review-item-step {:number number}]})
          (range 1 (inc total-reports)))}))

(defn- empty-content [req policy-id]
  [:div {:id "insurance-survey-empty"
         :class "wa-stack wa-gap-l"}
   (ui2/empty-state
    [:i18n/tr :insurance/review-no-items-title]
    [:i18n/tr :insurance/review-no-items-body])
   [:div {:class "wa-cluster wa-gap-s"}
    [button/Button
     {:appearance "filled"
      :variant    "brand"
      :href       (urls/link-coverage-create
                   policy-id
                   (urls/absolute-link-insurance-survey-start
                    (get-in req [:system :env]) policy-id))}
     [:i18n/tr :insurance/add-coverage]]
    [button/Button {:appearance "outlined"
                    :data-id    "insurance-survey-dismiss"
                    :data-action (d*/act req ::actions/dismiss-review)}
     [:i18n/tr :insurance/review-finish]]]])

(defn- closed-content []
  [:div {:id "insurance-survey-closed"}
   [:wa-callout {:appearance "outlined" :variant "warning"}
    [:div {:class "wa-stack wa-gap-xs"}
     [:strong [:i18n/tr :insurance/review-closed-title]]
     [:span [:i18n/tr :insurance/review-closed-body]]
     [:a {:href (urls/link-faq-insurance-team)}
      [:i18n/tr :insurance/review-contact-team]]]]])

(defn- complete-content [req policy-id]
  [:div {:id "insurance-survey-complete"
         :class "wa-stack wa-gap-l"}
   [:wa-callout {:appearance "outlined"
                 :variant    "success"
                 :role       "status"}
    [:div {:class "wa-stack wa-gap-xs"}
     [:strong [:i18n/tr :insurance/review-complete-title]]
     [:span [:i18n/tr :insurance/review-complete-body]]]]
   [:div {:class "wa-cluster wa-gap-s"}
    [button/Button {:appearance "filled"
                    :variant    "brand"
                    :href       (urls/link-coverage-create
                                 policy-id
                                 (urls/absolute-link-insurance-survey-start
                                  (get-in req [:system :env]) policy-id))}
     [:i18n/tr :insurance/add-coverage]]
    [button/Button {:appearance "outlined" :href "/"}
     [:i18n/tr :action/done]]]])

(defn- encouragement-content [req data]
  (let [completed (- (:total-reports data) (:total-todo data))]
    [:div {:id "insurance-survey-encouragement"
           :class "wa-stack wa-gap-l"}
     [:wa-callout {:appearance "outlined"
                   :variant    "success"
                   :role       "status"}
      [:div {:class "wa-stack wa-gap-xs"}
       [:strong [:i18n/tr :insurance/review-good-job]]
       [:span [:i18n/tr :insurance/review-completed-progress
               {:completed completed
                :remaining (:total-todo data)}]]]]
     [button/Button {:id          "insurance-survey-continue"
                     :appearance  "filled"
                     :variant     "brand"
                     :data-id     "insurance-survey-continue"
                     :data-action (d*/act req ::actions/continue-review)}
      [:i18n/tr :insurance/review-keep-going]]]))

(defn- edit-content [req data page-state]
  (let [coverage   (:insurance.survey.report/coverage (:active-report data))
        instrument (:instrument.coverage/instrument coverage)
        form-state (merge (coverage->edit coverage) (:edit page-state))]
    [:form {:id             "insurance-survey-edit-form"
            :class          "wa-stack wa-gap-xl"
            :data-id        "insurance-survey-edit"
            :data-action    (d*/act req ::actions/save-edit)
            :data-on:submit "evt.preventDefault();"}
     (ui2/section-card
      {:title    [:i18n/tr :insurance/review-correct-data-title]
       :subtitle [:i18n/tr :insurance/review-correct-data-body]
       :divider? true}
      [:div {:class "insurance-coverage-edit-form-grid"}
       (input-field form-state :instrument-name [:i18n/tr :instrument/name]
                    {:required true} [:i18n/tr :instrument/name-hint])
       (category-field (:db req) form-state)
       (input-field form-state :make [:i18n/tr :instrument/make]
                    {:required true} [:i18n/tr :instrument/make-hint])
       (input-field form-state :model [:i18n/tr :instrument/model]
                    {} [:i18n/tr :instrument/model-hint])
       (input-field form-state :serial-number [:i18n/tr :instrument/serial-number]
                    {} [:i18n/tr :instrument/if-available])
       (input-field form-state :build-year [:i18n/tr :instrument/build-year]
                    {} [:i18n/tr :instrument/if-available])
       (textarea-field form-state)])
     (upload/upload-section
      req instrument
      {:complete-label [:i18n/tr :insurance/upload-complete]
       :drop-label     [:i18n/tr :insurance/upload-drop-label]
       :empty-body     [:i18n/tr :insurance/no-photos]
       :empty-title    [:i18n/tr :insurance/photos]
       :error-label    [:i18n/tr :insurance/upload-error]
       :help-label     [:i18n/tr :insurance/upload-help]
       :input-id       "insurance-survey-photo-upload"
       :progress-label [:i18n/tr :insurance/upload-progress]
       :subtitle       [:i18n/tr :insurance/photo-upload-subtitle]
       :title          [:i18n/tr :insurance/photo-upload]})
     (ui2/section-card
      {:title    [:i18n/tr :insurance/instrument-coverage]
       :subtitle [:i18n/tr :insurance/review-coverage-body]
       :divider? true}
      [:div {:class "insurance-coverage-edit-form-grid"}
       (input-field form-state :item-count [:i18n/tr :insurance/item-count]
                    {:type "number" :min 1 :step 1 :required true}
                    [:i18n/tr :insurance/item-count-hint])
       (input-field form-state :value [:i18n/tr :insurance/value]
                    {:type "number" :min 1 :step 1 :required true}
                    [:i18n/tr :insurance/value-hint])
       (input-field form-state :insurer-id [:i18n/tr :insurance/insurer-id]
                    {} [:i18n/tr :insurance/insurer-id-hint])
       (coverage-types-field form-state coverage (:policy data))])
     (when-let [top-error (form/field-error form-state :_top)]
       [:wa-callout {:appearance "outlined" :variant "danger" :role "alert"}
        top-error])
     [:div {:class "wa-cluster wa-gap-s"}
      [button/Button {:appearance         "filled"
                      :variant            "brand"
                      :type               "submit"
                      :data-attr:disabled "!!$loading && $loading !== 'insurance-survey-edit'"
                      :data-attr:loading  "$loading === 'insurance-survey-edit'"}
       [:i18n/tr :action/save]]]]))

(defn- active-content [req data page-state]
  (let [coverage (:insurance.survey.report/coverage (:active-report data))]
    [:div {:class "wa-stack wa-gap-xl"}
     (when-let [error (:error page-state)]
       [:wa-callout {:appearance "outlined" :variant "danger" :role "alert"}
        error])
     (if (= :encouragement (:mode page-state))
       (encouragement-content req data)
       (list
        (progress (:total-reports data) (:current-index data))
        (if (= :edit (:mode page-state))
          (edit-content req data page-state)
          (list
           (question-panel req page-state coverage)
           (instrument-card coverage (:decisions page-state))))))]))

(defn page [{:keys [db policy] :as req}]
  (let [policy-id         (http.util/path-param-uuid! req :policy-id)
        policy            (or policy (q/retrieve-policy db policy-id))
        current-member-id (get-in req [:session :session/member :member/member-id])
        data              (queries/survey-data db policy-id current-member-id)
        page-state        (actions/page-state req data)
        edit              (merge (some-> data :active-report
                                         :insurance.survey.report/coverage
                                         coverage->edit)
                                 (:edit page-state))]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :standard
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
           [:i18n/tr :insurance/title]]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
           (:insurance.policy/name policy)]
          [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/coverage-review]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy policy)
                             :label (:insurance.policy/name policy)}]
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class              "insurance-coverage-edit-page wa-stack wa-gap-2xl"
             :data-preserve-attr "data-signals"
             :data-signals       (d*/->signals
                                  {actions/signal-key
                                   {:policyId (str policy-id)
                                    :answer   ""
                                    :edit     (edit->signals edit)}})}
       [page-header/PageHeader
        {:title    [:i18n/tr :insurance/coverage-review]
         :subtitle (:insurance.policy/name policy)}]
       (case (:status data)
         :closed      (closed-content)
         :complete    (complete-content req policy-id)
         :empty       (empty-content req policy-id)
         :unavailable (ui2/empty-state
                       [:i18n/tr :insurance/review-not-available-title]
                       [:i18n/tr :insurance/review-not-available])
         (active-content req data page-state))
       (upload/upload-script)]])))

(d*/refresh-all!)
