(ns app.account.break.views
  (:require
   [app.account.actions :as actions]
   [app.account.queries :as queries]
   [app.account.view-support :as support]
   [app.datastar :as d*]
   [app.ui2.avatar :as avatar]
   [app.ui2.button :as button]
   [app.ui2.card :as card]))

(def form-id "account-break-form")

(defn- update-on-change [req]
  {:data-on:change (str "@post('"
                        (d*/act req ::actions/update-break-settings)
                        "')")})

(defn- visibility-attrs [visible? expression]
  (cond-> {:data-show expression
           :data-attr:hidden (str "!(" expression ")")}
    (not visible?) (assoc :hidden true)))

(defn- availability-control [req state]
  [:div {:class "account-break-toggle wa-cluster wa-align-items-center wa-justify-content-center"}
   [:wa-switch (cond-> (merge
                        {:id "account-break-active"
                         :name "break-active"
                         :size "l"
                         :data-bind__prop.checked__event.change "account-break.active"}
                        (update-on-change req))
                 (:active state) (assoc :checked true))
    [:span (merge {:id "account-break-available-label"}
                  (visibility-attrs (not (:active state))
                                    "!$account-break.active"))
     [:i18n/tr :account-settings/break-available-label]]
    [:span (merge {:id "account-break-away-label"}
                  (visibility-attrs (:active state)
                                    "$account-break.active"))
     [:i18n/tr :account-settings/break-away-label]]]])

(defn- avatar-preview [member state]
  [:div {:id "account-break-avatar-frame"
         :class (str "break-avatar-frame" (when (:active state) " paused"))
         :data-class:paused "$account-break.active"}
   [avatar/Avatar {::avatar/member member
                   ::avatar/link? false
                   ::avatar/icon :user
                   ::avatar/image-size 160
                   :class "break-avatar"}]
   [:span {:id "account-break-pause-overlay"
           :class "pause-overlay"
           :aria-hidden "true"}
    [:i18n/tr :account-settings/break-pause-overlay]]])

(defn- date-field [req state field id label]
  (support/field
   {:state state
    :root "account-break"
    :field field
    :id id
    :label [:i18n/tr label]
    :attrs (merge {:type "date" :form form-id}
                  (update-on-change req))}))

(defn- schedule-fields [req state]
  [:div (merge {:id "account-break-status-fields"
                :class "break-fields wa-stack wa-gap-l"}
               (visibility-attrs (:active state) "$account-break.active"))
   [:fieldset {:class "wa-stack wa-gap-xs"}
    [:legend {:class "break-schedule-title wa-heading-xl"}
     [:i18n/tr :account-settings/break-start-title]]
    (support/radio-option
     {:id "account-break-start-now"
      :name "break-start-choice"
      :value "now"
      :signal "account-break.start-choice"
      :checked? (= "now" (:start-choice state))
      :label [:i18n/tr :account-settings/break-start-now]
      :form form-id
      :attrs (update-on-change req)})
    (support/radio-option
     {:id "account-break-start-future"
      :name "break-start-choice"
      :value "date"
      :signal "account-break.start-choice"
      :checked? (= "date" (:start-choice state))
      :label [:i18n/tr :account-settings/break-start-date]
      :form form-id
      :attrs (update-on-change req)})]
   [:div {:class "account-field-grid wa-grid wa-gap-m"}
    [:div (merge {:id "account-break-start-date-row"}
                 (visibility-attrs
                  (and (:active state) (= "date" (:start-choice state)))
                  (str "$account-break.active && "
                       "$account-break.start-choice === 'date'")))
     (date-field req state :start-date "account-break-start-date"
                 :account-settings/break-start-date)]
    (date-field req state :end-date "account-break-end-date"
                :account-settings/break-end-date)]])

