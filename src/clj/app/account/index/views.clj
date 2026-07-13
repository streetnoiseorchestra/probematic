(ns app.account.index.views
  (:require
   [app.account.actions :as actions]
   [app.account.view-support :as support]
   [app.datastar :as d*]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]))

(def directory-items
  [{:href "/account-settings/profile"
    :icon :user
    :title :account-settings/profile-row-title}
   {:href "/account-settings/notifications"
    :icon :bell
    :icon-library :phosphor
    :title :account-settings/notifications-row-title}
   {:href "/account-settings/preferences"
    :icon :palette
    :icon-library :phosphor
    :title :account-settings/preferences-row-title}
   {:href "/account-settings/on-a-break"
    :icon :pause-circle
    :icon-library :phosphor
    :title :account-settings/break-row-title}
   {:href "#"
    :icon :question
    :title :account-settings/help-row-title
    :prevent-default? true}
   {:href "/logout"
    :icon :sign-out
    :icon-library :phosphor
    :title :account-settings/logout-row-title}])

(defn- directory-row
  [{:keys [href icon icon-library title prevent-default?]}]
  [:a (cond-> {:href href :class "account-settings-row sno-no-visited"}
        prevent-default? (assoc :data-on:click "evt.preventDefault();"))
   [ico/Icon {::ico/library (or icon-library :snoico)
              ::ico/name icon}]
   [:span [:i18n/tr title]]])

(defn- app-action [req platform]
  (str "evt.preventDefault(); $account-app.platform = '" platform
       "'; @post('" (d*/act req ::actions/launch-app) "')"))

(defn- app-link [req platform child]
  [:a {:href "#"
       :class "account-app-link"
       :data-on:click (app-action req platform)}
   child])

(defn- apps-card [req app-state]
  [card/Card {:id "account-settings-apps"
              :class "account-settings-group"
              :appearance "outlined"}
   [:h2 {:slot "header" :class "wa-heading-m wa-text-center"}
    [:i18n/tr :account-settings/apps-title]]
   [:div {:class "account-apps-body wa-stack wa-gap-m wa-align-items-center"}
    [:p {:class "wa-body-m wa-color-text-quiet wa-text-center"}
     (support/instance-tr req :account-settings/apps-description)]
    [:div {:class "wa-cluster wa-gap-xs wa-justify-content-center"}
     (app-link req "ios"
               [:img {:src "/img/app-store-ios.png"
                      :alt [:i18n/tr :account-settings/app-store-ios-alt]}])
     (app-link req "android"
               [:img {:src "/img/app-store-android.png"
                      :alt [:i18n/tr :account-settings/app-store-android-alt]}])]
    (app-link
     req
     "pwa"
     [:span {:class "pwa-link wa-flank wa-gap-xs wa-align-items-center"}
      [ico/Icon {::ico/library :phosphor ::ico/name :download-simple}]
      [:span [:i18n/tr :account-settings/app-pwa-title]]])
    (support/feedback app-state)]])

(defn page [{:keys [page-state] :as req}]
  (let [app-state (merge {:platform ""} (:account-app page-state))]
    (support/account-main
     [:div {:class "account-directory wa-stack wa-gap-l"
            :data-signals (d*/->signals {:account-app app-state})}
      [:header
       [:h1 {:class "wa-heading-2xl wa-text-center"}
        [:i18n/tr :account-settings/title]]]
      [card/Card {:id "account-settings-directory"
                  :class "account-settings-group"
                  :appearance "outlined"}
       [:h2 {:slot "header" :class "wa-heading-m wa-text-center"}
        [:i18n/tr :account-settings/settings-title]]
       (into [:nav {:aria-label [:i18n/tr :account-settings/title]}]
             (map directory-row)
             directory-items)]
      (apps-card req app-state)])))

(d*/refresh-all!)
