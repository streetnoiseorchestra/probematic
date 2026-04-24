(ns app.settings.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.queries :as q]
   [app.settings.actions :as actions]
   [app.settings.domain :as domain]
   [app.ui2.button :as btn]
   [app.ui2.dialog :as dialog]
   [app.ui2.form :as form]
   [app.ui2.input :as input]
   [app.ui2.select :as sel]
   [app.urls :as urls]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn remove-on-click [req form-name form-key-id action ent-id]
  (->expr (set! ($ ~(name form-name) "." ~(name form-key-id)) ~(str ent-id))
          (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params action)))))

(defn safe-dom-id [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")))

(defn remove-dialog-id [prefix ent-id]
  (str prefix "-remove-" (safe-dom-id ent-id)))

(defn remove-confirm-dialog [{:keys [tr] :as req} {:keys [dialog-id title prompt action form-name form-key-id ent-id]}]
  #_[:wa-dialog {:id    dialog-id
                 :label title}
     [:p prompt]
     [:wa-button {:slot       "footer"
                  :appearance "outlined"
                  :data-dialog "close"}
      (tr [:action/cancel])]
     [:wa-button {:slot                         "footer"
                  :appearance                   "filled"
                  :variant                      "danger"
                  :data-dialog                  "close"
                ;; :data-on:click__viewtransition (remove-on-click req form-name form-key-id action ent-id)
                  :data-attr:disabled "!!$loading && $loading !== 'discount-type.discount-type-id'"
                  :data-attr:loading  "$loading === 'discount-type.discount-type-id'"
                  :data-id            discount-type-id
                  :data-action        (d*/act req ::actions/open-discount-type-edit)}
      (tr [:action/confirm-delete])]])

(defn active-badge [active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active? (assoc :variant "success")
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

(defn team-create-form [{:keys [tr] :as req}]
  (let [form-data {:ns      :team-create
                   :open    "team-create.open"
                   :command (d*/act req ::actions/create-team)
                   :fields  {:team-name ""}}
        controls  (input/input-button
                   [form/Input {::form/label   (tr [:team/name])
                                ::form/form    form-data
                                ::form/variant :hidden
                                :placeholder   (tr [:team/name])
                                :type          :text
                                :name          :team-name}]
                   [btn/Button {::btn/intent                   :secondary
                                :tabindex                      "-1"
                                :data-on:click__viewtransition "$team-create.open=false"}
                    (tr [:action/cancel])]
                   [btn/Button {::btn/intent :primary
                                ::btn/icon   (fn [attrs]
                                               [:wa-icon (merge {:name "plus"}
                                                                attrs)])
                                :type        :submit}
                    (tr [:action/create])])]
    [:div {:data-show "$team-create.open"}
     [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
      [form/Form {::form/form form-data}
       controls]]]))

(defn team-members-edit [{:keys [tr] :as req} all-members form-data {:team/keys [members team-type]}]
  [:div {:class "sm:col-span-6 flex flex-col space-y-2"}
   (if (seq members)
     [:div {:class "flex flex-col mb-4"}
      (->> members
           (map (fn [{:member/keys [name member-id] :as member}]
                  [:div {:class "grid grid-cols-3 items-center justify-between border-b border-gray-100"}
                   [:a {:class "col-span-2 link-blue" :href (urls/link-member member)} name]
                   [btn/Button {::btn/intent                    :link-destructive
                                ::btn/size                      :xsmall
                                :type                           :button
                                :data-on:click__viewtransition  (->expr (set! $team.remove-member-id ~(str member-id))
                                                                        (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params ::actions/remove-team-member))))}
                    (tr [:action/remove])]])))]
     [:div {:class "text-gray-500 italic mb-4"} (tr [:team/no-members])])
   (input/input-button
    [form/Select {::form/required? false
                  ::form/form      form-data
                  ::form/label     (tr [:team/choose-add-member])
                  ::form/variant   :hidden
                  ::form/options   (sel/member-options all-members :with-empty-opt? true)
                  :name            :member-id
                  :value           (when team-type (name team-type))}]
    [btn/Button {::btn/intent                    :secondary
                 :type                           :button
                 :data-on:click__viewtransition  (d*/act req ::actions/add-team-member)}
     (tr [:action/add])])])

(defn team-type-options [tr]
  (into [{:value "" :label " - "}]
        (map (fn [m] {:label (tr [m]) :value (name m)}) domain/team-types)))

(defn team-edit-form [{:keys [tr db] :as req} {team-name :team/name :team/keys [team-id team-type] :as team}]
  (let [all-members (q/members-for-select db)
        form-data   {:ns      :team
                     :open    "team.open"
                     :command (d*/act req ::actions/update-team)
                     :fields  {:team-name        team-name
                               :team-id          team-id
                               :team-type        (when team-type (name team-type))
                               :member-id        nil
                               :remove-member-id nil}}]
    [:div
     [form/Form {::form/form form-data}
      [form/Section {::form/compact? true}
       [form/HiddenInput {::form/form form-data :name :team-id}]
       [form/Input {::form/label (tr [:team/name])
                    ::form/form  form-data
                    :class       "sm:col-span-3"
                    :type        :text
                    :name        :team-name}]
       [form/Select {::form/label   (tr [:team/team-type])
                     ::form/form    form-data
                     ::form/options (team-type-options tr)
                     :class         "sm:col-span-3"
                     :name          :team-type}]]
      [form/Section {::form/compact? true
                     ::form/subtitle (tr [:team/members])}
       (team-members-edit req all-members form-data team)]
      [form/Actions {::form/left  [btn/Button {::btn/intent :secondary-destructive
                                               :data-on:click (format "$_delete-confirm-%s=true" team-id)}
                                   (tr [:action/delete])]
                     ::form/right (list
                                   [btn/Button {::btn/intent   :secondary
                                                ::btn/centered? true
                                                :data-dialog    "close"}
                                    (tr [:action/cancel])]
                                   [btn/Button {::btn/intent   :primary
                                                ::btn/centered? true
                                                :type           :submit}
                                    (tr [:action/save])])}]]]))

