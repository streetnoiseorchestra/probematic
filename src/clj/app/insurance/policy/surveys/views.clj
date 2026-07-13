(ns app.insurance.policy.surveys.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.policy.surveys.actions :as actions]
   [app.insurance.policy.surveys.queries :as queries]
   [app.insurance.ui :as insurance.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [tick.core :as t]))

(def reminders-dialog-id "insurance-survey-reminders-dialog")
(def close-dialog-id "insurance-survey-close-dialog")

(defn- date-time-input-value
  [value]
  (when value
    (subs (str (t/truncate (t/date-time value) :minutes)) 0 16)))

(defn- default-closes-at []
  (date-time-input-value
   (t/>> (t/date-time) (t/new-period 4 :weeks))))

(defn- form-state
  [{:keys [page-state]} policy active-survey]
  (merge
   {:policy-id (:insurance.policy/policy-id policy)
    :survey-id (:insurance.survey/survey-id active-survey)
    :name      (or (:insurance.survey/survey-name active-survey)
                   (:insurance.policy/name policy))
    :closes-at (or (date-time-input-value
                    (:insurance.survey/closes-at active-survey))
                   (default-closes-at))}
   (get-in page-state [actions/form-key :form])))

(defn- result-callout
  [result]
  (when-let [status (:status result)]
    [:wa-callout {:appearance "outlined"
                  :variant    (if (= :error status) "danger" "success")
                  :role       (if (= :error status) "alert" "status")}
     (case status
       :created [:i18n/tr :insurance/survey-created]
       :updated [:i18n/tr :insurance/survey-updated]
       :closed  [:i18n/tr :insurance/survey-closed]
       :sent    [:i18n/tr :insurance/survey-reminders-sent
                 {:count (:count-sent result)}]
       :empty   [:i18n/tr :insurance/survey-reminders-empty]
       :error   (:message result)
       nil)]))

(defn- survey-form
  [req active-survey form-state]
  (let [name-error      (form/field-error form-state :name)
        closes-at-error (form/field-error form-state :closes-at)
        top-error       (form/field-error form-state :_top)]
    (ui2/section-card
     {:title    [:i18n/tr :insurance/survey-details-title]
      :subtitle [:i18n/tr :insurance/survey-details-subtitle]
      :divider? true}
     [:form {:id             "insurance-survey-admin-form"
             :class          "wa-stack wa-gap-l"
             :data-id        "insurance-survey-admin"
             :data-action    (d*/act req ::actions/save-survey)
             :data-on:submit "evt.preventDefault();"
             :data-signals:insurance-survey-admin__ifmissing
             (d*/->signals
              {:policyId (str (:policy-id form-state))
               :surveyId (some-> (:survey-id form-state) str)
               :name     (:name form-state)
               :closesAt (:closes-at form-state)})}
      (when-not active-survey
        (ui2/empty-state
         [:i18n/tr :insurance/survey-no-open-title]
         [:i18n/tr :insurance/survey-no-open-body]))
      [:div {:class "wa-grid"
             :style "--min-column-size: 18rem;"}
       [:label {:class "wa-stack wa-gap-2xs"
                :for   "insurance-survey-name"}
        [:span [:i18n/tr :insurance/survey-name]]
        [:input {:id               "insurance-survey-name"
                 :name             "name"
                 :required         true
                 :value            (form/text-value (:name form-state))
                 :data-bind        "insuranceSurveyAdmin.name"
                 :aria-invalid     (when name-error "true")
                 :aria-describedby "insurance-survey-name-hint"}]
        [:small {:id    "insurance-survey-name-hint"
                 :class (if name-error
                          "text-danger"
                          "wa-color-text-quiet")}
         (or name-error [:i18n/tr :insurance/survey-name-hint])]]
       [:label {:class "wa-stack wa-gap-2xs"
                :for   "insurance-survey-closes-at"}
        [:span [:i18n/tr :insurance/survey-closes-at]]
        [:input {:id               "insurance-survey-closes-at"
                 :name             "closes-at"
                 :type             "datetime-local"
                 :required         true
                 :value            (form/text-value (:closes-at form-state))
                 :data-bind        "insuranceSurveyAdmin.closesAt"
                 :aria-invalid     (when closes-at-error "true")
                 :aria-describedby "insurance-survey-closes-at-hint"}]
        [:small {:id    "insurance-survey-closes-at-hint"
                 :class (if closes-at-error
                          "text-danger"
                          "wa-color-text-quiet")}
         (or closes-at-error [:i18n/tr :insurance/survey-closes-at-hint])]]]
      (when top-error
        [:wa-callout {:appearance "outlined"
                      :variant    "danger"
                      :role       "alert"}
         top-error])])))