(defn- status-copy [state]
  (let [status (:status state)
        shown? (and (:active state) (contains? #{"scheduled" "ended"} status))
        expression (str "$account-break.active && "
                        "($account-break.status === 'scheduled' || "
                        "$account-break.status === 'ended')")]
    [:div (merge {:id "account-break-status-copy"
                  :class "break-status-copy"}
                 (visibility-attrs shown? expression))
     [:p (merge {:id "account-break-status-scheduled"}
                (visibility-attrs (and (:active state) (= "scheduled" status))
                                  (str "$account-break.active && "
                                       "$account-break.status === 'scheduled'")))
      [:i18n/tr :account-settings/break-status-scheduled]]
     [:p (merge {:id "account-break-status-ended"}
                (visibility-attrs (and (:active state) (= "ended" status))
                                  (str "$account-break.active && "
                                       "$account-break.status === 'ended'")))
      [:i18n/tr :account-settings/break-status-ended]]]))

(defn- status-card [req member state]
  [card/Card {:class "break-status-card wa-brand-purple" :appearance "plain"}
   [:div {:id "account-break-card-content"
          :class "wa-stack wa-gap-l"}
    [:div {:class "break-summary wa-stack wa-gap-m wa-align-items-center wa-text-center"}
     (avatar-preview member state)
     (when-let [member-name (:member/name member)]
       [:strong {:class "break-member-name wa-heading-xl"} member-name])
     (availability-control req state)
     (status-copy state)]
    (schedule-fields req state)
    (when (:_feedback state)
      [:div {:id "account-break-feedback"}
       (support/feedback state)])]
   [:div {:slot "footer-actions" :class "wa-cluster wa-gap-xs"}
    [button/Button (merge
                    {:id "account-break-end-early"
                     :appearance "filled"
                     :variant "neutral"
                     :class "break-end-button"
                     :data-dialog "open account-break-end-dialog"}
                    (visibility-attrs
                     (and (:active state)
                          (contains? #{"scheduled" "away"} (:status state)))
                     (str "$account-break.active && "
                          "($account-break.status === 'scheduled' || "
                          "$account-break.status === 'away')")))
     [:i18n/tr :account-settings/break-end-early]]]])

(defn- explanation-item [title description]
  [:li
   [:strong [:i18n/tr title]]
   " "
   [:span [:i18n/tr description]]])

(defn- explanation [req]
  [:section {:class "break-explanation wa-stack wa-gap-l"}
   [:div {:class "wa-stack wa-gap-2xs"}
    [:h2 {:class "wa-heading-m"}
     [:i18n/tr :account-settings/break-explanation-title]]
    [:p (support/instance-tr req :account-settings/break-explanation-intro)]]
   [:ul {:class "wa-stack wa-gap-m"}
    [:li
     [:strong [:i18n/tr :account-settings/break-explanation-avatar-title]]
     " "
     [:span (support/instance-tr
             req
             :account-settings/break-explanation-avatar-description)]]
    (explanation-item :account-settings/break-explanation-search-title
                      :account-settings/break-explanation-search-description)
    (explanation-item :account-settings/break-explanation-activity-title
                      :account-settings/break-explanation-activity-description)]
   [:div {:class "wa-stack wa-gap-2xs"}
    [:h2 {:class "wa-heading-m"}
     [:i18n/tr :account-settings/break-notifications-title]]
    [:p [:i18n/tr :account-settings/break-notifications-description]]]
   [:div {:class "wa-stack wa-gap-2xs"}
    [:h2 {:class "wa-heading-m"}
     [:i18n/tr :account-settings/break-auto-responder-title]]
    [:p [:i18n/tr :account-settings/break-auto-responder-description]]]])

(defn- end-dialog [req]
  [:wa-dialog {:id "account-break-end-dialog"
               :label [:i18n/tr :account-settings/break-end-dialog-title]
               :with-footer true
               :data-preserve-attr "open"}
   [:p [:i18n/tr :account-settings/break-end-dialog-description]]
   [button/Button {:slot "footer"
                   :appearance "outlined"
                   :data-dialog "close"}
    [:i18n/tr :action/cancel]]
   [button/Button {:slot "footer"
                   :appearance "filled"
                   :variant "danger"
                   :data-dialog "close"
                   :data-id "account-break-end"
                   :data-action (d*/act req ::actions/end-break)}
    [:i18n/tr :account-settings/break-end-dialog-confirm]]])

(defn page [{:keys [db page-state] :as req}]
  (let [title             [:i18n/tr :account-settings/break-title]
        current-member-id (support/current-member-id req)
        state             (queries/break-page-state page-state)
        member            (queries/current-member db current-member-id)]
    (support/standard-page
     {:title title
      :subtitle [:i18n/tr :account-settings/break-subtitle]
      :actions []
      :after [(end-dialog req)]}
     [:form {:id form-id
             :data-id "account-break"
             :data-signals (d*/->signals {:account-break state})
             :data-init "window.StreetnoiseAccountBreak.syncTimeZone($account-break)"
             :data-on:submit "evt.preventDefault();"}
      (status-card req member state)]
     (explanation req))))

(d*/refresh-all!)
