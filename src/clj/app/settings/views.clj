(ns app.settings.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.queries :as q]
   [app.settings.actions :as actions]
   [app.settings.domain :as domain]
   [app.urls :as urls]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn safe-dom-id [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")))

(defn remove-dialog-id [prefix ent-id]
  (str prefix "-remove-" (safe-dom-id ent-id)))

(defn active-badge [active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active?       (assoc :variant "success")
               (not active?) (assoc :variant "neutral"))
   (if active? "Active" "Inactive")])

(defn settings-card [{:keys [id title subtitle actions] :as attrs} & children]
  (into
   [:section (merge {:id    id
                     :class "wa-stack"}
                    (dissoc attrs :id :title :subtitle :actions))
    [:div {:class "wa-flank:end wa-align-items-start"}
     [:div {:class "wa-stack"}
      [:h2 title]
      (when subtitle
        [:span {:class "wa-caption-s"} subtitle])]
     (when actions
       (into [:div {:class "wa-cluster wa-gap-xs"}]
             actions))]]
   children))

(defn row-action-menu [{:keys [button-id disabled? items]}]
  (list
   [:wa-dropdown {:placement "bottom-end"}
    [:wa-button {:id         button-id
                 :slot       "trigger"
                 :appearance "plain"
                 :disabled   disabled?
                 :aria-label "More actions"}
     [:wa-icon {:name "more-vert" :label "More actions"}]]
    (for [{:keys [label icon] :as item} items]
      [:wa-dropdown-item (dissoc item :icon)
       (when icon
         [:wa-icon {:slot "icon" :name icon :variant "regular"}])
       label])]
   [:wa-tooltip {:for button-id :without-arrow true}
    "More"]))

(defn table-shell [& children]
  (into
   [:div {:style "overflow-x: auto; background-color: var(--wa-color-neutral-fill-quiet); border-radius: var(--wa-panel-border-radius);"}]
   children))

(defn empty-state [title body]
  [:wa-callout {:appearance "outlined" :variant "neutral"}
   [:wa-icon {:slot "icon" :name "info-circle" :variant "regular"}]
   [:div {:class "wa-stack wa-gap-2xs"}
    [:strong title]
    [:span body]]])

(defn team-type-label [tr team-type]
  (if team-type
    (tr [team-type])
    "—"))

(defn team-type-options [tr]
  (into [{:value "" :label " - "}]
        (map (fn [m] {:label (tr [m]) :value (name m)}) domain/team-types)))

(defn team-member-label [{:member/keys [name nick]}]
  (if (seq nick)
    (str name " (" nick ")")
    name))

(defn team-create-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error]} (:team-create page-state)
        team-name-error (-> error :team-name :error)]
    (when (get-in page-state [:team-create :open])
      [:wa-dialog {:id                    "team-create-dialog"
                   :label                 (tr [:team/create-team])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-team-create)))}
       [:form {:id             "team-create-form"
               :data-id        "team-create"
               :data-action    (d*/act req ::actions/create-team)
               :data-on:submit "evt.preventDefault();"}
        [:wa-input {:placeholder  (tr [:team/name])
                    :type         :text
                    :required     true
                    :label        (tr [:team/name])
                    :autofocus    true
                    :hint         team-name-error
                    :data-invalid (if team-name-error "true" nil)
                    :data-bind    "team-create.team-name"
                    :name         :team-name}]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :form               "team-create-form"
                    :data-attr:disabled "!!$loading && $loading !== 'team-create'"
                    :data-attr:loading  "$loading === 'team-create'"}
        (tr [:action/create])]])))

