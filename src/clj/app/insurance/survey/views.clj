(ns app.insurance.survey.views
  (:require
   [app.config :as config]
   [app.datastar :as d*]
   [app.form :as form]
   [app.html :as html]
   [app.insurance.coverage.upload :as upload]
   [app.insurance.survey.actions :as actions]
   [app.insurance.survey.flow :as flow]
   [app.insurance.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
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

(def ^:private category-tints
  ["var(--wa-color-blue-50)"
   "var(--wa-color-cyan-50)"
   "var(--wa-color-green-50)"
   "var(--wa-color-indigo-50)"
   "var(--wa-color-orange-50)"
   "var(--wa-color-pink-50)"
   "var(--wa-color-purple-50)"
   "var(--wa-color-yellow-50)"])

(defn- coverage-category-tint [coverage]
  (let [category (get-in coverage [:instrument.coverage/instrument
                                   :instrument/category])
        category-key (or (:instrument.category/category-id category)
                         (:instrument.category/code category)
                         (:instrument.category/name category)
                         :uncategorized)]
    (nth category-tints (mod (hash category-key) (count category-tints)))))

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
            (queries/instrument-categories db)]
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

(defn- coverage-type-icons
  [coverage-id coverage-types]
  (when-let [tokens (->> coverage-types
                         (map-indexed
                          (fn [index coverage-type]
                            (insurance-ui/coverage-type-token
                             "insurance-survey-coverage-type"
                             coverage-id
                             index
                             (:insurance.coverage.type/name coverage-type))))
                         (mapcat identity)
                         seq)]
    (into [:span {:class "insurance-survey-card-coverage-types wa-cluster wa-gap-2xs"}]
          tokens)))

(defn- card-detail-item
  [label value attrs]
  (into (cond-> [:div]
          attrs (conj attrs))
        [[:dt {:class "trim-none"} label]
         [:dd {:class "trim-none"} (ui2/muted value)]]))

(defn- instrument-card [req coverage decisions current?]
  (let [{:instrument.coverage/keys [cost coverage-id instrument item-count private? types value]}
        coverage
        private? (cond
                   (some #{:confirm-not-band} decisions) true
                   (some #{:confirm-band} decisions) false
                   :else private?)
        category-name (get-in instrument
                              [:instrument/category :instrument.category/name])
        photo         (first (queries/image-uris req instrument))
        details  [[[:i18n/tr :instrument/make] (:instrument/make instrument)]
                  [[:i18n/tr :instrument/model] (:instrument/model instrument)]
                  (when private?
                    [[:i18n/tr :insurance/annual-cost]
                     (if cost
                       (ui2/money cost :EUR)
                       [:i18n/tr :insurance/cost-unavailable])])
                  [[:i18n/tr :insurance/value] (ui2/money value :EUR)]
                  [[:i18n/tr :insurance/item-count] (or item-count 1)]
                  (when (seq types)
                    [[:i18n/tr :insurance/coverage-types]
                     (coverage-type-icons coverage-id types)])
                  [[:i18n/tr :instrument/serial-number] (:instrument/serial-number instrument)]
                  [[:i18n/tr :instrument/build-year] (:instrument/build-year instrument)]
                  (when-not (str/blank? (:instrument/description instrument))
                    [[:i18n/tr :instrument/description]
                     (:instrument/description instrument)
                     {:class "wa-span-grid"}])]]
    [card/Card (cond-> {:class      "insurance-survey-instrument-card"
                        :appearance "outlined"
                        :style      {"--insurance-survey-category-tint"
                                     (coverage-category-tint coverage)}}
                 current? (assoc :id "insurance-survey-instrument"))
     [:figure {:slot  "media"
               :class "insurance-survey-card-media"}
      (when photo
        [:img {:src     (:thumbnail photo)
               :alt     (:instrument/name instrument)
               :loading (if current? "eager" "lazy")
               :decoding "async"
               :onerror  "this.hidden = true; this.nextElementSibling.hidden = false;"}])
      [:div (cond-> {:class "insurance-survey-card-image-fallback"}
              photo (assoc :hidden true))
       [ico/Icon {::ico/name :music-note-outline}]]
      (when-not (str/blank? category-name)
        [:figcaption category-name])]
     [:div {:slot  "header"
            :class "insurance-survey-card-heading wa-stack wa-gap-3xs"}
      [:h2 {:class "wa-heading-m trim-none"} (:instrument/name instrument)]
      [:span {:class "wa-caption-s wa-color-text-quiet"}
       [:i18n/tr (if private?
                   :insurance/ownership-private
                   :insurance/ownership-band)]]]
     (into [:dl {:class      "insurance-survey-card-facts"
                 :tabindex   0
                 :aria-label [:i18n/tr :insurance/review-card-details]}]
           (for [[label value attrs] (keep identity details)
                 :when (some? value)]
             (card-detail-item label value attrs)))]))

(defn- milestone [data]
  [:aside {:id        "insurance-survey-milestone"
           :class     "insurance-survey-milestone wa-cluster wa-gap-xs"
           :role      "status"
           :aria-live "polite"}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :star}]
   [:span {:class "wa-stack wa-gap-3xs"}
    [:strong [:i18n/tr :insurance/review-good-job]]
    [:span [:i18n/tr :insurance/review-milestone
            {:remaining (:total-todo data)}]]]])

#_(defn- animation-lab [data]
    [:aside {:id                                  "insurance-survey-animation-lab"
             :class                               "insurance-survey-animation-lab wa-stack wa-gap-xs"
             :data-insurance-survey-animation-lab true}
     [:strong {:class "wa-caption-s wa-color-text-quiet"}
      [:i18n/tr :insurance/review-animation-lab]]
     [:div {:class "wa-cluster wa-gap-xs"}
      [button/Button {:appearance                "filled"
                      :size                      "small"
                      :type                      "button"
                      :data-animation-lab-action "sequence"}
       [:i18n/tr :insurance/review-animation-sequence]]
      [button/Button {:appearance                "outlined"
                      :size                      "small"
                      :type                      "button"
                      :data-animation-lab-action "throw"}
       [:i18n/tr :insurance/review-animation-throw]]
      [button/Button {:appearance                "outlined"
                      :size                      "small"
                      :type                      "button"
                      :data-animation-lab-action "advance"}
       [:i18n/tr :insurance/review-animation-advance]]
      [button/Button {:appearance                "outlined"
                      :size                      "small"
                      :type                      "button"
                      :data-animation-lab-action "question"}
       [:i18n/tr :insurance/review-animation-question]]
      [button/Button {:appearance                "outlined"
                      :size                      "small"
                      :type                      "button"
                      :data-animation-lab-action "encouragement"}
       [:i18n/tr :insurance/review-animation-encouragement]]
      [button/Button {:appearance                "plain"
                      :size                      "small"
                      :type                      "button"
                      :data-animation-lab-action "reset"}
       [:i18n/tr :insurance/review-animation-reset]]]
     [:template {:data-animation-lab-milestone true}
      (milestone data)]])

(defn- instrument-deck [req data page-state]
  (let [additional-reports (rest (:todo-reports data))
        deck-count         (count additional-reports)]
    (into
     [:div {:id    "insurance-survey-card-deck"
            :class "insurance-survey-card-deck"
            :style {"--deck-count" deck-count}}]
     (concat
      (for [[idx report] (map-indexed vector additional-reports)
            :let [depth         (inc idx)
                  visual-depth (min depth 6)
                  next-depth   (dec visual-depth)
                  coverage     (:insurance.survey.report/coverage report)
                  report-id    (:insurance.survey.report/report-id report)]]
        [:div {:class       "insurance-survey-card-layer"
               :aria-hidden true
               :inert       true
               :style       {"--deck-block-offset" (str (* visual-depth -0.4) "rem")
                             "--deck-next-block-offset" (str (* next-depth -0.4) "rem")
                             "--deck-layer" (- 100 depth)
                             "--deck-next-scale" (- 1.0 (* next-depth 0.03))
                             "--deck-scale" (- 1.0 (* visual-depth 0.03))
                             "--insurance-survey-category-tint"
                             (coverage-category-tint coverage)
                             "view-transition-name"
                             (str "insurance-survey-layer-" report-id)}}
         (instrument-card req coverage [] false)])
      (cond->
       [[:div {:id    "insurance-survey-card-current"
               :class "insurance-survey-card-current"}
         (instrument-card
          req
          (:insurance.survey.report/coverage (:active-report data))
          (:decisions page-state)
          true)]]
        (:milestone? page-state) (conj (milestone data)))))))

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
    [:section {:id              "insurance-survey-question"
               :class           "insurance-survey-question"
               :aria-labelledby "insurance-survey-question-title"}
     [:form {:class          "wa-stack wa-gap-s"
             :data-id        "insurance-survey-question"
             :data-attr:aria-busy "$loading === 'insurance-survey-transition' ? 'true' : 'false'"
             :data-on:submit (str "evt.preventDefault(); "
                                  "$insuranceSurvey.answer = evt.submitter.value; "
                                  "$insuranceSurvey.transitionKind = evt.submitter.dataset.transitionKind; "
                                  "$loading = 'insurance-survey-transition'; "
                                  "const submit = () => @post('" action-url "'); "
                                  "if (window.InsuranceSurveyMotion) { "
                                  "window.InsuranceSurveyMotion.submit($insuranceSurvey.transitionKind, submit); "
                                  "} else { submit(); }")}
      [:div {:class       "wa-stack wa-gap-2xs"
             :aria-live   "polite"
             :aria-atomic "true"}
       [:h2 {:id    "insurance-survey-question-title"
             :class "wa-heading-m"}
        (question-node step-key question coverage)]
       (when secondary
         [:p {:class "wa-color-text-quiet"} [:i18n/tr secondary]])
       (when (= step-key :go-private)
         [:p {:class "wa-color-text-quiet"}
          [:i18n/tr :insurance/review-private-cost
           {:cost (or (ui2/money-format (:instrument.coverage/cost coverage) :EUR)
                      "—")}]])]
      (into
       [:div {:class "wa-cluster wa-gap-xs"}]
       (for [{:keys [id label next]} answers]
         [button/Button {:appearance "outlined"
                         :type       "submit"
                         :name       "answer"
                         :value      (name id)
                         :data-transition-kind (if (= :complete next)
                                                 "item"
                                                 "question")
                         :data-attr:disabled "$loading === 'insurance-survey-transition'"}
          [:i18n/tr label]]))]]))

