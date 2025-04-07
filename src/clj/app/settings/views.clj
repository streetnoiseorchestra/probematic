(ns app.settings.views
  (:require [app.datastar :as d*]
            [app.html :as html]
            [app.icons :as icon]
            [app.queries :as q]
            [app.settings.domain :as domain]
            [app.settings.routes :as commands]
            [app.ui :as ui]
            [app.ui.button :as button]
            [app.ui.button2 :as button2]
            [app.ui.core :as uic]
            [app.ui.dialog :as dialog]
            [app.ui.form :as form]
            [app.ui.input :as input]
            [app.ui.layout :as l]
            [app.urls :as urls]))

(defn team-create-form [{:keys [tr] :as req}]
  (let [form {:ns      :team-create
              :open    "team-create.open"
              :command (d*/dispatch req ::commands/create-team)
              :fields  {:team-name ""}}]
    [:div {:data-show "$team-create.open"}
     [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
      (form/form {:-form form}
                 (input/input-button (form/input {:-label      (tr [:team/name])
                                                  :-form       form
                                                  :-variant    :hidden
                                                  :placeholder (tr [:team/name])
                                                  :type        :text
                                                  :name        :team-name})
                                     (button2/button {:tabindex                      "-1"
                                                      :-priority                     :secondary
                                                      :data-on-click__viewtransition "$team-create.open=false"}
                                                     (tr [:action/cancel]))
                                     (button2/button {:-priority :primary
                                                      :-icon     icon/plus
                                                      :type      :submit}
                                                     (tr [:action/create]))))]]))

(defn team-members-edit [{:keys [tr] :as req} all-members form {:team/keys [members team-type]}]
  [:div {:class "sm:col-span-6 flex flex-col space-y-2"}
   ;; List of current members with remove buttons
   (if (seq members)
     [:div {:class "flex flex-col mb-4"}
      (->> members
           (map (fn [{:member/keys [name member-id] :as member}]
                  [:div {:class "grid grid-cols-3 items-center justify-between border-b border-gray-100"}
                   [:a {:class "col-span-2 link-blue" :href (urls/link-member member)} name]
                   (button2/button {:-priority                     :link-destructive
                                    :-size                         :xsmall
                                    :type                          :button
                                    :data-on-click__viewtransition (d*/expr (d*/assign "team.remove-member-id" member-id)
                                                                            (d*/dispatch req ::commands/remove-team-member))}
                                   (tr [:action/remove]))])))]

     [:div {:class "text-gray-500 italic mb-4"} (tr [:team/no-members])])
   (input/input-button
    (form/select {:-required? true
                  :-form      form
                  :-label     (tr [:team/choose-add-member])
                  :-variant   :hidden
                  :name       :member-id
                  :value      (when team-type (name team-type))
                  :-options   (ui/member-select-options all-members :with-empty-opt? true)})
    (button2/button {:-priority                     :secondary
                     :type                          :button
                     :data-on-click__viewtransition (d*/dispatch req ::commands/add-team-member)}
                    (tr [:action/add])))])

(defn team-edit-form [{:keys [tr db] :as req} {team-name :team/name :team/keys [team-id team-type] :as team}]
  (let [all-members (q/members-for-select db)
        form        {:ns      :team
                     :open    "team.open"
                     :command (d*/dispatch req ::commands/update-team)
                     :fields  {:team-name        team-name
                               :team-id          team-id
                               :team-type        (when team-type (name team-type))
                               :member-id        nil
                               :remove-member-id nil}}]
    [:div
     (form/form {:-form form}
                (form/section {:-compact? true}
                              (form/hidden {:name :team-id :-form form})
                              (form/input {:-label (tr [:team/name])
                                           :-form  form
                                           :class  "sm:col-span-3"
                                           :type   :text
                                           :name   :team-name})
                              (form/select {:-label   (tr [:team/team-type])
                                            :-form    form
                                            :-options (concat [{:value "" :label " - "}] (map (fn [m] {:label (tr [m]) :value (name m)}) domain/team-types))
                                            :class    "sm:col-span-3"
                                            :name     :team-type}))
                (form/section {:-compact? true :-subtitle (tr [:team/members])}
                              (team-members-edit req all-members form team))
                (form/actions
                 {:-left  (button2/button {:-priority     :secondary-destructive
                                           :data-on-click (format "$_delete-confirm-%s=true" team-id)}
                                          (tr [:action/delete]))
                  :-right (list
                           (button2/button {:-priority   :secondary
                                            :-centered?  true
                                            :data-dialog "close"}
                                           (tr [:action/cancel]))
                           (button2/button {:-priority  :primary
                                            :-centered? true
                                            :type       :submit}
                                           (tr [:action/save])))}))]))