(defn team-edit-form [{:keys [tr db page-state] :as req}]
  (let [{:keys [error team-id member-id team-type]} (:team page-state)
        team            (when team-id (q/retrieve-team db team-id))
        all-members     (q/members-for-select db)
        team-name-error (-> error :team-name :error)]
    (when team-id
      [:wa-dialog {:id                    "team-edit-dialog"
                   :label                 (tr [:action/update])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (str "if (evt.target !== el) return; evt.preventDefault(); @post('"
                                               (d*/act req ::actions/close-team-edit)
                                               "')")}
       [:form {:id             "team-edit-form"
               :data-id        "team"
               :data-action    (d*/act req ::actions/update-team)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type :hidden :name "team.team-id" :value nil}]
        [:div {:class "wa-stack wa-gap-m"}
         [:wa-input {:placeholder  (tr [:team/name])
                     :type         :text
                     :required     true
                     :label        (tr [:team/name])
                     :autofocus    true
                     :hint         team-name-error
                     :data-invalid (if team-name-error "true" nil)
                     :data-bind    "team.team-name"
                     :name         :team-name}]
         (into
          [:wa-select {:label     (tr [:team/team-type])
                       :name      :team-type
                       :value     (or team-type "")
                       :data-bind "team.team-type"}]
          (for [{:keys [label value]} (team-type-options tr)]
            [:wa-option {:value value} label]))
         [:div {:class "wa-stack wa-gap-s"}
          [:div {:class "wa-stack wa-gap-2xs"}
           [:span {:class "wa-caption-s"} (tr [:team/members])]
           (if (seq (:team/members team))
             (into
              [:div {:class "wa-stack wa-gap-2xs"}]
              (for [{:member/keys [member-id name] :as member} (:team/members team)]
                (let [loading-id (pr-str (str member-id))]
                  [:div {:class "wa-flank:end wa-align-items-center wa-gap-xs"
                         :style "padding: var(--wa-space-2xs) 0; border-bottom: 1px solid var(--wa-color-neutral-border-quiet);"}
                   [:a {:href (urls/link-member member)} name]
                   [:wa-button {:appearance         "plain"
                                :variant            "danger"
                                :size               "small"
                                :type               "button"
                                :data-id            (str member-id)
                                :data-action        (d*/act req ::actions/remove-team-member)
                                :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                                :data-attr:loading  (str "$loading === " loading-id)
                                :data-on:mousedown  (->expr (set! $team.remove-member-id ~(str member-id)))}
                    (tr [:action/remove])]])))
             [:span {:class "wa-caption-s"
                     :style "color: var(--wa-color-text-quiet); font-style: italic;"}
              (tr [:team/no-members])])]
          [:div {:class "wa-cluster wa-align-items-end"}
           (into
            [:wa-select {:label     (tr [:team/choose-add-member])
                         :name      :member-id
                         :value     (or member-id "")
                         :data-bind "team.member-id"}
             [:wa-option {:value ""} " - "]]
            (for [member all-members]
              [:wa-option {:value (:member/member-id member)}
               (team-member-label member)]))
           [:wa-button {:appearance         "outlined"
                        :variant            "brand"
                        :size               "medium"
                        :type               "button"
                        :data-id            "team-add-member"
                        :data-action        (d*/act req ::actions/add-team-member)
                        :data-attr:disabled "$team.member-id == null || $team.member-id === '' || (!!$loading && $loading !== 'team-add-member')"
                        :data-attr:loading  "$loading === 'team-add-member'"}
            (tr [:action/add])]]]]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :form               "team-edit-form"
                    :data-attr:disabled "!!$loading && $loading !== 'team'"
                    :data-attr:loading  "$loading === 'team'"}
        (tr [:action/save])]])))

(defn team-remove-dialog [{:keys [tr] :as req} {team-name :team/name :team/keys [team-id]}]
  (let [loading-id (pr-str (str team-id))]
    [:wa-dialog {:id    (remove-dialog-id "team" team-id)
                 :label (tr [:action/confirm-generic])}
     [:p (tr [:action/confirm-delete-team] [(str "\"" team-name "\"")])]
     [:wa-button {:slot        "footer"
                  :appearance  "outlined"
                  :data-dialog "close"}
      (tr [:action/cancel])]
     [:wa-button {:slot               "footer"
                  :appearance         "filled"
                  :variant            "danger"
                  :data-dialog        "close"
                  :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                  :data-attr:loading  (str "$loading === " loading-id)
                  :data-id            team-id
                  :data-action        (d*/act req ::actions/delete-team)}
      (tr [:action/confirm-delete])]]))

(defn team-table-row [{:keys [tr] :as req} _edit-any-row? {team-name :team/name :team/keys [team-id members team-type]}]
  (let [button-id  (str "team-actions-" team-id)
        loading-id (pr-str (str team-id))]
    [:tr {:id (str "team-container-" team-id)}
     [:td {:style "vertical-align: middle"} team-name]
     [:td {:style "vertical-align: middle"}
      (if (seq members)
        [:div {:class "wa-cluster wa-gap-2xs"}
         (for [{:member/keys [name] :as member} members]
           [:a {:href (urls/link-member member)}
            name])]
        [:span {:style "color: var(--wa-color-text-quiet); font-style: italic;"}
         (tr [:team/no-members])])]
     [:td {:style "vertical-align: middle"} (team-type-label tr team-type)]
     [:td {:style "vertical-align: top; text-align: end"}
      (row-action-menu {:button-id button-id
                        :items     [{:label              (tr [:action/update])
                                     :icon               "edit-pencil"
                                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                                     :data-attr:loading  (str "$loading === " loading-id)
                                     :data-id            team-id
                                     :data-action        (d*/act req ::actions/open-team-edit)}
                                    {:label       (tr [:action/remove])
                                     :icon        "xmark"
                                     :variant     "danger"
                                     :data-dialog (format "open %s" (remove-dialog-id "team" team-id))}]})]]))

