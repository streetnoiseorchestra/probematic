(ns app.members.detail.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.datastar :as d*]
   [app.keycloak :as keycloak]
   [app.members.detail.actions :as actions]
   [app.members.ui :as members.ui]
   [app.qrcode :as qr]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]
   [tick.core :as t])
  (:import
   [java.text NumberFormat]
   [java.util Locale]))

(defn- member-name [{:member/keys [name nick]}]
  (if (str/blank? nick)
    name
    (str name " (" nick ")")))

(defn- avatar-src [member]
  (when-let [tpl (:member/avatar-template member)]
    (str "https://forum.streetnoise.at"
         (str/replace tpl "{size}" "200"))))

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

(defn- status-badge [tr active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active?       (assoc :variant "success")
               (not active?) (assoc :variant "neutral"))
   (if active?
     (tr [:Active])
     (tr [:Inactive]))])

(defn- keycloak-enabled? [{:keys [system]} keycloak-id]
  (when (seq keycloak-id)
    (try
      (keycloak/user-account-enabled? (:keycloak system) keycloak-id)
      (catch Throwable _
        false))))

(defn- sno-id-enabled-badge [tr enabled?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               enabled? (assoc :variant "success")
               (not enabled?) (assoc :variant "danger"))
   (if enabled?
     (tr [:member/sno-id-enabled])
     (tr [:member/sno-id-disabled]))])

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

(defn- field-error [form-state field]
  (get-in form-state [:_error field :error]))

