(ns app.members.invite-accept.views
  (:require
   [app.i18n :as i18n]
   [app.members.invite-accept.service :as service]
   [app.ui2 :as ui2]))

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

(defn- invite-form [req {:keys [member invite-code]}]
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
      [:a {:href (service/login-link req member)}
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