(defn open-modal-button [{:keys [tr] :as req} form form-key-id command edit-any-row? ent-id]
  (let [fetching-signal  (str (name form) "-fetching")
        $fetching-signal (str "$" fetching-signal)
        $form-ent-signal (format "$%s.%s" (name form) (name form-key-id))]
    (button2/button (uic/attr-map :-priority                       :link
                                  :-disabled?                      edit-any-row?
                                  :type                            :button
                                  :id                              (str "update-btn-" ent-id)
                                  :data-indicator  fetching-signal
                                  :data-attr-disabled              $fetching-signal
                                  :data-class                      (format "{'spinning': %s && %s == '%s'}" $fetching-signal $form-ent-signal  ent-id)
                                  :data-on-click (d*/expr (format "%s='%s'" $form-ent-signal ent-id)
                                                          (d*/dispatch req command)))
                    (tr [:action/update]))))

(defn team-row [{:keys [tr] :as req} editing? edit-any-row? {team-name :team/name :team/keys [team-id members] :as team}]
  (list
   (when editing?
     (dialog/confirm-dialog :id (str "_delete-confirm-" team-id)
                            :title (tr [:action/confirm-generic])
                            :text (tr [:action/confirm-delete-team] [(str "\"" team-name "\"")])
                            :on-hide  (format "$_delete-confirm-%s=false" team-id)
                            :confirm-text (tr [:action/confirm-delete])
                            :cancel-text (tr [:action/cancel])
                            :on-confirm (d*/dispatch req ::commands/delete-team)
                            :icon icon/triangle-exclamation))
   (when editing?
     (dialog/form-dialog {:id      (str "edit-team-" team-id)
                          :title   "Edit Team"
                          :open    (format "$team.open && $team.team-id == '%s'" team-id)
                          :on-hide (d*/expr
                                    (str "$team.open &&" (d*/dispatch req ::commands/close-team-edit)))}
                         (team-edit-form req team)))

   [:dt {:class (uic/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
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
     (open-modal-button req :team :team-id ::commands/open-team-edit edit-any-row? team-id)]]))

(defn teams-panel
  [{:keys [page-state db tr] :as req}]
  (let [edit-id      (d*/get-form-current page-state :team :team-id)
        teams        (q/retrieve-all-teams db)
        editing-any? (some? edit-id)]
    [:div {:id                      "teams-panel"
           :data-signals__ifmissing (d*/->signals {:team-create {:open false}
                                                   :team        {:open false}})
           :data-signals            (d*/->signals {:team {:team-id edit-id}})}
     (l/panel {:-title   "Teams"
               :subtitle "Because someone has to do the work"}
              [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
               (map-indexed (fn [_idx  {:team/keys [team-id] :as team}]
                              (let [editing? (= edit-id team-id)]
                                [:div {:class "sm:flex" :id (str "team-container-" team-id)}
                                 (team-row req editing? editing-any? team)]))
                            teams)]

              (button2/button {:data-on-click__viewtransition "$team-create.open = !$team-create.open"
                               :data-show                     "!$team-create.open"
                               :-icon                         icon/plus}
                              (tr [:team/create-team]))
              (team-create-form req))]))

(defn travel-discount-type-create-form [{:keys [tr] :as req}]
  (let [form {:ns      :discount-type-create
              :open    "discount-type-create.open"
              :command (d*/dispatch req ::commands/create-discount-type)
              :fields  {:discount-type-name ""}}]
    [:div {:data-show "$discount-type-create.open"}
     [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
      (form/form {:-form form}
                 (input/input-button (form/input {:-label      (tr [:travel-discounts/discount-type-name])
                                                  :-form       form
                                                  :-variant    :hidden
                                                  :placeholder "Klimaticket Mond"
                                                  :type        :text
                                                  :name        :discount-type-name})
                                     (button2/button {:tabindex                      "-1"
                                                      :-priority                     :secondary
                                                      :data-on-click__viewtransition "$discount-type-create.open=false"}
                                                     (tr [:action/cancel]))
                                     (button2/button {:-priority :primary
                                                      :-icon     icon/plus
                                                      :type      :submit}
                                                     (tr [:action/create]))))]]))

(defn travel-discount-type-edit-form [{:keys [tr] :as req} {:travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (let [form {:ns      :discount-type
              :open    "discount-type.open"
              :command (d*/dispatch req ::commands/update-discount-type)
              :fields  {:discount-type-name    discount-type-name
                        :discount-type-id      discount-type-id
                        :discount-type-enabled enabled?}}]
    [:div
     (form/form {:-form form}
                (form/section {:-compact? true}
                              (form/hidden {:name :discount-type-id :-form form})
                              (form/input {:-label (tr [:travel-discounts/discount-type-name])
                                           :-form  form
                                           :class  "sm:col-span-3"
                                           :type   :text
                                           :name   :discount-type-name})
                              (form/toggle {:-label (tr [:Active])
                                            :-form  form
                                            :value  "enabled"
                                            :name   :discount-type-enabled
                                            :class  "sm:col-span-3"}))

                (form/actions
                 {:-left  (button2/button {:-priority     :secondary-destructive
                                           :data-on-click (format "$_delete-confirm-%s=true" discount-type-id)}
                                          (tr [:action/delete]))
                  :-right (list
                           (button2/button {:-priority   :secondary
                                            :-centered?  true
                                            :data-dialog "close"}
                                           (tr [:action/cancel]))
                           (button2/button {:-priority  :primary
                                            :-centered? true
                                            :type       :submit}
                                           (tr [:action/save])))}))]))
(defn travel-discount-type-row [{:keys [tr] :as req} editing? edit-any-row? {:travel.discount.type/keys [discount-type-id discount-type-name enabled?] :as dt}]
  (assert discount-type-id)
  (assert dt)
  (list
   (when editing?

     (dialog/confirm-dialog :id (str "_delete-confirm-" discount-type-id)
                            :title (tr [:action/confirm-generic])
                            :text (tr [:action/confirm-delete] [(str "\"" discount-type-name "\"")])
                            :on-hide  (format "$_delete-confirm-%s=false" discount-type-id)
                            :confirm-text (tr [:action/confirm-delete])
                            :cancel-text (tr [:action/cancel])
                            :on-confirm (d*/dispatch req ::commands/delete-discount-type)
                            :icon icon/triangle-exclamation))
   (when editing?
     (dialog/form-dialog {:id      (str "edit-discount-type-" discount-type-id)
                          :title   (tr [:travel-discounts/discount-type-name])
                          :open    (format "$discount-type.open && $discount-type.discount-type-id == '%s'" discount-type-id)
                          :on-hide (d*/expr
                                    (str "$discount-type.open && " (d*/dispatch req ::commands/close-discount-type-edit)))}
                         (travel-discount-type-edit-form req dt)))

   [:dt {:class (uic/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div discount-type-name]]
   [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"} (ui/bool-bubble enabled?)]
    [:div {:class "flex space-x-2 text-left"}
     (open-modal-button req :discount-type :discount-type-id ::commands/open-discount-type-edit edit-any-row? discount-type-id)]]))

(defn travel-discount-types [{:keys [page-state db tr] :as req}]
  (let [edit-id        (d*/get-form-current page-state :discount-type :discount-type-id)
        editing-any?   (some? edit-id)
        discount-types (q/retrieve-all-discount-types db)]
    (l/panel {:-title                  (tr [:travel-discounts/title])
              :id                      "travel-discount-types"
              :data-signals__ifmissing (d*/->signals {:discount-type-create {:open false}
                                                      :discount-type        {:open false}})
              :data-signals            (d*/->signals {:discount-type {:discount-type-id edit-id}})}

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
               (button/button {:data-on-click__viewtransition "$discount-type-create.open = !$discount-type-create.open"
                               :data-show                     "!$discount-type-create.open"
                               :-icon                         icon/plus}
                              (tr [:travel-discounts/add-discount-type]))]])))

(defn sections-reordering [{:keys [tr] :as req} sections]
  [:div
   [:p "This is the order in which the sections appear on gig pages."]
   [:dl {:class             "mt-2 divide-y divide-gray-100 text-sm leading-6" :id "sections-sort-container"
         :data-on-reordered (d*/expr
                             "$section.order = event.detail.orderInfo"
                             (d*/dispatch req ::commands/update-section-order))}
    (map-indexed (fn [idx section]
                   (let [section-name (:section/name section)]
                     [:div {:class             "sm:flex sm:items-center cursor-pointer"
                            :data-drag-item-id section-name
                            :id                (str "section-container-" section-name)}
                      [:div {:class "drag-handle cursor-pointer pr-3"} (icon/bars {:class "h-5 w-5"})]
                      [:dt {:class (uic/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
                       [:input {:type "hidden" :value idx :data-sort-order section-name}]
                       [:div section-name]]]))
                 sections)]
   [:div {:class "flex border-t border-gray-100 pt-6"}
    (button/button {:data-on-click__viewtransition (d*/expr (d*/dispatch req ::commands/close-section-reorder))
                    :-priority                     :primary} (tr [:action/done]))]
   [:div {:data-on-load "initEventSortable('sections-sort-container')"}]])

(defn section-edit-form [{:keys [tr] :as req} {section-id :section/name enabled? :section/active?}]
  (let [form {:ns      :section
              :open    "section.open"
              :command (d*/dispatch req ::commands/update-section)
              :fields  {:section-old-name section-id
                        :section-name     section-id
                        :section-enabled  (true? enabled?)}}]
    [:div
     (form/form {:-form form}
                (form/section {:-compact? true}
                              (form/hidden {:name :section-old-name :-form form})
                              (form/input {:-label (tr [:section])
                                           :-form  form
                                           :class  "sm:col-span-3"
                                           :type   :text
                                           :name   :section-name})
                              (form/toggle {:-label (tr [:Active])
                                            :-form  form
                                            :value  "enabled"
                                            :name   :section-enabled
                                            :class  "sm:col-span-3"}))

                (form/actions
                 {:-right (list
                           (button2/button {:-priority   :secondary
                                            :-centered?  true
                                            :data-dialog "close"}
                                           (tr [:action/cancel]))
                           (button2/button {:-priority  :primary
                                            :-centered? true
                                            :type       :submit}
                                           (tr [:action/save])))}))]))

(defn section-row [{:keys [tr] :as req} editing? edit-any-row? {:as section section-id :section/name enabled? :section/active?}]
  (assert section)
  (list
   (when editing?
     (dialog/form-dialog {:id      (str "edit-section-" section-id)
                          :title   (tr [:section])
                          :open    (format "$section.open && $section.section-id == '%s'" section-id)
                          :on-hide (d*/expr
                                    (str "$section.open && " (d*/dispatch req ::commands/close-section-edit)))}
                         (section-edit-form req section)))

   [:dt {:class (uic/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div section-id]]
   [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"} (ui/bool-bubble enabled?)]
    [:div {:class "flex space-x-2 text-left"}
     (open-modal-button req :section :section-id ::commands/open-section-edit edit-any-row? section-id)]]))

(defn section-create-form [{:keys [tr] :as req}]
  (let [form {:ns      :section-create
              :open    "section-create.open"
              :command (d*/dispatch req ::commands/create-section)
              :fields  {:section-name ""}}]
    [:div {:data-show "$section-create.open"}
     [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
      (form/form {:-form form}
                 (input/input-button (form/input {:-label      (tr [:section])
                                                  :-form       form
                                                  :-variant    :hidden
                                                  :placeholder "Bass"
                                                  :type        :text
                                                  :name        :section-name})
                                     (button2/button {:tabindex                      "-1"
                                                      :-priority                     :secondary
                                                      :data-on-click__viewtransition "$section-create.open=false"}
                                                     (tr [:action/cancel]))
                                     (button2/button {:-priority :primary
                                                      :-icon     icon/plus
                                                      :type      :submit}
                                                     (tr [:action/create]))))]]))

(defn sections-default  [{:keys [tr] :as req} sections edit-id editing-any?]
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
    (button/button {:data-on-click__viewtransition "$section-create.open = !$section-create.open"
                    :data-show                     "!$section-create.open"
                    :-icon                         icon/plus}
                   (tr [:section-add]))]])

(defn sections [{:keys [page-state db tr] :as req}]
  (let [edit-id      (d*/get-form-current page-state :section :section-id)
        reordering?  (get-in page-state [:section-reorder :open])
        editing-any? (some? edit-id)
        sections     (q/retrieve-sections db)]
    (l/panel {:-title   (tr [:sections])
              :-buttons (when-not reordering?
                          (button/button {:data-on-click__viewtransition (d*/expr (d*/dispatch req ::commands/open-section-reorder))} (tr [:action/reorder])))
              :id       "sections-panel"

              :data-signals__ifmissing (d*/->signals {:section-create  {:open false}
                                                      :section         {:open false}
                                                      :section-reorder {:open false}})
              :data-signals            (d*/->signals {:section {:section-id edit-id
                                                                :order      nil}})}

             (if reordering?
               (sections-reordering req sections)
               (sections-default req sections edit-id editing-any?)))))

(defn band-settings [{:keys [tr] :as req}]
  (html/->str
   [:main {:class "flex-1" :id "main"}
    (ui/page-header :title (tr [:nav/band-settings]))
    (teams-panel req)
    (travel-discount-types req)
    (sections req)]))

(d*/refresh-all!)