(defn- form-input [form-state signal label attrs]
  (let [field (keyword (last (str/split signal #"\.")))
        error (field-error form-state field)]
    [:wa-input (merge {:label        label
                       :appearance   "outlined"
                       :size         "medium"
                       :value        (get form-state field "")
                       :hint         error
                       :data-invalid (when error "true")
                       :data-bind    signal}
                      attrs)]))

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

(defn- section-select [{:keys [tr] :as req} form-state sections]
  (let [error (field-error form-state :section-name)]
    (into
     [:wa-select {:label        (tr [:section])
                  :appearance   "outlined"
                  :size         "medium"
                  :value        (:section-name form-state)
                  :hint         error
                  :data-invalid (when error "true")
                  :data-bind    "member-detail.contact.section-name"
                  :data-on:blur (:data-on:blur (validate-field-on-blur req :section-name))}
      [:wa-option {:value ""} " - "]]
     (for [{:section/keys [name]} sections]
       [:wa-option {:value name} name]))))

(defn- sno-id-admin-fields [{:keys [tr] :as req} form-state]
  [:div {:class "member-detail-sno-id-admin-fields wa-stack wa-gap-m"}
   [:wa-divider]
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong (tr [:sno-id])]
    [:span {:class "wa-caption-s"}
     (tr [:member/sno-id-enable-disabled-tooltip])]]
   [:div {:class "wa-grid wa-gap-m"}
    (form-input form-state
                "member-detail.contact.username"
                (tr [:member/username])
                (validate-field-on-keydown req :username))
    (form-input form-state
                "member-detail.contact.keycloak-id"
                (tr [:member/keycloak-id])
                (validate-field-on-keydown req :keycloak-id))
    [:div {:class "wa-stack wa-gap-2xs"}
     [:span {:class "wa-caption-s"} (tr [:member/sno-id-enabled-disabled])]
     [:wa-switch {:size           "medium"
                  :checked        (:sno-id-enabled form-state)
                  :data-bind      "member-detail.contact.sno-id-enabled"
                  :data-on:change "$member-detail.contact.sno-id-enabled = !$member-detail.contact.sno-id-enabled"}
      (if (:sno-id-enabled form-state)
        (tr [:member/sno-id-enabled])
        (tr [:member/sno-id-disabled]))]]]])

(defn- contact-form [{:keys [tr] :as req} form-state sections]
  (let [validate-on-keydown #(validate-field-on-keydown req %)]
    [:form {:id             "member-contact-form"
            :data-id        "member-contact"
            :data-action    (d*/act req ::actions/update-contact)
            :data-on:submit "evt.preventDefault();"}
     [:div {:class "wa-stack wa-gap-l"}
      (when-let [top-error (field-error form-state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"}
         top-error])
      [:input {:type "hidden" :data-bind "member-detail.contact.member-id"}]
      [:div {:class "wa-grid wa-gap-m"}
       (form-input form-state
                   "member-detail.contact.name"
                   (tr [:member/name])
                   (merge {:required true :autofocus true} (validate-on-keydown :name)))
       (form-input form-state
                   "member-detail.contact.nick"
                   (tr [:member/nick])
                   (merge {:required true} (validate-on-keydown :nick)))
       (form-input form-state
                   "member-detail.contact.email"
                   (tr [:Email])
                   (merge {:type "email" :required true} (validate-on-keydown :email)))
       (form-input form-state
                   "member-detail.contact.phone"
                   (tr [:Phone])
                   (merge {:type "tel" :required true} (validate-on-keydown :phone)))
       (section-select req form-state sections)
       [:div {:class "wa-stack wa-gap-2xs"}
        [:span {:class "wa-caption-s"} (tr [:member/active?])]
        [:wa-switch {:size           "medium"
                     :checked        (:active form-state)
                     :data-bind      "member-detail.contact.active"
                     :data-on:change "$member-detail.contact.active = !$member-detail.contact.active"}
         (tr [:Active])]]]
      (when (auth/current-user-admin? req)
        (sno-id-admin-fields req form-state))
      (ui2/action-bar
       {}
       [[:wa-button {:appearance  "outlined"
                     :type        "button"
                     :data-id     "member-contact-cancel"
                     :data-action (d*/act req ::actions/close-contact-edit)}
         (tr [:action/cancel])]
        [:wa-button {:appearance         "filled"
                     :variant            "brand"
                     :type               "submit"
                     :data-attr:disabled "!!$loading && $loading !== 'member-contact'"
                     :data-attr:loading  "$loading === 'member-contact'"}
         (tr [:action/save])]])]]))

(defn- keycloak-link [{:keys [system]} keycloak-id]
  (if (seq keycloak-id)
    [:a {:href (keycloak/link-user-edit (:env system) keycloak-id)} keycloak-id]
    (muted nil)))

(defn- profile-details [{:keys [tr] :as req} member]
  (let [{:member/keys [email phone username keycloak-id active? nick]} member
        section-name (get-in member [:member/section :section/name])
        current-user-admin? (auth/current-user-admin? req)
        enabled?     (when current-user-admin? (keycloak-enabled? req keycloak-id))]
    [:dl {:class "particulars"}
     (detail-item (tr [:section]) (muted section-name))
     (detail-item (tr [:member/nick]) (muted nick))
     (detail-item (tr [:member/email]) (email-link email))
     (detail-item (tr [:member/phone]) (phone-link phone))
     (detail-item (tr [:member/active?]) (status-badge tr active?))
     (detail-item (tr [:member/username]) (muted username))
     (detail-item (tr [:member/keycloak-id]) (keycloak-link req keycloak-id))
     (detail-item (tr [:sno-id]) (if (and current-user-admin? keycloak-id)
                                   (sno-id-enabled-badge tr enabled?)
                                   (sno-id-badge keycloak-id)))]))

(defn- form-state->signals [form-state]
  (if (map? form-state)
    (dissoc form-state :_error)
    form-state))

(defn- date-value [value]
  (members.ui/date-value value))

(defn- expiry-badge [{:keys [tr]} discount]
  (members.ui/travel-discount-badge tr discount (date-value (:travel.discount/expiry-date discount))))

(defn- discount-type-select [{:keys [tr]} form-state discount-types]
  (let [error (field-error form-state :discount-type-id)]
    (into
     [:wa-select {:label             (tr [:travel-discounts/discount-type-name])
                  :appearance        "outlined"
                  :size              "medium"
                  :value             (:discount-type-id form-state)
                  :hint              error
                  :data-invalid      (when error "true")
                  :data-bind         "member-detail.travel-discount-create.discount-type-id"
                  :data-on:wa-change "$member-detail.travel-discount-create.discount-type-id = evt.target.value"}
      [:wa-option {:value ""} " - "]]
     (for [{:travel.discount.type/keys [discount-type-id discount-type-name]} discount-types]
       [:wa-option {:value (str discount-type-id)} discount-type-name]))))

(defn- travel-discount-create-form [{:keys [tr] :as req} form-state discount-types]
  (when form-state
    [:form {:id             "member-travel-discount-create-form"
            :data-id        "member-travel-discount-create"
            :data-action    (d*/act req ::actions/add-travel-discount)
            :data-on:submit "evt.preventDefault();"}
     [:div {:class "wa-stack wa-gap-m"}
      (when-let [top-error (field-error form-state :_top)]
        [:wa-callout {:appearance "outlined" :variant "danger"}
         top-error])
      [:input {:type      "hidden"
               :value     (:member-id form-state)
               :data-bind "member-detail.travel-discount-create.member-id"}]
      [:div {:class "wa-grid wa-gap-m"}
       (discount-type-select req form-state discount-types)
       (form-input form-state
                   "member-detail.travel-discount-create.expiry-date"
                   (tr [:travel-discounts/expiry-date])
                   {:type             "date"
                    :data-on:wa-input "$member-detail.travel-discount-create.expiry-date = evt.target.value"})]
      (ui2/action-bar
       {}
       [[:wa-button {:appearance  "outlined"
                     :type        "button"
                     :data-id     "member-travel-discount-create-cancel"
                     :data-action (d*/act req ::actions/close-travel-discount-create)}
         (tr [:action/cancel])]
        [:wa-button {:appearance         "filled"
                     :variant            "brand"
                     :type               "submit"
                     :data-attr:disabled "!!$loading && $loading !== 'member-travel-discount-create'"
                     :data-attr:loading  "$loading === 'member-travel-discount-create'"}
         (tr [:action/add])]])]]))

(defn- travel-discount-edit-form [{:keys [tr] :as req} form-state]
  [:form {:id             (str "member-travel-discount-edit-" (:discount-id form-state))
          :data-id        "member-travel-discount"
          :data-action    (d*/act req ::actions/update-travel-discount)
          :data-on:submit "evt.preventDefault();"}
   [:div {:class "wa-cluster wa-gap-s wa-align-items-end wa-justify-content-end"}
    (when-let [top-error (field-error form-state :_top)]
      [:wa-callout {:appearance "outlined" :variant "danger"}
       top-error])
    [:input {:type      "hidden"
             :value     (:discount-id form-state)
             :data-bind "member-detail.travel-discount.discount-id"}]
    (form-input form-state
                "member-detail.travel-discount.expiry-date"
                (tr [:travel-discounts/expiry-date])
                {:type             "date"
                 :data-on:wa-input "$member-detail.travel-discount.expiry-date = evt.target.value"})
    [:wa-button {:appearance  "outlined"
                 :type        "button"
                 :data-id     "member-travel-discount-cancel"
                 :data-action (d*/act req ::actions/close-travel-discount-edit)}
     (tr [:action/cancel])]
    [:wa-button {:appearance         "filled"
                 :variant            "brand"
                 :type               "submit"
                 :data-attr:disabled "!!$loading && $loading !== 'member-travel-discount'"
                 :data-attr:loading  "$loading === 'member-travel-discount'"}
     (tr [:action/save])]]])

(defn- travel-discount-remove-dialog [{:keys [tr] :as req} {:travel.discount/keys [discount-id discount-type]}]
  (ui2/remove-dialog
   {:id            (ui2/remove-dialog-id "travel-discount" discount-id)
    :label         (tr [:action/confirm-generic])
    :cancel-label  (tr [:action/cancel])
    :confirm-label (tr [:action/confirm-delete])
    :confirm-attrs {:data-id     discount-id
                    :data-action (d*/act req ::actions/delete-travel-discount)}}
   [:p (str (tr [:action/delete]) " " (:travel.discount.type/discount-type-name discount-type) "?")]))

(defn- travel-discount-row [{:keys [tr] :as req} edit-state {:travel.discount/keys [discount-id discount-type] :as discount}]
  (let [editing? (= discount-id (:discount-id edit-state))]
    [:tr
     [:td {:class "align-middle"}
      (:travel.discount.type/discount-type-name discount-type)]
     [:td {:class "align-middle"}
      (if editing?
        (travel-discount-edit-form req edit-state)
        (expiry-badge req discount))]
     [:td {:class "align-middle text-right"}
      (when-not editing?
        [:div {:class "wa-cluster wa-gap-2xs wa-justify-content-end"}
         [:wa-button {:appearance  "outlined"
                      :variant     "brand"
                      :size        "small"
                      :type        "button"
                      :data-id     discount-id
                      :data-action (d*/act req ::actions/open-travel-discount-edit)}
          (tr [:action/update])]
         [:wa-button {:appearance  "outlined"
                      :variant     "danger"
                      :size        "small"
                      :type        "button"
                      :data-dialog (format "open %s" (ui2/remove-dialog-id "travel-discount" discount-id))}
          (tr [:action/delete])]])]]))

(defn- travel-discounts-table [{:keys [tr] :as req} edit-state discounts]
  (if (seq discounts)
    (ui2/table-shell
     [:table
      [:thead
       [:tr
        [:th (tr [:travel-discounts/discount-type-name])]
        [:th (tr [:travel-discounts/expiry-date])]
        [:th]]]
      [:tbody
       (for [discount discounts]
         (travel-discount-row req edit-state discount))]])
    (ui2/empty-state
     (tr [:travel-discounts/none])
     (tr [:travel-discounts/subtitle]))))

(defn- travel-discounts-panel [{:keys [db page-state tr] :as req} member]
  (let [create-state   (get-in page-state [:member-detail :travel-discount-create])
        edit-state     (get-in page-state [:member-detail :travel-discount])
        discount-types (q/retrieve-all-discount-types db)
        discounts      (q/member-travel-discounts member)]
    [:div {:class "wa-stack wa-gap-l"}
     (ui2/section-card
      {:subtitle (tr [:travel-discounts/subtitle])
       :actions  (when-not create-state
                   [[:wa-button {:appearance  "outlined"
                                 :variant     "brand"
                                 :type        "button"
                                 :data-id     (:member/member-id member)
                                 :data-action (d*/act req ::actions/open-travel-discount-create)}
                     (tr [:travel-discounts/add-discount])]])}
      (when create-state
        [:div {:class "wa-stack wa-gap-s"}
         (travel-discount-create-form req create-state discount-types)
         [:wa-divider]])
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

(defn- ledger-balance-status [{:keys [tr system] :as req} balance entries]
  (let [member-owes-band? (pos? balance)
        band-owes-member? (neg? balance)
        {:keys [iban bic account-name]} (config/band-bank-info (-> system :env))]
    (cond
      band-owes-member?
      (tr [:band-owes-you] [(currency-format (abs balance))])
      member-owes-band?
      [:div {:class "wa-grid wa-gap-m"}
       [:div {:class "wa-justify-content-center"}
        (when-let [qr-value (payment-qr-value req balance entries)]
          [:wa-qr-code {:value qr-value :label "Scan this code with your banking app to start a transfer"}])]
       [:div {:class "wa-flank:end content-percentage-70"}
        [:p (tr [:please-pay-to-band] [(currency-format balance)])]
        (when (and account-name iban bic)
          [:div {:class "wa-stack wa-gap-3xs"}
           [:span "Name: " [:strong account-name]]
           [:span "IBAN: " [:strong (iban-format iban)]]
           [:span "BIC: " [:strong bic]]
           [:span (tr [:or-scan-qr-code])]])]])))

(defn- ledger-balance-card [{:keys [tr] :as req} member ledger]
  (let [balance (or (:ledger/balance ledger) 0)
        entries (:ledger/entries ledger)]
    [:div {:class ""}
     [:div {:class "wa-stack wa-gap-xs"}
      [:span {:class "wa-caption-s"} (tr [:outstanding-balance])]
      [:div {:class (str "wa-heading-xl " (ledger-amount-color-class balance))}
       (currency-format balance)]
      [:a {:href (urls/link-member-ledger-table member)} "Why?"]]
     (ledger-balance-status req balance entries)]))

(defn- ledger-entry-direction-options [{:keys [tr]} member kind]
  (let [name (:member/name member)]
    (if (= kind "payment")
      [{:value "credit" :label (tr [:ledger.entry/payment-direction-credit] [name])}
       {:value "debit" :label (tr [:ledger.entry/payment-direction-debit] [name])}]
      [{:value "debit" :label (tr [:ledger.entry/direction-debit] [name])}
       {:value "credit" :label (tr [:ledger.entry/direction-credit] [name])}])))

(defn- ledger-direction-button [value label]
  [:wa-button {:appearance    "outlined"
               :variant       "brand"
               :type          "button"
               :data-on:click (str "$_ledgerDirection = '" value "'; "
                                   "$member-detail.ledger-entry.tx-direction = '" value "'")}
   label])

(defn- ledger-direction-choice [req member form-state]
  [:div {:class     "wa-stack wa-gap-s"
         :data-show "$_ledgerDirection === ''"}
   (when-let [error (field-error form-state :tx-direction)]
     [:wa-callout {:appearance "outlined" :variant "danger"}
      error])
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong "Choose what happened"]
    [:span {:class "wa-caption-s"}
     "Start with the sentence that best describes this transaction."]]
   (into
    [:div {:class "wa-cluster wa-gap-s"}]
    (for [{:keys [value label]} (ledger-entry-direction-options req member (:tx-kind form-state))]
      (ledger-direction-button value label)))])

(defn- ledger-entry-create-form [{:keys [tr] :as req} member form-state]
  (let [title          (if (= "payment" (:tx-kind form-state))
                         (tr [:ledger/add-payment])
                         (tr [:ledger/add-debt]))
        direction-text (into {}
                             (map (juxt :value :label))
                             (ledger-entry-direction-options req member (:tx-kind form-state)))]
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
      (when-let [top-error (field-error form-state :_top)]
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
      (ledger-direction-choice req member form-state)
      [:div {:class     "wa-stack wa-gap-m"
             :data-show "$_ledgerDirection !== ''"}
       [:div {:class "wa-flank:end wa-align-items-center"}
        [:span {:class     "wa-caption-s"
                :data-show "$_ledgerDirection === 'debit'"}
         (get direction-text "debit")]
        [:span {:class     "wa-caption-s"
                :data-show "$_ledgerDirection === 'credit'"}
         (get direction-text "credit")]
        [:wa-button {:appearance    "plain"
                     :type          "button"
                     :data-on:click "$_ledgerDirection = ''; $member-detail.ledger-entry.tx-direction = ''"}
         "Change direction"]]
       [:div {:class "wa-grid wa-gap-m"}
        (form-input form-state
                    "member-detail.ledger-entry.tx-date"
                    (tr [:ledger.entry/tx-date])
                    {:type             "date"
                     :required         true
                     :data-on:wa-input "$member-detail.ledger-entry.tx-date = evt.target.value"})
        (form-input form-state
                    "member-detail.ledger-entry.description"
                    (tr [:ledger.entry/description])
                    {:required         true
                     :data-on:wa-input "$member-detail.ledger-entry.description = evt.target.value"})
        (form-input form-state
                    "member-detail.ledger-entry.amount"
                    (tr [:ledger.entry/amount])
                    {:inputmode        "decimal"
                     :placeholder      "42,05"
                     :required         true
                     :data-on:wa-input "$member-detail.ledger-entry.amount = evt.target.value"})]
       (ui2/action-bar
        {}
        [[:wa-button {:appearance  "outlined"
                      :type        "button"
                      :data-id     "member-ledger-entry-create-cancel"
                      :data-action (d*/act req ::actions/close-ledger-entry-create)}
          (tr [:action/cancel])]
         [:wa-button {:appearance         "filled"
                      :variant            "brand"
                      :type               "submit"
                      :data-attr:disabled "!!$loading && $loading !== 'member-ledger-entry-create'"
                      :data-attr:loading  "$loading === 'member-ledger-entry-create'"}
          (tr [:action/save])]])]
      (ui2/action-bar
       {:data-show "$_ledgerDirection === ''"}
       [[:wa-button {:appearance  "outlined"
                     :type        "button"
                     :data-id     "member-ledger-entry-create-cancel"
                     :data-action (d*/act req ::actions/close-ledger-entry-create)}
         (tr [:action/cancel])]])]]))

(defn- ledger-entry-remove-dialog [{:keys [tr] :as req} {:ledger.entry/keys [entry-id description]}]
  (ui2/remove-dialog
   {:id            (ui2/remove-dialog-id "ledger-entry" entry-id)
    :label         (tr [:action/confirm-generic])
    :cancel-label  (tr [:action/cancel])
    :confirm-label (tr [:action/confirm-delete])
    :confirm-attrs {:data-id     entry-id
                    :data-action (d*/act req ::actions/delete-ledger-entry)}}
   [:p (str (tr [:action/delete]) " " description "?")]))

(defn- ledger-entry-row [{:keys [tr]} {:ledger.entry/keys [amount description tx-date entry-id]}]
  [:tr {:id (str "ledger-entry-" entry-id)}
   [:td {:class "align-middle"}
    [:time {:datetime (str tx-date)} (date-value tx-date)]]
   [:td {:class "align-middle"} description]
   [:td {:class "align-middle text-right"} (ledger-amount-badge amount)]
   [:td {:class "align-middle text-right"}
    [:wa-button {:appearance  "plain"
                 :variant     "danger"
                 :size        "small"
                 :type        "button"
                 :data-dialog (format "open %s" (ui2/remove-dialog-id "ledger-entry" entry-id))}
     (tr [:action/delete])]]])

(defn- ledger-entries-table [{:keys [tr] :as req} entries]
  (if (seq entries)
    (ui2/table-shell
     [:table {:id "member-ledger-table"}
      [:thead
       [:tr
        [:th (tr [:ledger.entry/tx-date])]
        [:th (tr [:ledger.entry/description])]
        [:th {:class "text-right"} (tr [:ledger.entry/amount])]
        [:th]]]
      [:tbody
       (for [entry entries]
         (ledger-entry-row req entry))]])
    [:div {:id "member-ledger-table"}
     (ui2/empty-state
      (tr [:no-transactions-yet])
      "Ledger entries will appear here after a debt or payment is recorded.")]))

(defn- member-ledger-panel [{:keys [db page-state tr] :as req} member]
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
                   [[:wa-button {:appearance  "outlined"
                                 :variant     "brand"
                                 :type        "button"
                                 :data-id     (:member/member-id member)
                                 :data-action (d*/act req ::actions/open-ledger-debt-create)}
                     (tr [:ledger/add-debt])]
                    [:wa-button {:appearance  "outlined"
                                 :variant     "brand"
                                 :type        "button"
                                 :data-id     (:member/member-id member)
                                 :data-action (d*/act req ::actions/open-ledger-payment-create)}
                     (tr [:ledger/add-payment])]])}
      (ledger-balance-card req member ledger)
      (when create-state
        [:div {:class "wa-stack wa-gap-s"}
         [:wa-divider]
         (ledger-entry-create-form req member create-state)])
      [:wa-divider]
      (ledger-entries-table req entries)
      (for [entry entries]
        (ledger-entry-remove-dialog req entry)))]))

