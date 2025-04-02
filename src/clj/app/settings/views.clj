(ns app.settings.views
  (:require [app.auth :as auth]
            [app.ui.input :as input]
            [app.datastar :as d*]
            [app.icons :as icon]
            [app.queries :as q]
            [app.settings.controller :as controller]
            [app.settings.domain :as domain]
            [app.settings.routes :as settings]
            [app.ui :as ui]
            [app.ui.button :as button]
            [app.ui.dialog :as dialog]
            [app.ui.dl :as dl]
            [app.ui.layout :as l]
            [app.urls :as urls]
            [app.util :as util]
            [app.ui.core :as uic]
            [ctmx.core :as ctmx]
            [ctmx.rt :as rt]))

(defn team-create-form [{:keys [tr] :as req}]
  [:div {:data-show "$team-create-form-open" :class "team-add-form"}
   [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
    [:div {:class "sm:grid sm:grid-cols-4 sm:items-start sm:gap-4"}
     [:div {:class "sm:col-span-2"}
      [:div {:class "flex rounded-md shadow-xs ring-1 ring-inset ring-gray-300 focus-within:ring-2 focus-within:ring-inset focus-within:ring-sno-orange-600 sm:max-w-md"}
       (ui/text  :placeholder (tr [:team/name]) :id "team-name" :name "team-name"
                 :extra-attrs {:data-bind "team-name"})]]
     [:div {:class "sm:col-span-2 flex space-x-2"}
      (button/button {:class                         "grid-cols-1 mt-4 sm:mt-0"
                      :tabindex                      "-1"
                      :-priority                     :white
                      :data-on-click__viewtransition "$team-create-form-open=false"}
                     (tr [:action/cancel]))
      (button/button {:class                         "grid-cols-1 mt-4 sm:mt-0"
                      :-priority                     :primary
                      :data-on-click__viewtransition (d*/dispatch req settings/command-create-team)
                      :-icon                         icon/plus}
                     (tr [:action/create]))]]]])

