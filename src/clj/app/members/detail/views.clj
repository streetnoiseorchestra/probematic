(ns app.members.detail.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.form :as form]
   [app.datastar :as d*]
   [app.insurance.ui :as insurance.ui]
   [app.keycloak :as keycloak]
   [app.ledger.domain :as ledger.domain]
   [app.members.detail.actions :as actions]
   [app.members.domain :as members.domain]
   [app.members.ui :as members.ui]
   [app.qrcode :as qr]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.avatar :as avatar]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.divider :as divider]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]
   [tick.core :as t])
  (:import
   [java.text NumberFormat]
   [java.util Locale]))

(defn- muted [value]
  (if (str/blank? (str value))
    [:span {:class "wa-color-text-quiet"} "—"]
    value))

(defn- phone-link [phone]
  (if (str/blank? (str phone))
    (muted phone)
    [:a {:href (str "tel:" (str/replace phone #"\\s+" ""))} phone]))

(defn- email-link [email]
  (if (str/blank? (str email))
    (muted email)
    [:a {:href (str "mailto:" email)} email]))

(defn- keycloak-enabled? [{:keys [system]} keycloak-id]
  (when (seq keycloak-id)
    (try
      (keycloak/user-account-enabled? (:keycloak system) keycloak-id)
      (catch Throwable _
        false))))

(defn- sno-id-enabled-badge [enabled?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               enabled? (assoc :variant "success")
               (not enabled?) (assoc :variant "danger"))
   [:i18n/tr (members.domain/sno-id-status-label-key enabled?)]])

(defn- sno-id-badge [keycloak-id]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               keycloak-id (assoc :variant "success")
               (nil? keycloak-id) (assoc :variant "neutral"))
   (if keycloak-id
     "Linked"
     "Not linked")])

(defn- detail-item [label value]
  [:div
   [:dt label]
   [:dd value]])

(defn- required-marker []
  [:span {:aria-hidden "true"} " *"])

(defn- field-label [label required?]
  [:span {:slot "label"}
   label
   (when required?
     (required-marker))])

(defn- form-input [form-state signal label attrs]
  (let [field (form/signal-field signal)
        error (form/field-error form-state field)]
    [:wa-input (merge {:appearance   "outlined"
                       :size         "m"
                       :value        (get form-state field "")
                       :hint         error
                       :data-invalid (when error "true")
                       :data-bind    signal}
                      attrs)
     (field-label label (:required attrs))]))

(defn- validate-field-action [req field]
  (str "$member-detail.contact.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-contact-field)
       "')"))

(defn- validate-field-on-blur [req field]
  {:data-on:blur (validate-field-action req field)})

(defn- validate-field-on-keydown [req field]
  {:data-on:keydown__debounce.500ms (validate-field-action req field)})

(defn- section-select [req form-state sections]
  (let [error (form/field-error form-state :section-name)]
    (into
     [:wa-select {:appearance   "outlined"
                  :size         "m"
                  :value        (:section-name form-state)
                  :required     true
                  :hint         error
                  :data-invalid (when error "true")
                  :data-bind    "member-detail.contact.section-name"
                  :data-on:blur (:data-on:blur (validate-field-on-blur req :section-name))}
      (field-label [:i18n/tr (members.domain/member-attribute-label-key :member/section)] true)
      [:wa-option {:value ""} " - "]]
     (for [{:section/keys [name]} sections]
       [:wa-option {:value name} name]))))

(defn- sno-id-admin-fields [req form-state]
  [:div {:class "member-detail-sno-id-admin-fields wa-stack wa-gap-m"}
   [divider/Divider]
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong [:i18n/tr :members/sno-id]]
    [:span {:class "wa-caption-s"}
     [:i18n/tr :members/sno-id-disabled-tooltip]]]
   [:div {:class "wa-grid wa-gap-m"}
    (form-input form-state
                "member-detail.contact.username"
                [:i18n/tr (members.domain/member-attribute-label-key :member/username)]
                (merge {:required true}
                       (validate-field-on-keydown req :username)))
    (form-input form-state
                "member-detail.contact.keycloak-id"
                [:i18n/tr (members.domain/member-attribute-label-key :member/keycloak-id)]
                (validate-field-on-keydown req :keycloak-id))
    [:div {:class "wa-stack wa-gap-2xs"}
     [:span {:class "wa-caption-s"} [:i18n/tr :members/sno-id-status-label]]
     [:wa-switch {:size           "m"
                  :checked        (:sno-id-enabled form-state)
                  :data-bind      "member-detail.contact.sno-id-enabled"
                  :data-on:change "$member-detail.contact.sno-id-enabled = !$member-detail.contact.sno-id-enabled"}
      [:i18n/tr (members.domain/sno-id-status-label-key (:sno-id-enabled form-state))]]]]])

(defn- contact-form [req form-state sections]
  (let [validate-on-keydown #(validate-field-on-keydown req %)]
    [:form {:id             "member-contact-form"
            :data-id        "member-contact"
            :data-action    (d*/act req ::actions/update-contact)
            :data-on:submit "evt.preventDefault();"}
     [:div {:class "wa-stack wa-gap-l"}
      (when-let [top-error (form/field-error form-state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"}
         top-error])
      [:input {:type "hidden" :data-bind "member-detail.contact.member-id"}]
      [:div {:class "wa-grid wa-gap-m"}
       (form-input form-state
                   "member-detail.contact.name"
                   [:i18n/tr (members.domain/member-attribute-label-key :member/name)]
                   (merge {:required true :autofocus true} (validate-on-keydown :name)))
       (form-input form-state
                   "member-detail.contact.nick"
                   [:i18n/tr (members.domain/member-attribute-label-key :member/nick)]
                   (merge {:required true} (validate-on-keydown :nick)))
       (form-input form-state
                   "member-detail.contact.email"
                   [:i18n/tr (members.domain/member-attribute-label-key :member/email)]
                   (merge {:type "email" :required true} (validate-on-keydown :email)))
       (form-input form-state
                   "member-detail.contact.phone"
                   [:i18n/tr (members.domain/member-attribute-label-key :member/phone)]
                   (merge {:type "tel" :required true} (validate-on-keydown :phone)))
       (section-select req form-state sections)
       [:div {:class "wa-stack wa-gap-2xs"}
        [:span {:class "wa-caption-s"} [:i18n/tr (members.domain/member-attribute-label-key :member/active?)]]
        [:wa-switch {:size           "m"
                     :checked        (:active form-state)
                     :data-bind      "member-detail.contact.active"
                     :data-on:change "$member-detail.contact.active = !$member-detail.contact.active"}
         [:i18n/tr :status-active]]]]
      (when (auth/current-user-admin? req)
        (sno-id-admin-fields req form-state))]]))

(defn- keycloak-link [{:keys [system]} keycloak-id]
  (if (seq keycloak-id)
    [:a {:href (keycloak/link-user-edit (:env system) keycloak-id)} keycloak-id]
    (muted nil)))

(defn- profile-details [req member]
  (let [{:member/keys [email phone username keycloak-id active? nick]} member
        section-name (get-in member [:member/section :section/name])
        current-user-admin? (auth/current-user-admin? req)
        enabled?     (when current-user-admin? (keycloak-enabled? req keycloak-id))]
    [:dl {:class "particulars"}
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/section)] (muted section-name))
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/nick)] (muted nick))
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/email)] (email-link email))
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/phone)] (phone-link phone))
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/active?)] (ui2/active-badge active?))
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/username)] (muted username))
     (detail-item [:i18n/tr (members.domain/member-attribute-label-key :member/keycloak-id)] (keycloak-link req keycloak-id))
     (detail-item [:i18n/tr :members/sno-id] (if (and current-user-admin? keycloak-id)
                                               (sno-id-enabled-badge enabled?)
                                               (sno-id-badge keycloak-id)))]))

