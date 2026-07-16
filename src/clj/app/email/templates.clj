(ns app.email.templates
  (:require
   [app.config :as config]
   [app.gigs.domain :as gig.domain]
   [app.markdown :as markdown]
   [app.secret-box :as secret-box]
   [app.ui2 :as ui2]
   [app.urls :as url]
   [app.util :as util]
   [clojure.string :as str]
   [hiccup2.core :refer [html]]
   [selmer.parser :as selmer]
   [selmer.util :as selmer.util]
   [tick.core :as t]))

(def ^:private email-format-req
  {:current-locale :de})

(defn- format-date [value]
  (ui2/format-date email-format-req :compact-with-weekday value))

(defn- format-date-time [value]
  (ui2/format-date-time email-format-req :medium value))

(defn- format-time [value]
  (ui2/format-time email-format-req :short value))

(defn- money-cents-format [value currency]
  (ui2/money-format (/ value 100) currency))

(defn payload-for-attendance [env gig-id member-id attendance-plan]
  (secret-box/encrypt
   {:member/member-id member-id
    :gig/gig-id gig-id
    :attendance/plan attendance-plan}
   (config/app-secret-key env)))

(defn payload-for-reminder [env gig-id member-id]
  (secret-box/encrypt
   {:member/member-id member-id
    :gig/gig-id gig-id
    :reminder true}
   (config/app-secret-key env)))

(defn- gig-attendance-link [env gig-id member-id attendance-plan]
  (let [payload (if (= :reminder attendance-plan)
                  (payload-for-reminder env gig-id member-id)
                  (payload-for-attendance env
                                          gig-id
                                          member-id
                                          attendance-plan))]
    (str (url/absolute-gig-answer-link-base env)
         "?answer="
         payload)))

(defn- edited-gig-attribute-label [tr attribute]
  (if-let [label-key (gig.domain/gig-attribute-label-key attribute)]
    (tr [label-key])
    (name attribute)))

(defn template-snippet-gig-details [{:keys [tr]} {:gig/keys [date end-date title set-time call-time end-time status location more-details pay-deal gig-type]}]
  [:div
   [:p [:strong title]]
   [:ul
    [:li (tr [:gigs/type-label]) ": " (tr [(gig.domain/gig-type-label-key gig-type)])]
    [:li (tr [:gigs/status-label]) ": " (tr [(gig.domain/gig-status-label-key status)])]
    [:li (tr [:gigs/date]) ": " (format-date date)]
    (when end-date
      [:li (tr [:gigs/end-date]) ": " (format-date end-date)])
    (when call-time
      [:li (tr [:gigs/call-time]) ": " (format-time call-time)])
    (when set-time
      [:li (tr [:gigs/set-time]) ": " (format-time set-time)])
    (when end-time
      [:li (tr [:gigs/end-time]) ": " (format-time end-time)])
    (when location
      [:li (tr [:gigs/location]) ": " (markdown/render-one-line location)])
    (when-not (str/blank? pay-deal)
      [:li (tr [:gigs/pay-deal]) ": " pay-deal])]

   (when more-details
     [:p (tr [:gigs/more-details]) ": " [:br]
      (markdown/render more-details)])])

(defn template-snippet-gig-details-plain [{:keys [tr]} {:gig/keys [date end-date title set-time call-time end-time status location more-details pay-deal gig-type]}]
  (selmer/render
   "# {{title}}

{% for item in gig-details %}* {{item.name}}: {{item.value}}
{% endfor %}
{% if not more-details|empty? %}{{more-details-label}}:
{{more-details}}{% endif %}"
   {:title title
    :more-details more-details
    :more-details-label (tr [:gigs/more-details])
    :gig-details (util/remove-nils [;
                                    {:name (tr [:gigs/type-label]) :value (tr [(gig.domain/gig-type-label-key gig-type)])}
                                    {:name (tr [:gigs/status-label]) :value (tr [(gig.domain/gig-status-label-key status)])}
                                    {:name (tr [:gigs/date]) :value  (format-date date)}
                                    (when end-date
                                      {:name (tr [:gigs/end-date]) :value (format-date end-date)})
                                    (when call-time
                                      {:name (tr [:gigs/call-time]) :value (format-time call-time)})
                                    (when set-time
                                      {:name (tr [:gigs/set-time]) :value (format-time set-time)})
                                    (when end-time
                                      {:name (tr [:gigs/end-time]) :value (format-time end-time)})
                                    (when location
                                      {:name (tr [:gigs/location]) :value location})
                                    (when-not (str/blank? pay-deal)
                                      {:name (tr [:gigs/pay-deal]) :value pay-deal})
                                    ;;
                                    ])}))
