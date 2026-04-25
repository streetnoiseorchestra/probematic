(ns app.members.detail.views
  (:require
   [app.datastar :as d*]
   [app.keycloak :as keycloak]
   [app.members.detail.actions :as actions]
   [app.queries :as q]
   [app.settings.view-support :as support]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- tr-fn [tr]
  (or tr (fn [path & _] (name (last path)))))

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
  [:div {:class "wa-stack wa-gap-xs"}
   [:dt label]
   [:dd value]])

(defn- field-error [form-state field]
  (get-in form-state [:error field :error]))

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

(defn- section-select [{:keys [tr]} form-state sections]
  (let [error (field-error form-state :section-name)]
    (into
     [:wa-select {:label        (tr [:section])
                  :appearance   "outlined"
                  :size         "medium"
                  :value        (:section-name form-state)
                  :hint         error
                  :data-invalid (when error "true")
                  :data-bind    "member-detail.contact.section-name"}
      [:wa-option {:value ""} " - "]]
     (for [{:section/keys [name]} sections]
       [:wa-option {:value name} name]))))

(defn- contact-form [{:keys [tr] :as req} form-state sections]
  [:form {:id             "member-contact-form"
          :data-id        "member-contact"
          :data-action    (d*/act req ::actions/update-contact)
          :data-on:submit "evt.preventDefault();"}
   [:div {:class "wa-stack wa-gap-l"}
    (when-let [top-error (field-error form-state :_top)]
      [:wa-callout {:appearance "outlined" :variant "danger"}
       top-error])
    [:input {:type "hidden"
             :data-bind "member-detail.contact.member-id"}]
    [:div {:class "wa-grid wa-gap-m" :style "--min-column-size: 18rem;"}
     (form-input form-state "member-detail.contact.name" (tr [:member/name]) {:required true :autofocus true})
     (form-input form-state "member-detail.contact.nick" (tr [:member/nick]) {})
     (form-input form-state "member-detail.contact.email" (tr [:Email]) {:type "email" :required true})
     (form-input form-state "member-detail.contact.phone" (tr [:Phone]) {:type "tel" :required true})
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
      (tr [:action/save])]]]])

(defn- keycloak-link [{:keys [system]} keycloak-id]
  (if (seq keycloak-id)
    [:a {:href (keycloak/link-user-edit (:env system) keycloak-id)} keycloak-id]
    (muted nil)))

(defn- contact-details [{:keys [tr] :as req} member]
  (let [{:member/keys [email phone username keycloak-id active?]} member]
    [:dl {:class "wa-grid wa-gap-2xl" :style "--min-column-size: 24ch;"}
     (detail-item (tr [:member/active?]) (status-badge tr active?))
     (detail-item (tr [:member/email]) (muted email))
     (detail-item (tr [:member/phone]) (muted phone))
     (detail-item (tr [:member/username]) (muted username))
     (detail-item (tr [:member/keycloak-id]) (keycloak-link req keycloak-id))
     (detail-item (tr [:sno-id]) (sno-id-badge keycloak-id))]))

(defn- contact-panel [{:keys [db page-state tr] :as req} member]
  (let [form-state (get-in page-state [:member-detail :contact])
        sections   (q/retrieve-sections db)]
    (support/settings-card
     {:title    (tr [:Contact-Information])
      :subtitle "Core member profile data used by the band roster and SNO ID."
      :actions  [[:wa-button {:appearance "outlined"
                              :href       (str "/member-vcard/" (:member/member-id member))}
                  (tr [:Contact-Download])]
                 (when-not form-state
                   [:wa-button {:appearance  "outlined"
                                :variant     "brand"
                                :type        "button"
                                :data-id     (:member/member-id member)
                                :data-action (d*/act req ::actions/open-contact-edit)}
                    (tr [:action/edit])])]}
     (if form-state
       (contact-form req form-state sections)
       (contact-details req member)))))

(defn- placeholder-panel [title body]
  [:div {:class "wa-stack wa-gap-m"}
   (support/empty-state title body)])

(defn- member-header [tr member]
  (let [section-name (get-in member [:member/section :section/name])
        src          (avatar-src member)]
    [:div {:class "wa-flank wa-flex-nowrap wa-align-items-center"}
     [:wa-avatar (cond-> {:label (member-name member)
                          :shape "rounded"
                          :style "--size: 4rem"}
                   src (assoc :image src))
      (when-not src
        [:wa-icon {:library "snoico"
                   :name    "user"
                   :slot    "icon"}])]
     [:div {:class "wa-stack wa-gap-2xs"}
      [:wa-breadcrumb
       [:wa-breadcrumb-item {:href "/members"}
        (tr [:nav/members])]
       [:wa-breadcrumb-item
        (member-name member)]]
      [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
       [:h1 {:style "margin: 0;"} (member-name member)]
       (status-badge tr (:member/active? member))]
      [:span {:class "wa-caption-s"}
       (or section-name (tr [:section-none]))]]]))

(defn page [{:keys [db page-state tr] :as req}]
  (let [tr        (tr-fn tr)
        member-id (http.util/path-param-uuid! req :member-id)
        member    (q/retrieve-member db member-id)
        req       (assoc req :tr tr)]
    (support/datastar-page
     [:div {:class        "wa-stack wa-gap-2xl"
            :data-signals (d*/->signals {:member-detail {:contact (get-in page-state [:member-detail :contact])}})}
      (member-header tr member)
      [:wa-divider]
      [:wa-tab-group {:active "contact"}
       [:wa-tab {:panel "contact"} (tr [:Contact-Information])]
       [:wa-tab {:panel "discounts"} (tr [:travel-discounts/title])]
       [:wa-tab {:panel "ledger"} "Ledger"]
       [:wa-tab {:panel "insurance"} (tr [:member/insurance-title])]
       [:wa-tab {:panel "activity"} "Gigs & Probes"]

       [:wa-tab-panel {:name "contact"}
        (contact-panel req member)]
       [:wa-tab-panel {:name "discounts"}
        (placeholder-panel (tr [:travel-discounts/title]) "Travel discount management will move here next.")]
       [:wa-tab-panel {:name "ledger"}
        (placeholder-panel "Ledger" "Member ledger activity will move here next.")]
       [:wa-tab-panel {:name "insurance"}
        (placeholder-panel (tr [:member/insurance-title]) "Insurance and instrument details will move here next.")]
       [:wa-tab-panel {:name "activity"}
        (placeholder-panel "Gigs & Probes" "Attendance statistics will move here next.")]]])))

(d*/refresh-all!)
