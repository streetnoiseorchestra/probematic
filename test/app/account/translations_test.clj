(ns app.account.translations-test
  (:require
   [app.i18n.fluent :as fluent]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def expected-account-message-ids
  #{:account-settings/title
    :account-settings/toolbar-label
    :account-settings/settings-title
    :account-settings/profile-row-title
    :account-settings/profile-row-description
    :account-settings/notifications-row-title
    :account-settings/notifications-row-description
    :account-settings/preferences-row-title
    :account-settings/preferences-row-description
    :account-settings/break-row-title
    :account-settings/break-row-description
    :account-settings/help-row-title
    :account-settings/help-row-description
    :account-settings/logout-row-title
    :account-settings/logout-row-description
    :account-settings/apps-title
    :account-settings/apps-description
    :account-settings/app-store-ios-alt
    :account-settings/app-store-android-alt
    :account-settings/app-pwa-title
    :account-settings/app-pwa-description
    :account-settings/app-prototype-feedback
    :account-settings/error-app-platform-invalid
    :account-settings/profile-title
    :account-settings/profile-subtitle
    :account-settings/avatar-label
    :account-settings/avatar-choose
    :account-settings/avatar-replace
    :account-settings/avatar-remove
    :account-settings/avatar-empty-callout
    :account-settings/avatar-staged-feedback
    :account-settings/avatar-removed-feedback
    :account-settings/name-label
    :account-settings/nickname-label
    :account-settings/email-label
    :account-settings/username-label
    :account-settings/phone-label
    :account-settings/current-status-label
    :account-settings/date-of-birth-label
    :account-settings/profile-saved-feedback
    :account-settings/login-security-title
    :account-settings/login-security-description
    :account-settings/login-security-link
    :account-settings/error-name-required
    :account-settings/error-email-required
    :account-settings/error-email-invalid
    :account-settings/error-username-required
    :account-settings/error-username-invalid
    :account-settings/error-phone-invalid
    :account-settings/error-date-invalid
    :account-settings/error-nick-taken
    :account-settings/error-email-taken
    :account-settings/error-username-taken
    :account-settings/error-phone-taken
    :account-settings/error-profile-invalid
    :account-settings/error-current-member-missing
    :account-settings/error-avatar-name
    :account-settings/error-avatar-type
    :account-settings/error-avatar-size
    :account-settings/preferences-title
    :account-settings/appearance-title
    :account-settings/appearance-light
    :account-settings/appearance-dark
    :account-settings/appearance-system
    :account-settings/date-time-title
    :account-settings/time-zone-label
    :account-settings/time-zone-description-before-profile
    :account-settings/time-zone-profile-link
    :account-settings/time-zone-description-before-notifications
    :account-settings/time-zone-notifications-link
    :account-settings/week-start-label
    :account-settings/week-start-description
    :account-settings/week-start-monday
    :account-settings/week-start-sunday
    :account-settings/time-format-label
    :account-settings/time-format-description
    :account-settings/time-format-12-hour
    :account-settings/time-format-24-hour
    :account-settings/preferences-save
    :account-settings/preferences-saved-feedback
    :account-settings/error-time-zone-invalid
    :account-settings/error-week-start-invalid
    :account-settings/error-time-format-invalid
    :account-settings/notifications-title
    :account-settings/notifications-subtitle
    :account-settings/notifications-enabled
    :account-settings/notifications-disabled
    :account-settings/notifications-disabled-description
    :account-settings/notifications-turn-on
    :account-settings/notifications-turn-off
    :account-settings/notifications-summary-mobile-everything-right-away
    :account-settings/notifications-summary-mobile-everything-daily
    :account-settings/notifications-summary-mobile-gigs-right-away
    :account-settings/notifications-summary-mobile-gigs-daily
    :account-settings/notifications-summary-email-mobile-everything-right-away
    :account-settings/notifications-summary-email-mobile-everything-daily
    :account-settings/notifications-summary-email-mobile-gigs-right-away
    :account-settings/notifications-summary-email-mobile-gigs-daily
    :account-settings/notifications-summary-browser-mobile-everything-right-away
    :account-settings/notifications-summary-browser-mobile-everything-daily
    :account-settings/notifications-summary-browser-mobile-gigs-right-away
    :account-settings/notifications-summary-browser-mobile-gigs-daily
    :account-settings/notifications-summary-email-browser-mobile-everything-right-away
    :account-settings/notifications-summary-email-browser-mobile-everything-daily
    :account-settings/notifications-summary-email-browser-mobile-gigs-right-away
    :account-settings/notifications-summary-email-browser-mobile-gigs-daily
    :account-settings/notifications-what-title
    :account-settings/notifications-what-everything
    :account-settings/notifications-what-everything-description
    :account-settings/notifications-what-gigs
    :account-settings/notifications-what-gigs-description
    :account-settings/notifications-reminders-title
    :account-settings/notifications-reminder-attendance
    :account-settings/notifications-reminder-attendance-description
    :account-settings/notifications-reminder-polls
    :account-settings/notifications-reminder-polls-description
    :account-settings/notifications-how-title
    :account-settings/notifications-email
    :account-settings/notifications-email-description
    :account-settings/notifications-browser
    :account-settings/notifications-browser-description
    :account-settings/notifications-browser-enable
    :account-settings/error-browser-unsupported
    :account-settings/error-browser-permission-invalid
    :account-settings/notifications-unread-title
    :account-settings/notifications-unread-description
    :account-settings/notifications-unread-numbered
    :account-settings/notifications-unread-unnumbered
    :account-settings/notifications-when-title
    :account-settings/notifications-when-right-away
    :account-settings/notifications-when-right-away-description
    :account-settings/notifications-when-daily
    :account-settings/notifications-when-daily-description
    :account-settings/notifications-batch-morning
    :account-settings/notifications-batch-afternoon
    :account-settings/notifications-batch-evening
    :account-settings/error-notification-what-invalid
    :account-settings/error-unread-style-invalid
    :account-settings/error-notification-when-invalid
    :account-settings/error-batch-time-invalid
    :account-settings/break-title
    :account-settings/break-subtitle
    :account-settings/break-available-label
    :account-settings/break-away-label
    :account-settings/break-status-scheduled
    :account-settings/break-status-ended
    :account-settings/break-pause-overlay
    :account-settings/break-explanation-title
    :account-settings/break-explanation-intro
    :account-settings/break-explanation-avatar-title
    :account-settings/break-explanation-avatar-description
    :account-settings/break-explanation-search-title
    :account-settings/break-explanation-search-description
    :account-settings/break-explanation-activity-title
    :account-settings/break-explanation-activity-description
    :account-settings/break-notifications-title
    :account-settings/break-notifications-description
    :account-settings/break-auto-responder-title
    :account-settings/break-auto-responder-description
    :account-settings/break-start-title
    :account-settings/break-start-now
    :account-settings/break-start-date
    :account-settings/break-end-date
    :account-settings/break-save
    :account-settings/break-end-early
    :account-settings/break-end-dialog-title
    :account-settings/break-end-dialog-description
    :account-settings/break-end-dialog-confirm
    :account-settings/break-saved-feedback
    :account-settings/break-ended-feedback
    :account-settings/error-break-availability-invalid
    :account-settings/error-break-start-choice-invalid
    :account-settings/error-break-date-order})