(defn- form-state->signals [form-state]
  (if (map? form-state)
    (dissoc form-state :_error)
    form-state))

(defn- expiry-badge [discount]
  (members.ui/travel-discount-badge discount (form/date-value (:travel.discount/expiry-date discount))))

(defn- discount-type-select [form-state discount-types]
  (let [error (form/field-error form-state :discount-type-id)]
    (into
     [:wa-select {:appearance        "outlined"
                  :size              "m"
                  :value             (:discount-type-id form-state)
                  :required          true
                  :hint              error
                  :data-invalid      (when error "true")
                  :data-bind         "member-detail.travel-discount-create.discount-type-id"
                  :data-on:wa-change "$member-detail.travel-discount-create.discount-type-id = evt.target.value"}
      (field-label [:i18n/tr :members/travel-discount-type] true)
      [:wa-option {:value ""} " - "]]
     (for [{:travel.discount.type/keys [discount-type-id discount-type-name]} discount-types]
       [:wa-option {:value (str discount-type-id)} discount-type-name]))))

(defn- travel-discount-create-form [req form-state discount-types]
  (when form-state
    [:form {:id             "member-travel-discount-create-form"
            :data-id        "member-travel-discount-create"
            :data-action    (d*/act req ::actions/add-travel-discount)
            :data-on:submit "evt.preventDefault();"}
     [:div {:class "wa-stack wa-gap-m"}
      (when-let [top-error (form/field-error form-state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"}
         top-error])
      [:input {:type      "hidden"
               :value     (:member-id form-state)
               :data-bind "member-detail.travel-discount-create.member-id"}]
      [:div {:class "wa-grid wa-gap-m"}
       (discount-type-select form-state discount-types)
       (form-input form-state
                   "member-detail.travel-discount-create.expiry-date"
                   [:i18n/tr :members/travel-discount-expiry-date]
                   {:type             "date"
                    :required         true
                    :data-on:wa-input "$member-detail.travel-discount-create.expiry-date = evt.target.value"})]
      (ui2/action-bar
       {}
       [[button/Button {:appearance  "outlined"
                        :data-id     "member-travel-discount-create-cancel"
                        :data-action (d*/act req ::actions/close-travel-discount-create)}
         [:i18n/tr :action/cancel]]
        [button/Button {:appearance         "filled"
                        :variant            "brand"
                        :type               "submit"
                        :data-attr:disabled "!!$loading && $loading !== 'member-travel-discount-create'"
                        :data-attr:loading  "$loading === 'member-travel-discount-create'"}
         [:i18n/tr :action/add]]])]]))

(defn- travel-discount-edit-form [req form-state]
  [:form {:id             (str "member-travel-discount-edit-" (:discount-id form-state))
          :data-id        "member-travel-discount"
          :data-action    (d*/act req ::actions/update-travel-discount)
          :data-on:submit "evt.preventDefault();"}
   [:div {:class "wa-cluster wa-gap-s wa-align-items-end wa-justify-content-end"}
    (when-let [top-error (form/field-error form-state :_top)]
      [:wa-callout {:appearance "outlined" :variant "danger"}
       top-error])
    [:input {:type      "hidden"
             :value     (:discount-id form-state)
             :data-bind "member-detail.travel-discount.discount-id"}]
    (form-input form-state
                "member-detail.travel-discount.expiry-date"
                [:i18n/tr :members/travel-discount-expiry-date]
                {:type             "date"
                 :required         true
                 :data-on:wa-input "$member-detail.travel-discount.expiry-date = evt.target.value"})
    [button/Button {:appearance  "outlined"
                    :data-id     "member-travel-discount-cancel"
                    :data-action (d*/act req ::actions/close-travel-discount-edit)}
     [:i18n/tr :action/cancel]]
    [button/Button {:appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :data-attr:disabled "!!$loading && $loading !== 'member-travel-discount'"
                    :data-attr:loading  "$loading === 'member-travel-discount'"}
     [:i18n/tr :action/save]]]])

(defn- travel-discount-remove-dialog [req {:travel.discount/keys [discount-id discount-type]}]
  (ui2/remove-dialog
   {:id            (ui2/remove-dialog-id "travel-discount" discount-id)
    :label         [:i18n/tr :action/confirm-generic]
    :cancel-label  [:i18n/tr :action/cancel]
    :confirm-label [:i18n/tr :action/confirm-delete]
    :confirm-attrs {:data-id     discount-id
                    :data-action (d*/act req ::actions/delete-travel-discount)}}
   [:p [:i18n/tr :members/confirm-delete-travel-discount
        {:discount-type (:travel.discount.type/discount-type-name discount-type)}]]))

(defn- travel-discount-row [req edit-state {:travel.discount/keys [discount-id discount-type] :as discount}]
  (let [editing? (= discount-id (:discount-id edit-state))]
    [:tr
     [:td {:class "align-middle"}
      (:travel.discount.type/discount-type-name discount-type)]
     [:td {:class "align-middle"}
      (if editing?
        (travel-discount-edit-form req edit-state)
        (expiry-badge discount))]
     [:td {:class "align-middle text-right"}
      (when-not editing?
        [:div {:class "wa-cluster wa-gap-2xs wa-justify-content-end"}
         [button/Button {:appearance  "outlined"
                         :variant     "brand"
                         :size        "s"
                         :data-id     discount-id
                         :data-action (d*/act req ::actions/open-travel-discount-edit)}
          [:i18n/tr :action/update]]
         [button/Button {:appearance  "outlined"
                         :variant     "danger"
                         :size        "s"
                         :data-dialog (format "open %s" (ui2/remove-dialog-id "travel-discount" discount-id))}
          [:i18n/tr :action/delete]]])]]))

(defn- travel-discounts-table [req edit-state discounts]
  (if (seq discounts)
    (ui2/table-shell
     [:table
      [:thead
       [:tr
        [:th [:i18n/tr :members/travel-discount-type]]
        [:th [:i18n/tr :members/travel-discount-expiry-date]]
        [:th]]]
      [:tbody
       (for [discount discounts]
         (travel-discount-row req edit-state discount))]])
    (ui2/empty-state
     [:i18n/tr :members/travel-discounts-empty]
     [:i18n/tr :members/travel-discounts-subtitle])))

(defn- travel-discounts-panel [{:keys [db page-state] :as req} member]
  (let [create-state   (get-in page-state [:member-detail :travel-discount-create])
        edit-state     (get-in page-state [:member-detail :travel-discount])
        discount-types (q/retrieve-all-discount-types db)
        discounts      (q/member-travel-discounts member)]
    [:div {:class "wa-stack wa-gap-l"}
     (ui2/section-card
      {:subtitle [:i18n/tr :members/travel-discounts-subtitle]
       :actions  (when-not create-state
                   [[button/Button {:appearance  "outlined"
                                    :variant     "brand"
                                    :data-id     (:member/member-id member)
                                    :data-action (d*/act req ::actions/open-travel-discount-create)}
                     [:i18n/tr :members/travel-discount-add]]])}
      (when create-state
        [:div {:class "wa-stack wa-gap-s"}
         (travel-discount-create-form req create-state discount-types)
         [divider/Divider]])
      (travel-discounts-table req edit-state discounts)
      (for [discount discounts]
        (travel-discount-remove-dialog req discount)))]))

(defn- currency-format [cents]
  (.format (NumberFormat/getCurrencyInstance Locale/GERMANY) (/ (or cents 0) 100.0)))

(defn- ledger-amount-variant [amount]
  (cond
    (pos? amount) "danger"
    (neg? amount) "success"
    :else "neutral"))

(defn- ledger-amount-color-class [amount]
  (if (pos? amount)
    "text-danger"
    "text-success"))

(defn- ledger-amount-badge [amount]
  [:wa-badge {:appearance "outlined"
              :pill       true
              :variant    (ledger-amount-variant amount)}
   (currency-format amount)])

(defn- iban-format [iban]
  (when iban
    (->> (str/replace iban #"\s" "")
         (partition-all 4)
         (map #(apply str %))
         (str/join " "))))

(defn- maybe-transfer-reference [balance entries]
  (when (= balance (:ledger.entry/amount (first entries)))
    (:ledger.entry/description (first entries))))

#_(defn- payment-qr-src [{:keys [system]} balance entries]
    (let [{:keys [iban bic account-name]} (config/band-bank-info (-> system :env))]
      (when (and (pos? balance) iban bic account-name)
        (qr/image-to-data-uri
         (qr/qr
          (qr/sepa-payment-code {:bic                    bic
                                 :iban                   iban
                                 :name                   account-name
                                 :amount                 (/ balance 100.0)
                                 :unstructured-reference (maybe-transfer-reference balance entries)})
          300
          300)))))
(defn- payment-qr-value [{:keys [system]} balance entries]
  (let [{:keys [iban bic account-name]} (config/band-bank-info (-> system :env))]
    (when (and (pos? balance) iban bic account-name)
      (qr/sepa-payment-code {:bic                    bic
                             :iban                   iban
                             :name                   account-name
                             :amount                 (/ balance 100.0)
                             :unstructured-reference (maybe-transfer-reference balance entries)}))))

(defn- ledger-balance-status [{:keys [system] :as req} balance entries]
  (let [member-owes-band? (pos? balance)
        band-owes-member? (neg? balance)
        {:keys [iban bic account-name]} (config/band-bank-info (-> system :env))]
    (cond
      band-owes-member?
      [:i18n/tr :ledger/band-owes-you {:amount (currency-format (abs balance))}]
      member-owes-band?
      [:div {:class "wa-grid wa-gap-m"}
       [:div {:class "wa-justify-content-center"}
        (when-let [qr-value (payment-qr-value req balance entries)]
          [:wa-qr-code {:value qr-value :label "Scan this code with your banking app to start a transfer"}])]
       [:div {:class "wa-flank:end content-percentage-70"}
        [:p [:i18n/tr :ledger/please-pay-to-band {:amount (currency-format balance)}]]
        (when (and account-name iban bic)
          [:div {:class "wa-stack wa-gap-3xs"}
           [:span "Name: " [:strong account-name]]
           [:span "IBAN: " [:strong (iban-format iban)]]
           [:span "BIC: " [:strong bic]]
           [:span [:i18n/tr :ledger/or-scan-qr-code]]])]])))

(defn- ledger-balance-card [req member ledger]
  (let [balance (or (:ledger/balance ledger) 0)
        entries (:ledger/entries ledger)]
    [:div {:class ""}
     [:div {:class "wa-stack wa-gap-xs"}
      [:span {:class "wa-caption-s"} [:i18n/tr :ledger/outstanding-balance]]
      [:div {:class (str "wa-heading-xl " (ledger-amount-color-class balance))}
       (currency-format balance)]
      [:a {:href (urls/link-member-money member)} "Why?"]]
     (ledger-balance-status req balance entries)]))

(defn- ledger-entry-direction-options [member kind]
  (let [member-name (:member/name member)
        directions  (if (= kind "payment")
                      ["credit" "debit"]
                      ["debit" "credit"])]
    (mapv (fn [direction]
            {:value direction
             :label [:i18n/tr
                     (ledger.domain/entry-direction-label-key [kind direction])
                     {:member-name member-name}]})
          directions)))

(defn- ledger-direction-button [value label]
  [button/Button {:appearance    "outlined"
                  :variant       "brand"
                  :data-on:click (str "$_ledgerDirection = '" value "'; "
                                      "$member-detail.ledger-entry.tx-direction = '" value "'")}
   label])

(defn- ledger-direction-choice [member form-state]
  [:div {:class     "wa-stack wa-gap-s"
         :data-show "$_ledgerDirection === ''"}
   (when-let [error (form/field-error form-state :tx-direction)]
     [:wa-callout {:appearance "outlined" :variant "danger"}
      error])
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong "Choose what happened"]
    [:span {:class "wa-caption-s"}
     "Start with the sentence that best describes this transaction."]]
   (into
    [:div {:class "wa-cluster wa-gap-s"}]
    (for [{:keys [value label]} (ledger-entry-direction-options member (:tx-kind form-state))]
      (ledger-direction-button value label)))])

