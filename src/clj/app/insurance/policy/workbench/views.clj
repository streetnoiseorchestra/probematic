(ns app.insurance.policy.workbench.views
  (:require
   [app.datastar :as d*]
   [app.insurance.domain :as domain]
   [app.insurance.policy.workbench.actions :as actions]
   [app.insurance.policy.workbench.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.avatar :as avatar]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]))

(def view-label-keys
  {:all            [:insurance.workbench/view-all]
   :todo           [:insurance.workbench/view-todo]
   :missing-id     [:insurance.workbench/view-missing-id]
   :missing-photos [:insurance.workbench/view-missing-photos]
   :private        [:insurance.workbench/view-private]
   :changed        [:insurance.workbench/view-changed]
   :new            [:insurance.workbench/view-new]
   :removed        [:insurance.workbench/view-removed]})

(def ownership-label-keys
  {:all     [:insurance.workbench/ownership-all]
   :band    [:insurance.workbench/ownership-band]
   :private [:insurance.workbench/ownership-private]})

(def value-filter-operator-label-keys
  {:greater-than [:insurance.workbench/value-greater-than]
   :less-than    [:insurance.workbench/value-less-than]
   :equal-to     [:insurance.workbench/value-equal-to]
   :between      [:insurance.workbench/value-between]})

(defn- value-filter-operator-label
  [tr operator]
  (tr (value-filter-operator-label-keys operator)))

(defn- policy-id
  [{:keys [parameters path-params]}]
  (let [value (or (get-in parameters [:path :policy-id])
                  (:policy-id path-params))]
    (cond
      (uuid? value) value
      (string? value) (parse-uuid value))))

(defn- query-param
  [params k]
  (or (get params k)
      (get params (name k))))

(defn- value-filter-query-params
  [value-filter]
  (let [{:keys [operator value min max]} value-filter
        operator (when operator (domain/normalize-value-filter-operator operator))]
    (case operator
      :between {:value-operator operator
                :value          nil
                :value-min      min
                :value-max      max}
      {:value-operator operator
       :value          value
       :value-min      nil
       :value-max      nil})))

(defn- query-params
  [req]
  (let [params    (or (get-in req [:parameters :query])
                      (:query-params req)
                      {})
        page      (query-param params :page)
        page-size (query-param params :page-size)]
    (cond-> {:view                (query-param params :view)
             :review-filter       (query-param params :review-filter)
             :member-q            (query-param params :member-q)
             :category-id         (query-param params :category-id)
             :coverage-type-id    (query-param params :coverage-type-id)
             :ownership           (query-param params :ownership)
             :missing-photos      (query-param params :missing-photos)
             :missing-harmonia-id (query-param params :missing-harmonia-id)
             :workflow-status     (query-param params :workflow-status)
             :change-status       (query-param params :change-status)
             :value-operator      (query-param params :value-operator)
             :value               (query-param params :value)
             :value-min           (query-param params :value-min)
             :value-max           (query-param params :value-max)
             :group               (query-param params :group)}
      (some? page)      (assoc :page page)
      (some? page-size) (assoc :page-size page-size))))

(defn- active-filter-params
  [{:keys [page-state]}]
  (let [filters (get-in page-state [:insurance-workbench :filters])]
    (cond-> {}
      (contains? filters :member-q) (assoc :member-q (:member-q filters))
      (contains? filters :category-ids) (assoc :category-id (:category-ids filters))
      (contains? filters :coverage-type-ids) (assoc :coverage-type-id (:coverage-type-ids filters))
      (contains? filters :ownership) (assoc :ownership (:ownership filters))
      (contains? filters :missing-photos?) (assoc :missing-photos (:missing-photos? filters))
      (contains? filters :missing-harmonia-id?) (assoc :missing-harmonia-id (:missing-harmonia-id? filters))
      (contains? filters :workflow-statuses) (assoc :workflow-status (:workflow-statuses filters))
      (contains? filters :change-statuses) (assoc :change-status (:change-statuses filters))
      (contains? filters :value-filter) (merge (value-filter-query-params (:value-filter filters))))))

(defn- workbench-params
  [req]
  (merge (query-params req)
         (active-filter-params req)))

(defn- page-breadcrumb
  [{:keys [tr]} policy]
  [breadcrumb/Breadcrumb
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
    (:insurance.policy/name policy)]
   [breadcrumb/BreadcrumbItem (tr [:insurance.workbench/title])]])

(defn- option
  [selected value label]
  [:wa-option (cond-> {:value (name value)}
                (= selected value) (assoc :selected true))
   label])

(def filter-field-label-keys
  {:category       [:instrument/category]
   :ownership      [:insurance.workbench/ownership]
   :coverage-types [:insurance/coverage-types]
   :photos         [:insurance.workbench/photos]
   :harmonia-id    [:instrument.coverage/insurer-id]
   :workflow       [:insurance.workbench/workflow-status]
   :change         [:insurance.workbench/change-status]
   :value          [:insurance/value]})

(def filter-fields
  [:category
   :ownership
   :coverage-types
   :photos
   :harmonia-id
   :workflow
   :change
   :value])