(defn teams-panel [{:keys [page-state db tr] :as req}]
  (let [teams (q/retrieve-all-teams db)]
    [:div {:id           "teams-panel"
           :data-signals (d*/->signals {:team-create (:team-create page-state)
                                        :team        (:team page-state)})}
     (team-create-form req)
     (team-edit-form req)
     (for [team teams]
       (team-remove-dialog req team))
     (settings-card {:title    "Teams"
                     :subtitle "Because someone has to do the work"
                     :actions  [[:wa-button {:appearance  "outlined"
                                             :variant     "brand"
                                             :size        "medium"
                                             :with-start  true
                                             :data-id     "team-create"
                                             :data-action (d*/act req ::actions/open-team-create)}
                                 [:wa-icon {:slot "start" :name "plus"}]
                                 (tr [:team/create-team])]]}
                    (table-shell
                     (if (seq teams)
                       [:table
                        [:thead
                         [:tr
                          [:th "Team"]
                          [:th "Members"]
                          [:th "Type"]
                          [:th]]]
                        [:tbody
                         (for [team teams]
                           (team-table-row req nil team))]]
                       (empty-state "No teams yet."
                                    "Create a team to organize members around responsibilities."))))]))

(defn travel-discount-type-create-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error]} (:discount-type-create page-state)
        dt-name-error   (-> error :discount-type-name :error)]
    (when (get-in page-state [:discount-type-create :open])
      [:wa-dialog {:id              "discount-type-create-dialog"
                   :label           (tr [:travel-discounts/add-discount-type])

                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide (->expr
                                     (evt.preventDefault)
                                     (@post ~(d*/act req ::actions/close-discount-type-create)))}
       [:form {:id             "dt-create"
               :data-id        "discount-type-create"
               :data-action    (d*/act req ::actions/create-discount-type)
               :data-on:submit "evt.preventDefault();"}
        [:wa-input {:placeholder  "Klimaticket Mond"
                    :type         :text
                    :required     true
                    :label        (tr [:travel-discounts/discount-type-name])
                    :autofocus    true
                    :hint         (-> error :discount-type-name :error)
                    :data-invalid (if dt-name-error "true" nil)
                    :data-bind    "discount-type-create.discount-type-name"
                    :name         :discount-type-name}]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :data-attr:disabled "!!$loading && $loading !== 'discount-type-create'"
                    :data-attr:loading  "$loading === 'discount-type-create'"
                    :form               "dt-create"}
        (tr [:action/create])]])))

(defn travel-discount-type-edit-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error discount-type-id]} (:discount-type page-state)
        dt-name-error                    (-> error :discount-type-name :error)]
    (when discount-type-id
      [:wa-dialog {:id                    "discount-type-edit-dialog"
                   :label                 (tr [:travel-discounts/discount-type-name])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-discount-type-edit)))}
       [:form {:id "dt-edit-form"
               :data-id            "discount-type"
               :data-action        (d*/act req ::actions/update-discount-type)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type :hidden :name "discount-type.discount-type-id" :value nil}]
        [:wa-input {:placeholder  "Klimaticket Mond"
                    :type         :text
                    :required     true
                    :label        (tr [:travel-discounts/discount-type-name])
                    :autofocus    true
                    :hint         (-> error :discount-type-name :error)
                    :data-invalid (if dt-name-error "true" nil)
                    :data-bind    "discount-type.discount-type-name"
                    :name         :discount-type-name}]
        [:wa-switch {:data-attr:checked "$discount-type.discount-type-enabled"
                     :data-on:change    "$discount-type.discount-type-enabled = !$discount-type.discount-type-enabled"}
         (tr [:Active])]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :form               "dt-edit-form"
                    :data-attr:disabled "!!$loading && $loading !== 'discount-type'"
                    :data-attr:loading  "$loading === 'discount-type'"}
        (tr [:action/save])]])))

