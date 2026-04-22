(ns app.settings.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.queries :as q]
   [app.settings.actions :as actions]
   [app.settings.domain :as domain]
   [app.ui2.badge :as badge]
   [app.ui2.button :as btn]
   [app.ui2.core :as uic]
   [app.ui2.dialog :as dialog]
   [app.ui2.form :as form]
   [app.ui2.icon :as icon]
   [app.ui2.input :as input]
   [app.ui2.layout :as l]
   [app.ui2.select :as sel]
   [app.urls :as urls]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn plus-icon [attrs]
  [icon/Icon (merge {::icon/name :plus} attrs)])

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

(defn open-modal-button [{:keys [tr] :as req} form-name form-key-id action edit-any-row? ent-id]
  (let [fetching-signal  (str (name form-name) "-fetching")
        $fetching-signal (str "$" fetching-signal)
        form-ent-signal  (format "%s.%s" (name form-name) (name form-key-id))]
    [btn/Button {::btn/intent          :link
                 ::btn/disabled?       edit-any-row?
                 :type                 :button
                 :id                   (str "update-btn-" ent-id)
                 :data-indicator       fetching-signal
                 :data-attr:disabled   $fetching-signal
                 :data-class           (->expr {"spinning" (&& ($ fetching-signal)
                                                                  (= ($ form-ent-signal) ~(str ent-id)))})
                 :data-on:click        (->expr (set! ($ ~(name form-name) "." ~(name form-key-id)) ~(str ent-id))
                                               (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params action))))}
     (tr [:action/update])]))

