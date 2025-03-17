(ns app.settings.views
  (:require
   [app.urls :as urls]
   [app.auth :as auth]
   [app.icons :as icon]
   [app.queries :as q]
   [app.settings.domain :as domain]
   [app.settings.controller :as controller]
   [app.ui :as ui]
   [app.util :as util]
   [ctmx.core :as ctmx]
   [ctmx.rt :as rt]))

(declare teams-panel)

(ctmx/defcomponent ^:endpoint teams-create-handler [{:keys [db tr] :as req}]
  (when (util/post? req)
    (let [{:keys [error]} (controller/create-team! req)]
      (teams-panel (util/make-get-request req) error))))

(ctmx/defcomponent ^:endpoint teams-update-handler [{:keys [db tr] :as req}]
  (when (util/post? req)
    (let [{:keys [error]} (controller/update-team! req)]
      (teams-panel (util/make-get-request req) error))))

(ctmx/defcomponent ^:endpoint teams-delete-handler [{:keys [db tr] :as req}]
  (when (util/delete? req)
    (let [{:keys [error]} (controller/delete-team! req)]
      (teams-panel (util/make-get-request req) error))))

(defn team-create-form [{:keys [tr] :as req}]
  [:form {:class  "team-add-form hidden"
          :hx-target (util/hash :comp/teams-panel)
          :hx-post (util/endpoint-path teams-create-handler)}
   [:div {:class "pb-12 sm:space-y-0 sm:divide-y sm:divide-gray-900/10 sm:pb-0"}
    [:div {:class "sm:grid sm:grid-cols-4 sm:items-start sm:gap-4"}
     [:label {:for "team-name" :class "block text-sm font-medium leading-6 text-gray-900 sm:pt-1.5"}
      (tr [:team/name])]
     [:div {:class "sm:col-span-2"}
      [:div {:class "flex rounded-md shadow-xs ring-1 ring-inset ring-gray-300 focus-within:ring-2 focus-within:ring-inset focus-within:ring-sno-orange-600 sm:max-w-md"}
       (ui/text  :placeholder "" :id "team-name" :name "team-name")]]
     (ui/button :class "grid-cols-1 mt-4 sm:mt-0"
                :priority :primary
                :icon icon/plus
                :label (tr [:action/create]))]]])

