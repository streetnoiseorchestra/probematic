(ns app.members.invite.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.members.invite.actions :as actions]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]))

(defn- default-form-state []
  {:member-id     (str (random-uuid))
   :name          ""
   :nick          ""
   :email         ""
   :username      ""
   :phone         ""
   :section-name  ""
   :active        true
   :create-sno-id true
   :error         {}})

(defn- form-input [form-state signal label attrs]
  (let [field (form/signal-field signal)
        error (form/field-error form-state :error field)]
    [:wa-input (merge {:label        label
                       :appearance   "outlined"
                       :size         "m"
                       :value        (get form-state field "")
                       :hint         error
                       :data-invalid (when error "true")
                       :data-bind    signal}
                      attrs)]))

(defn- section-select [{:keys [tr]} form-state sections]
  (let [error (form/field-error form-state :error :section-name)]
    (into
     [:wa-select {:label          (tr [:section])
                  :appearance     "outlined"
                  :size           "m"
                  :value          (:section-name form-state)
                  :hint           error
                  :data-invalid   (when error "true")
                  :data-bind      "member-invite.section-name"}
      [:wa-option {:value ""} " - "]]
     (for [{:section/keys [name]} sections]
       [:wa-option {:value name} name]))))

(defn- toggle-field [signal label description checked?]
  [:div {:class "wa-stack wa-gap-2xs"}
   [:wa-switch {:size           "m"
                :checked        checked?
                :data-bind      signal
                :data-on:change (str "$" signal " = !$" signal)}
    label]
   (when description
     [:span {:class "wa-caption-s"} description])])

(defn- invite-form [{:keys [tr] :as req} form-state sections]
  [:form {:id             "member-invite-form"
          :data-id        "member-invite"
          :data-action    (d*/act req ::actions/submit-member-invite)
          :data-on:submit "evt.preventDefault();"}
   [:div {:class "wa-stack wa-gap-l"}
    (when-let [top-error (form/field-error form-state :error :_top)]
      [:wa-callout {:appearance "outlined" :variant "danger"}
       top-error])
    (form-input form-state "member-invite.name" (tr [:member/name]) {:required true :autofocus true})
    (form-input form-state "member-invite.nick" (tr [:member/nick]) {})
    (form-input form-state "member-invite.email" (tr [:Email]) {:type "email" :required true})
    (form-input form-state "member-invite.username" (tr [:member/username]) {:required true})
    (form-input form-state "member-invite.phone" (tr [:Phone]) {:type "tel" :required true})
    (section-select req form-state sections)
    (toggle-field "member-invite.create-sno-id"
                  (tr [:member/create-sno-id])
                  (tr [:member/create-sno-id-description])
                  (:create-sno-id form-state))
    (toggle-field "member-invite.active"
                  (tr [:Active])
                  nil
                  (:active form-state))]])

(defn page [{:keys [db page-state] :as req}]
  (let [form-state (merge (default-form-state) (:member-invite page-state))
        sections   (q/retrieve-sections db)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :standard
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href "/members"}
           [:i18n/tr :members/title]]
          [breadcrumb/BreadcrumbItem [:i18n/tr :members/invite-member]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  "/members"
                             :label [:i18n/tr :members/title]}]
         ::page-toolbar/actions
         [[button/Button {:appearance "plain"
                          :href       "/members"}
           [:i18n/tr :action/cancel]]
          [button/Button {:appearance         "filled"
                          :variant            "brand"
                          :type               "submit"
                          :form               "member-invite-form"
                          :data-attr:disabled "!!$loading && $loading !== 'member-invite'"
                          :data-attr:loading  "$loading === 'member-invite'"}
           [:i18n/tr :members/invite-member]]]
         :aria-label [:i18n/tr :members/invite-toolbar-label]}]}
      [:div {:class        "wa-stack wa-gap-2xl"
             :data-signals (d*/->signals {:member-invite form-state})}
       [page-header/PageHeader
        {::page-header/title    [:i18n/tr :members/invite-member]
         ::page-header/subtitle [:i18n/tr :members/invite-description]}]
       (invite-form req form-state sections)]])))

(d*/refresh-all!)