(defn- response-status
  [completed?]
  [:wa-badge {:appearance "outlined"
              :variant    (if completed? "success" "neutral")
              :pill       true}
   [:i18n/tr (if completed?
               :insurance/survey-complete
               :insurance/survey-incomplete)]])

(defn- response-item
  [req {:keys [completed-count completed? member response-id total-count]}]
  (let [loading-id (str response-id)]
    [:li {:id (str "insurance-survey-response-" response-id)}
     [card/Card {:appearance "outlined"
                 :style      "--spacing: var(--wa-space-m);"}
      [:div {:class "wa-flank:end wa-gap-m wa-align-items-center"}
       [:div {:class "wa-stack wa-gap-2xs"
              :style "min-inline-size: 0;"}
        (insurance.ui/member-link member)
        [:div {:class "wa-cluster wa-gap-xs"}
         [:span {:class "wa-caption-s wa-color-text-quiet"}
          [:i18n/tr :insurance/survey-progress
           {:completed completed-count :total total-count}]]
         (response-status completed?)]]
       [button/Button {:appearance         "plain"
                       :data-id            loading-id
                       :data-action        (d*/act req ::actions/toggle-response)
                       :data-attr:disabled (str "!!$loading && $loading !== '"
                                                loading-id "'")
                       :data-attr:loading  (str "$loading === '" loading-id "'")}
        [:i18n/tr (if completed?
                    :insurance/survey-mark-incomplete
                    :insurance/survey-mark-complete)]]]]]))

