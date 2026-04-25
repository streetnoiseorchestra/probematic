(ns app.members.detail.views
  (:require
   [app.datastar :as d*]
   [app.keycloak :as keycloak]
   [app.members.detail.actions :as actions]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.util.http :as http.util]
   [clojure.string :as str]))

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
      [:div {:class "wa-grid wa-gap-m" :style "--min-column-size: 18rem;"}
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
      [:div {:class "wa-cluster wa-justify-content-end"}
       [:wa-button {:appearance  "outlined"
                    :type        "button"
                    :data-id     "member-contact-cancel"
                    :data-action (d*/act req ::actions/close-contact-edit)}
        (tr [:action/cancel])]
       [:wa-button {:appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :data-attr:disabled "!!$loading && $loading !== 'member-contact'"
                    :data-attr:loading  "$loading === 'member-contact'"}
        (tr [:action/save])]]]]))

(defn- keycloak-link [{:keys [system]} keycloak-id]
  (if (seq keycloak-id)
    [:a {:href (keycloak/link-user-edit (:env system) keycloak-id)} keycloak-id]
    (muted nil)))

(defn- profile-details [{:keys [tr] :as req} member]
  (let [{:member/keys [email phone username keycloak-id active? nick]} member
        section-name (get-in member [:member/section :section/name])]
    [:dl {:class "particulars"}
     (detail-item (tr [:section]) (muted section-name))
     (detail-item (tr [:member/nick]) (muted nick))
     (detail-item (tr [:member/email]) (email-link email))
     (detail-item (tr [:member/phone]) (phone-link phone))
     (detail-item (tr [:member/active?]) (status-badge tr active?))
     (detail-item (tr [:member/username]) (muted username))
     (detail-item (tr [:member/keycloak-id]) (keycloak-link req keycloak-id))
     (detail-item (tr [:sno-id]) (sno-id-badge keycloak-id))]))

(defn- placeholder-panel [title body]
  [:div {:class "wa-stack wa-gap-m"}
   (ui2/empty-state title body)])

(defn- profile-summary [{:keys [tr] :as req} member]
  (let [src (avatar-src member)]
    [:section {:class "wa-stack wa-gap-l"}
     [:div {:class "wa-flank:end wa-align-items-start"}
      [:div {:class "wa-flank wa-flex-nowrap wa-align-items-center"}
       [:wa-avatar (cond-> {:label (member-name member)
                            :shape "rounded"}
                     src (assoc :image src))
        (when-not src
          [:wa-icon {:library "snoico"
                     :name    "user"
                     :slot    "icon"}])]
       [:h1 (:member/name member)]]
      [:div {:class "wa-cluster wa-gap-xs"}
       [:wa-button {:appearance "outlined"
                    :href       (str "/member-vcard/" (:member/member-id member))}
        (tr [:Contact-Download])]
       [:wa-button {:appearance  "outlined"
                    :variant     "brand"
                    :type        "button"
                    :data-id     (:member/member-id member)
                    :data-action (d*/act req ::actions/open-contact-edit)}
        (tr [:action/edit])]]]
     (profile-details req member)]))

(defn- member-header [{:keys [db page-state tr] :as req} member]
  (let [form-state (get-in page-state [:member-detail :contact])
        sections   (when form-state (q/retrieve-sections db))]
    [:header {:class "member-detail-header wa-stack wa-gap-m"}
     [:wa-breadcrumb
      [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
      [:wa-breadcrumb-item {:href "/members"}
       (tr [:nav/members])]
      [:wa-breadcrumb-item (cond-> {}
                             form-state (assoc :href (str "/members/" (:member/member-id member))))
       (:member/name member)]
      (when form-state
        [:wa-breadcrumb-item (tr [:action/edit])])]
     (if form-state
       (ui2/section-card {:title    (tr [:action/edit])}
                         (contact-form req form-state sections))
       (profile-summary req member))]))

(defn page [{:keys [db page-state tr] :as req}]
  (let [member-id       (http.util/path-param-uuid! req :member-id)
        member          (q/retrieve-member db member-id)
        form-state      (get-in page-state [:member-detail :contact])
        contact-signals (dissoc form-state :_error)]
    (ui2/datastar-page
     [:div {:class        "wa-stack wa-gap-2xl members-detail-page"
            :data-signals (d*/->signals {:member-detail {:contact contact-signals}})}
      (member-header req member)
      (when-not form-state
        [:wa-tab-group {:active "discounts"}
         [:wa-tab {:panel "discounts"} (tr [:travel-discounts/title])]
         [:wa-tab {:panel "ledger"} "Ledger"]
         [:wa-tab {:panel "insurance"} (tr [:member/insurance-title])]
         [:wa-tab {:panel "activity"} "Gigs & Probes"]

         [:wa-tab-panel {:name "discounts"}
          (placeholder-panel (tr [:travel-discounts/title]) "Travel discount management will move here next.")]
         [:wa-tab-panel {:name "ledger"}
          (placeholder-panel "Ledger" "Member ledger activity will move here next.")]
         [:wa-tab-panel {:name "insurance"}
          (placeholder-panel (tr [:member/insurance-title]) "Insurance and instrument details will move here next.")]
         [:wa-tab-panel {:name "activity"}
          (placeholder-panel "Gigs & Probes" "Attendance statistics will move here next.")]])])))

(d*/refresh-all!)