(defn- ledger-entry-create-form [req member form-state]
  (let [title          (if (= "payment" (:tx-kind form-state))
                         [:i18n/tr :ledger/add-payment]
                         [:i18n/tr :ledger/add-debt])
        direction-text (into {}
                             (map (juxt :value :label))
                             (ledger-entry-direction-options member (:tx-kind form-state)))]
    [:form {:id             "member-ledger-entry-create-form"
            :data-id        "member-ledger-entry-create"
            :data-action    (d*/act req ::actions/add-ledger-entry)
            :data-signals   (d*/->signals {:_ledgerDirection (or (:tx-direction form-state) "")})
            :data-on:submit "evt.preventDefault();"}
     [:div {:class "wa-stack wa-gap-m"}
      [:div {:class "wa-stack wa-gap-2xs"}
       [:strong title]
       [:span {:class "wa-caption-s"}
        "Record money owed by or paid to the band."]]
      (when-let [top-error (form/field-error form-state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"}
         top-error])
      [:input {:type      "hidden"
               :value     (:member-id form-state)
               :data-bind "member-detail.ledger-entry.member-id"}]
      [:input {:type      "hidden"
               :value     (:tx-kind form-state)
               :data-bind "member-detail.ledger-entry.tx-kind"}]
      [:input {:type      "hidden"
               :value     (:tx-direction form-state)
               :data-bind "member-detail.ledger-entry.tx-direction"}]
      (ledger-direction-choice member form-state)
      [:div {:class     "wa-stack wa-gap-m"
             :data-show "$_ledgerDirection !== ''"}
       [:div {:class "wa-flank:end wa-align-items-center"}
        [:span {:class     "wa-caption-s"
                :data-show "$_ledgerDirection === 'debit'"}
         (get direction-text "debit")]
        [:span {:class     "wa-caption-s"
                :data-show "$_ledgerDirection === 'credit'"}
         (get direction-text "credit")]
        [button/Button {:appearance    "plain"
                        :data-on:click "$_ledgerDirection = ''; $member-detail.ledger-entry.tx-direction = ''"}
         "Change direction"]]
       [:div {:class "wa-grid wa-gap-m"}
        (form-input form-state
                    "member-detail.ledger-entry.tx-date"
                    [:i18n/tr (ledger.domain/entry-attribute-label-key :ledger.entry/tx-date)]
                    {:type             "date"
                     :required         true
                     :data-on:wa-input "$member-detail.ledger-entry.tx-date = evt.target.value"})
        (form-input form-state
                    "member-detail.ledger-entry.description"
                    [:i18n/tr (ledger.domain/entry-attribute-label-key :ledger.entry/description)]
                    {:required         true
                     :data-on:wa-input "$member-detail.ledger-entry.description = evt.target.value"})
        (form-input form-state
                    "member-detail.ledger-entry.amount"
                    [:i18n/tr (ledger.domain/entry-attribute-label-key :ledger.entry/amount)]
                    {:inputmode        "decimal"
                     :placeholder      "42,05"
                     :required         true
                     :data-on:wa-input "$member-detail.ledger-entry.amount = evt.target.value"})]
       (ui2/action-bar
        {}
        [[button/Button {:appearance  "outlined"
                         :data-id     "member-ledger-entry-create-cancel"
                         :data-action (d*/act req ::actions/close-ledger-entry-create)}
          [:i18n/tr :action/cancel]]
         [button/Button {:appearance         "filled"
                         :variant            "brand"
                         :type               "submit"
                         :data-attr:disabled "!!$loading && $loading !== 'member-ledger-entry-create'"
                         :data-attr:loading  "$loading === 'member-ledger-entry-create'"}
          [:i18n/tr :action/save]]])]
      (ui2/action-bar
       {:data-show "$_ledgerDirection === ''"}
       [[button/Button {:appearance  "outlined"
                        :data-id     "member-ledger-entry-create-cancel"
                        :data-action (d*/act req ::actions/close-ledger-entry-create)}
         [:i18n/tr :action/cancel]]])]]))

(defn- ledger-entry-remove-dialog [req {:ledger.entry/keys [entry-id description]}]
  (ui2/remove-dialog
   {:id            (ui2/remove-dialog-id "ledger-entry" entry-id)
    :label         [:i18n/tr :action/confirm-generic]
    :cancel-label  [:i18n/tr :action/cancel]
    :confirm-label [:i18n/tr :action/confirm-delete]
    :confirm-attrs {:data-id     entry-id
                    :data-action (d*/act req ::actions/delete-ledger-entry)}}
   [:p [:i18n/tr :ledger/confirm-delete-entry {:description description}]]))

(defn- ledger-entry-row [{:ledger.entry/keys [amount description tx-date entry-id]}]
  [:tr {:id (str "ledger-entry-" entry-id)}
   [:td {:class "align-middle"}
    [:time {:datetime (str tx-date)} (form/date-value tx-date)]]
   [:td {:class "align-middle"} description]
   [:td {:class "align-middle text-right"} (ledger-amount-badge amount)]
   [:td {:class "align-middle text-right"}
    [button/Button {:appearance  "plain"
                    :variant     "danger"
                    :size        "s"
                    :data-dialog (format "open %s" (ui2/remove-dialog-id "ledger-entry" entry-id))}
     [:i18n/tr :action/delete]]]])

(defn- ledger-entries-table [entries]
  (if (seq entries)
    (ui2/table-shell
     [:table {:id "member-ledger-table"}
      [:thead
       [:tr
        [:th [:i18n/tr (ledger.domain/entry-attribute-label-key :ledger.entry/tx-date)]]
        [:th [:i18n/tr (ledger.domain/entry-attribute-label-key :ledger.entry/description)]]
        [:th {:class "text-right"} [:i18n/tr (ledger.domain/entry-attribute-label-key :ledger.entry/amount)]]
        [:th]]]
      [:tbody
       (for [entry entries]
         (ledger-entry-row entry))]])
    [:div {:id "member-ledger-table"}
     (ui2/empty-state
      [:i18n/tr :ledger/no-transactions-yet]
      "Ledger entries will appear here after a debt or payment is recorded.")]))

(defn- member-ledger-panel [{:keys [db page-state] :as req} member]
  (let [create-state (get-in page-state [:member-detail :ledger-entry])
        ledger       (q/retrieve-ledger db (:member/member-id member))
        entries      (:ledger/entries ledger)]
    [:div {:class "wa-stack wa-gap-l"}
     (ui2/section-card
      {:subtitle [:span "What "
                  [:span {:class "text-danger"} "you owe the band (+)"]
                  " and what "
                  [:span {:class "text-success"} "the band owes you (-)"]]
       :actions  (when-not create-state
                   [[button/Button {:appearance  "outlined"
                                    :variant     "brand"
                                    :data-id     (:member/member-id member)
                                    :data-action (d*/act req ::actions/open-ledger-debt-create)}
                     [:i18n/tr :ledger/add-debt]]
                    [button/Button {:appearance  "outlined"
                                    :variant     "brand"
                                    :data-id     (:member/member-id member)
                                    :data-action (d*/act req ::actions/open-ledger-payment-create)}
                     [:i18n/tr :ledger/add-payment]]])}
      (ledger-balance-card req member ledger)
      (when create-state
        [:div {:class "wa-stack wa-gap-s"}
         [divider/Divider]
         (ledger-entry-create-form req member create-state)])
      [divider/Divider]
      (ledger-entries-table entries)
      (for [entry entries]
        (ledger-entry-remove-dialog req entry)))]))

(defn- money-value [value]
  (if (nil? value)
    (muted nil)
    (.format (NumberFormat/getCurrencyInstance Locale/GERMANY) value)))

(defn- insurance-kind-badge [private?]
  [:wa-badge {:appearance "outlined"
              :pill       true
              :variant    (if private? "neutral" "success")}
   [:i18n/tr (if private?
               :insurance/ownership-private
               :insurance/ownership-band)]])

(defn- insurance-policy-summary [policy]
  (if policy
    [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
     [:span {:class "wa-caption-s"} [:i18n/tr :insurance/insurance-policy]]
     [:a {:href (urls/link-policy policy)}
      (:insurance.policy/name policy)]
     [:wa-badge {:appearance "outlined" :pill true}
      [:i18n/tr (insurance.ui/policy-status-label-key
                 (:insurance.policy/status policy))]]
     (when-let [effective-until (:insurance.policy/effective-until policy)]
       [:span {:class "wa-caption-s"}
        [:i18n/tr :insurance/effective-until]
        ": "
        (form/date-value effective-until)])]
    [:wa-callout {:appearance "outlined" :variant "warning"}
     [:i18n/tr :none]]))

(defn- insurance-coverage-row [coverage]
  (let [{:instrument.coverage/keys [private? value]
         {:instrument/keys [name category]} :instrument.coverage/instrument}
        coverage
        category-name (:instrument.category/name category)
        kind-badge    (insurance-kind-badge private?)]
    [:tr
     [:td {:class "align-middle"}
      [:div {:class "wa-stack wa-gap-3xs"}
       [:a {:href (urls/link-coverage coverage)} name]
       [:div {:class "member-insurance-row-meta wa-cluster wa-gap-xs"}
        [:span (muted category-name)]
        kind-badge]]]
     [:td {:class "member-insurance-col align-middle"}
      (muted category-name)]
     [:td {:class "align-middle text-right"}
      (money-value value)]
     [:td {:class "member-insurance-col align-middle"}
      kind-badge]
     [:td {:class "align-middle text-right"}
      [button/Button {:appearance "plain"
                      :size       "s"
                      :href       (urls/link-coverage coverage)}
       [:i18n/tr :action/view]]]]))

(defn- insurance-coverages-table [coverages]
  (if (seq coverages)
    (ui2/table-shell
     [:table
      [:thead
       [:tr
        [:th [:i18n/tr :instrument/name]]
        [:th {:class "member-insurance-col"}
         [:i18n/tr :instrument/category]]
        [:th {:class "text-right"}
         [:i18n/tr :insurance/value]]
        [:th {:class "member-insurance-col"}
         [:i18n/tr :insurance/ownership]]
        [:th]]]
      [:tbody
       (for [coverage coverages]
         (insurance-coverage-row coverage))]])
    (ui2/empty-state
     [:i18n/tr :none]
     [:i18n/tr :members/insurance-subtitle])))

(defn- member-insurance-panel [{:keys [db]} member]
  (let [policy    (q/insurance-policy-effective-as-of db (t/inst) q/policy-pattern)
        coverages (q/instruments-for-member-covered-by db member policy q/instrument-coverage-detail-pattern)]
    [:div {:class "wa-stack wa-gap-l"}
     (ui2/section-card
      {:subtitle [:i18n/tr :members/insurance-subtitle]
       :actions  (when policy
                   [[button/Button {:appearance "outlined"
                                    :variant    "brand"
                                    :href       (urls/link-coverage-create (:insurance.policy/policy-id policy))}
                     [:i18n/tr :insurance/add-coverage]]])}
      [:div {:class "wa-stack wa-gap-m"}
       (insurance-policy-summary policy)
       (insurance-coverages-table coverages)])]))

(defn- contact-form-state [{:keys [page-state] :as req} member]
  (let [form-state (get-in page-state [:member-detail :contact])]
    (cond-> form-state
      (and form-state (auth/current-user-admin? req) (not (contains? form-state :sno-id-enabled)))
      (assoc :sno-id-enabled (boolean (keycloak-enabled? req (:member/keycloak-id member))))

      (and form-state (auth/current-user-admin? req) (not (contains? form-state :sno-id-enabled-original)))
      (assoc :sno-id-enabled-original (boolean (keycloak-enabled? req (:member/keycloak-id member)))))))

(def default-active-tab "travel")

(defn- normalize-active-tab [active-tab]
  (if (contains? actions/allowed-tabs active-tab)
    active-tab
    default-active-tab))

(defn- route-active-tab [req]
  (normalize-active-tab (http.util/path-param req :member-detail-tab)))

(defn- active-tab [req page-state]
  (normalize-active-tab
   (or (get-in page-state [:member-detail :active-tab])
       (route-active-tab req))))

(defn- tab-url-effect [member]
  (let [tab-urls (into {}
                       (for [tab actions/allowed-tabs]
                         [tab (urls/link-member-detail-tab member tab)]))]
    (format "window.history.replaceState({}, '', ((%s)[$member-detail.active-tab] || %s))"
            (d*/->signals tab-urls)
            (pr-str (urls/link-member-detail-tab member default-active-tab)))))

(defn- tab [req active-tab panel label]
  [:wa-tab (cond-> {:panel panel

                    :data-on:mousedown (->expr
                                        (evt.stopPropagation)
                                        (evt.preventDefault)
                                        (set! $member-detail.active-tab ~panel)
                                        (@post ~(d*/act req ::actions/set-active-tab)))}
             (= active-tab panel) (assoc :active true))
   label])

(defn- tab-panel [active-tab panel & children]
  (into [:wa-tab-panel (cond-> {:name panel}
                         (= active-tab panel) (assoc :active true))]
        (when (= active-tab panel)
          children)))

(defn page [{:keys [db page-state] :as req}]
  (let [member-id                       (http.util/path-param-uuid! req :member-id)
        member                          (q/retrieve-member db member-id)
        member-url                      (urls/link-member member)
        form-state                      (contact-form-state req member)
        sections                        (when form-state (q/retrieve-sections db))
        contact-signals                 (form-state->signals form-state)
        active-tab                      (active-tab req page-state)
        travel-discount-create-signals  (form-state->signals (get-in page-state [:member-detail :travel-discount-create]))
        travel-discount-signals         (form-state->signals (get-in page-state [:member-detail :travel-discount]))
        ledger-entry-signals            (form-state->signals (get-in page-state [:member-detail :ledger-entry]))]
    (ui2/datastar-page*
     [page-surface/PageSurface {::page-surface/toolbar
                                [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                                           [breadcrumb/Breadcrumb (cond-> {}
                                                                                    form-state (assoc ::breadcrumb/max-items [2 3]))
                                                            [breadcrumb/BreadcrumbItem {::breadcrumb/href "/members"}
                                                             [:i18n/tr :members/title]]
                                                            [breadcrumb/BreadcrumbItem (cond-> {}
                                                                                         form-state (assoc ::breadcrumb/href member-url))
                                                             (:member/name member)]
                                                            (when form-state
                                                              [breadcrumb/BreadcrumbItem [:i18n/tr :action/edit]])]
                                                           ::page-toolbar/actions
                                                           (if form-state
                                                             [[button/Button {:appearance  "plain"
                                                                              :data-id     "member-contact-cancel"
                                                                              :data-action (d*/act req ::actions/close-contact-edit)}
                                                               [:i18n/tr :action/cancel]]
                                                              [button/Button {:appearance         "filled"
                                                                              :variant            "brand"
                                                                              :type               "submit"
                                                                              :form               "member-contact-form"
                                                                              :data-attr:disabled "!!$loading && $loading !== 'member-contact'"
                                                                              :data-attr:loading  "$loading === 'member-contact'"}
                                                               [:i18n/tr :action/save]]]
                                                             [[button/Button {:appearance  "filled"
                                                                              :variant     "brand"
                                                                              :data-id     (:member/member-id member)
                                                                              :data-action (d*/act req ::actions/open-contact-edit)}
                                                               [:i18n/tr :action/edit]]])
                                                           ::page-toolbar/overflow-items
                                                           (when-not form-state
                                                             [[:wa-dropdown-item {:value   (str "/member-vcard/" (:member/member-id member))
                                                                                  :onclick "window.location = this.value"}
                                                               [:i18n/tr :members/download-contact]]])
                                                           ::page-toolbar/overflow-label
                                                           (when-not form-state [:i18n/tr :action/more-actions])
                                                           :aria-label
                                                           (if form-state
                                                             [:i18n/tr :members/edit-toolbar-label]
                                                             [:i18n/tr :members/detail-toolbar-label])}]}
      [:div {:class        "wa-stack wa-gap-2xl"
             :data-effect  (tab-url-effect member)
             :data-signals (d*/->signals {:member-detail {:active-tab             active-tab
                                                          :contact                contact-signals
                                                          :travel-discount-create travel-discount-create-signals
                                                          :travel-discount        travel-discount-signals
                                                          :ledger-entry           ledger-entry-signals}})}
       [:div {:class "wa-cluster wa-gap-s wa-align-items-center"}
        [avatar/Avatar {::avatar/member     member
                        ::avatar/image-size 200
                        ::avatar/icon       :user
                        :shape              "rounded"
                        :style              "--size: var(--sno-member-detail-avatar-size)"}]
        [page-header/PageHeader
         {::page-header/title (:member/name member)}]]
       (if form-state
         (contact-form req form-state sections)
         (profile-details req member))
       (when-not form-state
         [:wa-tab-group {:id     "member-detail-tabs"
                         :active active-tab}
          (tab req active-tab "travel" [:i18n/tr :members/travel-discounts-title])
          (tab req active-tab "money" "Money Stuff")
          (tab req active-tab "insurance" [:i18n/tr :members/insurance-title])

          (tab-panel active-tab "travel"
                     (travel-discounts-panel req member))
          (tab-panel active-tab "money"
                     (member-ledger-panel req member))
          (tab-panel active-tab "insurance"
                     (member-insurance-panel req member))])]])))

(d*/refresh-all!)