(ctmx/defcomponent teams-panel [{:keys [db tr] :as req} error]
  teams-create-handler teams-update-handler
  (let [teams (q/retrieve-all-teams db)]
    [:div {:id (util/id :comp/teams-panel)}
     (ui/panel {:title "Teams"
                :subtitle "Because someone has to do the work"
                :buttons (list
                          (ui/button :attr {:_ "on click remove .hidden from .team-add-form then add .hidden to me"}
                                     :icon icon/plus
                                     :label (tr [:team/create-team])))}
               [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
                (map-indexed (fn [idx  {team-name :team/name :team/keys [team-id members team-type] :as team}]
                               (let [form-class (str "team-form-" idx)
                                     label-class (str "team-label-" idx)
                                     add-member-class (str "team-add-member-" idx)]
                                 [:form {:class "sm:flex" :hx-target (util/hash :comp/teams-panel) :hx-post (util/endpoint-path teams-update-handler)}
                                  [:input {:type :hidden :name "team-id" :value (str team-id)}]
                                  ;; rw
                                  [:dt {:class (ui/cs "hidden text-gray-900 sm:w-64 sm:flex-none sm:pr-6" form-class)}
                                   [:div
                                    (ui/text :name "team-name" :value team-name :required? true :label (tr [:team/name]))]]
                                  [:dd {:class (ui/cs "hidden mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" form-class)}
                                   [:div {:class "text-gray-900 flex space-x-2"}
                                    [:div {:class "flex flex-col space-y-1"}
                                     (ui/select
                                      :id "team-type"
                                      :label (tr [:team/team-type])
                                      :value (when team-type (name team-type))
                                      :size :small
                                      :options (concat [{:value "" :label " - "}] (map (fn [m] {:label (tr [m]) :value (name m)}) domain/team-types)))]
                                    (if (seq members)
                                      [:div {:class "flex flex-col space-y-1"}
                                       [:p {:class "text-red-600"} (tr [:team/choose-remove-members])]
                                       (->> members
                                            (map (fn [{:member/keys [name member-id] :as member}]
                                                   [:div {:class "flex space-x-1"}
                                                    [:input {:type :checkbox :value (str member-id) :name "remove-members" :checked nil :class "h-4 w-4 rounded-sm border-gray-300 text-red-600 focus:ring-red-500"}]
                                                    [:div name]])))]

                                      "No members in this team yet.")]
                                   [:div {:class "flex space-x-2"}
                                    (ui/button :priority :white-destructive :label (tr [:action/delete]) :size :xsmall
                                               :hx-delete (util/comp-name #'teams-delete-handler)
                                               :hx-vals {:team-id (str team-id)}
                                               :attr {:_
                                                      (ui/confirm-modal-script
                                                       (tr [:action/confirm-generic])
                                                       (tr [:action/confirm-delete-team] [name])
                                                       (tr [:action/confirm-delete])
                                                       (tr [:action/cancel]))})
                                    (ui/button :priority :primary :label (tr [:action/save]) :size :xsmall)]]

                                  ;; add member
                                  [:dt {:class (ui/cs  "hidden text-gray-900 sm:w-64 sm:flex-none sm:pr-6" add-member-class)}
                                   [:div team-name]]
                                  [:dd {:class (ui/cs "hidden mt-1 mb-1 flex sm:items-center gap-x-6 sm:mt-0 sm:flex-auto" add-member-class)}
                                   [:div {:class "text-gray-900"}
                                    (ui/member-select :variant :inline-no-label :id "add-member-id" :members (q/members-for-select db) :with-empty-opt? true)]
                                   (ui/button :priority :primary :label (tr [:action/add]) :size :xsmall)]
                                  ;; ro
                                  [:dt {:class (ui/cs  "text-gray-900 sm:w-64 sm:flex-none sm:pr-6" label-class)}
                                   [:div team-name]]
                                  [:dd {:class (ui/cs "mt-1 flex sm:items-center justify-between gap-x-6 sm:mt-0 sm:flex-auto" label-class)}
                                   [:div {:class "text-gray-900"}
                                    (if (seq members)
                                      [:div {:class "flex space-x-2"}
                                       (->> members
                                            (map (fn [{:member/keys [name] :as member}]
                                                   [:a {:class "link-blue" :href (urls/link-member member)} name]))
                                            (interpose ", "))]

                                      "No members in this team yet.")]
                                   [:div {:class "flex space-x-2"}
                                    (ui/button :priority :link :label (tr [:team/add-member])
                                               :attr {:type :button
                                                      :_ (format  "on click remove .hidden from .%s then add .hidden to .%s" add-member-class label-class)})
                                    (ui/button :priority :link :label (tr [:action/update])
                                               :attr {:type :button
                                                      :_ (format  "on click remove .hidden from .%s then add .hidden to .%s" form-class label-class)})]]]))
                             teams)]
               (when error
                 [:div {:class "text-red-700 my-4"} "Error: " error])
               (team-create-form req))]))

(ctmx/defcomponent ^:endpoint travel-discount-type-single [{:keys [db tr] :as req}  idx discount-type-id]
  (let  [{:travel.discount.type/keys [discount-type-name enabled?]}  (if (util/post? req)
                                                                       (controller/update-discount-type req)
                                                                       (q/retrieve-discount-type db (util/ensure-uuid! discount-type-id)))
         form-class (str (path ".") "-form")
         label-class (str (path ".") "-label")]
    [:form {:class "sm:flex" :id (path ".")
            :hx-target (hash ".")
            :hx-post (util/endpoint-path travel-discount-type-single)}
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
                        :_ (format  "on click remove .hidden from .%s then add .hidden to .%s" form-class label-class)})]]))

(ctmx/defcomponent ^:endpoint travel-discount-types [{:keys [db tr] :as req}]
  (let [db-after (if (util/post? req)
                   (controller/create-discount-type req)
                   db)
        discount-types  (q/retrieve-all-discount-types db-after)
        req (util/make-get-request req {:db db-after})]
    (ui/panel {:title (tr [:travel-discounts/title])
               :id (path ".")}
              [:div
               [:dl {:class "divide-y divide-gray-100 text-sm leading-6"}
                (rt/map-indexed travel-discount-type-single req (map :travel.discount.type/discount-type-id discount-types))]
               [:div {:class "flex border-t border-gray-100 pt-6"}
                [:form {:class "discount-add-form hidden"
                        :hx-target (hash ".")
                        :hx-post (util/endpoint-path travel-discount-types)}
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
                   (util/put? req) (controller/order-sections req)
                   (util/post? req) (controller/create-section req)
                   :else db)
        sections  (q/retrieve-sections db-after)
        req (util/make-get-request req {:db db-after :reorder? reorder?})]
    (ui/panel {:title (tr [:sections])
               :id (path ".")
               :buttons (when-not reorder? [:form {:hx-get (util/endpoint-path sections)  :hx-target (hash ".")
                                                   :hx-vals {:reorder? true}}
                                            (ui/button :label (tr [:action/reorder]) :priority :white)])}

              [(if reorder? :form :div) {:class "sortable-container"}
               [:dl {:class "divide-y divide-gray-100 text-sm leading-6 sortable"}
                (rt/map-indexed section-single req (map :section/name sections))]
               [:div {:class "flex border-t border-gray-100 pt-6"}
                (when-not reorder?
                  [:form {:class "section-add-form hidden"
                          :hx-target (hash ".")
                          :hx-post (util/endpoint-path sections)}
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

(ctmx/defcomponent ^:endpoint settings-page [{:keys [db tr] :as req}]
  (let [member (auth/get-current-member req)]
    [:div
     (ui/page-header :title (tr [:nav/band-settings]))
     (teams-panel req nil)
     (travel-discount-types req)
     (sections req false)]))
