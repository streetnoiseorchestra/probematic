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
   [app.ui2.icon :as icon]
   [app.ui2.input :as input]
   [app.ui2.select :as sel]
   [app.urls :as urls]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn plus-icon [attrs]
  [icon/Icon (merge {::icon/name :plus} attrs)])

(defn open-form-on-click [req form-name form-key-id action ent-id]
  (->expr (set! ($ ~(name form-name) "." ~(name form-key-id)) ~(str ent-id))
          (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params action)))))

(defn remove-on-click [req form-name form-key-id action ent-id]
  (->expr (set! ($ ~(name form-name) "." ~(name form-key-id)) ~(str ent-id))
          (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params action)))))

(defn safe-dom-id [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")))

(defn remove-dialog-id [prefix ent-id]
  (str prefix "-remove-" (safe-dom-id ent-id)))

(defn remove-confirm-dialog [{:keys [tr] :as req} {:keys [dialog-id title prompt action form-name form-key-id ent-id]}]
  [:wa-dialog {:id    dialog-id
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
                :data-on:click__viewtransition (remove-on-click req form-name form-key-id action ent-id)}
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
      [:wa-dropdown-item (assoc (select-keys item [:variant :data-dialog :data-on:click :disabled]) :value label)
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
                                ::btn/icon   plus-icon
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
  (remove-confirm-dialog req {:dialog-id (remove-dialog-id "team" team-id)
                              :title     (tr [:action/confirm-generic])
                              :prompt    (tr [:action/confirm-delete-team] [(str "\"" team-name "\"")])
                              :action    ::actions/delete-team
                              :form-name :team
                              :form-key-id :team-id
                              :ent-id    team-id}))

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

(defn travel-discount-type-create-form [{:keys [tr] :as req}]
  (let [form-data {:ns      :discount-type-create
                   :open    "discount-type-create.open"
                   :command (d*/act req ::actions/create-discount-type)
                   :fields  {:discount-type-name ""}}
        controls  (input/input-button
                   [form/Input {::form/label   (tr [:travel-discounts/discount-type-name])
                                ::form/form    form-data
                                ::form/variant :hidden
                                :placeholder   "Klimaticket Mond"
                                :type          :text
                                :name          :discount-type-name}]
                   [btn/Button {::btn/intent                   :secondary
                                :tabindex                      "-1"
                                :data-on:click__viewtransition "$discount-type-create.open=false"}
                    (tr [:action/cancel])]
                   [btn/Button {::btn/intent :primary
                                ::btn/icon   plus-icon
                                :type        :submit}
                    (tr [:action/create])])]
    [:div {:data-show "$discount-type-create.open"}
     [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
      [form/Form {::form/form form-data}
       controls]]]))

(defn travel-discount-type-edit-form [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (let [form-data {:ns      :discount-type
                   :open    "discount-type.open"
                   :command (d*/act req ::actions/update-discount-type)
                   :fields  {:discount-type-name    discount-type-name
                             :discount-type-id      discount-type-id
                             :discount-type-enabled enabled?}}]
    [:div
     [form/Form {::form/form form-data}
      [form/Section {::form/compact? true}
       [form/HiddenInput {::form/form form-data :name :discount-type-id}]
       [form/Input {::form/label (tr [:travel-discounts/discount-type-name])
                    ::form/form  form-data
                    :class       "sm:col-span-3"
                    :type        :text
                    :name        :discount-type-name}]
       [form/Toggle {::form/label (tr [:Active])
                     ::form/form  form-data
                     :value       "enabled"
                     :name        :discount-type-enabled
                     :class       "sm:col-span-3"}]]
      [form/Actions {::form/left  [btn/Button {::btn/intent :secondary-destructive
                                               :data-on:click (format "$_delete-confirm-%s=true" discount-type-id)}
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

(defn travel-discount-type-dialogs [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name] :as dt}]
  (list
   [dialog/ConfirmDialog {:id                   (str "_delete-confirm-" discount-type-id)
                          ::dialog/title        (tr [:action/confirm-generic])
                          ::dialog/prompt       (tr [:action/confirm-delete] [(str "\"" discount-type-name "\"")])
                          ::dialog/on-hide      (format "$_delete-confirm-%s=false" discount-type-id)
                          ::dialog/confirm-text (tr [:action/confirm-delete])
                          ::dialog/cancel-text  (tr [:action/cancel])
                          ::dialog/on-confirm   (d*/act req ::actions/delete-discount-type)
                          ::dialog/icon         dialog/AlertIcon}]
   [dialog/FormDialog {:id              (str "edit-discount-type-" discount-type-id)
                       ::dialog/title   (tr [:travel-discounts/discount-type-name])
                       ::dialog/open    (format "$discount-type.open && $discount-type.discount-type-id == '%s'" discount-type-id)
                       ::dialog/on-hide (d*/act req ::actions/close-discount-type-edit)}
    (travel-discount-type-edit-form req dt)]))

(defn travel-discount-type-remove-dialog [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name]}]
  (remove-confirm-dialog req {:dialog-id   (remove-dialog-id "discount-type" discount-type-id)
                              :title       (tr [:action/confirm-generic])
                              :prompt      (tr [:action/confirm-delete-discount-type] [(str "\"" discount-type-name "\"")])
                              :action      ::actions/delete-discount-type
                              :form-name   :discount-type
                              :form-key-id :discount-type-id
                              :ent-id      discount-type-id}))

(defn travel-discount-type-table-row [{:keys [tr] :as req} _edit-any-row? {:travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (let [button-id (str "discount-type-actions-" discount-type-id)]
    [:tr {:id (str "dt-container-" discount-type-id)}
     [:td {:style "vertical-align: middle"} discount-type-name]
     [:td {:style "vertical-align: middle"} (active-badge enabled?)]
     [:td {:style "vertical-align: top; text-align: end"}
      (row-action-menu {:button-id button-id
                        :items     [{:label    (tr [:action/update])
                                     :icon     "edit-pencil"
                                     :disabled true}
                                    {:label       (tr [:action/remove])
                                     :icon        "xmark"
                                     :variant     "danger"
                                     :data-dialog (format "open %s" (remove-dialog-id "discount-type" discount-type-id))}]})]]))

(defn travel-discount-types [{:keys [page-state db tr] :as req}]
  (let [edit-id        (d*/get-form-current page-state :discount-type :discount-type-id)
        editing-any?   (some? edit-id)
        discount-types (q/retrieve-all-discount-types db)]
    [:div {:id                      "travel-discount-types"
           :data-signals__ifmissing (d*/->signals {:discount-type-create {:open false}
                                                   :discount-type        {:open false}})
           :data-signals            (d*/->signals {:discount-type {:discount-type-id edit-id}})}
     (for [discount-type discount-types]
       (travel-discount-type-remove-dialog req discount-type))
     (settings-card {:title    (tr [:travel-discounts/title])
                     :subtitle "Reusable labels for member travel discounts."
                     :actions  [[:wa-button {:appearance "outlined"
                                             :variant    "brand"
                                             :size       "medium"
                                             :with-start true
                                             :disabled   true}
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
                           (travel-discount-type-table-row req editing-any? dt))]]
                       (empty-state "No travel discount types yet."
                                    "Add discount types so members can select them consistently."))))]))

(defn sections-reordering [{:keys [tr] :as req} sections]
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

(defn section-edit-form [{:keys [tr] :as req} {section-id :section/name enabled? :section/active?}]
  (let [form-data {:ns      :section
                   :open    "section.open"
                   :command (d*/act req ::actions/update-section)
                   :fields  {:section-old-name section-id
                             :section-name     section-id
                             :section-enabled  (true? enabled?)}}]
    [:div
     [form/Form {::form/form form-data}
      [form/Section {::form/compact? true}
       [form/HiddenInput {::form/form form-data :name :section-old-name}]
       [form/Input {::form/label (tr [:section])
                    ::form/form  form-data
                    :class       "sm:col-span-3"
                    :type        :text
                    :name        :section-name}]
       [form/Toggle {::form/label (tr [:Active])
                     ::form/form  form-data
                     :value       "enabled"
                     :name        :section-enabled
                     :class       "sm:col-span-3"}]]
      [form/Actions {::form/right (list
                                   [btn/Button {::btn/intent   :secondary
                                                ::btn/centered? true
                                                :data-dialog    "close"}
                                    (tr [:action/cancel])]
                                   [btn/Button {::btn/intent   :primary
                                                ::btn/centered? true
                                                :type           :submit}
                                    (tr [:action/save])])}]]]))

(defn section-dialogs [{:keys [tr] :as req} {:as section section-id :section/name}]
  [dialog/FormDialog {:id              (str "edit-section-" section-id)
                      ::dialog/title   (tr [:section])
                      ::dialog/open    (format "$section.open && $section.section-id == '%s'" section-id)
                      ::dialog/on-hide (d*/act req ::actions/close-section-edit)}
   (section-edit-form req section)])

(defn section-remove-dialog [{:keys [tr] :as req} {:as section section-id :section/name}]
  (remove-confirm-dialog req {:dialog-id   (remove-dialog-id "section" section-id)
                              :title       (tr [:action/confirm-generic])
                              :prompt      (tr [:action/confirm-delete-section] [(str "\"" section-id "\"")])
                              :action      ::actions/delete-section
                              :form-name   :section
                              :form-key-id :section-id
                              :ent-id      section-id}))

(defn section-table-row [{:keys [tr] :as req} _edit-any-row? {:as section section-id :section/name enabled? :section/active?}]
  (let [button-id (str "section-actions-" section-id)]
    [:tr {:id (str "section-container-" section-id)}
     [:td {:style "vertical-align: middle"} section-id]
     [:td {:style "vertical-align: middle"} (active-badge enabled?)]
     [:td {:style "vertical-align: top; text-align: end"}
      (row-action-menu {:button-id button-id
                        :items     [{:label    (tr [:action/update])
                                     :icon     "edit-pencil"
                                     :disabled true}
                                    {:label       (tr [:action/remove])
                                     :icon        "xmark"
                                     :variant     "danger"
                                     :data-dialog (format "open %s" (remove-dialog-id "section" section-id))}]})]]))

(defn section-create-form [{:keys [tr] :as req}]
  (let [form-data {:ns      :section-create
                   :open    "section-create.open"
                   :command (d*/act req ::actions/create-section)
                   :fields  {:section-name ""}}
        controls  (input/input-button
                   [form/Input {::form/label   (tr [:section])
                                ::form/form    form-data
                                ::form/variant :hidden
                                :placeholder   "Bass"
                                :type          :text
                                :name          :section-name}]
                   [btn/Button {::btn/intent                   :secondary
                                :tabindex                      "-1"
                                :data-on:click__viewtransition "$section-create.open=false"}
                    (tr [:action/cancel])]
                   [btn/Button {::btn/intent :primary
                                ::btn/icon   plus-icon
                                :type        :submit}
                    (tr [:action/create])])]
    [:div {:data-show "$section-create.open"}
     [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
      [form/Form {::form/form form-data}
       controls]]]))

(defn sections-default [{:keys [tr] :as req} sections editing-any?]
  [:div {:class "wa-stack wa-gap-m"}
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
          (section-table-row req editing-any? section))]]
      (empty-state "No sections yet."
                   "Add sections to group members and organize gig views.")))])

(defn sections [{:keys [page-state db tr] :as req}]
  (let [edit-id      (d*/get-form-current page-state :section :section-id)
        editing-any? (some? edit-id)
        sections     (q/retrieve-sections db)]
    [:div {:id                      "sections-panel"
           :data-signals__ifmissing (d*/->signals {:section-create  {:open false}
                                                   :section         {:open false}
                                                   :section-reorder {:open false}})
           :data-signals            (d*/->signals {:section {:section-id edit-id
                                                             :order      nil}})}
     (for [section sections]
       (section-remove-dialog req section))
     (settings-card {:title    (tr [:sections])
                     :subtitle "Choose which sections are visible and how they are ordered."
                     :actions  [[:wa-button {:appearance "outlined"
                                             :variant    "brand"
                                             :size       "medium"
                                             :with-start true
                                             :disabled   true}
                                 [:wa-icon {:slot "start" :name "plus"}]
                                 (tr [:section-add])]
                                [:wa-button {:appearance "outlined"
                                             :size       "medium"
                                             :with-start true
                                             :disabled   true}
                                 [:wa-icon {:slot "start" :name "sort"}]
                                 (tr [:action/reorder])]]}
                    (sections-default req sections editing-any?))]))

(defn page [{:keys [tr] :as req}]
  (html/->str
   [:main {:id "main"}
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
