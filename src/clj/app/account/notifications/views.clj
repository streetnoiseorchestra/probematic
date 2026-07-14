(ns app.account.notifications.views
  (:require
   [app.account.actions :as actions]
   [app.account.queries :as queries]
   [app.account.view-support :as support]
   [app.datastar :as d*]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]))

(def form-id "account-notifications-form")

(defn- update-on-change [req]
  {:data-on:change (str "@post('"
                        (d*/act req ::actions/update-notification-settings)
                        "')")})

(defn- radio-group [req legend name signal selected options]
  (into
   [:fieldset {:class "wa-stack wa-gap-xs"}
    [:legend {:class "account-section-heading"}
     [:i18n/tr legend]]]
   (for [[value label description] options]
     (support/radio-option
      {:id       (str "account-notifications-" name "-" value)
       :name     name
       :value    value
       :signal   signal
       :checked? (= value selected)
       :label    [:i18n/tr label]
       :description (when description
                      (if (= description
                             :account-settings/notifications-what-gigs-description)
                        (support/instance-tr req description)
                        [:i18n/tr description]))
       :form     form-id
       :attrs    (update-on-change req)}))))

(defn- summary-key [{:keys [what delivery] delivery-time :when}]
  (let [channels (cond
                   (and (:email? delivery) (:browser? delivery))
                   "email-browser-mobile"

                   (:email? delivery) "email-mobile"
                   (:browser? delivery) "browser-mobile"
                   :else "mobile")
        scope    (if (= "gigs" what) "gigs" "everything")
        schedule (if (= "daily-batch" delivery-time) "daily" "right-away")]
    (keyword "account-settings"
             (str "notifications-summary-" channels "-" scope "-" schedule))))

(defn- toggle-action [req enabled?]
  (str "$account-notifications['enabled?'] = " (if enabled? "true" "false") "; "
       "@post('" (d*/act req ::actions/toggle-notifications) "')"))

(defn- browser-action [req]
  (str "$account-notifications.delivery['browser-capable?'] = ('Notification' in window); "
       "$account-notifications.delivery.browser-permission = "
       "(('Notification' in window) ? Notification.permission : 'default'); "
       "@post('" (d*/act req ::actions/enable-browser-notifications) "')"))

(defn- status-actions [req state]
  [:div {:class "notification-status-actions wa-cluster wa-gap-xs wa-justify-content-center"}
   (if (:enabled? state)
     (list
      (when-not (get-in state [:delivery :browser?])
        [button/Button
         {:id "enable-browser-notifications"
          :appearance "outlined"
          :variant "brand"
          :data-browser-capability-signal
          "account-notifications.delivery.browser-capable?"
          :data-browser-permission-signal
          "account-notifications.delivery.browser-permission"
          :data-on:click (browser-action req)}
         [:i18n/tr :account-settings/notifications-browser-enable]])
      [button/Button
       {:id "turn-notifications-off"
        :appearance "plain"
        :variant "brand"
        :data-on:click (toggle-action req false)}
       [:i18n/tr :account-settings/notifications-turn-off]])
     [button/Button
      {:id "turn-notifications-on"
       :appearance "outlined"
       :variant "brand"
       :data-on:click (toggle-action req true)}
      [:i18n/tr :account-settings/notifications-turn-on]])])

(defn- status-card [req state]
  [card/Card {:id "notification-status-summary"
              :class "notification-status-card"
              :appearance "filled-outlined"}
   [:div {:class "wa-stack wa-gap-s wa-align-items-center wa-text-center"}
    [:div {:class "wa-cluster wa-gap-xs wa-justify-content-center"}
     [ico/Icon {::ico/library :phosphor
                ::ico/name    (if (:enabled? state) :bell :bell-slash)}]
     [:h2 {:class "notification-status-heading"}
      [:i18n/tr (if (:enabled? state)
                  :account-settings/notifications-enabled
                  :account-settings/notifications-disabled)]]]
    [:p {:id "notification-status-description"}
     [:i18n/tr (if (:enabled? state)
                 (summary-key state)
                 :account-settings/notifications-disabled-description)]]
    (status-actions req state)]])

(defn- what-and-reminders [req state]
  [:section {:class "account-section wa-stack wa-gap-l"}
   (radio-group req
                :account-settings/notifications-what-title
                "notification-what"
                "account-notifications.what"
                (:what state)
                [["everything"
                  :account-settings/notifications-what-everything
                  :account-settings/notifications-what-everything-description]
                 ["gigs"
                  :account-settings/notifications-what-gigs
                  :account-settings/notifications-what-gigs-description]])
   [:fieldset {:class "wa-stack wa-gap-xs"}
    [:legend {:class "account-subsection-heading"}
     [:i18n/tr :account-settings/notifications-reminders-title]]
    (support/checkbox-option
     {:id "account-notifications-reminder-attendance"
      :name "reminder-attendance"
      :signal "account-notifications.reminders.attendance?"
      :checked? (get-in state [:reminders :attendance?])
      :label [:i18n/tr :account-settings/notifications-reminder-attendance]
      :description [:i18n/tr
                    :account-settings/notifications-reminder-attendance-description]
      :form form-id
      :attrs (update-on-change req)})
    (support/checkbox-option
     {:id "account-notifications-reminder-polls"
      :name "reminder-polls"
      :signal "account-notifications.reminders.polls?"
      :checked? (get-in state [:reminders :polls?])
      :label [:i18n/tr :account-settings/notifications-reminder-polls]
      :description [:i18n/tr
                    :account-settings/notifications-reminder-polls-description]
      :form form-id
      :attrs (update-on-change req)})]])