(defn gig-created-email-html
  [{:keys [tr env] :as sys} gig member reminder?]
  (let [gig-id (:gig/gig-id gig)
        member-id (:member/member-id member)
        can-make-it-link (gig-attendance-link env
                                              gig-id
                                              member-id
                                              :plan/definitely)
        cannot-make-it-link (gig-attendance-link env
                                                 gig-id
                                                 member-id
                                                 :plan/definitely-not)
        reminder-link (gig-attendance-link env
                                           gig-id
                                           member-id
                                           :reminder)
        gig-link (url/absolute-link-gig env (:gig/gig-id gig))]
    (str (html
          [:div
           [:p
            (tr [:email/greeting])]
           [:p (tr [(case (:gig/gig-type gig)
                      :gig.type/probe (if reminder? :email/remind-probe :email/new-probe-added)
                      :gig.type/extra-probe (if reminder? :email/remind-extra-probe :email/new-extra-probe-added)
                      :gig.type/meeting (if reminder? :email/remind-meeting  :email/new-meeting-added)
                      :gig.type/gig (if reminder? :email/remind-gig :email/new-gig-added))])]
           [:p]
           (template-snippet-gig-details sys gig)
           [:p]
           [:hr]
           [:p [:strong (tr [:email/can-you-make-it])]]
           [:p [:a {:href can-make-it-link} (tr [:email/can-make-it])]]
           [:p [:a {:href cannot-make-it-link} (tr [:email/cannot-make-it])]]
           [:p [:a {:href reminder-link} (tr [:email/want-reminder])]]
           [:p [:a {:href gig-link} (tr [:email/gig-info-page])]]
           [:p]
           [:p (tr [:email/sign-off])]]))))

(defn gig-created-email-plain
  [{:keys [tr env] :as sys} gig member reminder?]
  (selmer.util/without-escaping
   (let [gig-id (:gig/gig-id gig)
         member-id (:member/member-id member)
         can-make-it-link (gig-attendance-link env
                                               gig-id
                                               member-id
                                               :plan/definitely)
         cannot-make-it-link (gig-attendance-link env
                                                  gig-id
                                                  member-id
                                                  :plan/definitely-not)
         reminder-link (gig-attendance-link env
                                            gig-id
                                            member-id
                                            :reminder)
         gig-link (url/absolute-link-gig env (:gig/gig-id gig))]
     (selmer/render
      "{{greeting}}

{{intro}}

{{gig-info}}
-----
{{can-you-make-it}}

{{can-make-it}}: {{can-make-it-link}}

{{cannot-make-it}}: {{cannot-make-it-link}}

{{want-reminder}}: {{want-reminder-link}}

{{gig-info-page}}:  {{gig-info-page-link}}

{{sign-off}}
"
      {:greeting (tr [:email/greeting])
       :intro (tr [(case (:gig/gig-type gig)
                     :gig.type/probe (if reminder? :email/remind-probe :email/new-probe-added)
                     :gig.type/extra-probe (if reminder? :email/remind-extra-probe :email/new-extra-probe-added)
                     :gig.type/meeting (if reminder? :email/remind-meeting  :email/new-meeting-added)
                     :gig.type/gig (if reminder? :email/remind-gig :email/new-gig-added))])
       :gig-info (template-snippet-gig-details-plain sys gig)
       :can-you-make-it (tr [:email/can-you-make-it])
       :can-make-it (tr [:email/can-make-it])
       :can-make-it-link can-make-it-link

       :cannot-make-it (tr [:email/cannot-make-it])
       :cannot-make-it-link cannot-make-it-link

       :want-reminder (tr [:email/want-reminder])
       :want-reminder-link reminder-link

       :gig-info-page (tr [:email/gig-info-page])
       :gig-info-page-link gig-link
       :sign-off (tr [:email/sign-off])

        ;;
       }
      (template-snippet-gig-details sys gig)))))

