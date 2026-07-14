(ns app.insurance.policy.surveys.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.policy.surveys.actions :as actions]
   [app.insurance.queries :as queries]
   [app.insurance.ui :as insurance.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
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
       :closed  [:i18n/tr :insurance/survey-closed]
       :sent    [:i18n/tr :insurance/survey-reminders-sent
                 {:count (:count-sent result)}]
       :empty   [:i18n/tr :insurance/survey-reminders-empty]
       :error   (:message result)
       nil)]))

(defn- survey-form
  [req active-survey form-state]
  (let [closes-at-error (form/field-error form-state :closes-at)
        top-error       (form/field-error form-state :_top)
        survey-id       (some-> (:survey-id form-state) str)
        live-save       (when active-survey
                          (str "$insuranceSurveyAdmin.saveStatus = 'saving'; "
                               "$loading = '" survey-id "'; "
                               "$targetid = '" survey-id "'; "
                               "@post('"
                               (d*/act req ::actions/update-closes-at)
                               "')"))]
    (ui2/section-card
     {:title    [:i18n/tr :insurance/survey-details-title]
      :subtitle [:i18n/tr :insurance/survey-details-subtitle]
      :divider? true}
     [:form (cond-> {:id             "insurance-survey-admin-form"
                     :class          "wa-stack wa-gap-l"
                     :data-id        "insurance-survey-admin"
                     :data-on:submit "evt.preventDefault();"
                     :data-signals:insurance-survey-admin__ifmissing
                     (d*/->signals
                      {:policyId      (str (:policy-id form-state))
                       :surveyId      survey-id
                       :closesAt      (:closes-at form-state)
                       :responseFilter "all"
                       :saveStatus    "idle"})}
              (not active-survey)
              (assoc :data-action (d*/act req ::actions/start-survey)))
      (when-not active-survey
        (ui2/empty-state
         [:i18n/tr :insurance/survey-no-open-title]
         [:i18n/tr :insurance/survey-no-open-body]))
      [:label {:class "wa-stack wa-gap-2xs"
               :for   "insurance-survey-closes-at"}
       [:span [:i18n/tr :insurance/survey-closes-at]]
       [:input (cond-> {:id               "insurance-survey-closes-at"
                        :name             "closes-at"
                        :type             "datetime-local"
                        :required         true
                        :value            (form/text-value (:closes-at form-state))
                        :data-bind        "insuranceSurveyAdmin.closesAt"
                        :aria-invalid     (when closes-at-error "true")
                        :aria-describedby (str "insurance-survey-closes-at-hint"
                                               (when active-survey
                                                 " insurance-survey-closes-at-status"))}
                 live-save (assoc :data-on:change live-save))]
       [:span {:class "wa-cluster wa-gap-xs wa-align-items-baseline"}
        [:small {:id    "insurance-survey-closes-at-hint"
                 :class (if closes-at-error
                          "text-danger"
                          "wa-color-text-quiet")}
         (or closes-at-error [:i18n/tr :insurance/survey-closes-at-hint])]
        (when active-survey
          [:small {:id        "insurance-survey-closes-at-status"
                   :class     "insurance-survey-save-status wa-color-text-quiet"
                   :aria-live "polite"}
           [:span {:data-show "$insuranceSurveyAdmin.saveStatus === 'saving'"
                   :class     "saving"
                   :style     "display: none;"}
            [:i18n/tr :insurance/survey-closes-at-saving]]
           [:span {:data-show "$insuranceSurveyAdmin.saveStatus === 'saved'"
                   :data-on:animationend "$insuranceSurveyAdmin.saveStatus = 'idle'"
                   :class     "saved"
                   :style     "display: none;"}
            [:i18n/tr :insurance/survey-closes-at-saved]]
           [:span {:data-show "$insuranceSurveyAdmin.saveStatus === 'error'"
                   :style     "display: none;"}
            [:i18n/tr :insurance/survey-closes-at-save-failed]]])]]
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

(defn- response-row
  [req {:keys [completed-count completed? member response-id total-count]}]
  (let [loading-id   (str response-id)
        action-key   (if completed?
                       :insurance/survey-mark-incomplete
                       :insurance/survey-mark-complete)
        action-label [:i18n/tr action-key]]
    [:tr {:id        (str "insurance-survey-response-" response-id)
          :data-show (str "$insuranceSurveyAdmin.responseFilter === 'all' || "
                          "$insuranceSurveyAdmin.responseFilter === '"
                          (if completed? "completed" "incomplete") "'")}
     [:th {:scope "row"}
      [:div {:class "wa-stack wa-gap-2xs"}
       (insurance.ui/member-link member)
       [:div {:class "wa-cluster wa-gap-xs"}
        [:span {:class "wa-caption-s wa-color-text-quiet"}
         [:i18n/tr :insurance/survey-progress
          {:completed completed-count :total total-count}]]
        (response-status completed?)]]]
     [:td {:class "action wa-text-end"}
      [button/Button {:appearance         "plain"
                      :size               "s"
                      :variant            "brand"
                      :aria-label         action-label
                      :title              action-label
                      :data-id            loading-id
                      :data-action        (d*/act req ::actions/toggle-response)
                      :data-attr:disabled (str "!!$loading && $loading !== '"
                                               loading-id "'")
                      :data-attr:loading  (str "$loading === '" loading-id "'")}
       [ico/Icon (if completed?
                   {::ico/library :snoico
                    ::ico/name    :xmark
                    :slot         "start"}
                   {::ico/library :phosphor
                    ::ico/name    :check
                    :slot         "start"})]
       [:span {:class "label"} action-label]]]]))

(defn- responses-section
  [req response-rows]
  (let [completed-count  (count (filter :completed? response-rows))
        incomplete-count (- (count response-rows) completed-count)
        filters          [{:key   :insurance/survey-filter-all
                           :value "all"
                           :count (count response-rows)}
                          {:key   :insurance/survey-filter-incomplete
                           :value "incomplete"
                           :count incomplete-count}
                          {:key   :insurance/survey-filter-completed
                           :value "completed"
                           :count completed-count}]
        empty-rows       (keep
                          identity
                          [(when (empty? response-rows)
                             [:tr {:id        "insurance-survey-filter-empty-all"
                                   :data-show "$insuranceSurveyAdmin.responseFilter === 'all'"
                                   :style     "display: none;"}
                              [:td {:colspan 2
                                    :class   "wa-color-text-quiet wa-text-center"}
                               [:i18n/tr :insurance/survey-filter-empty-all]]])
                           (when (zero? incomplete-count)
                             [:tr {:id        "insurance-survey-filter-empty-incomplete"
                                   :data-show "$insuranceSurveyAdmin.responseFilter === 'incomplete'"
                                   :style     "display: none;"}
                              [:td {:colspan 2
                                    :class   "wa-color-text-quiet wa-text-center"}
                               [:i18n/tr :insurance/survey-filter-empty-incomplete]]])
                           (when (zero? completed-count)
                             [:tr {:id        "insurance-survey-filter-empty-completed"
                                   :data-show "$insuranceSurveyAdmin.responseFilter === 'completed'"
                                   :style     "display: none;"}
                              [:td {:colspan 2
                                    :class   "wa-color-text-quiet wa-text-center"}
                               [:i18n/tr :insurance/survey-filter-empty-completed]]])])]
    (ui2/section-card
     {:title    [:i18n/tr :insurance/survey-responses-title]
      :subtitle [:i18n/tr :insurance/survey-responses-subtitle]
      :divider? true}
     [:div {:class "wa-stack wa-gap-m"}
      (into
       [:wa-button-group {:label [:i18n/tr :insurance/survey-filter-label]}]
       (for [{:keys [count key value]} filters]
         [button/Button
          {:appearance                 (if (= "all" value) "filled" "outlined")
           :variant                    (when (= "all" value) "brand")
           :size                       "s"
           :data-on:click              (str "$insuranceSurveyAdmin.responseFilter = '"
                                            value "'")
           :data-class:wa-filled       (str "$insuranceSurveyAdmin.responseFilter === '"
                                            value "'")
           :data-class:wa-outlined     (str "$insuranceSurveyAdmin.responseFilter !== '"
                                            value "'")
           :data-class:wa-brand        (str "$insuranceSurveyAdmin.responseFilter === '"
                                            value "'")
           :data-attr:aria-pressed     (str "$insuranceSurveyAdmin.responseFilter === '"
                                            value "' ? 'true' : 'false'")}
          [:i18n/tr key {:count count}]]))
      (ui2/table-shell
       [:table {:class "wa-table insurance-survey-responses"}
        [:caption {:class "wa-visually-hidden"}
         [:i18n/tr :insurance/survey-responses-table-caption]]
        [:thead
         [:tr
          [:th {:scope "col"} [:i18n/tr :insurance/member]]
          [:th {:scope "col"
                :class "action wa-text-end"}
           [:span {:class "wa-visually-hidden"}
            [:i18n/tr :insurance/survey-response-actions]]]]]
        (into [:tbody]
              (concat (map #(response-row req %) response-rows)
                      empty-rows))])])))

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
      (for [{:insurance.survey/keys [closed-at closes-at created-at survey-id]}
            closed-surveys]
        [:li {:id (str "insurance-survey-closed-" survey-id)}
         [:span {:class "wa-color-text-quiet"}
          [:i18n/tr (if closed-at
                      :insurance/survey-history-closed
                      :insurance/survey-history-expired)
           {:created (ui2/format-date-time req :medium created-at)
            :ended   (ui2/format-date-time req :medium
                                           (or closed-at closes-at))}]]])))))

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
                             (if active-survey
                               [button/Button
                                {:appearance  "filled"
                                 :variant     "brand"
                                 :disabled    (not-any? (complement :completed?)
                                                        response-rows)
                                 :data-dialog (str "open " reminders-dialog-id)}
                                [ico/Icon {::ico/library :snoico
                                           ::ico/name    :envelope
                                           :slot         "start"}]
                                [:i18n/tr :insurance/survey-send-reminders]]
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
                                [:i18n/tr :insurance/start-survey]]))
        overflow-items     (when (and authorized? active-survey)
                             [[:wa-dropdown-item
                               {:variant     "danger"
                                :data-dialog (str "open " close-dialog-id)}
                               [ico/Icon {::ico/library :snoico
                                          ::ico/name    :xmark
                                          :slot         "icon"}]
                               [:i18n/tr :insurance/survey-close]]])]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :wide
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {::breadcrumb/max-items [2 2]}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
           [:i18n/tr :insurance/title]]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
           (:insurance.policy/name policy)]
          [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/manage-surveys]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy policy)
                             :label (:insurance.policy/name policy)}]
         ::page-toolbar/actions (when toolbar-action [toolbar-action])
         ::page-toolbar/overflow-items overflow-items
         ::page-toolbar/overflow-label (when overflow-items
                                         [:i18n/tr :insurance/survey-more-actions])
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
            (responses-section req response-rows))
          (closed-surveys-section req closed-surveys))
         [:wa-callout {:appearance "outlined"
                       :variant    "warning"}
          [:i18n/tr :insurance/survey-error-not-allowed]])]
      (when authorized?
        (survey-dialogs req active-survey))])))

(d*/refresh-all!)