(def table-columns
  [{:id :status :label-key [:insurance.workbench/status] :header-variants #{:nowrap}}
   {:id :member :label-key [:col/member]}
   {:id :instrument :label-key [:instrument/instrument]}
   {:id :category :label-key [:instrument/category]}
   {:id :ownership :label-key [:band-private]}
   {:id :photos :label-key [:insurance.workbench/photos] :align :end}
   {:id :harmonia-id :label-key [:instrument.coverage/insurer-id] :align :end :header-variants #{:nowrap}}
   {:id :value :label-key [:insurance/value-abbrev] :align :end}
   {:id :cost :label-key [:instrument.coverage/cost] :align :end}
   {:id :coverage-types :label-key [:insurance/coverage-types]}
   {:id :actions :label-key [:actions] :align :end}])

(def selection-column
  {:id :selection})

(def table-column-by-id
  (into {(:id selection-column) selection-column}
        (map (juxt :id identity))
        table-columns))

(defn- table-column
  [id]
  (table-column-by-id id))

(defn- column-visibility-override
  [columns id]
  (cond
    (contains? columns id) (get columns id)
    (contains? columns (name id)) (get columns (name id))))

(defn- visible-column-setting?
  [value]
  (not (or (false? value)
           (= "false" (some-> value str str/lower-case)))))

(defn- table-column-default-visible?
  [view id]
  (contains? (set (queries/default-column-ids (or view :all))) id))

(defn- table-column-visible?
  ([table id]
   (table-column-visible? :all table id))
  ([view table id]
   (let [view             (or view :all)
         view-columns     (or (get-in table [:columns-by-view view])
                              (get-in table [:columns-by-view (name view)])
                              (get-in table [:columnsByView view])
                              (get-in table [:columnsByView (name view)]))
         view-override    (column-visibility-override view-columns id)
         legacy-override  (column-visibility-override (:columns table) id)]
     (cond
       (some? view-override) (visible-column-setting? view-override)
       (some? legacy-override) (visible-column-setting? legacy-override)
       :else (table-column-default-visible? view id)))))

(defn- table-columns-state
  ([table]
   (table-columns-state :all table))
  ([view table]
   (into {}
         (for [{:keys [id]} table-columns]
           [(name id) (table-column-visible? view table id)]))))

(defn- table-columns-for
  ([group]
   (table-columns-for group :all nil))
  ([group table]
   (table-columns-for group :all table))
  ([group view table]
   (into [selection-column]
         (cond->> table-columns
           (= group :member) (remove #(= :member (:id %)))
           true (filter #(table-column-visible? view table (:id %)))))))

(def filter-popover-id
  "insurance-workbench-filter-popover")

(def filter-button-id
  "insurance-workbench-filter-button")

(defn- filter-chip-id
  [field]
  (str "insurance-workbench-filter-chip-" (name field)))

(def table-settings-popover-id
  "insurance-workbench-table-settings-popover")

(def table-settings-button-id
  "insurance-workbench-table-settings-button")

(defn- form-value
  [value]
  (if (keyword? value)
    (name value)
    (str value)))

(defn- blank-value?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))))

(defn- hidden-inputs
  [fields]
  (mapcat (fn [[field-name value]]
            (for [value (if (coll? value)
                          (sort-by str value)
                          [value])
                  :when (not (blank-value? value))]
              [:input {:type  "hidden"
                       :name  field-name
                       :value (form-value value)}]))
          fields))

(defn- category-query-values
  [filters]
  (not-empty (sort-by str (:category-ids filters))))

(defn- coverage-type-query-values
  [filters]
  (not-empty (sort-by str (:coverage-type-ids filters))))

(defn- workflow-status-query-values
  [filters]
  (not-empty (sort-by name (:workflow-statuses filters))))

(defn- change-status-query-values
  [filters]
  (not-empty (sort-by name (:change-statuses filters))))

(defn- active-boolean-query-value
  [value]
  (when value true))

(defn- value-filter-hidden-fields
  [filters]
  (let [{:keys [value-operator value value-min value-max]}
        (value-filter-query-params (:value-filter filters))]
    [["value-operator" value-operator]
     ["value" value]
     ["value-min" value-min]
     ["value-max" value-max]]))

(defn- query-state
  [filters view overrides]
  (merge {:view                view
          :member-q            (:member-q filters)
          :category-id         (category-query-values filters)
          :coverage-type-id    (coverage-type-query-values filters)
          :ownership           (:ownership filters)
          :missing-photos      (active-boolean-query-value (:missing-photos? filters))
          :missing-harmonia-id (active-boolean-query-value (:missing-harmonia-id? filters))
          :workflow-status     (workflow-status-query-values filters)
          :change-status       (change-status-query-values filters)
          :group               (:group filters)}
         (value-filter-query-params (:value-filter filters))
         overrides))

(defn- pagination-query-state
  [{:keys [filters pagination view]} overrides]
  (query-state filters
               view
               (merge {:page-size (:page-size pagination)}
                      overrides)))

(defn- view-button
  [tr {:keys [filters pagination policy view]} view-option]
  (let [active? (= view view-option)]
    [button/Button (cond-> {:appearance          (if active? "filled" "outlined")
                            :size                "s"
                            :href                (urls/link-policy-workbench
                                                  policy
                                                  (pagination-query-state
                                                   {:filters filters
                                                    :pagination pagination
                                                    :view view}
                                                   {:view view-option
                                                    :page 1}))
                            :data-workbench-view (name view-option)}
                     active? (assoc :variant "brand"))
     (tr (view-label-keys view-option))]))

(defn- view-select-form
  [tr {:keys [filters pagination policy view]}]
  (into [:form {:method "get"
                :action (urls/link-policy-workbench policy)}]
        (concat (hidden-inputs (concat [["group" (:group filters)]
                                        ["page" 1]
                                        ["page-size" (:page-size pagination)]
                                        ["member-q" (:member-q filters)]
                                        ["category-id" (category-query-values filters)]
                                        ["coverage-type-id" (coverage-type-query-values filters)]
                                        ["ownership" (:ownership filters)]
                                        ["missing-photos" (active-boolean-query-value (:missing-photos? filters))]
                                        ["missing-harmonia-id" (active-boolean-query-value (:missing-harmonia-id? filters))]
                                        ["workflow-status" (workflow-status-query-values filters)]
                                        ["change-status" (change-status-query-values filters)]]
                                       (value-filter-hidden-fields filters)))
                [(into [:wa-select {:name           "view"
                                    :value          (name view)
                                    :appearance     "outlined"
                                    :aria-label     (tr [:insurance.workbench/view])
                                    :data-on:change "evt.target.closest('form').requestSubmit()"}]
                       (for [view-option queries/supported-views]
                         [:wa-option {:value (name view-option)}
                          (tr (view-label-keys view-option))]))])))

(defn- view-button-row
  [tr workbench]
  [:div {:class "insurance-workbench-view-switcher"}
   (into [:nav {:aria-label (tr [:insurance.workbench/view])}]
         (for [view-option queries/supported-views]
           (view-button tr workbench view-option)))
   (view-select-form tr workbench)])

(defn- search-form
  [{:keys [tr] :as req} {:keys [filters pagination policy view]}]
  (into [:form {:method "get"
                :action (urls/link-policy-workbench policy)}]
        (concat (hidden-inputs (concat [["view" view]
                                        ["group" (:group filters)]
                                        ["page" 1]
                                        ["page-size" (:page-size pagination)]
                                        ["category-id" (category-query-values filters)]
                                        ["coverage-type-id" (coverage-type-query-values filters)]
                                        ["ownership" (:ownership filters)]
                                        ["missing-photos" (active-boolean-query-value (:missing-photos? filters))]
                                        ["missing-harmonia-id" (active-boolean-query-value (:missing-harmonia-id? filters))]
                                        ["workflow-status" (workflow-status-query-values filters)]
                                        ["change-status" (change-status-query-values filters)]]
                                       (value-filter-hidden-fields filters)))
                [[:wa-input {:name                         "member-q"
                             :aria-label                   (tr [:insurance.workbench/search])
                             :placeholder                  (tr [:insurance.workbench/member-search-placeholder])
                             :value                        (or (:member-q filters) "")
                             :appearance                   "outlined"
                             :with-clear                   true
                             :data-bind                    "insuranceWorkbench.memberQ"
                             :data-on:input__debounce.250ms
                             (str "@post('" (d*/act req ::actions/set-member-search-phrase) "')")}]])))

(defn- ownership-select
  [tr selected-ownership]
  (into [:wa-select {:value      (name selected-ownership)
                     :appearance "outlined"
                     :aria-label (tr [:insurance.workbench/ownership])
                     :data-bind  "insuranceWorkbench.filterDraft.ownership"}]
        (for [ownership [:all :band :private]]
          (option selected-ownership ownership (tr (ownership-label-keys ownership))))))

(defn- checkbox-list
  [signal-path selected-ids items]
  (let [selected-ids (set (map str selected-ids))]
    (into [:div {:class "wa-stack wa-gap-xs"
                 :style (str "max-block-size: min(20rem, 50vh); "
                             "overflow-y: auto; "
                             "padding-block: var(--wa-space-2xs); "
                             "scrollbar-gutter: stable;")}]
          (for [{:keys [id label]} items
                :let [id (str id)]]
            [:label {:class "wa-cluster wa-gap-xs wa-align-items-center"
                     :style "cursor: pointer;"}
             [:input (cond-> {:type      "checkbox"
                              :value     id
                              :data-bind signal-path}
                       (contains? selected-ids id) (assoc :checked true))]
             [:span label]]))))

(defn- category-select
  [_tr categories selected-category-ids]
  (checkbox-list "insuranceWorkbench.filterDraft.categoryIds"
                 selected-category-ids
                 (for [{:keys [category-id category-name]} categories]
                   {:id    category-id
                    :label category-name})))

(defn- coverage-type-select
  [_tr policy selected-coverage-type-ids]
  (checkbox-list "insuranceWorkbench.filterDraft.coverageTypeIds"
                 selected-coverage-type-ids
                 (for [{:insurance.coverage.type/keys [name type-id]} (:insurance.policy/coverage-types policy)]
                   {:id    type-id
                    :label name})))

(defn- boolean-switch
  [signal-path checked? label]
  [:wa-switch (cond-> {:data-bind__prop.checked__event.change signal-path}
                checked? (assoc :checked true))
   label])

(defn- missing-photos-switch
  [tr checked?]
  (boolean-switch "insuranceWorkbench.filterDraft.missingPhotos"
                  checked?
                  (tr [:insurance.workbench/missing-photos])))

(defn- missing-harmonia-id-switch
  [tr checked?]
  (boolean-switch "insuranceWorkbench.filterDraft.missingHarmoniaId"
                  checked?
                  (tr [:insurance.workbench/missing])))

(defn- workflow-status-select
  [tr selected-workflow-statuses]
  (checkbox-list "insuranceWorkbench.filterDraft.workflowStatuses"
                 selected-workflow-statuses
                 (for [status domain/simple-instrument-coverage-statuses]
                   {:id    (name status)
                    :label (insurance-ui/status-badge tr (domain/qualified-coverage-status status))})))

(defn- change-status-select
  [tr selected-change-statuses]
  (checkbox-list "insuranceWorkbench.filterDraft.changeStatuses"
                 selected-change-statuses
                 (for [status domain/simple-instrument-coverage-changes]
                   {:id    (name status)
                    :label (insurance-ui/change-badge tr (domain/qualified-coverage-change status))})))

(defn- value-input-attrs
  [signal-path attrs]
  (assoc attrs :data-bind signal-path))

(defn- value-filter-control
  [tr]
  [:div {:class "wa-stack wa-gap-s"}
   (into [:wa-select {:value      (name domain/default-value-filter-operator)
                      :appearance "outlined"
                      :aria-label (tr [:insurance.workbench/value-operator])
                      :data-bind  "insuranceWorkbench.filterDraft.valueOperator"}]
         (for [operator domain/value-filter-operators]
           [:wa-option {:value (name operator)}
            (value-filter-operator-label tr operator)]))
   [:div {:class     "wa-flank wa-gap-3xs"
          :style     (str "display: grid; "
                          "grid-template-columns: auto minmax(0, 1fr); "
                          "align-items: center;")
          :data-show "$insuranceWorkbench.filterDraft.valueOperator !== 'between'"}
    [ico/Icon {::ico/library :phosphor
               ::ico/name    :arrow-bend-down-right
               :class        "wa-color-text-quiet"}]
    [:input (value-input-attrs
             "insuranceWorkbench.filterDraft.value"
             {:type        "number"
              :min         "0"
              :placeholder "0"
              :aria-label  (tr [:insurance/value])})]]
   [:div {:class     "wa-flank wa-gap-3xs"
          :style     (str "display: grid; "
                          "grid-template-columns: auto minmax(0, 1fr); "
                          "align-items: center;")
          :data-show "$insuranceWorkbench.filterDraft.valueOperator === 'between'"}
    [ico/Icon {::ico/library :phosphor
               ::ico/name    :arrow-bend-down-right
               :class        "wa-color-text-quiet"}]
    [:div {:class "wa-gap-3xs"
           :style (str "display: grid; "
                       "grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); "
                       "align-items: center;")}
     [:input (value-input-attrs
              "insuranceWorkbench.filterDraft.valueMin"
              {:type        "number"
               :min         "0"
               :placeholder (tr [:insurance.workbench/value-min])
               :aria-label  (tr [:insurance.workbench/value-min])})]
     [:span {:class "wa-align-items-center wa-caption-s wa-color-text-quiet"}
      (tr [:insurance.workbench/and])]
     [:input (value-input-attrs
              "insuranceWorkbench.filterDraft.valueMax"
              {:type        "number"
               :min         "0"
               :placeholder (tr [:insurance.workbench/value-max])
               :aria-label  (tr [:insurance.workbench/value-max])})]]]])

(defn- apply-filter-js
  [req]
  (str (d*/action :post (d*/act req ::actions/apply-filter)) "; "
       "$insuranceWorkbench.filterPopover.open = false"))

(defn- show-filter-popover-js
  [anchor-id field]
  (let [source (if field "chip" "list")]
    (str "evt.preventDefault(); evt.stopImmediatePropagation(); "
         "$insuranceWorkbench.filterEditor.field = '" (or (some-> field name) "") "'; "
         "$insuranceWorkbench.filterEditor.source = '" source "'; "
         "$insuranceWorkbench.filterPopover.anchor = '" anchor-id "'; "
         "$insuranceWorkbench.filterPopover.open = true")))

(defn- remove-filter-js
  [req field]
  (case field
    :category
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'category'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.categoryIds = ($insuranceWorkbench.filterDraft.categoryIds || []).map(() => ''); "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :coverage-types
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'coverage-types'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.coverageTypeIds = ($insuranceWorkbench.filterDraft.coverageTypeIds || []).map(() => ''); "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :ownership
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'ownership'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.ownership = 'all'; "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :photos
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'photos'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.missingPhotos = false; "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :harmonia-id
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'harmonia-id'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.missingHarmoniaId = false; "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :workflow
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'workflow'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.workflowStatuses = ($insuranceWorkbench.filterDraft.workflowStatuses || []).map(() => ''); "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :change
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'change'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.changeStatuses = ($insuranceWorkbench.filterDraft.changeStatuses || []).map(() => ''); "
         (d*/action :post (d*/act req ::actions/apply-filter)))

    :value
    (str "evt.preventDefault(); evt.stopPropagation(); "
         "el.style.opacity = '0'; el.style.pointerEvents = 'none'; "
         "$insuranceWorkbench.filterEditor.field = 'value'; "
         "$insuranceWorkbench.filterPopover.open = false; "
         "$insuranceWorkbench.filterDraft.value = ''; "
         "$insuranceWorkbench.filterDraft.valueMin = ''; "
         "$insuranceWorkbench.filterDraft.valueMax = ''; "
         (d*/action :post (d*/act req ::actions/apply-filter)))))

(defn- filter-editor-shell
  [{:keys [tr] :as req} field & body]
  [:div {:class     "wa-stack wa-gap-s"
         :data-show (str "$insuranceWorkbench.filterEditor.field === '" (name field) "'")}
   [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
    [button/Button {:appearance    "outlined"
                    :size          "xs"
                    :aria-label    (tr [:action/back])
                    :data-show     "$insuranceWorkbench.filterEditor.source !== 'chip'"
                    :data-on:click "evt.preventDefault(); evt.stopPropagation(); $insuranceWorkbench.filterEditor.field = ''; $insuranceWorkbench.filterEditor.source = 'list'"}
     [ico/Icon {::ico/library :phosphor
                ::ico/name    :caret-left}]]
    [:strong (tr [:insurance.workbench/filter-by] [(tr (filter-field-label-keys field))])]]
   body
   [button/Button {:appearance    "filled"
                   :variant       "brand"
                   :data-on:click (apply-filter-js req)}
    (tr [:action/apply])]])

(defn- filter-field-button
  [tr field]
  [button/Button {:appearance        "plain"
                  :data-filter-field (name field)
                  :data-on:click     (str "$insuranceWorkbench.filterEditor.field = '" (name field) "'; "
                                          "$insuranceWorkbench.filterEditor.source = 'list'")
                  :style             (str "inline-size: 100%; justify-content: start; "
                                          "padding-inline: var(--wa-space-xs);")}
   (tr (filter-field-label-keys field))])

(defn- filter-popover
  [{:keys [tr] :as req} {:keys [available-categories filters policy]}]
  [:wa-popover {:id              filter-popover-id
                :data-attr:open "$insuranceWorkbench.filterPopover.open"
                :data-effect    "el.anchor = document.getElementById($insuranceWorkbench.filterPopover.anchor)"
                :data-on:wa-hide "if (evt.target === el) { $insuranceWorkbench.filterPopover.open = false }"
                :placement       "bottom-start"
                :without-arrow   true
                :style           "--max-width: 24rem;"}
   [:div {:class "wa-stack wa-gap-s"}
    [:div {:class     "wa-stack wa-gap-2xs"
           :data-show "!$insuranceWorkbench.filterEditor.field"}
     [:strong (tr [:action/filter])]
     (for [field filter-fields]
       (filter-field-button tr field))]
    (filter-editor-shell req
                         :category
                         (category-select tr available-categories (:category-ids filters)))
    (filter-editor-shell req
                         :ownership
                         (ownership-select tr (:ownership filters)))
    (filter-editor-shell req
                         :coverage-types
                         (coverage-type-select tr policy (:coverage-type-ids filters)))
    (filter-editor-shell req
                         :photos
                         (missing-photos-switch tr (:missing-photos? filters)))
    (filter-editor-shell req
                         :harmonia-id
                         (missing-harmonia-id-switch tr (:missing-harmonia-id? filters)))
    (filter-editor-shell req
                         :workflow
                         (workflow-status-select tr (:workflow-statuses filters)))
    (filter-editor-shell req
                         :change
                         (change-status-select tr (:change-statuses filters)))
    (filter-editor-shell req
                         :value
                         (value-filter-control tr))]])

(defn- filter-button
  [tr]
  [button/Button {:id            filter-button-id
                  :appearance    "outlined"
                  :type          "button"
                  :data-on:click (show-filter-popover-js filter-button-id nil)}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :funnel}]
   (tr [:action/filter])])

(defn- category-filter-value
  [available-categories category-ids]
  (let [category-ids  (set category-ids)
        known-labels   (->> available-categories
                            (filter #(contains? category-ids (:category-id %)))
                            (map :category-name))
        known-ids      (->> available-categories
                            (keep (fn [{:keys [category-id]}]
                                    (when (contains? category-ids category-id)
                                      category-id)))
                            (set))
        unknown-labels (->> (remove known-ids category-ids)
                            (sort-by str)
                            (map str))]
    (str/join ", " (concat known-labels unknown-labels))))

(defn- coverage-type-filter-value
  [policy coverage-type-ids]
  (let [coverage-type-ids (set coverage-type-ids)
        known-labels      (->> (:insurance.policy/coverage-types policy)
                               (filter #(contains? coverage-type-ids (:insurance.coverage.type/type-id %)))
                               (map :insurance.coverage.type/name))
        known-ids         (->> (:insurance.policy/coverage-types policy)
                               (keep (fn [{:insurance.coverage.type/keys [type-id]}]
                                       (when (contains? coverage-type-ids type-id)
                                         type-id)))
                               (set))
        unknown-labels    (->> (remove known-ids coverage-type-ids)
                               (sort-by str)
                               (map str))]
    (str/join ", " (concat known-labels unknown-labels))))

(defn- status-filter-value
  [label-fn key-fn tr values]
  (into [:span {:class "wa-cluster wa-gap-xs wa-align-items-center"}]
        (for [value (sort-by name values)]
          (label-fn tr (key-fn value)))))

(defn- value-filter-value
  [tr currency {:keys [operator value min max]}]
  (case operator
    :between
    [:span {:class "wa-cluster wa-gap-2xs wa-align-items-center"}
     (value-filter-operator-label tr :between)
     " "
     (ui2/money min currency)
     " "
     (tr [:insurance.workbench/and])
     " "
     (ui2/money max currency)]

    [:span {:class "wa-cluster wa-gap-2xs wa-align-items-center"}
     (value-filter-operator-label tr operator)
     " "
     (ui2/money value currency)]))

(defn- active-filter-chips
  [{:keys [tr]} {:keys [available-categories filters policy]}]
  (cond-> []
    (and (:ownership filters) (not= :all (:ownership filters)))
    (conj {:field :ownership
           :label (tr (filter-field-label-keys :ownership))
           :value (tr (ownership-label-keys (:ownership filters)))})

    (seq (:category-ids filters))
    (conj {:field :category
           :label (tr (filter-field-label-keys :category))
           :value (category-filter-value available-categories (:category-ids filters))})

    (seq (:coverage-type-ids filters))
    (conj {:field :coverage-types
           :label (tr (filter-field-label-keys :coverage-types))
           :value (coverage-type-filter-value policy (:coverage-type-ids filters))})

    (:missing-photos? filters)
    (conj {:field :photos
           :label (tr (filter-field-label-keys :photos))
           :value (tr [:insurance.workbench/missing])})

    (:missing-harmonia-id? filters)
    (conj {:field :harmonia-id
           :label (tr (filter-field-label-keys :harmonia-id))
           :value (tr [:insurance.workbench/missing])})

    (seq (:workflow-statuses filters))
    (conj {:field :workflow
           :label (tr (filter-field-label-keys :workflow))
           :value (status-filter-value insurance-ui/status-badge domain/qualified-coverage-status tr (:workflow-statuses filters))})

    (seq (:change-statuses filters))
    (conj {:field :change
           :label (tr (filter-field-label-keys :change))
           :value (status-filter-value insurance-ui/change-badge domain/qualified-coverage-change tr (:change-statuses filters))})

    (:value-filter filters)
    (conj {:field :value
           :label (tr (filter-field-label-keys :value))
           :value (value-filter-value tr (:insurance.policy/currency policy) (:value-filter filters))})))

(defn- active-filter-pill
  [req {:keys [field label value]}]
  (let [chip-id (filter-chip-id field)]
    [:wa-tag {:with-remove       true :size "m"
              :data-on:wa-remove (remove-filter-js req field)
              :class             "cursor-pointer"
              :style             "transition: opacity 1s ease-out;"}
     [:dl {:class         "wa-cluster wa-gap-2xs"
           :id            chip-id
           :data-on:click (show-filter-popover-js chip-id field)}
      [:dt {:class "wa-font-weight-bold wa-color-text-quiet"} label]
      [divider/Divider {::divider/orientation :vertical :style "min-block-size: 0.5lh"}]
      [:dd {:class "wa-font-weight-bold" :style "color: var(--wa-color-green-60)"} value]]]))

(defn- active-filters-bar
  [req workbench]
  (let [chips (active-filter-chips req workbench)]
    (when (seq chips)
      (into [:div {:class                         "wa-cluster wa-gap-xs"
                   :data-workbench-active-filters "true"
                   :style                         (str "background: var(--wa-color-neutral-fill-quiet); "
                                                       "border-radius: var(--wa-border-radius-m); "
                                                       "padding: var(--wa-space-xs);")}]
            (map #(active-filter-pill req %) chips)))))

(defn- table-column-toggle-js
  [req view id]
  (str "$insuranceWorkbench.table.view = '" (name (or view :all)) "'; "
       "$insuranceWorkbench.table.column = '" (name id) "'; "
       "$insuranceWorkbench.table.columnVisible = evt.target.checked; "
       "@post('" (d*/act req ::actions/toggle-table-column) "')"))

(defn- table-column-checkbox
  [req view table {:keys [id label-key]}]
  [:wa-checkbox (cond-> {:data-workbench-column-toggle (name id)
                         :data-on:change               (table-column-toggle-js req view id)}
                  (table-column-visible? view table id) (assoc :checked true))
   ((:tr req) label-key)])

(defn- group-switch-js
  [policy filters view]
  (let [member-url (urls/link-policy-workbench policy (query-state filters view {:group :member}))
        none-url   (urls/link-policy-workbench policy (query-state filters view {:group :none}))]
    (str "window.location.href = evt.target.checked ? "
         (pr-str member-url)
         " : "
         (pr-str none-url))))

(defn- table-settings-popover
  [{:keys [tr] :as req} {:keys [filters policy table view]}]
  [:wa-popover {:id            table-settings-popover-id
                :for           table-settings-button-id
                :placement     "bottom-end"
                :without-arrow true
                :style         "--max-width: 24rem;"}
   [:div {:class "wa-stack wa-gap-s"}
    [:strong (tr [:insurance.workbench/table-settings])]
    [:wa-switch (cond-> {:data-on:change (group-switch-js policy filters view)}
                  (= :member (:group filters)) (assoc :checked true))
     (tr [:insurance.workbench/group-member])]
    [:wa-divider]
    [:strong {:class "wa-caption-s wa-color-text-quiet"}
     (tr [:insurance.workbench/columns])]
    (for [column table-columns]
      (table-column-checkbox req view table column))]])

(defn- table-settings-button
  [tr]
  [button/Button {:id         table-settings-button-id
                  :appearance "outlined"
                  :type       "button"
                  :aria-label (tr [:insurance.workbench/table-settings])}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :sliders-horizontal
              :style      "font-size: var(--wa-font-size-l);"}]])

(defn- workbench-toolbar
  [{:keys [tr] :as req} workbench]
  [:div {:class "wa-stack wa-gap-s"}
   (view-button-row tr workbench)
   [:div {:class                     "wa-flank:end wa-gap-2xs"}
    (search-form req workbench)
    [:div {:class "wa-cluster wa-gap-2xs"}
     (filter-button tr)
     (table-settings-button tr)]]
   (active-filters-bar req workbench)
   (filter-popover req workbench)
   (table-settings-popover req workbench)])

(defn- signal-array-values
  [values]
  (mapv form-value values))

(defn- value-filter-signal-values
  [filters]
  (let [{:keys [operator value min max]} (:value-filter filters)]
    {:valueOperator (name (or operator domain/default-value-filter-operator))
     :value         (some-> value form-value)
     :valueMin      (some-> min form-value)
     :valueMax      (some-> max form-value)}))

(defn- selection-signals
  ([policy filters]
   (selection-signals policy filters nil :all))
  ([policy filters table]
   (selection-signals policy filters table :all))
  ([policy filters table view]
   (let [view (or view :all)]
     {:insuranceWorkbench {:policyId             (str (:insurance.policy/policy-id policy))
                           :memberQ              (or (:member-q filters) "")
                           :selectedCoverageIds  []
                           :bulkActionStuck      false
                           :targetWorkflowStatus "keep"
                           :targetChangeStatus   "keep"
                           :filterEditor         {:field        nil
                                                  :source       nil
                                                  :appliedField nil}
                           :filterPopover        {:anchor filter-button-id
                                                  :open   false}
                           :filterDraft          (merge {:categoryIds       (signal-array-values (category-query-values filters))
                                                         :ownership         (name (or (:ownership filters) :all))
                                                         :coverageTypeIds   (signal-array-values (coverage-type-query-values filters))
                                                         :missingPhotos     (boolean (:missing-photos? filters))
                                                         :missingHarmoniaId (boolean (:missing-harmonia-id? filters))
                                                         :workflowStatuses  (signal-array-values (workflow-status-query-values filters))
                                                         :changeStatuses    (signal-array-values (change-status-query-values filters))}
                                                        (value-filter-signal-values filters))
                           :table                {:view          (name view)
                                                  :groupByMember (= :member (:group filters))
                                                  :column        nil
                                                  :columnVisible nil
                                                  :columns       (table-columns-state view table)}}})))

(def selected-coverage-ids-js
  "($insuranceWorkbench.selectedCoverageIds || [])")

(def selected-count-js
  (str selected-coverage-ids-js ".length"))

(defn- js-string-array
  [values]
  (str "[" (str/join ", " (map (comp pr-str str) values)) "]"))

(defn- selected-coverage-id-js
  [coverage-id]
  (str selected-coverage-ids-js ".includes('" coverage-id "')"))

(defn- select-all-checked-js
  [coverage-ids]
  (if (seq coverage-ids)
    (str (js-string-array coverage-ids) ".every(id => " selected-coverage-ids-js ".includes(id))")
    "false"))

(defn- select-all-indeterminate-js
  [coverage-ids]
  (if (seq coverage-ids)
    (str (js-string-array coverage-ids) ".some(id => " selected-coverage-ids-js ".includes(id)) && "
         "!" (select-all-checked-js coverage-ids))
    "false"))

(defn- select-all-effect-js
  [coverage-ids]
  (str "el.checked = " (select-all-checked-js coverage-ids) "; "
       "el.indeterminate = " (select-all-indeterminate-js coverage-ids)))

(defn- select-all-change-js
  [coverage-ids]
  (str "$insuranceWorkbench.selectedCoverageIds = "
       (select-all-checked-js coverage-ids)
       " ? [] : "
       (js-string-array coverage-ids)))

(defn- select-all-attrs
  [tr coverage-ids]
  {:aria-label                (tr [:action/select-all])
   :data-workbench-select-all "true"
   :data-on:change           (select-all-change-js coverage-ids)
   :data-effect              (select-all-effect-js coverage-ids)})

(defn- row-selection-effect-js
  [coverage-id]
  (str "el.checked = " (selected-coverage-id-js coverage-id)))

(defn- row-selection-attrs
  [coverage-id]
  (let [coverage-id (str coverage-id)]
    {:data-effect    (row-selection-effect-js coverage-id)
     :data-on:change (str "if (evt.target.checked) { "
                          "$insuranceWorkbench.selectedCoverageIds = "
                          "Array.from(new Set([..." selected-coverage-ids-js ", '" coverage-id "'])); "
                          "} else { "
                          "$insuranceWorkbench.selectedCoverageIds = "
                          selected-coverage-ids-js ".filter(id => id !== '" coverage-id "'); "
                          "}")}))

(defn- bulk-selection-disabled-js
  []
  (str selected-count-js " === 0"))

(defn- bulk-action-disabled-js
  [editable?]
  (str "(" (bulk-selection-disabled-js) ") || "
       (if editable? "false" "true")))

(defn- bulk-status-action-js
  [req target-kind]
  (let [[target-signal keep-signal action] (case target-kind
                                             :workflow ["targetWorkflowStatus"
                                                        "targetChangeStatus"
                                                        ::actions/bulk-mark-workflow]
                                             :change ["targetChangeStatus"
                                                      "targetWorkflowStatus"
                                                      ::actions/bulk-set-change])]
    (str "$insuranceWorkbench." target-signal " = evt.detail.item.value; "
         "$insuranceWorkbench." keep-signal " = 'keep'; "
         "$loading = 'insurance-workbench-bulk'; "
         "@post('" (d*/act req action) "')")))

(def bulk-workflow-targets
  [{:target :todo :status :needs-review}
   {:target :reviewed :status :reviewed}
   {:target :active :status :coverage-active}])

(defn- bulk-status-dropdown
  [{:keys [tr] :as req} {:keys [editable? items label-key target-kind]}]
  (into [:wa-dropdown {:size              "m"
                       :data-on:wa-select (bulk-status-action-js req target-kind)}
         [button/Button {:appearance         "outlined"
                         :size               "s"
                         :slot               "trigger"
                         :with-caret         true
                         :disabled           true
                         :data-attr:disabled (bulk-action-disabled-js editable?)
                         :data-attr:loading  "$loading === 'insurance-workbench-bulk'"}
          (tr label-key)]]
        items))

(defn- bulk-workflow-status-dropdown
  [req editable?]
  (bulk-status-dropdown
   req
   {:editable?   editable?
    :label-key   [:insurance.workbench/mark-workflow]
    :target-kind :workflow
    :items       (for [{:keys [target status]} bulk-workflow-targets]
                   [:wa-dropdown-item {:value (name target)}
                    (insurance-ui/status-label (:tr req) (domain/qualified-coverage-status status))])}))

(defn- bulk-change-status-dropdown
  [req editable?]
  (bulk-status-dropdown
   req
   {:editable?   editable?
    :label-key   [:insurance.workbench/set-change]
    :target-kind :change
    :items       (for [change domain/simple-instrument-coverage-changes]
                   [:wa-dropdown-item {:value (name change)}
                    (insurance-ui/change-label (:tr req) (domain/qualified-coverage-change change))])}))

(defn- deselect-all-button
  [tr]
  [button/Button {:appearance         "plain"
                  :size               "s"
                  :disabled           true
                  :data-on:click      "$insuranceWorkbench.selectedCoverageIds = []"
                  :data-attr:disabled (bulk-selection-disabled-js)}
   (tr [:insurance.workbench/deselect-all])])

(def ^:private workbench-group-toggle-selector
  "[data-workbench-toggle]")

(defn- set-all-groups-expanded-js
  [expanded?]
  (str "document.querySelectorAll('" workbench-group-toggle-selector "')"
       ".forEach(el => el.setAttribute('aria-expanded', '" (if expanded? "true" "false") "'));"))

(def expand-all-groups-js
  (set-all-groups-expanded-js true))

(def collapse-all-groups-js
  (set-all-groups-expanded-js false))

(defn- expansion-actions
  [tr]
  [:menu
   [:li
    [button/Button {:appearance    "outlined"
                    :size          "s"
                    :data-on:click expand-all-groups-js}
     (tr [:insurance.workbench/expand-all])]]
   [:li
    [button/Button {:appearance    "outlined"
                    :size          "s"
                    :data-on:click collapse-all-groups-js}
     (tr [:insurance.workbench/collapse-all])]]])

(def bulk-action-sentinel-exit-js
  "$insuranceWorkbench.bulkActionStuck = el.getBoundingClientRect().top < 0")

(defn- bulk-action-sentinel
  []
  [:div {:class                  "insurance-workbench-bulk-action-sentinel"
         :aria-hidden            "true"
         :data-on-intersect      "$insuranceWorkbench.bulkActionStuck = false"
         :data-on-intersect__exit bulk-action-sentinel-exit-js}])

(defn- bulk-actions
  [{:keys [tr] :as req} editable?]
  [:div
   [:p
    [:strong
     [:span {:data-text selected-count-js} "0"]
     " "
     (tr [:insurance.workbench/selected])]
    (deselect-all-button tr)]
   [:menu
    [:li
     (bulk-workflow-status-dropdown req editable?)]
    [:li
     (bulk-change-status-dropdown req editable?)]]
   (when-not editable?
     [:small (tr [:insurance.workbench/read-only])])])

(defn- bulk-action-bar
  [{:keys [tr] :as req} {:keys [editable? filters rows]}]
  (list
   (bulk-action-sentinel)
   [:section {:class                              "insurance-workbench-bulk-action-bar"
              :data-class:insurance-workbench-bulk-action-bar--sticky (str selected-count-js " > 0")
              :data-class:insurance-workbench-bulk-action-bar--stuck  (str selected-count-js " > 0 && $insuranceWorkbench.bulkActionStuck")}
    (bulk-actions req editable?)
    (when (and (= :member (:group filters))
               (seq rows))
      (expansion-actions tr))]))

(defn- column-alignment-style
  [{:keys [align]}]
  (case align
    :end "text-align: end;"
    nil))

(def action-column-class
  "insurance-workbench-row-actions-cell")

(def status-column-class
  "insurance-workbench-status-cell")

(def header-variant-classes
  {:nowrap "insurance-workbench-table-heading--nowrap"})

(defn- action-column?
  [{:keys [id]}]
  (= :actions id))

(defn- status-column?
  [{:keys [id]}]
  (= :status id))

(defn- table-heading-class
  [column]
  (apply ui2/cs
         (cond-> []
           (action-column? column) (conj action-column-class)
           true (into (keep header-variant-classes (:header-variants column))))))

(defn- table-heading-attrs
  [column]
  (let [class (table-heading-class column)
        style (column-alignment-style column)]
    (cond-> {:scope "col"}
      (seq class) (assoc :class class)
      style (assoc :style style))))

(defn- table-cell-attrs
  [column]
  (cond-> (cond
            (action-column? column) {:class action-column-class}
            (status-column? column) {:class status-column-class}
            :else {})
    (column-alignment-style column)
    (assoc :style (column-alignment-style column))))

(defn- table-cell
  [column-id content]
  [:td (table-cell-attrs (table-column column-id)) content])

(defn- table-heading-content
  [tr coverage-ids {:keys [id label-key]}]
  (cond
    (= id :selection)
    [:wa-checkbox (select-all-attrs tr coverage-ids)]

    (= id :actions)
    [:span {:class "wa-visually-hidden"} (tr label-key)]

    :else
    (tr label-key)))

(defn- table-headings
  [tr columns coverage-ids]
  [:thead
   (into [:tr]
         (for [column columns]
           [:th (table-heading-attrs column)
            (table-heading-content tr coverage-ids column)]))])

(defn- missing-badge
  [tr]
  [:wa-badge {:appearance "outlined"
              :variant    "warning"
              :pill       true}
   (tr [:insurance.workbench/missing])])

(defn- member-name-with-avatar
  [{:keys [member-avatar-template member-id member-label]}]
  [:div {:class "wa-flank wa-gap-xs wa-align-items-center"
         :style "--flank-size: 1.75rem;"}
   [avatar/Avatar (cond-> {::avatar/name member-label
                           ::avatar/avatar-template member-avatar-template
                           :shape "rounded"
                           :style "--size: 1.75rem"
                           :loading "lazy"}
                    member-id (assoc ::avatar/href (urls/link-member member-id)))]
   (if member-id
     [:a {:href (urls/link-member member-id)} member-label]
     [:span member-label])])

(defn- coverage-row-action-button-id
  [coverage-id action]
  (str "insurance-workbench-row-action-"
       (name action)
       "-"
       (ui2/safe-dom-id coverage-id)))

(defn- row-action-button
  [button-id label href icon-name]
  [button/Button {:id         button-id
                  :href       href
                  :appearance "filled"
                  :size       "xs"
                  :variant    "neutral"
                  :aria-label label
                  :class      "insurance-workbench-row-action-button"}
   [:span {:class "insurance-workbench-row-action-icon"}
    [ico/Icon {::ico/library :phosphor
               ::ico/name    icon-name}]]])

(defn- row-action-dropdown-trigger
  [button-id label class appearance variant]
  [button/Button (cond-> {:id         button-id
                          :appearance appearance
                          :size       "xs"
                          :type       "button"
                          :slot       "trigger"
                          :aria-label label
                          :class      class}
                   variant (assoc :variant variant))
   [:span {:class "insurance-workbench-row-action-icon"}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :ellipsis}]]])

(defn- row-action-tooltip
  [button-id label]
  [:wa-tooltip {:for           button-id
                :placement     "top"
                :without-arrow true}
   label])

(defn- row-action-dropdown-item
  [label href icon-name]
  [:wa-dropdown-item {:value   href
                      :onclick "window.location = this.value"}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    icon-name
              :slot         "icon"}]
   label])

(defn- row-action-overflow-dropdown
  [{:keys [class trigger]} view-label view-href edit-label edit-href]
  [:wa-dropdown {:class     class
                 :placement "bottom-end"}
   trigger
   (row-action-dropdown-item view-label view-href :eye)
   (row-action-dropdown-item edit-label edit-href :pencil-simple)])

(defn- row-actions
  [{:keys [tr]} coverage-id]
  (let [view-label              (tr [:action/view])
        edit-label              (tr [:action/edit])
        actions-label           (tr [:actions])
        trigger-button-id       (coverage-row-action-button-id coverage-id :more)
        group-trigger-button-id (coverage-row-action-button-id coverage-id :more-group)
        dropdown-button-id      (coverage-row-action-button-id coverage-id :dropdown)
        view-button-id          (coverage-row-action-button-id coverage-id :view)
        edit-button-id          (coverage-row-action-button-id coverage-id :edit)
        view-href               (urls/link-coverage coverage-id)
        edit-href               (urls/link-coverage-edit coverage-id)]
    [:div {:class "insurance-workbench-row-actions-menu"}
     [:div {:class "insurance-workbench-row-actions-hover"}
      [button/Button {:id         trigger-button-id
                      :appearance "plain"
                      :size       "xs"
                      :type       "button"
                      :aria-label actions-label
                      :class      "insurance-workbench-row-actions-trigger insurance-workbench-row-actions-trigger--idle"}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :ellipsis
                  ;; line it up with the row-action-dropdown-trigger
                  :style "margin-bottom: 3px;"}]]
      [:wa-button-group {:class       "insurance-workbench-row-action-group"
                         :label       actions-label
                         :orientation "horizontal"}
       (row-action-button view-button-id
                          view-label
                          view-href
                          :eye)
       (row-action-button edit-button-id
                          edit-label
                          edit-href
                          :pencil-simple)
       (row-action-overflow-dropdown
        {:class   "insurance-workbench-row-actions-dropdown insurance-workbench-row-actions-dropdown--group"
         :trigger (row-action-dropdown-trigger
                   group-trigger-button-id
                   actions-label
                   "insurance-workbench-row-actions-trigger insurance-workbench-row-actions-trigger--group"
                   "filled"
                   "neutral")}
        view-label
        view-href
        edit-label
        edit-href)]]
     (row-action-overflow-dropdown
      {:class   "insurance-workbench-row-actions-dropdown insurance-workbench-row-actions-dropdown--mobile"
       :trigger (row-action-dropdown-trigger
                 dropdown-button-id
                 actions-label
                 "insurance-workbench-row-actions-trigger insurance-workbench-row-actions-trigger--dropdown"
                 "plain"
                 nil)}
      view-label
      view-href
      edit-label
      edit-href)
     (row-action-tooltip trigger-button-id actions-label)
     (row-action-tooltip group-trigger-button-id actions-label)
     (row-action-tooltip view-button-id view-label)
     (row-action-tooltip edit-button-id edit-label)]))

(defn- coverage-status-icon-id
  [coverage-id kind]
  (str "insurance-workbench-status-"
       (name kind)
       "-"
       (ui2/safe-dom-id coverage-id)))

(defn- row-status-icon
  [coverage-id kind label icon]
  (when icon
    (let [icon-id (coverage-status-icon-id coverage-id kind)]
      [[:span {:id                         icon-id
               :class                      "insurance-workbench-status-icon"
               :data-workbench-status-icon (name kind)
               :role                       "img"
               :aria-label                 label}
        icon]
       [:wa-tooltip {:for           icon-id
                     :placement     "top"
                     :without-arrow true}
        label]])))

(defn- row-status-icons
  [tr {:keys [change-status coverage-id workflow-status]}]
  (into [:span {:class                         "wa-cluster wa-gap-2xs wa-align-items-center"
                :data-workbench-status-icons "true"}]
        (mapcat identity)
        [(row-status-icon coverage-id
                          :workflow
                          (tr [workflow-status])
                          (insurance-ui/workflow-status-icon workflow-status))
         (row-status-icon coverage-id
                          :change
                          (tr [change-status])
                          (insurance-ui/change-status-icon change-status))]))

;; TODO Stop matching coverage type icons by user-defined database labels.
;; Add database-backed icon metadata or stable icon keys to coverage types instead.
(def ^:private coverage-type-icon-data
  {"Grundschutz"      {:icon :shield
                       :key  :grundschutz}
   "Nachzeit im Auto" {:icon :car-profile
                       :key  :nachzeit-im-auto}
   "Proberaum"        {:icon :warehouse
                       :key  :proberaum}})

(defn- coverage-type-label
  [coverage-type-name]
  (let [label (some-> coverage-type-name str str/trim)]
    (when-not (str/blank? label)
      label)))

(defn- coverage-type-icon-id
  [coverage-id index]
  (str "insurance-workbench-coverage-type-"
       (ui2/safe-dom-id coverage-id)
       "-"
       index))

(defn- coverage-type-token
  [coverage-id index coverage-type-name]
  (when-let [label (coverage-type-label coverage-type-name)]
    (if-let [{:keys [icon key]} (get coverage-type-icon-data label)]
      (let [icon-id (coverage-type-icon-id coverage-id index)]
        [[:span {:id                                icon-id
                 :data-workbench-coverage-type-icon (name key)
                 :role                              "img"
                 :aria-label                        label}
          [ico/Icon {::ico/library :phosphor
                     ::ico/name    icon}]]
         [:wa-tooltip {:for           icon-id
                       :placement     "top"
                       :without-arrow true}
          label]])
      [[:span label]])))

(defn- coverage-type-icons
  [coverage-id coverage-type-names]
  (->> coverage-type-names
       (keep coverage-type-label)
       (map-indexed #(coverage-type-token coverage-id %1 %2))
       (mapcat identity)
       seq))

(defn- row-cell-content
  [{:keys [tr]} currency row column-id]
  (let [{:keys [category-name coverage-id coverage-type-names harmonia-id instrument-name
                missing-insurer-id? missing-photo? photo-count private? insured-value cost]} row]
    (case column-id
      :selection
      [:wa-checkbox (merge {:aria-label (tr [:insurance.workbench/select-row])}
                           (row-selection-attrs coverage-id))]

      :member
      (member-name-with-avatar row)

      :instrument
      [:a {:href (urls/link-coverage coverage-id)} instrument-name]

      :category
      category-name

      :ownership
      (insurance-ui/ownership-badge-short tr private?)

      :photos
      (if missing-photo?
        (missing-badge tr)
        photo-count)

      :harmonia-id
      (if missing-insurer-id?
        (missing-badge tr)
        (ui2/muted harmonia-id))

      :status
      (row-status-icons tr row)

      :value
      (ui2/money insured-value currency)

      :cost
      (ui2/money cost currency)

      :coverage-types
      (coverage-type-icons coverage-id coverage-type-names)

      :actions
      (row-actions {:tr tr} coverage-id))))

(defn- row-cells
  [req currency columns row]
  (for [{:keys [id]} columns]
    (table-cell id (row-cell-content req currency row id))))

(defn- coverage-row
  ([req currency columns row]
   (coverage-row req currency columns row nil))
  ([req currency columns row attrs]
   (into [:tr attrs]
         (row-cells req currency columns row))))

(defn- flat-table
  [req {:keys [filters policy rows table view]}]
  (let [currency     (:insurance.policy/currency policy)
        coverage-ids (mapv :coverage-id rows)
        columns      (table-columns-for (:group filters) view table)]
    (ui2/table-shell
     [:table {:class "wa-table leading-condensed"}
      (table-headings (:tr req) columns coverage-ids)
      (into [:tbody]
            (map #(coverage-row req currency columns %) rows))])))

(defn- member-group-id
  [{:keys [member-id member-label]}]
  (ui2/safe-dom-id (or member-id member-label)))

(def ^:private group-toggle-js
  (str "const expanded = el.getAttribute('aria-expanded') === 'true'; "
       "el.setAttribute('aria-expanded', expanded ? 'false' : 'true');"))

(defn- member-heading-row
  [{:keys [tr]} columns group]
  (let [group-id (member-group-id group)]
    [:tr {:data-workbench-member-heading group-id}
     [:th {:scope   "rowgroup"
           :colspan (count columns)
           :style   (str "background: var(--wa-color-surface-raised); "
                         "border-block-start: var(--wa-border-width-s) solid var(--wa-color-surface-border); "
                         "padding-block: var(--wa-space-xs);")}
      [:div {:class "wa-split wa-gap-s wa-align-items-center"}
       [:div {:class "wa-cluster wa-gap-xs wa-align-items-center"}
        [button/Button {:appearance            "plain"
                        :size                  "s"
                        :class                 "insurance-workbench-member-toggle"
                        :aria-label            (:member-label group)
                        :data-workbench-toggle group-id
                        :aria-expanded         "true"
                        :data-on:click         group-toggle-js}
         [ico/Icon {::ico/library :phosphor
                    ::ico/name    :caret-right
                    :class        "insurance-workbench-member-toggle-icon"}]]
        (member-name-with-avatar group)]
       [:span {:class "wa-caption-s wa-color-text-quiet"}
        (tr [:insurance/item-count])
        ": "
        [:span {:class "wa-font-weight-bold"}
         (:row-count group)]]]]]))

(defn- member-footer-cell-attrs
  [edge kind]
  (cond-> {:class "insurance-workbench-member-footer-cell"}
    edge
    (assoc :data-workbench-member-footer-edge
           (case edge
             :start "start"
             :end "end"))
    kind
    (assoc :data-workbench-member-footer-cell
           (case kind
             :total-label "total-label"
             :total-value "total-value"
             :spacer "spacer"))))

(def ^:private member-footer-total-columns
  #{:value :cost})

(defn- member-footer-total-cell
  [{:keys [tr]} currency group {:keys [id]}]
  (case id
    :value
    [:td (merge {:title      (tr [:insurance/value])
                 :aria-label (tr [:insurance/value])}
                (member-footer-cell-attrs nil :total-value))
     (ui2/money (:total-insured-value group) currency)]

    :cost
    [:td (merge {:title      (tr [:insurance/cost])
                 :aria-label (tr [:insurance/cost])}
                (member-footer-cell-attrs nil :total-value))
     (ui2/money (:total-cost group) currency)]))

(defn- member-footer-row
  [{:keys [tr] :as req} currency columns group]
  (let [group-id      (member-group-id group)
        columns       (vec columns)
        total-indexes (keep-indexed (fn [idx {:keys [id]}]
                                      (when (contains? member-footer-total-columns id)
                                        idx))
                                    columns)]
    (into [:tr {:class                        "insurance-workbench-collapsible-row"
                :data-workbench-member-footer group-id}]
          (if (seq total-indexes)
            (let [first-total-idx (first total-indexes)
                  last-total-idx  (last total-indexes)
                  trailing-count  (- (count columns) (inc last-total-idx))]
              (concat
               (when (pos? first-total-idx)
                 [[:td (assoc (member-footer-cell-attrs :start :total-label)
                              :colspan first-total-idx)
                   [:strong {:class "wa-caption-s wa-color-text-quiet"}
                    (tr [:insurance/total])]]])
               (for [idx total-indexes]
                 (member-footer-total-cell req currency group (nth columns idx)))
               (when (pos? trailing-count)
                 [[:td (assoc (member-footer-cell-attrs :end :spacer)
                              :colspan trailing-count)]])))
            [[:td (assoc (member-footer-cell-attrs :start :total-label)
                         :colspan (count columns))
              [:strong {:class "wa-caption-s wa-color-text-quiet"}
               (tr [:insurance/total])]]]))))

(defn- member-group-body
  [req currency columns group]
  (let [group-id (member-group-id group)]
    (into [:tbody {:data-workbench-member-group group-id}
           (member-heading-row req columns group)]
          (concat
           (map #(coverage-row req
                               currency
                               columns
                               %
                               {:class "insurance-workbench-collapsible-row"})
                (:rows group))
           [(member-footer-row req currency columns group)]))))

(defn- grouped-table
  [req {:keys [groups policy table view]}]
  (let [currency     (:insurance.policy/currency policy)
        coverage-ids (mapv :coverage-id (mapcat :rows groups))
        columns      (table-columns-for :member view table)]
    (ui2/table-shell
     (into [:table {:class "wa-table leading-condensed"}
            (table-headings (:tr req) columns coverage-ids)]
           (map #(member-group-body req currency columns %) groups)))))

(defn- pagination-summary
  [tr {:keys [range-end range-start total-results]}]
  (tr [:insurance.workbench/pagination-summary] [range-start range-end total-results]))

(defn- pagination-url
  [workbench overrides]
  (urls/link-policy-workbench (:policy workbench)
                              (pagination-query-state workbench overrides)))

(defn- js-object-literal
  [m]
  (str "{"
       (str/join ", "
                 (for [[k v] m]
                   (str (pr-str (str k)) ": " (pr-str v))))
       "}"))

(defn- page-size-select-js
  [{:keys [pagination] :as workbench}]
  (let [urls (into {}
                   (for [page-size (:page-sizes pagination)]
                     [page-size (pagination-url workbench
                                                {:page 1
                                                 :page-size page-size})]))]
    (str "const urls = " (js-object-literal urls) "; "
         "window.location.href = urls[evt.detail.item.value]")))

(defn- pagination-nav-button
  [tr label-key icon-name href]
  [button/Button (cond-> {:appearance "plain"
                          :size       "s"
                          :aria-label (tr label-key)}
                   href (assoc :href href)
                   (nil? href) (assoc :disabled true))
   [ico/Icon {::ico/library :phosphor
              ::ico/name    icon-name}]])

(defn- page-size-item
  [selected-page-size page-size]
  (let [selected? (= selected-page-size page-size)]
    [:wa-dropdown-item {:value (str page-size)}
     [ico/Icon (cond-> {::ico/library :phosphor
                        ::ico/name    :check
                        :slot         "icon"}
                 (not selected?) (assoc :style "visibility: hidden;"))]
     page-size]))

(defn- page-size-dropdown
  [tr {:keys [pagination] :as workbench}]
  [:wa-dropdown {:data-workbench-page-size "true"
                 :data-on:wa-select       (page-size-select-js workbench)}
   [button/Button {:slot       "trigger"
                   :appearance "plain"
                   :size       "s"}
    (pagination-summary tr pagination)]
   [:h3 (tr [:insurance.workbench/rows-per-page])]
   (for [page-size (:page-sizes pagination)]
     (page-size-item (:page-size pagination) page-size))])

(defn- pagination-controls
  [{:keys [tr]} {:keys [pagination] :as workbench}]
  (let [prev-url (when (:has-prev? pagination)
                   (pagination-url workbench {:page (:prev-page pagination)}))
        next-url (when (:has-next? pagination)
                   (pagination-url workbench {:page (:next-page pagination)}))]
    [:div {:class                     "insurance-workbench-pagination"
           :data-workbench-pagination "true"}
     [:div {:class "wa-stack wa-gap-xs insurance-workbench-pagination__inner"}
      [divider/Divider]
      [:nav {:class      "wa-cluster wa-gap-2xs wa-align-items-center wa-justify-content-end"
             :aria-label (tr [:insurance.workbench/pagination])}
       (pagination-nav-button tr [:action/previous] :caret-left prev-url)
       (page-size-dropdown tr workbench)
       (pagination-nav-button tr [:action/next] :caret-right next-url)]]]))

(defn- rows-section
  [req {:keys [filters rows] :as workbench}]
  (if (seq rows)
    [:div {:class "wa-stack wa-gap-xs"}
     (case (:group filters)
       :member (grouped-table req workbench)
       :none (flat-table req workbench))
     (pagination-controls req workbench)]
    (ui2/empty-state ((:tr req) [:insurance.workbench/empty-title])
                     ((:tr req) [:insurance.workbench/empty-body]))))

(defn page
  [{:keys [db page-state tr] :as req}]
  (let [table     (get-in page-state [:insurance-workbench :table])
        workbench (assoc (queries/policy-workbench db (policy-id req) (workbench-params req))
                         :table table)
        policy    (:policy workbench)]
    (ui2/datastar-page2 {:class "full-width"}
                        [:div {:class              "insurance-workbench wa-stack wa-gap-xl"
                               :data-preserve-attr "data-signals"
                               :data-signals       (d*/->signals (selection-signals policy (:filters workbench) table (:view workbench)))}
                         [page-header/PageHeader
                          {:breadcrumb (page-breadcrumb req policy)
                           :title      (tr [:insurance.workbench/title])
                           :subtitle   (:insurance.policy/name policy)}]
                         (workbench-toolbar req workbench)
                         (bulk-action-bar req workbench)
                         (rows-section req workbench)])))

(d*/refresh-all!)
