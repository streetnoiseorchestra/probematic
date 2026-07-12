(ns app.dashboard.index.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.dashboard.queries :as queries]
   [app.datastar :as d*]
   [app.gigs.attendance.ui :as attendance.ui]
   [app.gigs.ui :as gigs.ui]
   [app.insurance.ui :as insurance.ui]
   [app.qrcode :as qr]
   [app.ui2 :as ui2]
   [app.ui2.card :as card]
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]))

(defn- member-nick [{:member/keys [name nick]}]
  (if (str/blank? nick) name nick))

(defn- dashboard-list [class rows]
  (into [:div {:class (ui2/cs "dashboard-list" class)}]
        rows))

(defn- dashboard-section [section-class list-class title rows]
  (when (seq rows)
    [:section {:class section-class}
     (ui2/section-divider title)
     (dashboard-list list-class rows)]))

(defn- dashboard-row [tag attrs class & children]
  (into [tag (update attrs :class #(ui2/cs "dashboard-row" "sno-no-visited" class %))]
        children))

(defn- gig-row [req {:gig/keys [gig-id title status call-time date end-date] :as gig}]
  (let [{:attendance/keys [plan motivation comment member]} (:attendance gig)
        member-id (:member/member-id member)]
    (dashboard-row
     :div
     {}
     "dashboard-gig-row"
     [:div {:class "dashboard-gig-status-cell"}
      (gigs.ui/gig-status-icon status {:class "dashboard-gig-status"})]
     [:div {:class "dashboard-gig-date"}
      [:span (ui2/date-range-display req :compact-with-weekday date end-date)]
      (when-not end-date
        [:span (or (ui2/format-time req :short call-time) "—")])]
     [:a {:href  (urls/link-gig gig)
          :class "dashboard-gig-title"}
      title]
     [:div {:class "dashboard-gig-plan"}
      (attendance.ui/plan-dropdown req gig-id member-id plan)]
     [:div {:class "dashboard-gig-motivation"}
      (attendance.ui/motivation-select req gig-id member-id motivation)]
     [:div {:class (ui2/cs "dashboard-gig-comment"
                           (attendance.ui/comment-class req gig-id member-id comment))}
      (attendance.ui/comment-control req gig-id member-id comment)])))

(defn- gig-section [req title gigs]
  (dashboard-section "dashboard-gig-section"
                     "dashboard-gig-list"
                     title
                     (mapv #(gig-row req %) gigs)))

(defn- insurance-todo-row [{:keys [tr]} {:insurance.policy/keys [name policy-id] :keys [total-needs-review total-changed total-new total-removed] :as policy}]
  (dashboard-row :div {}
                 "dashboard-insurance-todo-row"
                 [:div {:class       "dashboard-insurance-todo-status-cell"
                        :aria-hidden true}]
                 [:div {:class "dashboard-insurance-todo-name"}
                  [:a {:href (urls/link-policy policy)} name]]
                 [:div {:class "dashboard-insurance-todo-metrics"}
                  (insurance.ui/todo-metric tr {:id-prefix    "dashboard-insurance-todo"
                                                :class-prefix "dashboard-insurance-todo"
                                                :policy-id    policy-id
                                                :status       :needs-review
                                                :count        total-needs-review})
                  (insurance.ui/todo-metric tr {:id-prefix    "dashboard-insurance-todo"
                                                :class-prefix "dashboard-insurance-todo"
                                                :policy-id    policy-id
                                                :status       :changed
                                                :count        total-changed})
                  (insurance.ui/todo-metric tr {:id-prefix    "dashboard-insurance-todo"
                                                :class-prefix "dashboard-insurance-todo"
                                                :policy-id    policy-id
                                                :status       :new
                                                :count        total-new})
                  (insurance.ui/todo-metric tr {:id-prefix    "dashboard-insurance-todo"
                                                :class-prefix "dashboard-insurance-todo"
                                                :policy-id    policy-id
                                                :status       :removed
                                                :count        total-removed})]))

(defn- insurance-todos-section [{:keys [tr] :as req} policies]
  (dashboard-section "dashboard-insurance-todo-section"
                     "dashboard-insurance-todo-list"
                     (tr [:dashboard/insurance-todo])
                     (mapv #(insurance-todo-row req %) policies)))

(defn- dashboard-card [class title actions & children]
  (into
   [card/Card {:class (ui2/cs "dashboard-home-card" class)}
    [:h2 {:slot "header"} title]]
   (concat (map #(uic/assoc-attr % :slot "header-actions")
                (filter some? actions))
           children)))

(defn- response-count [unanswered insurance-todos]
  (+ (count unanswered) (count insurance-todos)))

(defn- responses-card [{:keys [tr] :as req} unanswered insurance-todos]
  (let [count (response-count unanswered insurance-todos)]
    (dashboard-card
     "responses"
     [:i18n/tr :my-responses]
     [[:wa-badge {:appearance "filled"
                  :variant    (if (pos? count) "warning" "neutral")
                  :pill       true}
       count]]
     (if (pos? count)
       (list
        (when (seq unanswered)
          (gig-section req
                       [:i18n/tr :gigs/attendance-needed]
                       unanswered))
        (when (seq insurance-todos)
          (insurance-todos-section
           (assoc req :tr tr)
           insurance-todos)))
       [:p {:class "empty"}
        [:i18n/tr :responses-empty]]))))

(defn- upcoming-card [req upcoming]
  (dashboard-card
   "upcoming"
   [:i18n/tr :gigs/upcoming-gigs-rehearsals]
   nil
   (if (seq upcoming)
     (dashboard-list "dashboard-gig-list" (mapv #(gig-row req %) upcoming))
     [:p {:class "empty"}
      [:i18n/tr :gigs/upcoming-empty]])
   [button/Button {:slot       "footer-actions"
                   :appearance "plain"
                   :href       (urls/link-gigs-home)}
    [:i18n/tr :gigs/view-all]]))

(defn- currency-format [cents]
  (ui2/money-format (/ (or cents 0) 100.0) :EUR))

(defn- iban-format [iban]
  (when iban
    (->> (str/replace iban #"\s" "")
         (partition-all 4)
         (map (partial apply str))
         (str/join " "))))

(defn- maybe-transfer-reference [balance entries]
  (when (= balance (:ledger.entry/amount (first entries)))
    (:ledger.entry/description (first entries))))

(defn- payment-qr-value [{:keys [system]} balance entries]
  (let [{:keys [iban bic account-name]} (config/band-bank-info (-> system :env))]
    (when (and (pos? balance) iban bic account-name)
      (qr/sepa-payment-code {:bic                    bic
                             :iban                   iban
                             :name                   account-name
                             :amount                 (/ balance 100.0)
                             :unstructured-reference (maybe-transfer-reference balance entries)}))))

(defn- ledger-widget [{:keys [tr system] :as req} {:ledger/keys [balance entries owner] :as _ledger}]
  (let [{:keys [iban bic account-name]} (config/band-bank-info (-> system :env))]
    (ui2/section-card
     {:title (tr [:dashboard/you-owe])}
     [card/Card {:class "dashboard-ledger-card"}
      [:div {:class "dashboard-ledger-grid"}
       [:div {:class "wa-stack wa-gap-xs"}
        [:span {:class "wa-caption-s"} (tr [:outstanding-balance])]
        [:div {:class "dashboard-ledger-balance text-danger"}
         (currency-format balance)]
        [:a {:href (urls/link-member-money owner)}
         (tr [:why])]]
       [:div {:class "wa-stack wa-gap-s"}
        [:p (tr [:please-pay-to-band] [(currency-format balance)])]
        (when (and account-name iban bic)
          [:div {:class "wa-stack wa-gap-3xs"}
           [:span "Name: " [:strong account-name]]
           [:span "IBAN: " [:strong (iban-format iban)]]
           [:span "BIC: " [:strong bic]]
           [:span (tr [:or-scan-qr-code])]])]
       (when-let [qr-value (payment-qr-value req balance entries)]
         [:div {:class "dashboard-ledger-qr"}
          [:wa-qr-code {:value qr-value
                        :label (tr [:or-scan-qr-code])}]])]])))

(def ^:private activity-fixtures
  [{:icon :calendar
    :title :activity/rehearsal-updated-title
    :detail :activity/rehearsal-updated-detail
    :when :activity/rehearsal-updated-when}
   {:icon :question
    :title :activity/poll-created-title
    :detail :activity/poll-created-detail
    :when :activity/poll-created-when}
   {:icon :music-note-outline
    :title :activity/repertoire-updated-title
    :detail :activity/repertoire-updated-detail
    :when :activity/repertoire-updated-when}])

(defn- greeting [member]
  [:i18n/tr
   (keyword (str "greeting-"
                 (name (util/time-window (util/local-time-austria!)))))
   {:name (member-nick member)}])

(defn- quick-actions []
  [:div {:class "quick-actions"}
   [button/Button {:class      "dashboard-home-quick-action"
                   :appearance "outlined"
                   :href       (urls/link-gig-create)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :circle-plus-solid
               :slot         "start"}]
    [:i18n/tr :gigs/new-gig]]
   [button/Button {:class      "dashboard-home-quick-action"
                   :appearance "outlined"
                   :href       (urls/link-polls-create)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :circle-plus-solid
               :slot         "start"}]
    [:i18n/tr :polls/new-poll]]])

(defn- activity-entry [{:keys [detail icon title when]}]
  [:li {:class "activity-entry"}
   [:div {:class "icon" :aria-hidden "true"}
    [ico/Icon {::ico/library :snoico
               ::ico/name    icon}]]
   [:div {:class "copy"}
    [:strong [:i18n/tr title]]
    [:p [:i18n/tr detail]]
    [:span [:i18n/tr when]]]])

(defn- activity-preview []
  [:aside {:class "activity"}
   [:div {:class "activity-heading"}
    [:h2 [:i18n/tr :activity/recent]]
    [button/Button {:appearance "plain"
                    :size       "small"
                    :disabled   true}
     [:i18n/tr :action/view-all]]]
   (into [:ol] (map activity-entry activity-fixtures))])

(defn- home-content
  [req member {:keys [insurance-todos ledger unanswered upcoming]}]
  [:div {:class        "dashboard-home"
         :data-signals (d*/->signals (attendance.ui/attendance-signals req))}
   [:section {:class "personal"}
    [:h1 {:class "greeting"} (greeting member)]
    [:h1 {:class "mobile-title"}
     [:i18n/tr :gigs/title]]
    (quick-actions)]
   [:section {:class "focus"}
    [:div {:class "wa-stack wa-gap-l"}
     (responses-card req unanswered insurance-todos)
     (when (and ledger (pos? (:ledger/balance ledger)))
       (ledger-widget req ledger))
     (upcoming-card req upcoming)]]
   (activity-preview)])

(defn page [{:keys [db] :as req}]
  (let [member (auth/get-current-member req)
        _      (assert member)
        data   (queries/dashboard-data db member)]
    (ui2/datastar-page*
     (home-content req member data))))

(d*/refresh-all!)