(defn- progress [total-reports current-index answered-count]
  (let [answered-count       (or answered-count 0)
        completed-items      (dec current-index)
        ;; Opening the survey earns first-question progress. Further branching
        ;; questions approach the end of this item's segment, and completing
        ;; the item fills the remainder.
        current-item-progress (if (pos? answered-count)
                                (/ (double answered-count)
                                   (inc answered-count))
                                0.25)
        value                (* 100.0
                                (/ (+ completed-items current-item-progress)
                                   total-reports))
        next-value           (* 100.0 (/ current-index total-reports))]
    [:wa-progress-bar
     {:id              "insurance-survey-progress"
      :class           "insurance-survey-progress"
      :label           [:i18n/tr :insurance/review-progress]
      :style           {"--insurance-survey-progress-value" (str value "%")}
      :value           value
      :data-next-value next-value}]))

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

(defn- complete-content [req data]
  (let [policy-id   (get-in data [:policy :insurance.policy/policy-id])
        response-id (get-in data [:response :insurance.survey.response/response-id])]
    [:div {:id                                  "insurance-survey-complete"
           :class                               "insurance-survey-completion wa-stack wa-gap-m"
           :data-insurance-survey-celebration   true
           :data-celebration-key                (str "insurance-survey-celebration-" response-id)
           :data-celebrate-automatically        "true"}
     [:div {:class "intro wa-stack wa-gap-xs"}
      [ico/Icon {::ico/name :shield-check-outline}]
      [:h1 {:class "wa-heading-l"}
       [:i18n/tr :insurance/review-complete-title]]
      [:p {:class "wa-color-text-quiet"}
       [:i18n/tr :insurance/review-complete-body]]]
     [button/Button {:id         "insurance-survey-celebrate"
                     :appearance "filled"
                     :variant    "brand"
                     :size       "large"
                     :data-celebration-button true}
      [ico/Icon {::ico/library :phosphor
                 ::ico/name    :star
                 :slot         "start"}]
      [:i18n/tr :insurance/review-celebrate]]
     [:p {:class                   "stage"
          :data-celebration-stage "1"
          :hidden                  true}
      [:i18n/tr :insurance/review-celebrate-again]]
     [:p {:class                   "stage"
          :data-celebration-stage "2"
          :hidden                  true}
      [:i18n/tr :insurance/review-celebrate-feels-good]]
     [:div {:class                   "stage final wa-stack wa-gap-m"
            :data-celebration-stage "3"
            :hidden                  true}
      [:p {:class "thanks wa-cluster wa-gap-2xs"}
       [:i18n/tr :insurance/review-celebrate-thanks]
       [ico/Icon {::ico/name  :smile
                  :class     "thanks-icon"}]]
      [:div {:class "wa-cluster wa-gap-s"}
       [button/Button {:appearance "filled"
                       :variant    "brand"
                       :href       (urls/link-coverage-create
                                    policy-id
                                    (urls/absolute-link-insurance-survey-start
                                     (get-in req [:system :env]) policy-id))}
        [:i18n/tr :insurance/add-coverage]]
       [button/Button {:appearance "outlined" :href "/"}
        [:i18n/tr :action/done]]]]]))