(defn team-row [{:keys [tr] :as req} editing? edit-any-row? {team-name :team/name :team/keys [team-id members] :as team}]
  (list
   (when editing?
     [dialog/ConfirmDialog {:id                    (str "_delete-confirm-" team-id)
                            ::dialog/title         (tr [:action/confirm-generic])
                            ::dialog/prompt        (tr [:action/confirm-delete-team] [(str "\"" team-name "\"")])
                            ::dialog/on-hide       (format "$_delete-confirm-%s=false" team-id)
                            ::dialog/confirm-text  (tr [:action/confirm-delete])
                            ::dialog/cancel-text   (tr [:action/cancel])
                            ::dialog/on-confirm    (d*/act req ::actions/delete-team)
                            ::dialog/icon          dialog/AlertIcon}])
   (when editing?
     [dialog/FormDialog {:id              (str "edit-team-" team-id)
                         ::dialog/title   "Edit Team"
                         ::dialog/open    (format "$team.open && $team.team-id == '%s'" team-id)
                         ::dialog/on-hide (d*/act req ::actions/close-team-edit)}
      (team-edit-form req team)])
   [:dt {:class (uic/cs "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div team-name]]
   [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"}
     (if (seq members)
       [:div {:class "inline"}
        (->> members
             (map (fn [{:member/keys [name] :as member}]
                    [:a {:class "link-blue" :href (urls/link-member member)}
                     name]))
             (interpose ", "))]
       [:span {:class "text-gray-500 italic"} (tr [:team/no-members])])]
    [:div {:class "flex space-x-2 text-left"}
     (open-modal-button req :team :team-id ::actions/open-team-edit edit-any-row? team-id)]]))

(defn teams-panel [{:keys [page-state db tr] :as req}]
  (let [edit-id      (d*/get-form-current page-state :team :team-id)
        teams        (q/retrieve-all-teams db)
        editing-any? (some? edit-id)]
    [:div {:id                      "teams-panel"
           :data-signals__ifmissing (d*/->signals {:team-create {:open false}
                                                   :team        {:open false}})
           :data-signals            (d*/->signals {:team {:team-id edit-id}})}
     [l/Panel {::l/title    "Teams"
               ::l/subtitle "Because someone has to do the work"}
      [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
       (map-indexed (fn [_idx {:team/keys [team-id] :as team}]
                      (let [editing? (= edit-id team-id)]
                        [:div {:class "sm:flex" :id (str "team-container-" team-id)}
                         (team-row req editing? editing-any? team)]))
                    teams)]
      [btn/Button {:data-on:click__viewtransition "$team-create.open = !$team-create.open"
                   :data-show                     "!$team-create.open"
                   ::btn/icon                     plus-icon}
       (tr [:team/create-team])]
      (team-create-form req)]]))

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

(defn travel-discount-type-row [{:keys [tr] :as req} editing? edit-any-row? {:travel.discount.type/keys [discount-type-id discount-type-name enabled?] :as dt}]
  (list
   (when editing?
     [dialog/ConfirmDialog {:id                   (str "_delete-confirm-" discount-type-id)
                            ::dialog/title        (tr [:action/confirm-generic])
                            ::dialog/prompt       (tr [:action/confirm-delete] [(str "\"" discount-type-name "\"")])
                            ::dialog/on-hide      (format "$_delete-confirm-%s=false" discount-type-id)
                            ::dialog/confirm-text (tr [:action/confirm-delete])
                            ::dialog/cancel-text  (tr [:action/cancel])
                            ::dialog/on-confirm   (d*/act req ::actions/delete-discount-type)
                            ::dialog/icon         dialog/AlertIcon}])
   (when editing?
     [dialog/FormDialog {:id              (str "edit-discount-type-" discount-type-id)
                         ::dialog/title   (tr [:travel-discounts/discount-type-name])
                         ::dialog/open    (format "$discount-type.open && $discount-type.discount-type-id == '%s'" discount-type-id)
                         ::dialog/on-hide (d*/act req ::actions/close-discount-type-edit)}
      (travel-discount-type-edit-form req dt)])
   [:dt {:class (uic/cs "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div discount-type-name]]
   [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"}
     [badge/BoolBubble {::badge/value enabled?}]]
    [:div {:class "flex space-x-2 text-left"}
     (open-modal-button req :discount-type :discount-type-id ::actions/open-discount-type-edit edit-any-row? discount-type-id)]]))

(defn travel-discount-types [{:keys [page-state db tr] :as req}]
  (let [edit-id        (d*/get-form-current page-state :discount-type :discount-type-id)
        editing-any?   (some? edit-id)
        discount-types (q/retrieve-all-discount-types db)]
    [l/Panel {::l/title                   (tr [:travel-discounts/title])
              :id                         "travel-discount-types"
              :data-signals__ifmissing    (d*/->signals {:discount-type-create {:open false}
                                                         :discount-type        {:open false}})
              :data-signals               (d*/->signals {:discount-type {:discount-type-id edit-id}})}
     [:div
      [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
       (map-indexed (fn [_idx dt]
                      (let [dt-id    (:travel.discount.type/discount-type-id dt)
                            editing? (= edit-id dt-id)]
                        [:div {:class "sm:flex" :id (str "dt-container-" dt-id)}
                         (travel-discount-type-row req editing? editing-any? dt)]))
                    discount-types)]
      [:div {:class "flex border-t border-gray-100 pt-6"}
       (travel-discount-type-create-form req)
       [btn/Button {:data-on:click__viewtransition "$discount-type-create.open = !$discount-type-create.open"
                    :data-show                     "!$discount-type-create.open"
                    ::btn/icon                     plus-icon}
        (tr [:travel-discounts/add-discount-type])]]]]))

(defn sections-reordering [{:keys [tr] :as req} sections]
  [:div
   [:p "This is the order in which the sections appear on gig pages."]
   [:dl {:class             "mt-2 divide-y divide-gray-100 text-sm leading-6"
         :id                "sections-sort-container"
         :data-on:reordered (->expr (set! $section.order event.detail.orderInfo)
                                    (@post ~(urls/url-for req :app.routes.datastar/act nil (d*/action-query-params ::actions/update-section-order))))}
    (map-indexed (fn [idx section]
                   (let [section-name (:section/name section)]
                     [:div {:class             "sm:flex sm:items-center cursor-pointer"
                            :data-drag-item-id section-name
                            :id                (str "section-container-" section-name)}
                      [:div {:class "drag-handle cursor-pointer pr-3"}
                       [icon/Icon {::icon/name :bars :class "h-5 w-5"}]]
                      [:dt {:class (uic/cs "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
                       [:input {:type "hidden" :value idx :data-sort-order section-name}]
                       [:div section-name]]]))
                 sections)]
   [:div {:class "flex border-t border-gray-100 pt-6"}
    [btn/Button {::btn/intent                   :primary
                 :data-on:click__viewtransition (d*/act req ::actions/close-section-reorder)}
     (tr [:action/done])]]
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

(defn section-row [{:keys [tr] :as req} editing? edit-any-row? {:as section section-id :section/name enabled? :section/active?}]
  (assert section)
  (list
   (when editing?
     [dialog/FormDialog {:id              (str "edit-section-" section-id)
                         ::dialog/title   (tr [:section])
                         ::dialog/open    (format "$section.open && $section.section-id == '%s'" section-id)
                         ::dialog/on-hide (d*/act req ::actions/close-section-edit)}
      (section-edit-form req section)])
   [:dt {:class (uic/cs "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div section-id]]
   [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"}
     [badge/BoolBubble {::badge/value enabled?}]]
    [:div {:class "flex space-x-2 text-left"}
     (open-modal-button req :section :section-id ::actions/open-section-edit edit-any-row? section-id)]]))

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

(defn sections-default [{:keys [tr] :as req} sections edit-id editing-any?]
  [:div
   [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
    (map-indexed (fn [_idx section]
                   (let [section-name (:section/name section)
                         editing?     (= edit-id section-name)]
                     [:div {:class "sm:flex" :id (str "section-container-" section-name)}
                      (section-row req editing? editing-any? section)]))
                 sections)]
   [:div {:class "flex border-t border-gray-100 pt-6"}
    (section-create-form req)
    [btn/Button {:data-on:click__viewtransition "$section-create.open = !$section-create.open"
                 :data-show                     "!$section-create.open"
                 ::btn/icon                     plus-icon}
     (tr [:section-add])]]])

(defn sections [{:keys [page-state db tr] :as req}]
  (let [edit-id      (d*/get-form-current page-state :section :section-id)
        reordering?  (get-in page-state [:section-reorder :open])
        editing-any? (some? edit-id)
        sections     (q/retrieve-sections db)]
    [l/Panel {::l/title                (tr [:sections])
              ::l/buttons              (when-not reordering?
                                         [btn/Button {:data-on:click__viewtransition (d*/act req ::actions/open-section-reorder)}
                                          (tr [:action/reorder])])
              :id                      "sections-panel"
              :data-signals__ifmissing (d*/->signals {:section-create  {:open false}
                                                      :section         {:open false}
                                                      :section-reorder {:open false}})
              :data-signals            (d*/->signals {:section {:section-id edit-id
                                                                :order      nil}})}
     (if reordering?
       (sections-reordering req sections)
       (sections-default req sections edit-id editing-any?))]))

(defn page [{:keys [tr] :as req}]
  (html/->str
   [:main {:class "flex-1" :id "main"}
    [l/PageHeader {::l/title (tr [:nav/band-settings])}]
    (teams-panel req)
    (travel-discount-types req)
    (sections req)]))

(d*/refresh-all!)
