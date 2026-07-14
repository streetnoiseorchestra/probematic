(ns app.account.profile.views
  (:require
   [app.account.actions :as actions]
   [app.account.queries :as queries]
   [app.account.view-support :as support]
   [app.datastar :as d*]
   [app.ui2.avatar :as avatar]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [clojure.string :as str]))

(def form-id "account-profile-form")

(defn- validate-field-action [req field]
  (str "$account-profile.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-profile-field)
       "')"))

(defn- validation-attrs [req field]
  {:data-on:blur (validate-field-action req field)})

(defn- profile-field [req state field id label attrs & [description]]
  (support/field
   {:state       state
    :root        "account-profile"
    :field       field
    :id          id
    :label       [:i18n/tr label]
    :description (when description [:i18n/tr description])
    :attrs       (merge attrs (validation-attrs req field))}))

(defn- avatar-section [req member state]
  (let [staged?         (true? (get-in state [:avatar :staged?]))
        removed?        (true? (:avatar-removed? state))
        current-avatar? (boolean (get-in member [:member/avatar :image/image-id]))
        real-avatar?    (and current-avatar? (not removed?))
        avatar-present? (or staged? real-avatar?)
        preview-member  (cond-> member
                          true (dissoc :member/avatar-template)
                          (not real-avatar?) (dissoc :member/avatar))]
    [:div {:class "avatar-editor wa-stack wa-gap-m wa-align-items-center"}
     [:div {:class "avatar-frame"}
      [avatar/Avatar {::avatar/member preview-member
                      ::avatar/link? false
                      ::avatar/allow-legacy? false
                      ::avatar/icon :user
                      ::avatar/image-size 160
                      :class "profile-avatar"}]
      [:img {:id                 "account-profile-avatar-preview"
             :class              (str "avatar-preview" (when staged? " staged"))
             :alt                [:i18n/tr :account-settings/avatar-label]
             :data-preserve-attr "src class"}]]
     (when-not avatar-present?
       [:div {:class "avatar-empty-prompt wa-stack wa-gap-2xs wa-align-items-center"}
        [:wa-callout {:class "avatar-empty" :appearance "outlined" :variant "warning"}
         [ico/Icon {::ico/library :snoico
                    ::ico/name :smile
                    :slot "icon"
                    :style "color: var(--wa-color-brand-fill-loud);"}]
         [:i18n/tr :account-settings/avatar-empty-callout]]
        [ico/Icon {::ico/library :phosphor
                   ::ico/name :arrow-fat-down
                   :class "avatar-upload-arrow"
                   :aria-hidden "true"}]])
     [:div {:class "avatar-actions wa-stack wa-gap-xs wa-align-items-center"}
      [button/Button {:appearance "outlined"
                      :type "button"
                      :data-on:click "document.getElementById('account-profile-avatar').click()"}
       [:i18n/tr (if avatar-present?
                   :account-settings/avatar-replace
                   :account-settings/avatar-choose)]]
      [:input {:id             "account-profile-avatar"
               :class          "wa-visually-hidden"
               :type           "file"
               :name           "avatar"
               :accept         "image/png,image/jpeg,image/webp"
               :data-on:change (str "if (window.StreetnoiseAccountAvatar.stage(evt, $account-profile)) "
                                    "@post('" (d*/act req ::actions/stage-avatar) "')")}]
      (when avatar-present?
        [button/Button {:appearance    "plain"
                        :variant       "brand"
                        :class         "avatar-remove-action"
                        :data-on:click (str "window.StreetnoiseAccountAvatar.remove($account-profile); "
                                            "@post('" (d*/act req ::actions/remove-avatar) "')")}
         [:i18n/tr :account-settings/avatar-remove]])]
     (when-let [error (support/field-error state :avatar)]
       [:p {:class "wa-caption-s wa-color-text-danger" :role "alert"} error])
     [:input {:id "account-profile-avatar-removed"
              :type "hidden"
              :name "avatar-removed?"
              :data-bind "account-profile.avatar-removed?"}]
     (support/feedback state)]))

(defn- profile-fields [req state]
  [:div {:class "wa-stack wa-gap-l"}
   [:div {:class "account-profile-fields wa-stack wa-gap-m"}
    (profile-field req state :name "account-profile-name"
                   :account-settings/name-label {:type "text" :required true})
    (profile-field req state :nick "account-profile-nick"
                   :account-settings/nickname-label {:type "text"})
    (profile-field req state :email "account-profile-email"
                   :account-settings/email-label {:type "email" :required true})
    (profile-field req state :username "account-profile-username"
                   :account-settings/username-label {:type "text" :required true})
    (profile-field req state :phone "account-profile-phone"
                   :account-settings/phone-label {:type "tel"})]
   [:div {:class "account-profile-fields wa-stack wa-gap-m"}
    (profile-field req state :current-status "account-profile-current-status"
                   :account-settings/current-status-label {:type "text"})
    (profile-field req state :date-of-birth "account-profile-date-of-birth"
                   :account-settings/date-of-birth-label
                   {:type "date" :autocomplete "bday"})]])

(defn- security-account-url [req]
  (let [{:keys [auth-server-url realm]}
        (get-in req [:system :env :keycloak])]
    (str (str/replace auth-server-url #"/+$" "")
         "/realms/" realm "/account")))

(defn- security-section [req]
  [:div {:class "account-profile-fields wa-stack wa-gap-s"}
   [:p (support/instance-tr
        req
        :account-settings/login-security-description)]
   [:a {:href (security-account-url req)
        :target "_blank"
        :rel "noopener"}
    (support/instance-tr req :account-settings/login-security-link)]])

(defn page [{:keys [db page-state] :as req}]
  (let [title             [:i18n/tr :account-settings/profile-title]
        current-member-id (support/current-member-id req)
        member            (queries/current-member db current-member-id)
        state             (queries/profile-page-state db current-member-id page-state)]
    (support/standard-page
     {:title        title
      :show-header? false
      :actions      [(support/save-action form-id [:i18n/tr :action/save])]}
     [:form {:id             form-id
             :class          "wa-stack wa-gap-l"
             :method         "post"
             :action         "/account-settings/profile/save"
             :enctype        "multipart/form-data"
             :data-id        "account-profile"
             :data-signals   (d*/->signals {:account-profile state})
             :data-on:submit
             (str "evt.preventDefault(); "
                  "document.getElementById('account-profile-tab-id').value = $tab-id; "
                  "@post('/account-settings/profile/save', {contentType: 'form'})")}
      [:input {:id "account-profile-tab-id"
               :type "hidden"
               :name "tab-id"}]
      (when-let [top-error (support/field-error state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"} top-error])
      [card/Card {:class "account-profile-card" :appearance "outlined"}
       [:div {:class "wa-stack wa-gap-xl"}
        (avatar-section req member state)
        (profile-fields req state)
        (security-section req)]]])))

(d*/refresh-all!)
