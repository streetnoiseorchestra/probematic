(ns app.insurance.policy.notifications.views
  (:require
   [app.datastar :as d*]
   [app.email :as email]
   [app.insurance.policy.notifications.actions :as actions]
   [app.insurance.policy.notifications.queries :as queries]
   [app.insurance.ui :as insurance.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [clojure.string :as str]))

(defn- payments-table
  [tr members-data available-member-ids]
  (let [selected-ids-js  "($insurancePayments.memberIds || [])"
        all-ids-js       (pr-str available-member-ids)
        all-selected-js  (if (seq available-member-ids)
                           (str all-ids-js ".every(id => " selected-ids-js ".includes(id))")
                           "false")
        some-selected-js (if (seq available-member-ids)
                           (str all-ids-js ".some(id => " selected-ids-js ".includes(id))")
                           "false")]
    (ui2/table-shell
     [:table {:class "wa-table"}
      [:caption {:class "wa-visually-hidden"}
       (tr [:insurance/payment-members-title])]
      [:thead
       [:tr
        [:th {:scope "col"}
         [:input {:type           "checkbox"
                  :aria-label     (tr [:action/select-all])
                  :checked        (boolean (seq available-member-ids))
                  :disabled       (empty? available-member-ids)
                  :data-effect    (str "el.checked = " all-selected-js "; "
                                       "el.indeterminate = " some-selected-js " && !" all-selected-js)
                  :data-on:change (str "$insurancePayments.memberIds = "
                                       "evt.target.checked ? " all-ids-js " : []")}]]
        [:th {:scope "col"} (tr [:insurance/member])]
        [:th {:scope "col"
              :style "text-align: end; white-space: normal;"}
         (tr [:insurance/private-instruments])]
        [:th {:scope "col"
              :style "text-align: end; white-space: normal;"}
         (tr [:insurance/total])]]]
      (into
       [:tbody]
       (for [{:keys [count-private member private-cost-total
                     private-costs-available?]}
             members-data
             :let [member-id (str (:member/member-id member))
                   unavailable-id (str "payment-cost-unavailable-" member-id)]]
         [:tr
          [:td
           [:input (cond-> {:type       "checkbox"
                            :value      member-id
                            :aria-label (tr [:insurance/select-member-for-payment]
                                            {:member-name (:member/name member)})}
                     private-costs-available?
                     (assoc :checked   true
                            :data-bind "insurancePayments.memberIds")

                     (not private-costs-available?)
                     (assoc :disabled         true
                            :aria-describedby unavailable-id))]]
          [:th {:scope "row"} (insurance.ui/member-link member)]
          [:td {:style "text-align: end;"} count-private]
          [:td {:style "text-align: end;"}
           (if private-costs-available?
             (ui2/money (/ private-cost-total 100M) :EUR)
             [:span {:id    unavailable-id
                     :class "wa-caption-s wa-color-text-quiet"}
              (tr [:insurance/cost-unavailable])])]]))])))

(defn page
  [{:keys [db policy tr] :as req}]
  (let [policy-id       (:insurance.policy/policy-id policy)
        current-member-id
        (get-in req [:session :session/member :member/member-id])
        {:keys [members-data sender-name time-range]}
        (queries/notification-data db policy-id current-member-id)
        available-members-data (filterv :private-costs-available? members-data)
        available-member-ids   (mapv #(str (get-in % [:member :member/member-id]))
                                     available-members-data)
        missing-category-names (->> members-data
                                    (mapcat :missing-category-names)
                                    distinct
                                    sort
                                    vec)
        result                 (get-in req [:page-state actions/form-key :result])
        sent?                  (= :sent (:status result))
        toolbar-actions        (if sent?
                                 [[button/Button {:appearance "filled"
                                                  :variant    "brand"
                                                  :href       (urls/link-policy policy)}
                                   [:i18n/tr :action/done]]]
                                 [[button/Button {:appearance "outlined"
                                                  :href       (urls/link-policy policy)}
                                   [:i18n/tr :action/cancel]]
                                  [button/Button {:appearance         "filled"
                                                  :variant            "brand"
                                                  :type               "submit"
                                                  :form               "insurance-payment-notifications-form"
                                                  :data-attr:disabled "$loading || ($insurancePayments.memberIds || []).length === 0"
                                                  :data-attr:loading  "$loading === 'insurance-payment-notifications'"}
                                   [:i18n/tr :insurance/send-payment-notifications]]])]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :wide
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
           [:i18n/tr :insurance/title]]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
           (:insurance.policy/name policy)]
          [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/request-payments-title]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy policy)
                             :label (:insurance.policy/name policy)}]
         ::page-toolbar/actions toolbar-actions
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class              "wa-stack wa-gap-xl"
             :data-preserve-attr "data-signals"
             :data-signals       (d*/->signals
                                  {actions/signal-key
                                   {:policyId  (str policy-id)
                                    :memberIds available-member-ids}})}
       [page-header/PageHeader
        {:title    (tr [:insurance/request-payments-title])
         :subtitle (tr [:insurance/request-payments-subtitle])}]
       (if sent?
         (ui2/section-card
          {:title (tr [:insurance/payment-notifications-sent-title])}
          [:wa-callout {:appearance "outlined"
                        :variant    "success"
                        :role       "status"
                        :aria-live  "polite"}
           (tr [:insurance/payment-notifications-sent]
               {:count (:count-sent result)})])
         [:form {:id             "insurance-payment-notifications-form"
                 :class          "wa-stack wa-gap-xl"
                 :data-id        "insurance-payment-notifications"
                 :data-action    (d*/act req ::actions/send-notifications)
                 :data-on:submit "evt.preventDefault()"}
          (when (= :error (:status result))
            [:wa-callout {:appearance "outlined"
                          :variant    "danger"
                          :role       "alert"}
             (:message result)])
          (when (seq missing-category-names)
            [:wa-callout {:appearance "outlined"
                          :variant    "warning"}
             (tr [:insurance/payments-missing-category-factors]
                 {:category-names (str/join ", " missing-category-names)})])
          (if (seq members-data)
            (ui2/section-card
             {:title    (tr [:insurance/payment-members-title])
              :subtitle (tr [:insurance/payment-members-subtitle])}
             (payments-table tr members-data available-member-ids))
            (ui2/empty-state
             (tr [:insurance/no-private-payments-title])
             (tr [:insurance/no-private-payments])))
          (when-let [sample-data (first available-members-data)]
            (ui2/section-card
             {:title    (tr [:insurance/payment-email-preview-title])
              :subtitle (tr [:insurance/payment-email-preview-subtitle])}
             [card/Card {:appearance "filled-outlined"
                         :style      "max-inline-size: 75ch;"}
              [:div {:class "wa-prose"
                     :style "overflow-x: auto;"}
               (email/render-insurance-debt-email-template
                req
                sender-name
                time-range
                sample-data)]]))])]])))

(d*/refresh-all!)
