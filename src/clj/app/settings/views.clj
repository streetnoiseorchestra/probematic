(ns app.settings.views
  (:require [app.auth :as auth]
            [app.datastar :as d*]
            [app.icons :as icon]
            [app.queries :as q]
            [app.settings.controller :as controller]
            [app.settings.domain :as domain]
            [app.settings.routes :as routes]
            [app.ui :as ui]
            [app.ui.button :as button]
            [app.ui.dialog :as dialog]
            [app.ui.dl :as dl]
            [app.ui.layout :as l]
            [app.urls :as urls]
            [app.util :as util]
            [ctmx.core :as ctmx]
            [ctmx.rt :as rt]))

(defn redact-name [n]
  #_(get
     {"Felix, Christian Rauch" "John James"
      "Christine Pichler"      "Carole Candy"
      "Casey Link"             "Alice Anyone"
      "Katharina Becker"       "Moe Mighty"} n "User Name")
  n)

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
                      :priority                      :white
                      :data-on-click__viewtransition "$team-create-form-open=false"}
                     (tr [:action/cancel]))
      (button/button {:class                         "grid-cols-1 mt-4 sm:mt-0"
                      :-priority                     :primary
                      :data-on-click__viewtransition (d*/post (urls/url-for req routes/teams))
                      :-icon                         icon/plus}
                     (tr [:action/create]))]]]])