(defn- money-value [value]
  (if (nil? value)
    (muted nil)
    (.format (NumberFormat/getCurrencyInstance Locale/GERMANY) value)))

(defn- insurance-kind-badge [{:keys [tr]} private?]
  [:wa-badge {:appearance "outlined"
              :pill       true
              :variant    (if private? "neutral" "success")}
   (if private?
     (tr [:instrument.coverage/private])
     (tr [:instrument.coverage/band]))])

(defn- insurance-policy-summary [{:keys [tr]} policy]
  (if policy
    [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
     [:span {:class "wa-caption-s"} (tr [:insurance/insurance-policy])]
     [:a {:href (urls/link-policy policy)}
      (:insurance.policy/name policy)]
     [:wa-badge {:appearance "outlined" :pill true}
      (tr [(:insurance.policy/status policy)])]
     (when-let [effective-until (:insurance.policy/effective-until policy)]
       [:span {:class "wa-caption-s"}
        (str (tr [:insurance/effective-until]) ": " (date-value effective-until))])]
    [:wa-callout {:appearance "outlined" :variant "warning"}
     (tr [:none])]))

(defn- insurance-coverage-row [{:keys [tr] :as req} coverage]
  (let [{:instrument.coverage/keys [private? value]
         {:instrument/keys [name category] :as instrument} :instrument.coverage/instrument}
        coverage
        category-name (:instrument.category/name category)
        kind-badge    (insurance-kind-badge req private?)]
    [:tr
     [:td {:class "align-middle"}
      [:div {:class "wa-stack wa-gap-3xs"}
       [:a {:href (urls/link-instrument instrument)} name]
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
      [:wa-button {:appearance "plain"
                   :size       "small"
                   :href       (urls/link-coverage coverage)}
       (tr [:action/view])]]]))

(defn- insurance-coverages-table [{:keys [tr] :as req} coverages]
  (if (seq coverages)
    (ui2/table-shell
     [:table
      [:thead
       [:tr
        [:th (tr [:instrument/name])]
        [:th {:class "member-insurance-col"}
         (tr [:instrument/category])]
        [:th {:class "text-right"}
         (tr [:instrument.coverage/value])]
        [:th {:class "member-insurance-col"}
         (tr [:band-private])]
        [:th]]]
      [:tbody
       (for [coverage coverages]
         (insurance-coverage-row req coverage))]])
    (ui2/empty-state
     (tr [:none])
     (tr [:member/insurance-subtitle]))))

(defn- member-insurance-panel [{:keys [db tr] :as req} member]
  (let [policy    (q/insurance-policy-effective-as-of db (t/inst) q/policy-pattern)
        coverages (q/instruments-for-member-covered-by db member policy q/instrument-coverage-detail-pattern)]
    [:div {:class "wa-stack wa-gap-l"}
     (ui2/section-card
      {:subtitle (tr [:member/insurance-subtitle])
       :actions  (when policy
                   [[:wa-button {:appearance "outlined"
                                 :variant    "brand"
                                 :href       (urls/link-coverage-create (:insurance.policy/policy-id policy))}
                     (tr [:instrument.coverage/create-button])]])}
      [:div {:class "wa-stack wa-gap-m"}
       (insurance-policy-summary req policy)
       (insurance-coverages-table req coverages)])]))

(defn- profile-summary [{:keys [tr] :as req} member]
  (let [src (avatar-src member)]
    [:section {:class "wa-stack wa-gap-l"}
     [:div {:class "sno-section-header"}
      [:div {:class "sno-title-block wa-flank wa-flex-nowrap wa-align-items-center"}
       [:wa-avatar (cond-> {:label (member-name member)
                            :shape "rounded"}
                     src (assoc :image src))
        (when-not src
          [:wa-icon {:library "snoico"
                     :name    "user"
                     :slot    "icon"}])]
       [:h1 (:member/name member)]]
      (ui2/action-bar
       {}
       [[:wa-button {:appearance "outlined"
                     :href       (str "/member-vcard/" (:member/member-id member))}
         (tr [:Contact-Download])]
        [:wa-button {:appearance  "outlined"
                     :variant     "brand"
                     :type        "button"
                     :data-id     (:member/member-id member)
                     :data-action (d*/act req ::actions/open-contact-edit)}
         (tr [:action/edit])]])]
     (profile-details req member)]))

(defn- contact-form-state [{:keys [page-state] :as req} member]
  (let [form-state (get-in page-state [:member-detail :contact])]
    (cond-> form-state
      (and form-state (auth/current-user-admin? req) (not (contains? form-state :sno-id-enabled)))
      (assoc :sno-id-enabled (boolean (keycloak-enabled? req (:member/keycloak-id member))))

      (and form-state (auth/current-user-admin? req) (not (contains? form-state :sno-id-enabled-original)))
      (assoc :sno-id-enabled-original (boolean (keycloak-enabled? req (:member/keycloak-id member)))))))

(defn- member-header [{:keys [db tr] :as req} member]
  (let [form-state (contact-form-state req member)
        sections   (when form-state (q/retrieve-sections db))]
    [:header {:class "member-detail-header wa-stack wa-gap-m"}
     [:wa-breadcrumb
      [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
      [:wa-breadcrumb-item {:href "/members"}
       (tr [:nav/members])]
      [:wa-breadcrumb-item (cond-> {}
                             form-state (assoc :href (urls/link-member member)))
       (:member/name member)]
      (when form-state
        [:wa-breadcrumb-item (tr [:action/edit])])]
     (if form-state
       (ui2/section-card {:title    (tr [:action/edit])}
                         (contact-form req form-state sections))
       (profile-summary req member))]))

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

(defn page [{:keys [db page-state tr] :as req}]
  (let [member-id                       (http.util/path-param-uuid! req :member-id)
        member                          (q/retrieve-member db member-id)
        form-state                      (contact-form-state req member)
        contact-signals                 (form-state->signals form-state)
        active-tab                      (active-tab req page-state)
        travel-discount-create-signals  (form-state->signals (get-in page-state [:member-detail :travel-discount-create]))
        travel-discount-signals         (form-state->signals (get-in page-state [:member-detail :travel-discount]))
        ledger-entry-signals            (form-state->signals (get-in page-state [:member-detail :ledger-entry]))]
    (ui2/datastar-page
     [:div {:class        "wa-stack wa-gap-2xl members-detail-page"
            :data-effect  (tab-url-effect member)
            :data-signals (d*/->signals {:member-detail {:active-tab             active-tab
                                                         :contact                contact-signals
                                                         :travel-discount-create travel-discount-create-signals
                                                         :travel-discount        travel-discount-signals
                                                         :ledger-entry           ledger-entry-signals}})}
      (member-header req member)
      (when-not form-state
        [:wa-tab-group {:id "member-detail-tabs"
                        :active active-tab}
         (tab req active-tab "travel" (tr [:travel-discounts/title]))
         (tab req active-tab "money" "Money Stuff")
         (tab req active-tab "insurance" (tr [:member/insurance-title]))

         (tab-panel active-tab "travel"
                    (travel-discounts-panel req member))
         (tab-panel active-tab "money"
                    (member-ledger-panel req member))
         (tab-panel active-tab "insurance"
                    (member-insurance-panel req member))])])))

(d*/refresh-all!)