(defn- unread-preview [req id numbered? selected label]
  (let [value (if numbered? "numbered" "unnumbered")]
    [:label {:for (str id "-input")
             :id id
             :class "unread-preview account-choice wa-stack wa-gap-xs"}
     [:input (merge {:id (str id "-input")
                     :type "radio"
                     :name "unread-style"
                     :value value
                     :checked (= value selected)
                     :form form-id
                     :data-bind "account-notifications.unread-style"}
                    (update-on-change req))]
     [:span {:class "wa-flank wa-gap-xs wa-align-items-center"}
      [:span {:class (str "ping-mark" (when numbered? " numbered"))
              :aria-hidden "true"}
       (when numbered? "3")]
      [:span [:i18n/tr label]]]]))

(defn- unread-choices [req state]
  [:fieldset {:class "wa-stack wa-gap-xs"}
   [:legend {:class "account-subsection-heading"}
    [:i18n/tr :account-settings/notifications-unread-title]]
   [:p {:class "wa-caption-s wa-color-text-quiet"}
    (support/instance-tr req
                         :account-settings/notifications-unread-description)]
   [:div {:class "notification-unread-choices wa-cluster wa-gap-l"}
    (unread-preview req
                    "notification-unread-numbered-preview"
                    true
                    (:unread-style state)
                    :account-settings/notifications-unread-numbered)
    (unread-preview req
                    "notification-unread-unnumbered-preview"
                    false
                    (:unread-style state)
                    :account-settings/notifications-unread-unnumbered)]])

(defn- delivery-card [req state]
  [:section {:class "account-section wa-stack wa-gap-m"}
   [:h2 {:class "account-section-heading"}
    [:i18n/tr :account-settings/notifications-how-title]]
   (support/checkbox-option
    {:id "account-notifications-email"
     :name "notification-email"
     :signal "account-notifications.delivery.email?"
     :checked? (get-in state [:delivery :email?])
     :label [:i18n/tr :account-settings/notifications-email]
     :description (support/instance-tr
                   req
                   :account-settings/notifications-email-description)
     :form form-id
     :attrs (update-on-change req)})
   (support/checkbox-option
    {:id "account-notifications-browser"
     :name "notification-browser"
     :signal "account-notifications.delivery.browser?"
     :checked? (get-in state [:delivery :browser?])
     :label (support/instance-tr req :account-settings/notifications-browser)
     :description [:i18n/tr :account-settings/notifications-browser-description]
     :form form-id
     :attrs (update-on-change req)})
   (when-let [error (or (support/field-error state :browser)
                        (support/field-error state :browser-permission))]
     [:p {:class "wa-caption-s wa-color-text-danger" :role "alert"} error])
   (unread-choices req state)])

(defn- timing-card [req state]
  (let [daily? (= "daily-batch" (:when state))
        expression "$account-notifications.when === 'daily-batch'"]
    [:section {:class "account-section wa-stack wa-gap-l"}
     (radio-group req
                  :account-settings/notifications-when-title
                  "notification-when"
                  "account-notifications.when"
                  (:when state)
                  [["right-away"
                    :account-settings/notifications-when-right-away
                    :account-settings/notifications-when-right-away-description]
                   ["daily-batch"
                    :account-settings/notifications-when-daily
                    :account-settings/notifications-when-daily-description]])
     (into
      [:fieldset (cond-> {:id "notification-batch-times"
                          :class "wa-stack wa-gap-xs"
                          :data-show expression
                          :data-attr:hidden (str "!(" expression ")")}
                   (not daily?) (assoc :hidden true))
       [:legend {:class "wa-visually-hidden"}
        [:i18n/tr :account-settings/notifications-when-daily]]]
      (for [[value label] [["08:00" :account-settings/notifications-batch-morning]
                           ["13:00" :account-settings/notifications-batch-afternoon]
                           ["20:00" :account-settings/notifications-batch-evening]]]
        (support/radio-option
         {:id (str "account-notifications-batch-" value)
          :name "batch-time"
          :value value
          :signal "account-notifications.batch-time"
          :checked? (= value (:batch-time state))
          :label [:i18n/tr label]
          :form form-id
          :attrs (update-on-change req)})))]))

(defn page [{:keys [db page-state] :as req}]
  (let [title [:i18n/tr :account-settings/notifications-title]
        state (queries/notification-page-state
               db
               (support/current-member-id req)
               page-state)]
    (support/standard-page
     {:title title
      :subtitle [:i18n/tr :account-settings/notifications-subtitle]
      :mobile-back? false
      :actions []}
     [:form {:id form-id
             :class "wa-stack wa-gap-l"
             :data-id "account-notifications"
             :data-signals (d*/->signals {:account-notifications state})}
      (status-card req state)
      (what-and-reminders req state)
      (delivery-card req state)
      (timing-card req state)
      (support/feedback state)])))

(d*/refresh-all!)
