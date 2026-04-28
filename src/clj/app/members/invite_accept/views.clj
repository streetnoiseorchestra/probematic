(ns app.members.invite-accept.views
  (:require
   [app.i18n :as i18n]
   [app.layout2 :as layout2]
   [app.members.invite-accept.service :as service]))

(defn- tr [req]
  (or (:tr req)
      (i18n/tr-from-req req)))

(defn- page-shell [req body]
  (layout2/html5-response
   req
   {:title "SNOrga"}
   [:body
    [:main {:id "main" :class "members-invite-accept-page"}
     [:section {:class "members-invite-accept-card wa-stack wa-gap-l"}
      [:div {:class "wa-stack wa-gap-s wa-align-items-center"}
       [:wa-icon {:library "snoico"
                  :name    "logotype"
                  :class   "members-invite-accept-logo"}]]
      body]]]))

(defn- invalid-page [req]
  (let [tr (tr req)]
    (page-shell
     req
     [:wa-callout {:appearance "outlined" :variant "danger"}
      (tr [:email/invite-expired])])))

(defn- invite-form [req {:keys [member invite-code]}]
  (let [tr (tr req)]
    (page-shell
     req
     [:form {:class "wa-stack wa-gap-l"
             :action "/invite-accept"
             :method "POST"}
      [:input {:type "hidden" :name "invite-code" :value invite-code}]
      [:div {:class "wa-stack wa-gap-2xs"}
       [:h1 {:class "wa-heading-l"} (tr [:account/create-sno-id-title])]
       [:p {:class "wa-body-m wa-color-text-quiet"}
        (tr [:account/create-sno-id-subtitle])]]
      [:wa-input {:label      (tr [:member/email])
                  :type       "email"
                  :value      (:member/email member)
                  :disabled   true
                  :appearance "outlined"}]
      [:wa-button {:appearance "filled"
                   :variant    "brand"
                   :type       "submit"}
       (tr [:account/create-account])]])))

(defn- success-page [req member]
  (let [tr (tr req)]
    (page-shell
     req
     [:div {:class "wa-stack wa-gap-l"}
      [:div {:class "wa-stack wa-gap-2xs"}
       [:h1 {:class "wa-heading-l"} (tr [:account/account-created-title])]
       [:p {:class "wa-body-m wa-color-text-quiet"}
        (tr [:account/account-created-subtitle])]]
      [:wa-button {:appearance "filled"
                   :variant    "brand"
                   :href       (service/login-link req member)}
       (tr [:login])]])))

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