(defn travel-discount-type-remove-dialog [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name]}]
  [:wa-dialog {:id    (remove-dialog-id "discount-type" discount-type-id)
               :label (tr [:action/confirm-generic])}
   [:p (tr [:action/confirm-delete-discount-type] [(str "\"" discount-type-name "\"")])]
   [:wa-button {:slot        "footer"
                :appearance  "outlined"
                :data-dialog "close"}
    (tr [:action/cancel])]
   [:wa-button {:slot               "footer"
                :appearance         "filled"
                :variant            "danger"
                :data-dialog        "close"
                :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                :data-id            discount-type-id
                :data-action        (d*/act req ::actions/delete-discount-type)}
    (tr [:action/confirm-delete])]])

(defn travel-discount-type-table-row [{:keys [tr] :as req} _edit-any-row? {:travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (let [button-id (str "discount-type-actions-" discount-type-id)]
    [:tr {:id (str "dt-container-" discount-type-id)}
     [:td {:style "vertical-align: middle"} discount-type-name]
     [:td {:style "vertical-align: middle"} (active-badge enabled?)]
     [:td {:style "vertical-align: top; text-align: end"}
      (row-action-menu {:button-id button-id
                        :items     [{:label          (tr [:action/update])
                                     :icon           "edit-pencil"
                                     :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                                     :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                                     :data-id            discount-type-id
                                     :data-action        (d*/act req ::actions/open-discount-type-edit)}
                                    {:label       (tr [:action/remove])
                                     :icon        "xmark"
                                     :variant     "danger"
                                     :data-dialog (format "open %s" (remove-dialog-id "discount-type" discount-type-id))}]})]]))

(defn travel-discount-types [{:keys [db tr page-state] :as req}]
  (let [discount-types (q/retrieve-all-discount-types db)]
    [:div {:id                      "travel-discount-types"
           :data-signals (d*/->signals {:discount-type-create (:discount-type-create page-state)
                                        :discount-type (:discount-type page-state)})}

     (travel-discount-type-create-form req)
     (travel-discount-type-edit-form req)
     (for [discount-type discount-types]
       (travel-discount-type-remove-dialog req discount-type))
     (settings-card {:title    (tr [:travel-discounts/title])
                     :subtitle "Reusable labels for member travel discounts."
                     :actions  [[:wa-button {:appearance     "outlined"
                                             :variant        "brand"
                                             :size           "medium"
                                             :with-start     true
                                             :data-id "discount-type-create"
                                             :data-action (d*/act req ::actions/open-discount-type-create)}
                                 [:wa-icon {:slot "start" :name "plus"}]
                                 (tr [:travel-discounts/add-discount-type])]]}
                    (table-shell
                     (if (seq discount-types)
                       [:table
                        [:thead
                         [:tr
                          [:th (tr [:travel-discounts/discount-type-name])]
                          [:th "Status"]
                          [:th]]]
                        [:tbody
                         (for [dt discount-types]
                           (travel-discount-type-table-row req nil dt))]]
                       (empty-state "No travel discount types yet."
                                    "Add discount types so members can select them consistently."))))]))

(defn sections-reordering [{:keys [tr page-state] :as req} sections]
  (when (get-in page-state [:section-reorder :open])
    [:wa-dialog {:id                    "section-reorder-dialog"
                 :label                 (tr [:action/reorder])
                 :data-init__delay.10ms "el.open = true"
                 :data-signals          (d*/->signals {:section {:order []}})
                 :data-preserve-attr    "open"
                 :data-on:wa-hide       (->expr
                                         (evt.preventDefault)
                                         (@post ~(d*/act req ::actions/close-section-reorder)))}
     [:div {:class "wa-stack wa-gap-m"}
      [:wa-callout {:appearance "outlined" :variant "neutral"}
       [:wa-icon {:slot "icon" :name "sort" :variant "regular"}]
       "Drag sections to control the order in which they appear on gig pages."]
      [:div {:id                "sortContainer"
             :class             "wa-stack"
             :data-on:reordered (->expr
                                 (set! $section.order evt.detail.orderInfo)
                                 (@post ~(d*/act req ::actions/update-section-order)))}
       (for [[idx section] (map-indexed vector sections)]
         (let [section-name (:section/name section)
               active?      (:section/active? section)]
           [:div {:data-drag-item-id section-name
                  :class             "wa-flank"
                  :style             (str "cursor: grab; padding: var(--wa-space-xs) var(--wa-space-s); border-radius: var(--wa-border-radius-m);"
                                          " background-color: "
                                          (if active?
                                            "var(--wa-color-success-fill-quiet)"
                                            "var(--wa-color-neutral-fill-normal)")
                                          "; color: "
                                          (if active?
                                            "var(--wa-color-text-normal)"
                                            "var(--wa-color-text-quiet)")
                                          "; opacity: "
                                          (if active? "1" "0.65")
                                          ";")}
            [:div {:class "drag-handle"
                   :style (str "color: "
                               (if active?
                                 "var(--wa-color-success-on-quiet)"
                                 "var(--wa-color-text-quiet)")
                               ";")}
             [:wa-icon {:name "dots-grid-3x3" :label "Drag to reorder"}]]
            [:div {:style "min-inline-size: 0;"}
             [:input {:type "hidden" :value idx :data-sort-order section-name}]
             [:strong section-name]]]))]]
     [:script {:type :module}
      (html/raw "
    import Sortable from '/js/sortable@1.15.7-esm.js';
    new Sortable(sortContainer, {
        animation: 100,
        ghostClass: 'bg-sno-green-300',
        onEnd: () => {
          const data = [];
          sortContainer
            .querySelectorAll('input[data-sort-order]')
            .forEach((el) => {
              const id = el.getAttribute('data-sort-order');
              if (!id) {
                console.warn('no data-sort-order value found on dragged item');
                return;
              }
              data.push(id);
            });
          sortContainer.dispatchEvent(
                new CustomEvent('reordered', {detail: {
                    orderInfo: data
                }})
            )
        }
    })")]
     [:wa-button {:slot        "footer"
                  :appearance  "outlined"
                  :data-dialog "close"}
      (tr [:action/done])]]))

(defn section-create-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error]} (:section-create page-state)
        section-name-error (-> error :section-name :error)]
    (when (get-in page-state [:section-create :open])
      [:wa-dialog {:id                    "section-create-dialog"
                   :label                 (tr [:section-add])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-section-create)))}
       [:form {:id              "section-create-form"
               :data-id         "section-create"
               :data-action     (d*/act req ::actions/create-section)
               :data-on:submit  "evt.preventDefault();"}
        [:wa-input {:placeholder  "Bass"
                    :type         :text
                    :required     true
                    :label        (tr [:section])
                    :autofocus    true
                    :hint         (-> error :section-name :error)
                    :data-invalid (if section-name-error "true" nil)
                    :data-bind    "section-create.section-name"
                    :name         :section-name}]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :form               "section-create-form"
                    :data-attr:disabled "!!$loading && $loading !== 'section-create'"
                    :data-attr:loading  "$loading === 'section-create'"}
        (tr [:action/create])]])))