(defn team-dialogs [{:keys [tr] :as req} {team-name :team/name :team/keys [team-id] :as team}]
  (list
   [dialog/ConfirmDialog {:id                   (str "_delete-confirm-" team-id)
                          ::dialog/title        (tr [:action/confirm-generic])
                          ::dialog/prompt       (tr [:action/confirm-delete-team] [(str "\"" team-name "\"")])
                          ::dialog/on-hide      (format "$_delete-confirm-%s=false" team-id)
                          ::dialog/confirm-text (tr [:action/confirm-delete])
                          ::dialog/cancel-text  (tr [:action/cancel])
                          ::dialog/on-confirm   (d*/act req ::actions/delete-team)
                          ::dialog/icon         dialog/AlertIcon}]
   [dialog/FormDialog {:id              (str "edit-team-" team-id)
                       ::dialog/title   "Edit Team"
                       ::dialog/open    (format "$team.open && $team.team-id == '%s'" team-id)
                       ::dialog/on-hide (d*/act req ::actions/close-team-edit)}
    (team-edit-form req team)]))

(defn team-remove-dialog [{:keys [tr] :as req} {team-name :team/name :team/keys [team-id]}]
  #_(remove-confirm-dialog req {:dialog-id   (remove-dialog-id "team" team-id)
                                :title
                                :prompt
                                :action      ::actions/delete-team
                                :form-name   :team
                                :form-key-id :team-id
                                :ent-id      team-id}))

(defn team-table-row [{:keys [tr] :as req} _edit-any-row? {team-name :team/name :team/keys [team-id members team-type]}]
  (let [button-id (str "team-actions-" team-id)]
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
                        :items     [{:label    (tr [:action/update])
                                     :icon     "edit-pencil"
                                     :disabled true}
                                    {:label       (tr [:action/remove])
                                     :icon        "xmark"
                                     :variant     "danger"
                                     :data-dialog (format "open %s" (remove-dialog-id "team" team-id))}]})]]))

(defn teams-panel [{:keys [page-state db tr] :as req}]
  (let [edit-id      (d*/get-form-current page-state :team :team-id)
        teams        (q/retrieve-all-teams db)
        editing-any? (some? edit-id)]
    [:div {:id                      "teams-panel"
           :data-signals__ifmissing (d*/->signals {:team-create {:open false}
                                                   :team        {:open false}})
           :data-signals            (d*/->signals {:team {:team-id edit-id}})}
     (for [team teams]
       (team-remove-dialog req team))
     (settings-card {:title    "Teams"
                     :subtitle "Because someone has to do the work"
                     :actions  [[:wa-button {:appearance "outlined"
                                             :variant    "brand"
                                             :size       "medium"
                                             :with-start true
                                             :disabled   true}
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
                           (team-table-row req editing-any? team))]]
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

(defn travel-discount-type-table-row [{:keys [tr] :as req} _edit-any-row? {:as dt :travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
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
    (tap> [:dt-view-ps (:discount-type page-state) :sig (d*/->signals {:discount-type (:discount-type page-state)})])
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

#_(defn sections-reordering [{:keys [tr] :as req} sections]
    [:div {:class "wa-stack wa-gap-m"}
     [:wa-callout {:appearance "outlined" :variant "neutral"}
      [:wa-icon {:slot "icon" :name "sort" :variant "regular"}]
      "Drag sections to control the order in which they appear on gig pages."]
     (table-shell
      [:div {:id                "sections-sort-container"
             :class             "wa-stack wa-gap-0"
             :style             "padding-block: var(--wa-space-2xs);"
             :data-on:reordered (->expr (set! $section.order event.detail.orderInfo)
                                        (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params ::actions/update-section-order))))}
       (for [[idx section] (map-indexed vector sections)]
         (let [section-name (:section/name section)]
           [:div {:data-drag-item-id section-name
                  :id                (str "section-container-" section-name)
                  :style             "display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--wa-space-s); padding: var(--wa-space-s) 0;"}
            [:div {:class "drag-handle"
                   :style "color: var(--wa-color-text-quiet); cursor: grab;"}
             [:wa-icon {:name "drag" :label "Drag to reorder"}]]
            [:div {:style "min-inline-size: 0;"}
             [:input {:type "hidden" :value idx :data-sort-order section-name}]
             [:strong section-name]]
            [:wa-badge {:appearance "outlined" :variant "neutral" :pill true}
             (inc idx)]]))])
     [:div {:data-init "initEventSortable('sections-sort-container')"}]])

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
           :data-signals (d*/->signals {:section-create (:section-create page-state)
                                        :section        (:section page-state)})}
     (section-create-form req)
     (section-edit-form req)
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
                                #_[:wa-button {:appearance "outlined"
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
     #_(teams-panel req)
     (travel-discount-types req)
     (sections req)]]))

(d*/refresh-all!)