(defn team-edit-form [{:keys [tr db] :as req} {team-name :team/name :team/keys [team-id members team-type] :as team}]
  (let [all-members (q/members-for-select db)
        signal      #(str "team." %)]
    [:div
     [:input {:type :hidden :name "team-id" :value (str team-id)}]
     [:form {:on-submit                                      (d*/expr "console.log('submit')" (d*/post (urls/url-for req routes/command-delete-team-member)))
             :data-signals                                   (d*/->signals {:team {:team-name team-name
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
                                  [:a {:class "col-span-2 link-blue" :href (urls/link-member member)} (redact-name name)]
                                  (button/button {:-priority                     :link-destructive
                                                  :-size                         :xsmall
                                                  :type                          :button
                                                  :data-on-click__viewtransition (d*/expr (d*/assign "team.remove-member-id" member-id)
                                                                                          (d*/post (urls/url-for req routes/command-delete-team-member)))}
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
                                   :data-on-click__viewtransition (d*/post (urls/url-for req routes/command-add-team-member))}
                                  (tr [:action/add]))]])))]

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
                                               (d*/post (urls/url-for req routes/command-update-team)))}
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
                                                 (d*/delete (urls/url-for req routes/teams)))
                            :icon icon/triangle-exclamation))
   (when editing?
     (dialog/form-dialog {:id      (str "edit-team-" team-id)
                          :title   "Edit Team"
                          :open    (format "$current-edit-id == '%s'" team-id)
                          :on-hide (d*/expr
                                    "console.log('on-hiding') "
                                    (str "!!$current-edit-id &&" (d*/delete (urls/url-for req routes/teams-form))))}
                         (team-edit-form req team)))

   [:dt {:class (ui/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6")}
    [:div team-name]]
   [:dd {:class (ui/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto")}
    [:div {:class "text-gray-900"}
     (if (seq members)
       [:div {:class "inline"}
        (->> members
             (map (fn [{:member/keys [name] :as member}]
                    [:a {:class "link-blue" :href (urls/link-member member)}
                     (redact-name name)]))
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
                                 :data-on-click                   (format "$current-edit-id='%s'; @post('%s')" team-id (urls/url-for req routes/teams-form)))
                      (tr [:action/update])))]]))

(defn teams-panel
  ([req error]
   (teams-panel req error nil))
  ([{:keys [page-state db tr] :as req} error edit-id]
   (let [edit-id      (:current-edit-id page-state)
         teams        (q/retrieve-all-teams db)
         editing-any? (some? edit-id)]
     [:div {:id                      (util/id :comp/teams-panel)
            :data-signals__ifmissing (d*/->signals {:team-create-form-open false})
            :data-signals            (d*/->signals {:team-id         ""
                                                    :current-edit-id edit-id})}
      (l/panel {:-title    "Teams1234567"
                :subtitle "Because someone has to do the work"}
               [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
                (map-indexed (fn [idx  {team-name :team/name :team/keys [team-id members team-type] :as team}]
                               (let [editing? (= edit-id team-id)]
                                 [:div {:class "sm:flex" :id (str "team-container-" team-id)}
                                  (team-row req editing? editing-any? team)]))
                             teams)]

               (button/button {:data-on-click__viewtransition "$team-create-form-open = !$team-create-form-open"
                               :data-show                     "!$team-create-form-open"
                               :-icon                         icon/plus}
                              (tr [:team/create-team]))
               (team-create-form req)
               (when error
                 [:div {:class "text-red-700 my-4"} "Error: " error])
               #_[:pre {:data-text "ctx.signals.JSON()"}])])))

(defn teams-edit-form-handler [{:keys [db tr] :as req}]
  (let [team-id (-> req :parameters :body :current-edit-id)]
    (d*/state-transact! req #(assoc % :current-edit-id team-id))
    {:status 204}))

(defn close-teams-edit-form-handler [{:keys [db tr] :as req}]
  (let [team-id (-> req :parameters :body :current-edit-id)]
    (d*/state-transact! req #(dissoc % :current-edit-id))
    {:status 204}))

(defn teams-create-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/create-team! req)]
    (if error
      (d*/respond-fragment req (teams-panel req error))
      (d*/respond req #(d*/merge-signals! % (d*/->signals {:team-create-form-open false}))))))

(defn teams-update-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/update-team! req)]
    (if error
      (d*/respond-fragment req (teams-panel req error))
      (do
        (d*/state-transact! req #(dissoc % :current-edit-id))
        {:status 204}))))

(defn teams-remove-member-handler [{:keys [db tr] :as req}]
  (controller/remove-member! req)
  {:status 204})

(defn teams-add-member-handler [{:keys [db tr] :as req}]
  (controller/add-member! req)
  {:status 204})

(defn teams-delete-handler [{:keys [db tr] :as req}]
  (let [{:keys [error]} (controller/delete-team! req)
        tab-id          (-> req :body-params :tab-id)]
    (if error
      (teams-panel (util/make-get-request req) error)
      (do
        (swap! d*/!page-state update tab-id assoc :current-edit-id nil)
        {:status 204}))))

(d*/refresh-all!)

(ctmx/defcomponent ^:endpoint travel-discount-type-single [{:keys [db tr] :as req}  idx discount-type-id]
  (let  [{:travel.discount.type/keys [discount-type-name enabled?]} (if (util/post? req)
                                                                      (controller/update-discount-type req)
                                                                      (q/retrieve-discount-type db (util/ensure-uuid! discount-type-id)))
         form-class                                                 (str (path ".") "-form")
         label-class                                                (str (path ".") "-label")]
    [:form {:class     "sm:flex" :id (path ".")
            :hx-target (hash ".")
            :hx-post   (util/endpoint-path travel-discount-type-single)}
     [:input {:type :hidden :value discount-type-id :name "discount-type-id"}]

     ;; rw
     [:dt {:class (ui/cs  "hidden mb-2 text-gray-900 sm:w-64 sm:flex-none sm:pr-6" form-class)}
      [:div {:class "mt-2"}]
      (ui/text :name "discount-type-name" :value discount-type-name :required? true :label (tr [:travel-discounts/discount-type-name]))]
     [:dd {:class (ui/cs "hidden mt-1 flex  sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" form-class)}
      [:div {:class "mt-2"} (ui/toggle-checkbox :name "enabled?" :checked? enabled? :id (path "enabled"))]
      (ui/button :priority :primary :label (tr [:action/save]) :size :xsmall)]

     ;; ro
     [:dt {:class (ui/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6" label-class)}
      [:div discount-type-name]]
     [:dd {:class (ui/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" label-class)}
      [:div {:class "text-gray-900"} (ui/bool-bubble enabled?)]
      (ui/button :priority :link :label (tr [:action/update])
                 :attr {:type :button
                        :_    (format  "on click remove .hidden from .%s then add .hidden to .%s" form-class label-class)})]]))

(ctmx/defcomponent ^:endpoint travel-discount-types [{:keys [db tr] :as req}]
  (let [db-after       (if (util/post? req)
                         (controller/create-discount-type req)
                         db)
        discount-types (q/retrieve-all-discount-types db-after)
        req            (util/make-get-request req {:db db-after})]
    (ui/panel {:title (tr [:travel-discounts/title])
               :id    (path ".")}
              [:div
               [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
                (rt/map-indexed travel-discount-type-single req (map :travel.discount.type/discount-type-id discount-types))]
               [:div {:class "flex border-t border-gray-100 pt-6"}
                [:form {:class     "discount-add-form hidden"
                        :hx-target (hash ".")
                        :hx-post   (util/endpoint-path travel-discount-types)}
                 [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
                  [:div {:class "sm:grid sm:grid-cols-4 sm:items-start sm:gap-4"}
                   [:label {:for "discount-type-name" :class "block text-sm font-medium leading-6 text-gray-900 sm:pt-1.5"}
                    (tr [:travel-discounts/discount-type-name])]
                   [:div {:class "sm:col-span-2"}
                    [:div {:class "flex rounded-md shadow-xs ring-1 ring-inset ring-gray-300 focus-within:ring-2 focus-within:ring-inset focus-within:ring-sno-orange-600 sm:max-w-md"}
                     (ui/text  :placeholder "Klimaticket Mond" :id "discount-type-name" :name "discount-type-name")]]
                   (ui/button :class "grid-cols-1 mt-4 sm:mt-0"
                              :priority :primary
                              :label (tr [:action/add]))]]]
                (ui/button :attr {:_ "on click remove .hidden from .discount-add-form then add .hidden to me"}
                           :icon icon/plus
                           :label (tr [:travel-discounts/add-discount-type]))]])))

(ctmx/defcomponent ^:endpoint section-single [{:keys [reorder? db tr] :as req}  idx section-name]
  (let  [{:section/keys [name active? position]}  (if (util/post? req)
                                                    (controller/update-section req)
                                                    (q/retrieve-section-by-name db section-name))
         section-name name
         form-class (str (path ".") "-form")
         label-class (str (path ".") "-label")]
    [(if reorder? :div :form) {:class "sm:flex sm:items-center" :id (path ".")
                               :hx-target (hash ".")
                               :hx-post (util/endpoint-path section-single)}
     (when reorder?
       [:div {:class "drag-handle cursor-pointer pr-3"} (icon/bars {:class "h-5 w-5"})])

     [:input {:type :hidden :value section-name :name "old-section-name"}]
     [:input {:type :hidden :value section-name :name (path "section-name")}]
     [:input {:type :hidden :name (path "position") :value idx :data-sort-order true}]
     ;; rw
     [:dt {:class (ui/cs "hidden mb-2 text-gray-900 sm:w-64 sm:flex-none sm:pr-6" form-class)}
      [:div {:class "mt-2"}]
      (ui/text :name "section-name" :value section-name :required? true :label (tr [:section]))]
     [:dd {:class (ui/cs "hidden mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" form-class)}
      [:div {:class "mt-2"} (ui/toggle-checkbox :name "active?" :checked? active? :id (path "active"))]
      (ui/button :priority :primary :label (tr [:action/save]) :size :xsmall)]

     ;; ro
     [:dt {:class (ui/cs "text-gray-900 sm:w-64 sm:flex-none sm:pr-6" label-class)}
      [:div section-name]]
     [:dd {:class (ui/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" label-class)}
      [:div {:class "text-gray-900"} (ui/bool-bubble (true? active?))]
      (ui/button :priority :link :label (tr [:action/update])
                 :class (ui/cs (when reorder? "invisible"))
                 :attr {:type :button
                        :_ (format "on click remove .hidden from .%s then add .hidden to .%s" form-class label-class)})]]))

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
     #(ui/page-header :title (tr [:nav/band-settings]))
     (teams-panel req nil)
     (travel-discount-types req)
     (sections req false)]))