(defn- edit-content [req data page-state]
  (let [coverage   (:insurance.survey.report/coverage (:active-report data))
        instrument (:instrument.coverage/instrument coverage)
        form-state (merge (coverage->edit coverage) (:edit page-state))]
    [:form {:id             "insurance-survey-edit-form"
            :class          "wa-stack wa-gap-xl"
            :data-id        "insurance-survey-edit"
            :data-action    (d*/act req ::actions/save-edit)
            :data-on:submit (str "evt.preventDefault(); "
                                 "$insuranceSurvey.transitionKind = 'item';")}
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
  (let [coverage        (:insurance.survey.report/coverage (:active-report data))
        transition-kind (name (or (:transition-kind page-state) :none))]
    [:div {:class                          "insurance-survey-workflow wa-stack wa-gap-s"
           :data-transition-kind           transition-kind
           :data-attr:data-transition-kind "$insuranceSurvey.transitionKind"}
     (when-let [error (:error page-state)]
       [:wa-callout {:appearance "outlined" :variant "danger" :role "alert"}
        error])
     (progress (:total-reports data)
               (:current-index data)
               (:answered-count page-state))
     (if (= :edit (:mode page-state))
       (edit-content req data page-state)
       [:div {:class "insurance-survey-stage wa-stack wa-gap-s"}
        (instrument-deck req data page-state)
        (question-panel req page-state coverage)
        #_(when (:dev? req)
            (animation-lab data))])]))

(defn page [{:keys [db] :as req}]
  (let [policy-id         (http.util/path-param-uuid! req :policy-id)
        dev?              (or (:dev? req)
                              (config/dev-mode? (-> req :system :env)))
        req               (assoc req :dev? dev?)
        current-member-id (get-in req [:session :session/member :member/member-id])
        data              (queries/survey-data db policy-id current-member-id)
        page-state        (actions/page-state req data)
        transition-kind   (name (or (:transition-kind page-state) :none))
        edit              (merge (some-> data :active-report
                                         :insurance.survey.report/coverage
                                         coverage->edit)
                                 (:edit page-state))]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :compact
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
           [:i18n/tr :home]]
          [breadcrumb/BreadcrumbItem
           [:i18n/tr :insurance/review-title]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-dashboard)
                             :label [:i18n/tr :home]}]
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class              "insurance-survey-page wa-stack wa-gap-l"
             :data-preserve-attr "data-signals"
             :data-signals       (d*/->signals
                                  {actions/signal-key
                                   {:policyId (str policy-id)
                                    :answer   ""
                                    :transitionKind transition-kind
                                    :edit     (edit->signals edit)}})}
       (case (:status data)
         :closed      (closed-content)
         :complete    (complete-content req data)
         :empty       (empty-content req policy-id)
         :unavailable (ui2/empty-state
                       [:i18n/tr :insurance/review-not-available-title]
                       [:i18n/tr :insurance/review-not-available])
         (active-content req data page-state))
       (upload/upload-script)
       (html/script "/js/insurance-survey-celebration.js" :type "module")
       (html/script "/js/insurance-survey-motion.js" :type "module")
       #_(when dev?
           (html/script "/js/insurance-survey-animation-lab.js" :type "module"))]])))

(d*/refresh-all!)
