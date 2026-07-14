(ns app.account.preferences.views
  (:require
   [app.account.actions :as actions]
   [app.account.queries :as queries]
   [app.account.view-support :as support]
   [app.datastar :as d*]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.urls :as urls]))

(def form-id "account-preferences-form")

(def appearance-options
  [{:value "light" :label :account-settings/appearance-light :icon :sun}
   {:value "dark" :label :account-settings/appearance-dark :icon :moon}
   {:value "system" :label :account-settings/appearance-system :icon :monitor}])

(def week-start-options
  [{:value "monday" :label :account-settings/week-start-monday}
   {:value "sunday" :label :account-settings/week-start-sunday}])

(def time-format-options
  [{:value "12-hour" :label :account-settings/time-format-12-hour}
   {:value "24-hour" :label :account-settings/time-format-24-hour}])

(defn- section-heading [label]
  [:h2 {:class "preferences-section-heading wa-heading-xl"}
   [:i18n/tr label]])

(defn- appearance-section []
  [:section {:id "account-appearance"
             :class "preferences-section wa-stack wa-gap-l"}
   (section-heading :account-settings/appearance-title)
   (into
    [:fieldset {:id "account-appearance-controller"
                :class "appearance-choices"
                :data-init "window.StreetnoiseAppearance.sync()"}
     [:legend {:class "wa-visually-hidden"}
      [:i18n/tr :account-settings/appearance-title]]]
    (for [{:keys [value label icon]} appearance-options]
      [:label {:for (str "account-appearance-" value)
               :class "appearance-choice"}
       [:input {:id (str "account-appearance-" value)
                :class "wa-visually-hidden"
                :type "radio"
                :name "appearance"
                :value value
                :data-on:change "window.StreetnoiseAppearance.set(evt.target.value)"}]
       [ico/Icon {::ico/library :phosphor
                  ::ico/name icon
                  :aria-hidden "true"}]
       [:span [:i18n/tr label]]]))])

(defn- preference-select
  [state field id label description options & [attrs]]
  (let [description-id (str id "-description")]
    [:div {:class "account-field wa-stack wa-gap-2xs"}
     [:label {:for id :class "wa-caption-s"}
      [:i18n/tr label]]
     [:p {:id description-id
          :class "preference-hint wa-caption-s wa-color-text-quiet"}
      (if (keyword? description) [:i18n/tr description] description)]
     (into
      [:select (merge {:id id
                       :name (name field)
                       :form form-id
                       :aria-describedby description-id
                       :data-bind (str "account-preferences." (name field))}
                      attrs)]
      (for [{:keys [value label]} options]
        [:option {:value value :selected (= value (get state field))}
         (if (keyword? label) [:i18n/tr label] label)]))
     (when-let [error (support/field-error state field)]
       [:span {:class "wa-caption-s wa-color-text-danger" :role "alert"} error])]))

(defn- time-zone-description [req]
  [:span
   (support/instance-tr
    req
    :account-settings/time-zone-description-before-profile)
   " "
   [:a {:href (urls/link-account-profile)
        :class "sno-no-visited"}
    [:i18n/tr :account-settings/time-zone-profile-link]]
   [:i18n/tr :account-settings/time-zone-description-before-notifications]
   " "
   [:a {:href (urls/link-account-notifications)
        :class "sno-no-visited"}
    [:i18n/tr :account-settings/time-zone-notifications-link]]
   "."])

(defn- date-time-section [req state]
  [:section {:class "preferences-section wa-stack wa-gap-l"}
   (section-heading :account-settings/date-time-title)
   [:div {:class "preferences-fields wa-stack wa-gap-m"}
    (preference-select
     state
     :time-zone
     "account-preferences-time-zone"
     :account-settings/time-zone-label
     (time-zone-description req)
     (queries/time-zone-options)
     (when-not (:time-zone-persisted? state)
       {:data-init
        "window.StreetnoiseAccountPreferences.syncTimeZone(el, $account-preferences)"}))
    (preference-select state
                       :week-start
                       "account-preferences-week-start"
                       :account-settings/week-start-label
                       (support/instance-tr
                        req
                        :account-settings/week-start-description)
                       week-start-options)
    (preference-select state
                       :time-format
                       "account-preferences-time-format"
                       :account-settings/time-format-label
                       (support/instance-tr
                        req
                        :account-settings/time-format-description)
                       time-format-options)
    (support/feedback state)]])

(defn page [{:keys [db page-state] :as req}]
  (let [title [:i18n/tr :account-settings/preferences-title]
        state (queries/preferences-page-state
               db
               (support/current-member-id req)
               page-state)]
    (support/standard-page
     {:title title
      :actions []}
     [:div {:class "account-preferences wa-stack wa-gap-xl"
            :data-signals (d*/->signals {:account-preferences state})}
      (appearance-section)
      [:form {:id             form-id
              :class          "wa-stack wa-gap-xl"
              :data-id        "account-preferences"
              :data-action    (d*/act req ::actions/save-date-time-preferences)
              :data-on:submit "evt.preventDefault();"}
       (date-time-section req state)
       [button/Button {:id "account-preferences-save"
                       :class "preferences-save"
                       :appearance "filled"
                       :variant "brand"
                       :type "submit"
                       :form form-id}
        [:i18n/tr :account-settings/preferences-save]]]])))

(d*/refresh-all!)
