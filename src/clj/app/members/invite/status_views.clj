(ns app.members.invite.status-views
  (:require
   [app.html :as html]
   [app.layout2 :as layout]
   [app.ui2.button :as button]
   [app.ui2.icon :as icon]
   [jsonista.core :as json]))

(defn fragment [{:keys [status]} invite-code login-url]
  [:section {:id        "invitation-status"                                                                                :class "wa-stack wa-gap-m wa-align-items-center wa-text-center"
             :style     {:max-width "30rem"}                                                                               :role  "status"                                                 :aria-live "polite" :aria-atomic "true"
             :data-init (str "window.StreetnoiseInvitationStatus.show(el," (json/write-value-as-string (name status)) ")")}
   (case status
     :creating [:wa-spinner {:style {:font-size "3rem" :--speed "1.6s"} :aria-hidden "true"}]
     :accepted [:span {:id    "invitation-success-mark"                                     :aria-hidden "true"
                       :style {:font-size "3rem" :color "var(--wa-color-success-on-quiet)"}}
                [icon/Icon {::icon/library :phosphor ::icon/name :check}]]
     nil)
   [:h1 {:style {:margin 0}}
    [:i18n/tr (case status
                :creating :members/invite-setup-creating-title
                :accepted :members/invite-setup-accepted-title
                :pending :members/invite-setup-retry-title
                :operator-required :members/invite-setup-help-title
                :invitation-expired)]]
   (case status
     :accepted
     (list
      [:p {:style {:margin 0}} [:i18n/tr :members/invite-setup-accepted-body]]
      [button/Button {:id "invitation-continue" :href login-url :appearance "plain"}
       [:i18n/tr :members/invite-setup-continue]])
     :pending
     (list
      [:p {:style {:margin 0}} [:i18n/tr :members/invite-setup-retry-body]]
      [:form {:method "POST" :action "/invite-accept"}
       [:input {:type "hidden" :name "invite-code" :value invite-code}]
       [button/Button {:type "submit" :appearance "filled" :variant "brand"}
        [:i18n/tr :members/invite-setup-retry]]])
     :operator-required [:p {:style {:margin 0}} [:i18n/tr :members/invite-setup-help-body]]
     nil)])

(defn fragment-html [req projection invite-code login-url]
  (html/->str (:tr req) (fragment projection invite-code login-url)))

(defn page [req projection invite-code login-url stream-url]
  (layout/html5-response
   req
   {:extra-head [(layout/public-script req "js/invitation-status.js" :defer false)]}
   [:body {:style {:margin 0}}
    [:main (cond-> {:class "wa-stack wa-align-items-center wa-justify-content-center"
                    :style {:min-height "100svh" :padding "var(--wa-space-xl)" :box-sizing "border-box"}}
             (not= :accepted (:status projection))
             (assoc :data-init (str "@get(" (json/write-value-as-string stream-url) ")")))
     (fragment projection invite-code login-url)]]))
