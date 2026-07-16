(ns app.members.invite-accept.views
  (:require
   [app.members.domain :as members.domain]
   [app.members.invite-accept.service :as service]
   [app.ui2 :as ui2]))

(defn- page-shell [req description & body]
  (apply ui2/standalone-page
         {:title       "SNOrga"
          :description description
          :lang        (some-> req :current-locale name)
          :translator  (:tr req)}
         body))

(defn- invalid-page [req]
  (page-shell
   req
   [:i18n/tr :invitation-expired]
   [:header {:class "danger"}
    [:p "SNO ID"]
    [:h1 [:i18n/tr :invitation-expired]]]))

(defn- invite-form [req {:keys [member invite-code]}]
  (page-shell
   req
   [:i18n/tr :members/invite-accept-create-subtitle]
   [:header
    [:p "SNOrga"]
    [:h1 [:i18n/tr :members/invite-accept-create-title]]]
   [:p [:i18n/tr :members/invite-accept-create-subtitle]]
   [:dl
    [:dt [:i18n/tr (members.domain/member-attribute-label-key :member/email)]]
    [:dd [:code (:member/email member)]]]
   [:footer
    [:form {:action "/invite-accept"
            :method "POST"}
     [:input {:type "hidden" :name "invite-code" :value invite-code}]
     [:button {:type "submit"}
      [:i18n/tr :members/invite-accept-create-account]]]]))

(defn- success-page [req member]
  (page-shell
   req
   [:i18n/tr :members/invite-accept-created-subtitle]
   [:header
    [:p "SNOrga"]
    [:h1 [:i18n/tr :members/invite-accept-created-title]]]
   [:p [:i18n/tr :members/invite-accept-created-subtitle]]
   [:footer
    [:a {:href (service/login-link req member)}
     [:i18n/tr :login]]]))

(defn invite-accept [req]
  (if-let [invite-data (service/load-invite req)]
    (invite-form req invite-data)
    (invalid-page req)))

(defn invite-accept-post [req]
  (try
    (success-page req (service/setup-account! req))
    (catch Throwable e
      (if (= :code-expired (-> e ex-data :reason))
        (invalid-page req)
        (throw e)))))