(defn- responses-section
  [req response-rows]
  (ui2/section-card
   {:title    [:i18n/tr :insurance/survey-responses-title]
    :subtitle [:i18n/tr :insurance/survey-responses-subtitle]
    :divider? true}
   (if (seq response-rows)
     (into
      [:ul {:class "wa-stack wa-gap-xs"
            :style "list-style: none; margin: 0; padding: 0;"}]
      (map #(response-item req %) response-rows))
     (ui2/empty-state
      [:i18n/tr :insurance/survey-responses-title]
      [:i18n/tr :insurance/survey-no-responses]))))

(defn- active-actions
  [response-rows]
  [:div {:class "wa-cluster wa-gap-s"}
   [button/Button {:appearance  "outlined"
                   :variant     "brand"
                   :disabled    (not-any? (complement :completed?) response-rows)
                   :data-dialog (str "open " reminders-dialog-id)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :envelope
               :slot         "start"}]
    [:i18n/tr :insurance/survey-send-reminders]]
   [button/Button {:appearance  "outlined"
                   :variant     "danger"
                   :data-dialog (str "open " close-dialog-id)}
    [:i18n/tr :insurance/survey-close]]])

(defn- closed-surveys-section
  [req closed-surveys]
  (when (seq closed-surveys)
    (ui2/section-card
     {:title    [:i18n/tr :insurance/survey-closed-title]
      :subtitle [:i18n/tr :insurance/survey-closed-subtitle]
      :divider? true}
     (into
      [:ul {:class "wa-stack wa-gap-xs"
            :style "list-style: none; margin: 0; padding: 0;"}]
      (for [{:insurance.survey/keys [closed-at survey-name survey-id]}
            closed-surveys]
        [:li {:id    (str "insurance-survey-closed-" survey-id)
              :class "wa-split wa-gap-m"}
         [:strong survey-name]
         [:span {:class "wa-color-text-quiet"}
          [:i18n/tr :insurance/survey-closed-on
           {:date (ui2/format-date-time req :medium closed-at)}]]])))))

(defn- survey-dialogs
  [req active-survey]
  (when active-survey
    (let [survey-id (str (:insurance.survey/survey-id active-survey))]
      (list
       [:wa-dialog {:id                 reminders-dialog-id
                    :label              [:i18n/tr :insurance/survey-send-reminders-title]
                    :data-preserve-attr "open"}
        [:p [:i18n/tr :insurance/survey-send-reminders-body]]
        [button/Button {:slot        "footer"
                        :appearance  "outlined"
                        :data-dialog "close"}
         [:i18n/tr :action/cancel]]
        [button/Button {:slot        "footer"
                        :appearance  "filled"
                        :variant     "brand"
                        :data-dialog "close"
                        :data-id     survey-id
                        :data-attr:disabled
                        (str "!!$loading && $loading !== '" survey-id "'")
                        :data-attr:loading
                        (str "$loading === '" survey-id "'")
                        :data-action (d*/act req ::actions/send-reminders)}
         [:i18n/tr :insurance/survey-reminders-confirm]]]
       [:wa-dialog {:id                 close-dialog-id
                    :label              [:i18n/tr :insurance/survey-close-title]
                    :data-preserve-attr "open"}
        [:p [:i18n/tr :insurance/survey-close-body]]
        [button/Button {:slot        "footer"
                        :appearance  "outlined"
                        :data-dialog "close"}
         [:i18n/tr :action/cancel]]
        [button/Button {:slot        "footer"
                        :appearance  "filled"
                        :variant     "danger"
                        :data-dialog "close"
                        :data-id     survey-id
                        :data-attr:disabled
                        (str "!!$loading && $loading !== '" survey-id "'")
                        :data-attr:loading
                        (str "$loading === '" survey-id "'")
                        :data-action (d*/act req ::actions/close-survey)}
         [:i18n/tr :insurance/survey-close-confirm]]]))))

(defn page
  [req]
  (let [policy-id         (http.util/path-param-uuid! req :policy-id)
        current-member-id (get-in req [:session :session/member :member/member-id])
        {:keys [active-survey authorized? closed-surveys policy response-rows]}
        (queries/policy-surveys (:db req) policy-id current-member-id)
        form-state         (form-state req policy active-survey)
        result             (get-in req [:page-state actions/form-key :result])
        toolbar-action     (when authorized?
                             [button/Button
                              {:appearance         "filled"
                               :variant            "brand"
                               :type               "submit"
                               :form               "insurance-survey-admin-form"
                               :data-attr:disabled "$loading === 'insurance-survey-admin'"
                               :data-attr:loading  "$loading === 'insurance-survey-admin'"}
                              [ico/Icon {::ico/library :phosphor
                                         ::ico/name    :clipboard-text
                                         :slot         "start"}]
                              [:i18n/tr (if active-survey
                                          :action/save
                                          :insurance/start-survey)]])]
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
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
           (:insurance.policy/name policy)]
          [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/manage-surveys]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy policy)
                             :label (:insurance.policy/name policy)}]
         ::page-toolbar/actions (when toolbar-action [toolbar-action])
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class "wa-stack wa-gap-xl"}
       [page-header/PageHeader
        {:title    [:i18n/tr :insurance/survey-admin-title]
         :subtitle [:i18n/tr :insurance/survey-admin-subtitle]}]
       (result-callout result)
       (if authorized?
         (list
          (survey-form req active-survey form-state)
          (when active-survey
            (active-actions response-rows))
          (when active-survey
            (responses-section req response-rows))
          (closed-surveys-section req closed-surveys))
         [:wa-callout {:appearance "outlined"
                       :variant    "warning"}
          [:i18n/tr :insurance/survey-error-not-allowed]])]
      (when authorized?
        (survey-dialogs req active-survey))])))

(d*/refresh-all!)
