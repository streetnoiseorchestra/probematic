(ns app.account.notifications.views-test
  (:require
   [app.account.test-support :as support]
   [app.ui2.button :as button]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is]]))

(defn values-for [name view]
  (mapv #(-> % support/attrs :value)
        (filter #(= name (:name (support/attrs %)))
                (support/elements :input view))))

(deftest notification-page-renders-the-complete-approved-choice-set
  (let [page (support/public-fn 'app.account.notifications.views/page)]
    (is (fn? page) "app.account.notifications.views/page should exist")
    (when page
      (let [view (page (support/request))]
        (is (= ["everything" "gigs"] (values-for "notification-what" view)))
        (is (= ["numbered" "unnumbered"] (values-for "unread-style" view)))
        (is (= ["right-away" "daily-batch"] (values-for "notification-when" view)))
        (is (= ["morning" "afternoon" "evening"]
               (values-for "batch-time" view)))
        (is (some? (support/element-by-id "notification-status-summary" view)))
        (is (some? (support/element-by-id "notification-unread-numbered-preview" view)))
        (is (some? (support/element-by-id "notification-unread-unnumbered-preview" view)))
        (is (some? (:data-show
                    (support/attrs
                     (support/element-by-id "notification-batch-times" view)))))
        (is (empty? (support/elements :wa-input view)))))))

(deftest notification-page-posts-every-choice-through-an-immediate-action
  (let [page (support/public-fn 'app.account.notifications.views/page)]
    (is (fn? page) "app.account.notifications.views/page should exist")
    (when page
      (let [view (page (support/request))]
        (is (= #{:app.account.actions/toggle-notifications
                 :app.account.actions/enable-browser-notifications
                 :app.account.actions/update-notification-settings}
               (support/actions-in view)))
        (is (nil? (support/element-by-id "account-notifications-enabled" view)))
        (is (every? #(= :app.account.actions/update-notification-settings
                        (support/action-keyword (:data-on:change (support/attrs %))))
                    (support/elements :input view)))
        (is (= "account-notifications.delivery.browser-capable?"
               (:data-browser-capability-signal
                (support/attrs
                 (support/element-by-id "enable-browser-notifications" view)))))
        (is (= "account-notifications.delivery.browser-permission"
               (:data-browser-permission-signal
                (support/attrs
                 (support/element-by-id "enable-browser-notifications" view)))))
        (is (re-find
             #"\$account-notifications\['enabled\?'\] = false"
             (-> (support/element-by-id "turn-notifications-off" view)
                 support/attrs
                 :data-on:click)))
        (is (re-find
             #"\$account-notifications\.delivery\['browser-capable\?'\] ="
             (-> (support/element-by-id "enable-browser-notifications" view)
                 support/attrs
                 :data-on:click)))))))

(deftest notification-status-uses-buttons-and-only-offers-browser-enablement-when-needed
  (let [page (support/public-fn 'app.account.notifications.views/page)]
    (is (fn? page) "app.account.notifications.views/page should exist")
    (when page
      (let [enabled (page (support/request))
            browser-enabled
            (page (support/request
                   {:page-state
                    {:account-notifications {:delivery {:browser? true}}}}))
            disabled
            (page (support/request
                   {:page-state {:account-notifications {:enabled? false}}}))]
        (is (= {:appearance "plain"}
               (select-keys
                (support/attrs (support/element-by-id "turn-notifications-off" enabled))
                [:appearance])))
        (is (= {:appearance "outlined" :variant "brand"}
               (select-keys
                (support/attrs
                 (support/element-by-id "enable-browser-notifications" enabled))
                [:appearance :variant])))
        (is (nil? (support/element-by-id "enable-browser-notifications"
                                         browser-enabled)))
        (is (= {:appearance "outlined" :variant "brand"}
               (select-keys
                (support/attrs (support/element-by-id "turn-notifications-on" disabled))
                [:appearance :variant])))
        (is (nil? (support/element-by-id "turn-notifications-off" disabled)))
        (is (nil? (support/element-by-id "enable-browser-notifications" disabled)))
        (is (= [:i18n/tr :account-settings/notifications-disabled-description]
               (nth (support/element-by-id "notification-status-description" disabled) 2)))
        (is (re-find
             #"\$account-notifications\['enabled\?'\] = true"
             (-> (support/element-by-id "turn-notifications-on" disabled)
                 support/attrs
                 :data-on:click)))))))

(deftest notification-status-summary-adapts-to-every-delivery-combination-and-schedule
  (let [page (support/public-fn 'app.account.notifications.views/page)]
    (is (fn? page) "app.account.notifications.views/page should exist")
    (when page
      (doseq [[email? browser? message]
              [[false false :account-settings/notifications-summary-mobile-everything-right-away]
               [true false :account-settings/notifications-summary-email-mobile-everything-right-away]
               [false true :account-settings/notifications-summary-browser-mobile-everything-right-away]
               [true true :account-settings/notifications-summary-email-browser-mobile-everything-right-away]]]
        (let [view (page
                    (support/request
                     {:page-state
                      {:account-notifications
                       {:delivery {:email? email? :browser? browser?}}}}))]
          (is (= [:i18n/tr message]
                 (nth (support/element-by-id "notification-status-description" view) 2)))))
      (let [view
            (page
             (support/request
              {:page-state
               {:account-notifications
                {:what "gigs"
                 :when "daily-batch"
                 :delivery {:email? true :browser? true}}}}))]
        (is (= [:i18n/tr
                :account-settings/notifications-summary-email-browser-mobile-gigs-daily]
               (nth (support/element-by-id "notification-status-description" view) 2)))))))

(deftest notification-page-uses-the-standard-account-shell
  (let [page (support/public-fn 'app.account.notifications.views/page)]
    (is (fn? page) "app.account.notifications.views/page should exist")
    (when page
      (let [contract (page-shell/page-contract
                      (page (support/request))
                      {:include-header? true})]
        (is (= {:width       :standard
                :breadcrumbs [:account-settings/title
                              :account-settings/notifications-title]
                :mobile      {:href "/account-settings"
                              :label :account-settings/title}
                :actions     []
                :overflow    []
                :heading     :account-settings/notifications-title}
               (select-keys contract
                            [:width :breadcrumbs :mobile :actions :overflow :heading])))
        (is (= 1 (count (support/elements button/BackButton
                                          (page (support/request))))))))))

(deftest notification-copy-uses-the-configured-instance-name
  (let [page (support/public-fn 'app.account.notifications.views/page)]
    (is (fn? page) "app.account.notifications.views/page should exist")
    (when page
      (let [view (page (support/request
                        {:system {:env {:name "The Test Band"}}}))]
        (doseq [message-id
                [:account-settings/notifications-what-gigs-description
                 :account-settings/notifications-email-description
                 :account-settings/notifications-browser
                 :account-settings/notifications-unread-description]]
          (is (= {:instance-name "The Test Band"}
                 (nth (support/translation-node message-id view) 2))))))))