(defn section-edit-form [{:keys [tr page-state] :as req}]
  (let [{:keys [error section-id]} (:section page-state)
        section-name-error         (-> error :section-name :error)]
    (when section-id
      [:wa-dialog {:id                    "section-edit-dialog"
                   :label                 (tr [:section])
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-section-edit)))}
       [:form {:id             "section-edit-form"
               :data-id        "section"
               :data-action    (d*/act req ::actions/update-section)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type :hidden :name "section.section-id" :value nil}]
        [:wa-input {:placeholder  "Bass"
                    :type         :text
                    :required     true
                    :label        (tr [:section])
                    :autofocus    true
                    :hint         (-> error :section-name :error)
                    :data-invalid (if section-name-error "true" nil)
                    :data-bind    "section.section-name"
                    :name         :section-name}]
        [:wa-switch {:data-attr:checked "$section.section-enabled"
                     :data-on:change    "$section.section-enabled = !$section.section-enabled"}
         (tr [:Active])]]
       [:wa-button {:slot        "footer"
                    :appearance  "outlined"
                    :data-dialog "close"}
        (tr [:action/cancel])]
       [:wa-button {:slot               "footer"
                    :appearance         "filled"
                    :variant            "brand"
                    :type               "submit"
                    :form               "section-edit-form"
                    :data-attr:disabled "!!$loading && $loading !== 'section'"
                    :data-attr:loading  "$loading === 'section'"}
        (tr [:action/save])]])))