(defn gig-updated-email-html
  "edited-attrs should be a list of gig entity attribute names that were edited"
  [{:keys [tr env] :as sys} gig edited-attrs]
  (str (html
        [:div
         [:p
          (tr [:email/greeting])]
         [:p (tr [:email/gig-edited])]
         [:p]
         [:p (tr [:email/gig-edit-type]) ": " (str/join ", " (map #(edited-gig-attribute-label tr %) edited-attrs))]
         [:p]
         (template-snippet-gig-details sys gig)
         [:p]
         [:hr]
         [:p [:a {:href (url/absolute-link-gig  env (:gig/gig-id gig))} (tr [:email/change-availability])]]
         [:p]
         [:p (tr [:email/sign-off])]])))

(defn gig-updated-email-plain [{:keys [tr env] :as sys} gig edited-attrs]
  (selmer.util/without-escaping
   (let [gig-link (url/absolute-link-gig env (:gig/gig-id gig))]
     (selmer/render
      "{{greeting}}

{{intro}}

{{gig-edit-type-label}}:
{{gig-edit-type-attrs}}

-----
{{gig-info}}
-----
{{need-to-change}}
{{gig-info-page}}:  {{gig-info-page-link}}

{{sign-off}}
"
      {:greeting (tr [:email/greeting])
       :intro (tr [:email/gig-edited])
       :gig-edit-type-label (tr [:email/gig-edit-type])
       :gig-edit-type-attrs (str/join ", " (map #(edited-gig-attribute-label tr %) edited-attrs))
       :gig-info (template-snippet-gig-details-plain sys gig)
       :need-to-change (tr [:email/change-availability])
       :gig-info-page (tr [:email/gig-info-page])
       :gig-info-page-link gig-link
       :sign-off (tr [:email/sign-off])

       ;;
       }
      (template-snippet-gig-details sys gig)))))

(defn new-user-invite-html [{:keys [tr env]} invite-code]
  (str (html
        [:div
         [:p
          (tr [:email/greeting])]
         [:p (tr [:email/invite-new-user-intro])]
         [:p (tr [:email/invite-new-user-intro2])]
         (let [invite-link (url/absolute-link-new-user-invite env invite-code)]
           [:p [:a {:href invite-link} invite-link]])
         [:p]
         [:p (tr [:email/sign-off])]])))

(defn new-user-invite-plain [{:keys [tr env]} invite-code]
  (selmer.util/without-escaping
   (let [invite-link (url/absolute-link-new-user-invite env invite-code)]
     (selmer/render
      "{{greeting}}

{{intro}}

{{intro2}}

{{invite-link}}

{{sign-off}}
"
      {:greeting (tr [:email/greeting])
       :intro (tr [:email/invite-new-user-intro])
       :intro2 (tr [:email/invite-new-user-intro2])
       :invite-link invite-link
       :sign-off (tr [:email/sign-off])}))))

(defn summarize-instrument-str [{:instrument/keys [name make model serial-number build-year]}]
  (str name
       (when (not (str/blank? make)) (str " - " make))
       (when (not (str/blank? model)) (str " - " model))
       (when (not (str/blank? serial-number)) (str " - " serial-number))
       (when (not (str/blank? build-year)) (str " - " build-year))))

(defn build-insurance-debt-args [{:keys [env]} {:member/keys [name member-id]} private-coverages sender-name time-range amount-cents]
  (let [{:keys [iban bic account-name]} (config/band-bank-info env)]
    {:member-name name
     :sender-name sender-name
     :time-range time-range
     :amount (if (string? amount-cents) amount-cents (money-cents-format amount-cents :EUR))
     :private-instruments (map (fn [{:instrument.coverage/keys [cost instrument description value]}]
                                 {:instrument-summary (summarize-instrument-str  instrument)
                                  :value (ui2/money-format value :EUR)
                                  :cost (ui2/money-format cost :EUR)
                                  :description description})
                               private-coverages)
     :account-name account-name
     :iban iban
     :bic bic
     :insurance-link (url/absolute-link-member-ledger env member-id)}))

(defn insurance-debt-hiccup [{:keys [tr]} {:keys [member-name private-instruments time-range amount account-name iban bic insurance-link sender-name]}]
  [:div
   [:p
    (tr [:email/greeting-personal] {:member-name member-name})]
   [:p (tr [:insurance/payment-email-intro]) [:strong time-range]]
   [:p [:strong (tr [:insurance/payment-email-member-costs]
                    {:member-name member-name})]]
   [:table
    [:tr
     [:td [:strong (tr [:instrument/instrument])]]
     [:td {:align "right"} [:strong (tr [:insurance/value])]]
     [:td {:align "right"} [:strong (tr [:insurance/cost])]]]
    (for [{:keys [value description instrument-summary cost]} private-instruments]
      (list
       [:tr
        [:td
         [:span instrument-summary]
         (when description (list [:br] [:span description]))]
        [:td {:align "right"} value]
        [:td {:align "right"} cost]]))
    [:tr
     [:td]
     [:td {:align "right"} (tr [:total])]
     [:td {:align "right"} amount]]]
   [:p (tr [:ledger/please-pay-to-band] {:amount amount})]
   [:p [:strong (tr [:insurance/payment-email-bank-data])]]
   [:p
    account-name [:br]
    iban [:br]
    bic [:br]]
   [:p (tr [:insurance/payment-email-cost-details])]
   [:p [:a {:href insurance-link} insurance-link]]
   [:p (tr [:insurance/payment-email-claim-guidance])]
   [:p (tr [:insurance/payment-email-contact])]
   [:p]
   [:p
    (tr [:email/sign-off-personal])
    [:br] sender-name
    [:br] "Versicherungsteam StreetNoise Orchestra"]])

(defn insurance-debt-html [sys args]
  (str (html (insurance-debt-hiccup sys args))))

(defn insurance-debt-plain [{:keys [tr]} {:keys [member-name private-instruments time-range amount account-name iban bic insurance-link sender-name]}]
  (selmer.util/without-escaping
   (selmer/render
    "{{greeting}}

{{p1}}

{{p2}}

{{instruments}}

{{amount}}

{{please-pay}}

{{bank-data}}
-------------

{{account-name}}
{{iban}}
{{bic}}

{{p3}} {{insurance-link}}
{{p4}}
{{p5}}

{{sign-off}}
{{sender-name}}
Versicherungsteam StreetNoise Orchestra
"
    {:greeting (tr [:email/greeting-personal] {:member-name member-name})
     :p1 (str (tr [:insurance/payment-email-intro]) " " time-range)
     :p2 (tr [:insurance/payment-email-member-costs]
             {:member-name member-name})
     :amount (str (tr [:total]) ": " amount)
     :instruments (str/join "\n"
                            (map (fn [{:keys [value instrument-summary cost]}]
                                   (str "- " instrument-summary " (" (tr [:insurance/value]) ": " value ")"  " - " cost))
                                 private-instruments))
     :please-pay (tr [:ledger/please-pay-to-band] {:amount amount})
     :bank-data (tr [:insurance/payment-email-bank-data])
     :account-name account-name
     :iban iban
     :bic bic
     :insurance-link insurance-link
     :p3 (tr [:insurance/payment-email-cost-details])
     :p4 (tr [:insurance/payment-email-claim-guidance])
     :p5 (tr [:insurance/payment-email-contact])
     :sender-name sender-name
     :sign-off (tr [:email/sign-off-personal])})))

(defn generic-email-plain
  ([sys body-text cta-text cta-url]
   (generic-email-plain sys body-text cta-text cta-url nil))
  ([{:keys [tr]} body-text cta-text cta-url {:keys [sign-off greeting]}]
   (selmer.util/without-escaping
    (selmer/render
     "{{greeting}}

{{body-text}}

{{cta-text}}

{{cta-url}}

{{sign-off}}
"
     {:greeting (or greeting (tr [:email/greeting]))
      :body-text body-text
      :cta-text cta-text
      :cta-url cta-url
      :sign-off (or sign-off (tr [:email/sign-off]))}))))

(defn generic-email-html
  ([sys body-hiccup cta-text cta-url]
   (generic-email-html sys body-hiccup cta-text cta-url nil))
  ([{:keys [tr]} body-hiccup cta-text cta-url {:keys [sign-off greeting]}]
   (str (html
         [:div
          [:p
           (or greeting (tr [:email/greeting]))]
          (if (vector? body-hiccup)
            body-hiccup
            [:p body-hiccup])
          (when cta-text
            [:p [:a {:href cta-url} cta-text]])

          [:p]
          [:p
           (or sign-off (tr [:email/sign-off]))]]))))

(defn poll-created-email-plain-body [tr poll]
  (let [title (:poll/title poll)
        description (:poll/description poll)
        closes-at (:poll/closes-at poll)]
    (selmer/render
     "
### {{title}}

* {{ closes-at-label }}: {{ closes-at}}

{% if description|not-empty %}{{ description }}{% endif %}
#### {{ options-label }}:

{% for option in options %}* {{ option }}
{% endfor %}"

     {:title title
      :options-label (tr [:polls/options])
      :description description
      :options (map :poll.option/value (:poll/options poll))
      :closes-at-label (tr [:polls/closes-at-label])
      :closes-at (format-date-time closes-at)})))

(defn poll-created-email-html-body [tr poll]
  (markdown/render (poll-created-email-plain-body tr poll)))

(defn insurance-survey-created-email-plain-body [tr {:keys [closes-at member-most-instruments member-most-instrument-count]}]
  (let [closes-at-str (format-date-time closes-at)
        closes-at-str-bolded (str "**" closes-at-str "**")
        closes-at-days (-> (t/instant)
                           (t/between  closes-at)
                           (t/days))]

    (selmer/render
     "
### {{title}}

{{ p1 }}

{{ p2 }}

{{ p3 }}
"

     {:title (tr [:insurance/survey-email-title])
      :p1 (tr [:insurance/survey-email-intro])
      :p2
      (if (and member-most-instruments (> member-most-instrument-count 10))
        (tr [:insurance/survey-email-add-instruments-many]
            {:member-name (or (:member/nick member-most-instruments)
                              (:member/name member-most-instruments))
             :count       member-most-instrument-count})
        (tr [:insurance/survey-email-add-instruments]))
      :p3 (tr [:insurance/survey-email-deadline]
              {:closes-at closes-at-str-bolded
               :days      closes-at-days})})))

(defn insurance-survey-created-email-html-body [tr closes-at]
  (markdown/render (insurance-survey-created-email-plain-body tr closes-at)))
