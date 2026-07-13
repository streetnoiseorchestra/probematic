(ns app.account.profile.views
  (:require
   [app.account.actions :as actions]
   [app.account.queries :as queries]
   [app.account.view-support :as support]
   [app.datastar :as d*]
   [app.ui2.avatar :as avatar]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]))

(def form-id "account-profile-form")

(defn- validate-field-action [req field]
  (str "$account-profile.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-profile-field)
       "')"))

(defn- validation-attrs [req field]
  {:data-on:blur (validate-field-action req field)
   :data-on:input__debounce.500ms (validate-field-action req field)})

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
        current-avatar? (boolean (seq (:member/avatar-template member)))
        real-avatar?    (and current-avatar? (not removed?))
        avatar-present? (or staged? real-avatar?)
        preview-member  (cond-> member
                          (not real-avatar?) (dissoc :member/avatar-template))]
    [card/Card {:class "account-profile-avatar" :appearance "outlined"}
     [:h2 {:slot "header"} [:i18n/tr :account-settings/avatar-label]]
     [:div {:class "avatar-editor wa-stack wa-gap-m wa-align-items-center"}
      [:div {:class "avatar-frame"}
       [avatar/Avatar {::avatar/member preview-member
                       ::avatar/link? false
                       ::avatar/icon :user
                       ::avatar/image-size 160
                       :class "profile-avatar"}]
       [:img {:id                 "account-profile-avatar-preview"
              :class              (str "avatar-preview" (when staged? " staged"))
              :src                (when staged?
                                    (avatar/avatar-template-src
                                     (:member/avatar-template member)))
              :alt                [:i18n/tr :account-settings/avatar-label]
              :data-preserve-attr "src class"}]]
      (when-not avatar-present?
        [:div {:class "avatar-empty-prompt wa-stack wa-gap-2xs wa-align-items-center"}
         [:wa-callout {:class "avatar-empty" :appearance "outlined" :variant "warning"}
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
      (support/feedback state)]]))

(defn- profile-fields [req state]
  [card/Card {:appearance "outlined"}
   [:h2 {:slot "header"} [:i18n/tr :account-settings/profile-title]]
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
                    {:type "date" :autocomplete "bday"})]]])

(defn- security-section [req]
  [card/Card {:appearance "outlined"}
   [:h2 {:slot "header"} [:i18n/tr :account-settings/login-security-title]]
   [:div {:class "wa-stack wa-gap-s"}
    [:p (support/instance-tr
         req
         :account-settings/login-security-description)]
    [:a {:href "https://id.streetnoise.at/realms/sno/account"
         :target "_blank"
         :rel "noopener"}
     (support/instance-tr req :account-settings/login-security-link)]]])

(defn page [{:keys [db page-state] :as req}]
  (let [title             [:i18n/tr :account-settings/profile-title]
        current-member-id (support/current-member-id req)
        member            (queries/current-member db current-member-id)
        state             (queries/profile-page-state db current-member-id page-state)]
    (support/standard-page
     {:title    title
      :subtitle [:i18n/tr :account-settings/profile-subtitle]
      :actions  [(support/save-action form-id [:i18n/tr :action/save])]}
     [:form {:id             form-id
             :class          "wa-stack wa-gap-l"
             :data-id        "account-profile"
             :data-action    (d*/act req ::actions/save-profile)
             :data-signals   (d*/->signals {:account-profile state})
             :data-on:submit "evt.preventDefault();"}
      (when-let [top-error (support/field-error state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"} top-error])
      (avatar-section req member state)
      (profile-fields req state)]
     (security-section req))))

(d*/refresh-all!)