(defn section-remove-dialog [{:keys [tr] :as req} {:section/keys [name]}]
  (let [loading-id (pr-str (str name))]
    [:wa-dialog {:id    (remove-dialog-id "section" name)
                 :label (tr [:action/confirm-generic])}
     [:p (tr [:action/confirm-delete-section] [(str "\"" name "\"")])]
     [:wa-button {:slot        "footer"
                  :appearance  "outlined"
                  :data-dialog "close"}
      (tr [:action/cancel])]
     [:wa-button {:slot               "footer"
                  :appearance         "filled"
                  :variant            "danger"
                  :data-dialog        "close"
                  :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                  :data-attr:loading  (str "$loading === " loading-id)
                  :data-id            name
                  :data-action        (d*/act req ::actions/delete-section)}
      (tr [:action/confirm-delete])]]))

(defn section-table-row [{:keys [tr] :as req} _edit-any-row? {:section/keys [name active?]}]
  (let [button-id  (str "section-actions-" name)
        loading-id (pr-str (str name))]
    [:tr {:id (str "section-container-" name)}
     [:td {:style "vertical-align: middle"} name]
     [:td {:style "vertical-align: middle"} (active-badge active?)]
     [:td {:style "vertical-align: top; text-align: end"}
      (row-action-menu {:button-id button-id
                        :items     [{:label              (tr [:action/update])
                                     :icon               "edit-pencil"
                                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                                     :data-attr:loading  (str "$loading === " loading-id)
                                     :data-id            name
                                     :data-action        (d*/act req ::actions/open-section-edit)}
                                    {:label       (tr [:action/remove])
                                     :icon        "xmark"
                                     :variant     "danger"
                                     :data-dialog (format "open %s" (remove-dialog-id "section" name))}]})]]))

(defn sections [{:keys [page-state db tr] :as req}]
  (let [sections (q/retrieve-sections db)]
    [:div {:id           "sections-panel"
           :data-signals (d*/->signals {:section-create  (:section-create page-state)
                                        :section         (:section page-state)
                                        :section-reorder (:section-reorder page-state)})}
     (section-create-form req)
     (section-edit-form req)
     (sections-reordering req sections)
     (for [section sections]
       (section-remove-dialog req section))
     (settings-card {:title    (tr [:sections])
                     :subtitle "Choose which sections are visible and how they are ordered."
                     :actions  [[:wa-button {:appearance "outlined"
                                             :variant    "brand"
                                             :size       "medium"
                                             :with-start true
                                             :data-id    "section-create"
                                             :data-action (d*/act req ::actions/open-section-create)}
                                 [:wa-icon {:slot "start" :name "plus"}]
                                 (tr [:section-add])]
                                [:wa-button {:appearance "outlined"
                                             :size       "medium"
                                             :with-start true
                                             :data-id    "section-reorder"
                                             :data-action (d*/act req ::actions/open-section-reorder)}
                                 [:wa-icon {:slot "start" :name "sort"}]
                                 (tr [:action/reorder])]]}
                    (table-shell
                     (if (seq sections)
                       [:table
                        [:thead
                         [:tr
                          [:th (tr [:section])]
                          [:th "Status"]
                          [:th]]]
                        [:tbody
                         (for [section sections]
                           (section-table-row req nil section))]]
                       (empty-state "No sections yet."
                                    "Add sections to group members and organize gig views."))))]))

(defn page [{:keys [tr] :as req}]
  (html/->str
   [:main {:id "main"
           ;; top level action event handlers, handle bubbled up events
           :data-on:submit (->expr (when evt.target.dataset.action
                                     (evt.target.setAttribute "loading" "")
                                     (set! $loading evt.target.dataset.id)
                                     (set! $targetid evt.target.dataset.id)
                                     (@post ("`${evt.target.dataset.action}`"))))
           :data-on:mousedown (->expr (when evt.target.dataset.action
                                        (evt.target.setAttribute "loading" "")
                                        (set! $loading evt.target.dataset.id)
                                        (set! $targetid evt.target.dataset.id)
                                        (@post ("`${evt.target.dataset.action}`"))))
           :data-on:keydown (->expr (when (and evt.target.dataset.action (= evt.key "Enter"))
                                      (evt.target.setAttribute "loading" "")
                                      (set! $loading evt.target.dataset.id)
                                      (set! $targetid evt.target.dataset.id)
                                      (@post ("`${evt.target.dataset.action}`"))))}

    [:div {:class "wa-stack wa-gap-2xl"}
     [:div {:class "wa-stack"}
      [:span {:class "wa-caption-s"} "Administration"]
      [:h1 (tr [:nav/band-settings])]
      [:span {:class "wa-caption-s"}
       "Manage teams, travel discount types, and sections from one place."]
      [:wa-divider]]
     (teams-panel req)
     (travel-discount-types req)
     (sections req)]]))

(d*/refresh-all!)