(defn team-edit-form [{:keys [tr db] :as req} {team-name :team/name :team/keys [team-id members team-type] :as team}]
  (let [all-members (q/members-for-select db)
        signal      #(str "team." %)]
    [:div
     [:input {:type :hidden :name "team-id" :value (str team-id)}]
     [:div {:data-signals__ifmissing                        (d*/->signals {:team {:team-name team-name
                                                                                  :team-id   team-id
                                                                                  :team-type (when team-type (name team-type))}})
            :data-signals-team.remove-member-id__case.kebab "null"}
      (dl/dl
       (list
        (dl/item {:-span 3 :-label (tr [:team/name])}
                 (ui/text :name "team-name" :value team-name :required? true :attr {:data-bind (signal "team-name")}))
        (dl/item {:-span 3 :-label (tr [:team/team-type])}
                 (ui/select
                  :id "team-type"
                  :name "team-type"
                  :attr {:data-bind (signal "team-type")}
                  :value (when team-type (name team-type))
                  :options (concat [{:value "" :label " - "}] (map (fn [m] {:label (tr [m]) :value (name m)}) domain/team-types))))

        (dl/item {:-span 3 :-label (tr [:team/members])}
                 [:div {:class "flex flex-col space-y-2"}
                  ;; List of current members with remove buttons
                  (if (seq members)
                    [:div {:class "flex flex-col space-y-1 mb-4"}
                     (->> members
                          (map (fn [{:member/keys [name member-id] :as member}]
                                 [:div {:class "grid grid-cols-3 items-center justify-between py-1 border-b border-gray-100"}
                                  [:a {:class "col-span-2 link-blue" :href (urls/link-member member)} name]
                                  (button/button {:-priority                     :link-destructive
                                                  :-size                         :xsmall
                                                  :type                          :button
                                                  :data-on-click__viewtransition (d*/expr (d*/assign "team.remove-member-id" member-id)
                                                                                          (d*/dispatch req settings/command-delete-team-member))}
                                                 (tr [:action/remove]))])))]

                    [:div {:class "text-gray-500 italic mb-4"} (tr [:team/no-members])])
                  [:div {:class "flex space-x-2"}
                   [:div {:class "flex-grow"}
                    (ui/member-select :variant :inline-no-label
                                      :id "member-id"
                                      :attr {:data-bind (signal "member-id")}
                                      ;; :size :small
                                      :name "member-id"
                                      :members all-members
                                      :with-empty-opt? true)]
                   (button/button {:-priority                     :white
                                   :-size                         :xsmall
                                   :type                          :button
                                   :data-on-click__viewtransition (d*/dispatch req settings/command-add-team-member)}
                                  (tr [:action/add]))]])
        (dl/item {:-span 3 :-label [:span {:class "text-red-700"} "Error"] :data-show "$team-update-error"}
                 [:span {:class "text-red-700" :data-text "$team-update-error"}])))]

     [:div
      {:class "py-5 flex justify-between items-center"}
      [:div {:class "flex items-center space-x-3 space-x-4"}
       (button/button {:-priority     :white-destructive
                       :-size         :xsmall
                       :data-on-click (format "$delete-confirm-%s=true" team-id)}
                      (tr [:action/delete]))]
      [:div {:class "flex justify-end space-x-4"}
       (button/button {:-priority   :white
                       :-centered?  true
                       :data-dialog "close"}
                      (tr [:action/cancel]))
       (button/button {:-priority     :primary
                       :-centered?    true
                       :data-on-click (d*/expr (d*/assign "team-id" team-id)
                                               (d*/dispatch  req settings/command-update-team))}
                      (tr [:action/save]))]]]))

(defn team-row [{:keys [tr] :as req} editing? edit-any-row? {team-name :team/name :team/keys [team-id members team-type] :as team}]
  (list
   (when editing?
     (dialog/confirm-dialog :id (str "delete-confirm-" team-id)
                            :title (tr [:action/confirm-generic])
                            :text (tr [:action/confirm-delete-team] [(str "\"" team-name "\"")])
                            :on-hide  (format "$delete-confirm-%s=false" team-id)
                            :confirm-text (tr [:action/confirm-delete])
                            :cancel-text (tr [:action/cancel])
                            :on-confirm (d*/expr (d*/assign "team-id" team-id)
                                                 (d*/dispatch req settings/command-delete-team))
                            :icon icon/triangle-exclamation))
   (when editing?
     (dialog/form-dialog {:id      (str "edit-team-" team-id)
                          :title   "Edit Team"
                          :open    (format "$current-edit-id == '%s'" team-id)
                          :on-hide (d*/expr
                                    "console.log('on-hiding') "
                                    (str "!!$current-edit-id &&" (d*/dispatch req settings/command-close-team-edit-form)))}
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
     (let [fetching-signal  (str "fetching-" team-id)
           $fetching-signal (str "$" fetching-signal)]
       (button/button (array-map :-priority                       :link
                                 :-disabled?                      edit-any-row?
                                 :type                            :button
                                 :id                              (str "update-btn-" team-id)
                                 :data-indicator                  fetching-signal
                                 :data-attr-disabled              $fetching-signal
                                 :data-class                      (format "{'spinning': %s}" $fetching-signal)
                                 :data-on-click (d*/expr (d*/assign "current-edit-id" team-id)
                                                         (d*/dispatch req settings/command-open-team-edit-form)))
                      (tr [:action/update])))]]))

(defn teams-panel
  [{:keys [page-state db tr] :as req} error]
  (let [edit-id      (:current-edit-id page-state)
        teams        (q/retrieve-all-teams db)
        editing-any? (some? edit-id)]
    [:div {:id                      "teams-panel"
           :data-signals__ifmissing (d*/->signals {:team-create-form-open false
                                                   :team-create-error     false
                                                   :team-update-error     false})
           :data-signals            (d*/->signals {:team-id         nil
                                                   :current-edit-id edit-id})}
     (l/panel {:-title   "Teams"
               :subtitle "Because someone has to do the work"}
              [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
               (map-indexed (fn [idx  {:team/keys [team-id] :as team}]
                              (let [editing? (= edit-id team-id)]
                                [:div {:class "sm:flex" :id (str "team-container-" team-id)}
                                 (team-row req editing? editing-any? team)]))
                            teams)]

              (button/button {:data-on-click__viewtransition "$team-create-form-open = !$team-create-form-open"
                              :data-show                     "!$team-create-form-open"
                              :-icon                         icon/plus}
                             (tr [:team/create-team]))
              (team-create-form req)
              [:div {:class "text-red-700 my-4" :data-show "$team-create-error"} "Error: " [:span {:data-text "$team-create-error"}]]
              #_[:pre {:data-text "ctx.signals.JSON()"}])]))
(d*/refresh-all!)

(defn discount-type-update-handler [req]
  (controller/update-discount-type req))

(defn OLD_travel-discount-type-single [{:keys [db tr] :as req}  idx discount-type-id]
  (let  [{:travel.discount.type/keys [discount-type-name enabled?]} (q/retrieve-discount-type db (util/ensure-uuid! discount-type-id))]
    [:form {:class "sm:flex" :id "discount-type-ID"}
     ;; rw
     [:dt {:class (uic/cs  "hidden mb-2 text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
      [:div {:class "mt-2"}]
      (ui/text :name "discount-type-name" :value discount-type-name :required? true :label (tr [:travel-discounts/discount-type-name]))]
     [:dd {:class (uic/cs "hidden mt-1 flex  sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
      [:div {:class "mt-2"} (input/toggle-checkbox {:-name "enabled?" :-checked? enabled? :-id "enabled"})]
      (ui/button :priority :primary :label (tr [:action/save]) :size :xsmall)]

     ;; ro
     [:dt {:class (uic/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
      [:div discount-type-name]]
     [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
      [:div {:class "text-gray-900"} (ui/bool-bubble enabled?)]
      (ui/button :priority :link :label (tr [:action/update])
                 :attr {:type :button
                        :_    (format  "on click remove .hidden from .%s then add .hidden to .%s" "" "")})]]))

(defn travel-discount-type-create-form [{:keys [tr] :as req}]
  [:div {:data-show "$discount-create-form-open"}
   [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
    [:form {:class          "sm:grid sm:grid-cols-4 sm:items-start sm:gap-4"
            :data-on-submit (d*/dispatch req settings/command-add-discount-type)}
     [:div {:class "sm:col-span-2"}
      [:div {:class "flex rounded-md shadow-xs ring-1 ring-inset ring-gray-300 focus-within:ring-2 focus-within:ring-inset focus-within:ring-sno-orange-600 sm:max-w-md"}
       (ui/text  :placeholder "Klimaticket Mond" :id "discount-type-name" :name "discount-type-name"
                 :required? true :extra-attrs {:data-bind "discount-type-name"})]]
     [:div {:class "sm:col-span-2 flex space-x-2"}
      (button/button {:class                         "grid-cols-1 mt-4 sm:mt-0"
                      :tabindex                      "-1"
                      :-priority                     :white
                      :data-on-click__viewtransition "$discount-create-form-open=false"}
                     (tr [:action/cancel]))
      (button/button {:class     "grid-cols-1 mt-4 sm:mt-0"
                      :type      :submit
                      :-priority :primary
                      :-icon     icon/plus}
                     (tr [:action/create]))]]]])

(defn travel-discount-type-edit-form [{:keys [tr db] :as req} {:as dt :travel.discount.type/keys [discount-type-id discount-type-name enabled?]}]
  (assert discount-type-id)
  (let [signal #(str "discount-type." %)]
    [:div
     [:input {:type :hidden :name "discount-type-id" :value (str discount-type-id)}]
     [:div {:data-signals__ifmissing (d*/->signals {:discount-type {:discount-type-name    discount-type-name
                                                                    :discount-type-id      discount-type-id
                                                                    :discount-type-enabled enabled?}})}
      (dl/dl
       (list
        (dl/item {:-span 3 :-label (tr [:travel-discounts/discount-type-name])}
                 (ui/text :name "discount-type-name" :value discount-type-name :required? true :attr {:data-bind (signal "discount-type-name")}))
        (dl/item {:-span 3 :-label (tr [:Active])}
                 (input/toggle-checkbox {:-name "enabled?" :-checked? enabled? :-id "enabled" :data-bind (signal "discount-type-enabled")})
                 [:div {:class "flex flex-col space-y-2"}])
        (dl/item {:-span 3 :-label [:span {:class "text-red-700"} "Error"] :data-show "$discount-update-error"}
                 [:span {:class "text-red-700" :data-text "$discount-update-error"}])))]

     [:div
      {:class "py-5 flex justify-between items-center"}
      [:div {:class "flex items-center space-x-3 space-x-4"}
       (button/button {:-priority     :white-destructive
                       :-size         :xsmall
                       :data-on-click (format "$delete-confirm-%s=true" discount-type-id)}
                      (tr [:action/delete]))]
      [:div {:class "flex justify-end space-x-4"}
       (button/button {:-priority   :white
                       :-centered?  true
                       :data-dialog "close"}
                      (tr [:action/cancel]))
       (button/button {:-priority     :primary
                       :-centered?    true
                       :data-on-click (d*/expr (d*/assign "discount-type-id" discount-type-id)
                                               (d*/dispatch  req settings/command-update-discount-type))}
                      (tr [:action/save]))]]]))

(defn travel-discount-type-row [{:keys [tr] :as req} editing? edit-any-row? {:travel.discount.type/keys [discount-type-id discount-type-name enabled?] :as dt}]
  (assert discount-type-id)
  (assert dt)
  (list
   (when editing?
     (dialog/confirm-dialog :id (str "delete-confirm-" discount-type-id)
                            :title (tr [:action/confirm-generic])
                            :text (tr [:action/confirm-delete-team] [(str "\"" discount-type-name "\"")])
                            :on-hide  (format "$delete-confirm-%s=false" discount-type-id)
                            :confirm-text (tr [:action/confirm-delete])
                            :cancel-text (tr [:action/cancel])
                            :on-confirm (d*/expr (d*/assign "discount-type-id" discount-type-id)
                                                 (d*/dispatch req settings/command-delete-discount-type))
                            :icon icon/triangle-exclamation))
   (when editing?
     (dialog/form-dialog {:id      (str "edit-team-" discount-type-id)
                          :title   "Edit Team"
                          :open    (format "$discount-current-edit-id == '%s'" discount-type-id)
                          :on-hide (d*/expr
                                    (str "!!$discount-current-edit-id &&" (d*/dispatch req settings/command-close-discount-type-edit-form)))}
                         (travel-discount-type-edit-form req dt)))

   [:dt {:class (uic/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div discount-type-name]]
   [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"} (ui/bool-bubble enabled?)]
    [:div {:class "flex space-x-2 text-left"}
     (let [fetching-signal  (str "dt-fetching-" discount-type-id)
           $fetching-signal (str "$" fetching-signal)]
       (button/button (array-map :-priority                       :link
                                 :-disabled?                      edit-any-row?
                                 :type                            :button
                                 :id                              (str "dt-update-btn-" discount-type-id)
                                 :data-indicator                  fetching-signal
                                 :data-attr-disabled              $fetching-signal
                                 :data-class                      (format "{'spinning': %s}" $fetching-signal)
                                 :data-on-click (d*/expr (d*/assign "discount-current-edit-id" discount-type-id)
                                                         (d*/dispatch req settings/command-open-discount-type-edit-form)))
                      (tr [:action/update])))]]))
(defn travel-discount-types [{:keys [page-state db tr] :as req}]
  (let [edit-id        (:discount-current-edit-id page-state)
        editing-any?   (some? edit-id)
        discount-types (q/retrieve-all-discount-types db)]
    (l/panel {:-title                  (tr [:travel-discounts/title])
              :id                      "travel-discount-types"
              :data-signals__ifmissing (d*/->signals {:discount-create-form-open false
                                                      :discount-create-error     false
                                                      :discount-update-error     false})
              :data-signals            (d*/->signals {:discount-type-id         nil
                                                      :discount-current-edit-id edit-id})}

             [:div
              [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
               (map-indexed (fn [idx dt]
                              (let [dt-id    (:travel.discount.type/discount-type-id dt)
                                    editing? (= edit-id dt-id)]
                                [:div {:class "sm:flex" :id (str "dt-container-" dt-id)}
                                 (travel-discount-type-row req editing? editing-any? dt)]))
                            discount-types)]
              [:div {:class "flex border-t border-gray-100 pt-6"}
               (travel-discount-type-create-form req)
               (button/button {:data-on-click__viewtransition "$discount-create-form-open = !$discount-create-form-open"
                               :data-show                     "!$discount-create-form-open"
                               :-icon                         icon/plus}
                              (tr [:travel-discounts/add-discount-type]))]
              [:div {:class "text-red-700 my-4" :data-show "$discount-create-error"} "Error: " [:span {:data-text "$discount-create-error"}]]

              [:pre {:data-text "ctx.signals.JSON()"}]])))

(ctmx/defcomponent ^:endpoint section-single [{:keys [reorder? db tr] :as req}  idx section-name]
  (let  [{:section/keys [name active? position]} (if (util/post? req)
                                                   (controller/update-section req)
                                                   (q/retrieve-section-by-name db section-name))
         section-name                            name
         form-class                              (str (path ".") "-form")
         label-class                             (str (path ".") "-label")]
    [(if reorder? :div :form) {:class     "sm:flex sm:items-center" :id (path ".")
                               :hx-target (hash ".")
                               :hx-post   (util/endpoint-path section-single)}
     (when reorder?
       [:div {:class "drag-handle cursor-pointer pr-3"} (icon/bars {:class "h-5 w-5"})])

     [:input {:type :hidden :value section-name :name "old-section-name"}]
     [:input {:type :hidden :value section-name :name (path "section-name")}]
     [:input {:type :hidden :name (path "position") :value idx :data-sort-order true}]
     ;; rw
     [:dt {:class (uic/cs "hidden mb-2 text-gray-900 sm:w-64 sm:flex-none sm:pr-6" form-class)}
      [:div {:class "mt-2"}]
      (ui/text :name "section-name" :value section-name :required? true :label (tr [:section]))]
     [:dd {:class (uic/cs "hidden mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" form-class)}
      [:div {:class "mt-2"} (ui/toggle-checkbox :name "active?" :checked? active? :id (path "active"))]
      (ui/button :priority :primary :label (tr [:action/save]) :size :xsmall)]

     ;; ro
     [:dt {:class (uic/cs "text-gray-900 sm:w-64 sm:flex-none sm:pr-6" label-class)}
      [:div section-name]]
     [:dd {:class (uic/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" label-class)}
      [:div {:class "text-gray-900"} (ui/bool-bubble (true? active?))]
      (ui/button :priority :link :label (tr [:action/update])
                 :class (uic/cs (when reorder? "invisible"))
                 :attr {:type :button
                        :_    (format "on click remove .hidden from .%s then add .hidden to .%s" form-class label-class)})]]))

(ctmx/defcomponent ^:endpoint sections [{:keys [db tr] :as req} ^:boolean reorder?]
  (let [db-after (cond
                   (util/put? req)  (controller/order-sections req)
                   (util/post? req) (controller/create-section req)
                   :else            db)
        sections (q/retrieve-sections db-after)
        req      (util/make-get-request req {:db db-after :reorder? reorder?})]
    (ui/panel {:title   (tr [:sections])
               :id      (path ".")
               :buttons (when-not reorder? [:form {:hx-get  (util/endpoint-path sections) :hx-target (hash ".")
                                                   :hx-vals {:reorder? true}}
                                            (ui/button :label (tr [:action/reorder]) :priority :white)])}

              [(if reorder? :form :div) {:class "sortable-container"}
               [:dl {:class "divide-y divide-gray-100 text-sm leading-6 sortable"}
                (rt/map-indexed section-single req (map :section/name sections))]
               [:div {:class "flex border-t border-gray-100 pt-6"}
                (when-not reorder?
                  [:form {:class     "section-add-form hidden"
                          :hx-target (hash ".")
                          :hx-post   (util/endpoint-path sections)}
                   [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
                    [:div {:class "sm:grid sm:grid-cols-4 sm:items-start sm:gap-4"}
                     [:label {:for "section-name" :class "block text-sm font-medium leading-6 text-gray-900 sm:pt-1.5"}
                      (tr [:section])]
                     [:div {:class "sm:col-span-2"}
                      [:div {:class "flex rounded-md shadow-xs ring-1 ring-inset ring-gray-300 focus-within:ring-2 focus-within:ring-inset focus-within:ring-sno-orange-600 sm:max-w-md"}
                       (ui/text  :placeholder "Bass" :id "section-name" :name "section-name")]]
                     (ui/button :class "grid-cols-1 mt-4 sm:mt-0"
                                :priority :primary
                                :label (tr [:action/add]))]]])
                (if reorder?
                  (ui/button :priority :primary :label (tr [:action/save])
                             :hx-target (hash ".")
                             :hx-put (util/endpoint-path sections))
                  (ui/button :attr {:_ "on click remove .hidden from .section-add-form then add .hidden to me"}
                             :icon icon/plus
                             :label (tr [:section-add])))]])))

(defn settings-page [{:keys [db tr] :as req}]
  (let [member (auth/get-current-member req)]
    [:main {:class "flex-1" :id "main"}
     (ui/page-header :title (tr [:nav/band-settings]))
     (teams-panel req nil)
     (travel-discount-types req)
     (sections req false)]))
