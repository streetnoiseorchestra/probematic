(ns app.members.invite.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.i18n :as i18n]
   [app.members.invite.actions :as actions]
   [app.members.invite.workflows :as workflows]
   [app.members.queries :as members.queries]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util :as util]
   [tick.core :as t]))

(defn- default-form-state []
  {:name          ""
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
    [:wa-input (merge {:label              label
                       :appearance         "outlined"
                       :size               "m"
                       :value              (get form-state field "")
                       :hint               error
                       :data-invalid       (when error "true")
                       :data-bind          signal
                       :data-preserve-attr "value"}
                      attrs)]))

(defn- validate-field-action [req field]
  (str "$member-invite.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-member-invite-field)
       "')"))

(defn- validate-input-attrs [req field]
  (let [action (validate-field-action req field)]
    {:data-on:blur                    action
     :data-on:input__debounce.500ms action}))

(defn- section-select [{:keys [tr] :as req} form-state sections]
  (let [error  (form/field-error form-state :error :section-name)
        action (validate-field-action req :section-name)]
    (into
     [:wa-select {:label              (tr [:section])
                  :appearance         "outlined"
                  :size               "m"
                  :value              (:section-name form-state)
                  :hint               error
                  :data-invalid       (when error "true")
                  :data-bind          "member-invite.section-name"
                  :data-preserve-attr "value"
                  :data-on:blur       action}
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
    (form-input form-state
                "member-invite.name"
                (tr [:member/name])
                (merge {:required true :autofocus true}
                       (validate-input-attrs req :name)))
    (form-input form-state
                "member-invite.nick"
                (tr [:member/nick])
                (validate-input-attrs req :nick))
    (form-input form-state
                "member-invite.email"
                (tr [:Email])
                (merge {:type "email" :required true}
                       (validate-input-attrs req :email)))
    (form-input form-state
                "member-invite.username"
                (tr [:member/username])
                (merge {:required true}
                       (validate-input-attrs req :username)))
    (form-input form-state
                "member-invite.phone"
                (tr [:Phone])
                (merge {:type "tel" :required true}
                       (validate-input-attrs req :phone)))
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
       [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                  [breadcrumb/Breadcrumb {}
                                   [breadcrumb/BreadcrumbItem {::breadcrumb/href "/members"}
                                    [:i18n/tr :members/title]]
                                   [breadcrumb/BreadcrumbItem [:i18n/tr :members/invite-member]]]
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
      [:div {:class              "wa-stack wa-gap-2xl"
             :data-signals       (d*/->signals {:member-invite form-state})
             :data-preserve-attr "data-signals"}
       [page-header/PageHeader
        {::page-header/title    [:i18n/tr :members/invite-member]
         ::page-header/subtitle [:i18n/tr :members/invite-description]}]
       (invite-form req form-state sections)]])))

(defn- param [req k]
  (or (get-in req [:params k])
      (get-in req [:params (name k)])))

(defn invite-code [req]
  (param req :code))

(defn form-invite-code [req]
  (or (param req :invite-code)
      (invite-code req)))

(defn load-invite [req]
  (let [db          (:db req)
        invite-code (form-invite-code req)]
    (or (some-> (members.queries/acceptance-invitation
                 db
                 (or (:now req) (t/inst))
                 invite-code)
                (select-keys [:member :invite-code]))
        (some-> (members.queries/accepted-invitation-by-code db invite-code)
                (select-keys [:member])
                (assoc :invite-accepted? true)))))

(defn login-link [req member]
  (str (urls/absolute-link-login (get-in req [:system :env]))
       "?login_hint="
       (some-> (:member/email member) util/url-encode)))

(defn- tr [req]
  (or (:tr req)
      (i18n/tr-from-req req)))

(defn- page-shell [description & body]
  (apply ui2/standalone-page
         {:title       "SNOrga"
          :description description}
         body))

(defn- invalid-page [req]
  (let [tr (tr req)]
    (page-shell
     (tr [:email/invite-expired])
     [:header {:class "danger"}
      [:p "SNO ID"]
      [:h1 (tr [:email/invite-expired])]])))

(defn- accept-invite-form [req {:keys [member invite-code]}]
  (let [tr (tr req)]
    (page-shell
     (tr [:account/create-sno-id-subtitle])
     [:header
      [:p "SNOrga"]
      [:h1 (tr [:account/create-sno-id-title])]]
     [:p (tr [:account/create-sno-id-subtitle])]
     [:dl
      [:dt (tr [:member/email])]
      [:dd [:code (:member/email member)]]]
     [:footer
      [:form {:action "/invite-accept"
              :method "POST"}
       [:input {:type "hidden" :name "invite-code" :value invite-code}]
       [:button {:type "submit"}
        (tr [:account/create-account])]]])))

(defn- success-page [req member]
  (let [tr (tr req)]
    (page-shell
     (tr [:account/account-created-subtitle])
     [:header
      [:p "SNOrga"]
      [:h1 (tr [:account/account-created-title])]]
     [:p (tr [:account/account-created-subtitle])]
     [:footer
      [:a {:href (login-link req member)}
       (tr [:login])]])))

(defn invite-accept [req]
  (if-let [invite-data (load-invite req)]
    (if (:invite-accepted? invite-data)
      (success-page req (:member invite-data))
      (accept-invite-form req invite-data))
    (invalid-page req)))

(defn invite-accept-post [req]
  (try
    (success-page req (workflows/setup-account! req))
    (catch Throwable exception
      (case (-> exception ex-data :reason)
        :code-expired
        (invalid-page req)

        :acceptance-retry
        (invite-accept req)

        (throw exception)))))

(d*/refresh-all!)