(deftest account-fluent-resources-have-matching-complete-locales
  (doseq [locale [:en :de]]
    (let [locale-state (fluent/new-locale locale)]
      (doseq [message-id expected-account-message-ids]
        (let [translated (fluent/translate
                          locale-state
                          message-id
                          (cond-> {:instance-name "Test Instance"}
                            (= message-id :account-settings/app-prototype-feedback)
                            (assoc :platform "iOS")))]
          (is (and (string? translated) (not (str/blank? translated)))
              (str locale " is missing " message-id)))))))

(def instance-name-message-ids
  #{:account-settings/notifications-row-description
    :account-settings/logout-row-description
    :account-settings/apps-description
    :account-settings/app-pwa-description
    :account-settings/login-security-description
    :account-settings/login-security-link
    :account-settings/time-zone-description-before-profile
    :account-settings/week-start-description
    :account-settings/time-format-description
    :account-settings/notifications-what-gigs-description
    :account-settings/notifications-email-description
    :account-settings/notifications-browser
    :account-settings/notifications-unread-description
    :account-settings/break-explanation-intro
    :account-settings/break-explanation-avatar-description})

(deftest account-copy-interpolates-the-configured-instance-name
  (doseq [locale [:en :de]
          message-id instance-name-message-ids]
    (let [translated (fluent/translate (fluent/new-locale locale)
                                       message-id
                                       {:instance-name "Test Ensemble"})]
      (is (str/includes? translated "Test Ensemble")
          (str locale " does not interpolate the instance name in " message-id)))))
