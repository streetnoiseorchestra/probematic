(ns app.dashboard.index.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.dashboard.queries :as queries]
   [app.datastar :as d*]
   [app.gigs.attendance.ui :as attendance.ui]
   [app.gigs.ui :as gigs.ui]
   [app.icons :as icon]
   [app.qrcode :as qr]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]
   [tick.core :as t])
  (:import
   (java.text NumberFormat)
   (java.util Locale)))

(defn- member-nick [{:member/keys [name nick]}]
  (if (str/blank? nick) name nick))

(defn- js-value [value]
  (d*/->signals value))

(defn- date-value [dt]
  (when dt
    (t/date (if (inst? dt)
              (t/date-time dt)
              dt))))

(defn- formatted-date [dt]
  (if-let [date (date-value dt)]
    (t/format (t/formatter "E dd MMM yyyy" Locale/GERMAN) date)
    "—"))

(defn- formatted-time [time]
  (if time
    (t/format (t/formatter "HH:mm") time)
    "—"))

(defn- gig-date-range [{:gig/keys [date end-date]}]
  (if end-date
    [:span
     [:time {:datetime (str date)} (formatted-date date)]
     [:span {:aria-hidden true} " – "]
     [:time {:datetime (str end-date)} (formatted-date end-date)]]
    [:time {:datetime (str date)} (formatted-date date)]))

(defn- gig-row [req {:gig/keys [gig-id title status call-time end-date] :as gig}]
  (let [{:attendance/keys [plan motivation comment member]} (:attendance gig)
        member-id (:member/member-id member)]
    [:div {:class "dashboard-gig-row"}
     [:div {:class "dashboard-gig-status-cell"}
      (gigs.ui/gig-status-icon status {:class "dashboard-gig-status"})]
     [:div {:class "dashboard-gig-date"}
      [:span (gig-date-range gig)]
      (when-not end-date
        [:span (formatted-time call-time)])]
     [:a {:href  (urls/link-gig gig)
          :class "dashboard-gig-title"}
      title]
     [:div {:class "dashboard-gig-plan"}
      (attendance.ui/plan-dropdown req gig-id member-id plan)]
     [:div {:class "dashboard-gig-motivation"}
      (attendance.ui/motivation-select req gig-id member-id motivation)]
     [:div {:class (ui2/cs "dashboard-gig-comment"
                           (attendance.ui/comment-class req gig-id member-id comment))}
      (attendance.ui/comment-control req gig-id member-id comment)]]))

(defn- gig-section [req title gigs]
  (when (seq gigs)
    [:section {:class "dashboard-gig-section"}
     (ui2/section-divider title)
     [:div {:class "dashboard-gig-list"}
      (for [gig gigs]
        (gig-row req gig))]]))

(defn- currency-format [cents]
  (.format (NumberFormat/getCurrencyInstance Locale/GERMANY) (/ (or cents 0) 100.0)))

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
     [:wa-card {:class "dashboard-ledger-card"}
      [:div {:class "dashboard-ledger-grid"}
       [:div {:class "wa-stack wa-gap-xs"}
        [:span {:class "wa-caption-s"} (tr [:outstanding-balance])]
        [:div {:class "dashboard-ledger-balance text-danger"}
         (currency-format balance)]
        [:a {:href (urls/link-member-ledger-table owner)}
         (tr [:outstanding-balance])]]
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

(defn- calendar-url-data [env]
  (when-let [https (config/public-calendar-url env)]
    (let [webcal         (str/replace https "https" "webcal")
          encoded-webcal (urls/url-encode webcal)]
      {:https       https
       :webcal      webcal
       :google      (format "https://calendar.google.com/calendar/render?cid=%s" encoded-webcal)
       :outlook-365 (str "https://outlook.office.com/owa?path=%2Fcalendar%2Faction%2Fcompose&rru=addsubscription"
                         "&url=" encoded-webcal "&name=SNO-Kalender")
       :outlook-live (str "https://outlook.live.com/owa?path=%2Fcalendar%2Faction%2Fcompose&rru=addsubscription"
                          "&url=" encoded-webcal "&name=SNO-Kalender")})))

(defn- calendar-menu-icon
  ([icon-fn]
   (calendar-menu-icon icon-fn nil))
  ([icon-fn extra-class]
   (icon-fn {:slot  "icon"
             :class (ui2/cs "dashboard-calendar-service-icon" extra-class)})))

(defn- calendar-subscribe-button [{:keys [tr system]}]
  (when-let [{:keys [https webcal google outlook-365 outlook-live]} (calendar-url-data (:env system))]
    [:wa-dropdown {:placement "bottom-end"}
     [:wa-button {:slot       "trigger"
                  :appearance "outlined"
                  :with-caret true}
      [:wa-icon {:library "snoico"
                 :name    "calendar"
                 :slot    "start"}]
      (tr [:action/add-to-calendar])]
     [:wa-dropdown-item {:data-on:click (str "navigator.clipboard.writeText(" (js-value https) ")")}
      (calendar-menu-icon icon/copy "dashboard-calendar-service-icon--copy")
      (tr [:action/copy-link])]
     [:wa-dropdown-item {:value   outlook-365
                         :onclick "window.location = this.value"}
      (calendar-menu-icon icon/microsoft-365)
      "Microsoft 365"]
     [:wa-dropdown-item {:value   outlook-live
                         :onclick "window.location = this.value"}
      (calendar-menu-icon icon/outlook)
      "Outlook Live"]
     [:wa-dropdown-item {:value   google
                         :onclick "window.location = this.value"}
      (calendar-menu-icon icon/google-calendar)
      "Google Calendar"]
     [:wa-dropdown-item {:value   webcal
                         :onclick "window.location = this.value"}
      (calendar-menu-icon icon/apple-calendar "dashboard-calendar-service-icon--apple")
      "Apple Calendar"]]))

(defn- page-actions [req]
  [(calendar-subscribe-button req)
   [:wa-button {:appearance "filled"
                :variant    "brand"
                :href       (urls/link-gig-create)}
    ((:tr req) [:action/create-gig])]])

(defn page [{:keys [db tr] :as req}]
  (let [member (auth/get-current-member req)
        _      (assert member)
        {:keys [answered ledger unanswered]} (queries/dashboard-data db member)]
    (ui2/datastar-page
     [:div {:class        "dashboard-page wa-stack wa-gap-l"
            :data-signals (d*/->signals (attendance.ui/attendance-signals req))}
      (ui2/page-header
       {:title   (tr [(keyword "dashboard" (name (util/time-window (util/local-time-austria!))))]
                     [(member-nick member)])
        :actions (page-actions req)})
      (when (and ledger (pos? (:ledger/balance ledger)))
        (ledger-widget req ledger))
      (gig-section req (tr [:dashboard/unanswered]) unanswered)
      (gig-section req (tr [:dashboard/upcoming]) answered)
      (when-not (or (seq answered) (seq unanswered))
        (ui2/empty-state (tr [:gigs/no-future]) (tr [:action/create-gig])))])))

(d*/refresh-all!)
