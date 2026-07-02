(ns app.insurance.policy.workbench.views
  (:require
   [app.datastar :as d*]
   [app.insurance.domain :as domain]
   [app.insurance.policy.workbench.actions :as actions]
   [app.insurance.policy.workbench.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.ui2 :as ui2]
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
  (let [params (or (get-in req [:parameters :query])
                   (:query-params req)
                   {})]
    {:view                (query-param params :view)
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
     :group               (query-param params :group)}))

(defn- active-filter-params
  [{:keys [page-state]}]
  (let [filters (get-in page-state [:insurance-workbench :filters])]
    (cond-> {}
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
  [{:id :member :label-key [:col/member]}
   {:id :instrument :label-key [:instrument/instrument]}
   {:id :category :label-key [:instrument/category]}
   {:id :ownership :label-key [:band-private]}
   {:id :photos :label-key [:insurance.workbench/photos] :align :end}
   {:id :harmonia-id :label-key [:instrument.coverage/insurer-id] :align :end}
   {:id :workflow :label-key [:insurance.workbench/workflow-status]}
   {:id :change :label-key [:insurance.workbench/change-status]}
   {:id :value :label-key [:insurance/value] :align :end}
   {:id :cost :label-key [:instrument.coverage/cost] :align :end}
   {:id :coverage-types :label-key [:insurance/coverage-types]}
   {:id :actions :label-key [:actions]}])

(def selection-column
  {:id :selection})

(def table-column-by-id
  (into {(:id selection-column) selection-column}
        (map (juxt :id identity))
        table-columns))

(defn- table-column
  [id]
  (table-column-by-id id))

(defn- table-columns-for
  [group]
  (into [selection-column]
        (cond->> table-columns
          (= group :member) (remove #(= :member (:id %))))))

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

(defn- view-button
  [tr policy filters current-view view]
  (let [active? (= current-view view)]
    [button/Button (cond-> {:appearance          (if active? "filled" "outlined")
                            :size                "s"
                            :href                (urls/link-policy-workbench
                                                  policy
                                                  (query-state filters
                                                               current-view
                                                               {:view view}))
                            :data-workbench-view (name view)}
                     active? (assoc :variant "brand"))
     (tr (view-label-keys view))]))

(defn- view-select-form
  [tr {:keys [filters policy view]}]
  (into [:form {:method "get"
                :action (urls/link-policy-workbench policy)}]
        (concat (hidden-inputs (concat [["group" (:group filters)]
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
           (view-button tr (:policy workbench) (:filters workbench) (:view workbench) view-option)))
   (view-select-form tr workbench)])

(defn- search-form
  [{:keys [tr]} {:keys [filters policy view]}]
  (into [:form {:method "get"
                :action (urls/link-policy-workbench policy)}]
        (concat (hidden-inputs (concat [["view" view]
                                        ["group" (:group filters)]
                                        ["category-id" (category-query-values filters)]
                                        ["coverage-type-id" (coverage-type-query-values filters)]
                                        ["ownership" (:ownership filters)]
                                        ["missing-photos" (active-boolean-query-value (:missing-photos? filters))]
                                        ["missing-harmonia-id" (active-boolean-query-value (:missing-harmonia-id? filters))]
                                        ["workflow-status" (workflow-status-query-values filters)]
                                        ["change-status" (change-status-query-values filters)]]
                                       (value-filter-hidden-fields filters)))
                [[:wa-input {:name        "member-q"
                             :aria-label  (tr [:insurance.workbench/search])
                             :placeholder (tr [:insurance.workbench/member-search-placeholder])
                             :value       (or (:member-q filters) "")
                             :appearance  "outlined"}]])))

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

(defn- table-column-checkbox
  [tr {:keys [id label-key]}]
  [:wa-checkbox {:checked                      true
                 :data-workbench-column-toggle (name id)
                 :data-on:change               (str "$insuranceWorkbench.table.columns['" (name id) "'] = evt.target.checked")}
   (tr label-key)])

(defn- group-switch-js
  [policy filters view]
  (let [member-url (urls/link-policy-workbench policy (query-state filters view {:group :member}))
        none-url   (urls/link-policy-workbench policy (query-state filters view {:group :none}))]
    (str "window.location.href = evt.target.checked ? "
         (pr-str member-url)
         " : "
         (pr-str none-url))))

(defn- table-settings-popover
  [{:keys [tr]} {:keys [filters policy view]}]
  [:wa-popover {:id            table-settings-popover-id
                :for           table-settings-button-id
                :placement     "bottom-end"
                :without-arrow true
                :style         "--max-width: 24rem;"}
   [:div {:class "wa-stack wa-gap-s"}
    [:strong (tr [:insurance.workbench/table-settings])]
    [:wa-switch {:data-on:change "$insuranceWorkbench.table.wrapCells = evt.target.checked"}
     (tr [:insurance.workbench/wrap-cells])]
    [:wa-switch (cond-> {:data-on:change (group-switch-js policy filters view)}
                  (= :member (:group filters)) (assoc :checked true))
     (tr [:insurance.workbench/group-member])]
    [:wa-divider]
    [:strong {:class "wa-caption-s wa-color-text-quiet"}
     (tr [:insurance.workbench/columns])]
    (for [column table-columns]
      (table-column-checkbox tr column))]])

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
  [policy filters]
  {:insuranceWorkbench {:policyId              (str (:insurance.policy/policy-id policy))
                        :selectedCoverageIds  []
                        :targetWorkflowStatus "keep"
                        :targetChangeStatus   "keep"
                        :filterEditor         {:field        nil
                                               :source       nil
                                               :appliedField nil}
                        :filterPopover        {:anchor filter-button-id
                                               :open   false}
                        :filterDraft          (merge {:categoryIds       (signal-array-values (category-query-values filters))
                                                      :ownership         (name (:ownership filters))
                                                      :coverageTypeIds   (signal-array-values (coverage-type-query-values filters))
                                                      :missingPhotos     (boolean (:missing-photos? filters))
                                                      :missingHarmoniaId (boolean (:missing-harmonia-id? filters))
                                                      :workflowStatuses  (signal-array-values (workflow-status-query-values filters))
                                                      :changeStatuses    (signal-array-values (change-status-query-values filters))}
                                                     (value-filter-signal-values filters))
                        :table                {:wrapCells     false
                                               :groupByMember (= :member (:group filters))
                                               :columns       (into {}
                                                                    (map (fn [{:keys [id]}]
                                                                           [(name id) true])
                                                                         table-columns))}}})

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

(def workbench-group-row-selector
  "[data-workbench-group]")

(def expand-all-groups-js
  (str "document.querySelectorAll('" workbench-group-row-selector "')"
       ".forEach(row => row.hidden = false); "
       "document.querySelectorAll('[data-workbench-toggle]')"
       ".forEach(el => el.dataset.collapsed = 'false');"))

(def collapse-all-groups-js
  (str "document.querySelectorAll('" workbench-group-row-selector "')"
       ".forEach(row => row.hidden = true); "
       "document.querySelectorAll('[data-workbench-toggle]')"
       ".forEach(el => el.dataset.collapsed = 'true');"))

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

(def bulk-action-bar-stuck-js
  (str "const top = parseFloat(getComputedStyle(el).top) || 0; "
       "el.classList.toggle('insurance-workbench-bulk-action-bar--stuck', "
       selected-count-js
       " > 0 && el.getBoundingClientRect().top <= top)"))

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
  [:section {:class                              "insurance-workbench-bulk-action-bar"
             :data-class:insurance-workbench-bulk-action-bar--sticky (str selected-count-js " > 0")
             :data-effect                         bulk-action-bar-stuck-js
             :data-on:scroll__window__throttle.100ms bulk-action-bar-stuck-js}
   (bulk-actions req editable?)
   (when (and (= :member (:group filters))
              (seq rows))
     (expansion-actions tr))])

(defn- column-alignment-style
  [{:keys [align]}]
  (case align
    :end "text-align: end;"
    nil))

(defn- table-heading-attrs
  [column]
  (cond-> {:scope "col"}
    (column-alignment-style column)
    (assoc :style (column-alignment-style column))))

(defn- table-cell-attrs
  [column]
  (if-let [style (column-alignment-style column)]
    {:style style}
    {}))

(defn- table-cell
  [column-id content]
  [:td (table-cell-attrs (table-column column-id)) content])

(defn- table-heading-content
  [tr coverage-ids {:keys [id label-key]}]
  (if (= id :selection)
    [:wa-checkbox (select-all-attrs tr coverage-ids)]
    (tr label-key)))

(defn- table-headings
  [tr group coverage-ids]
  [:thead
   (into [:tr]
         (for [column (table-columns-for group)]
           [:th (table-heading-attrs column)
            (table-heading-content tr coverage-ids column)]))])

(defn- missing-badge
  [tr]
  [:wa-badge {:appearance "outlined"
              :variant    "warning"
              :pill       true}
   (tr [:insurance.workbench/missing])])

(defn- row-cells
  [{:keys [tr]} currency group row]
  (let [{:keys [category-name coverage-id coverage-type-names harmonia-id instrument-name
                member-id member-label missing-insurer-id? missing-photo? photo-count
                private? workflow-status change-status insured-value cost]} row]
    (concat
     [(table-cell :selection
                  [:wa-checkbox (merge {:aria-label (tr [:insurance.workbench/select-row])}
                                       (row-selection-attrs coverage-id))])]
     (when (= group :none)
       [(table-cell :member
                    (if member-id
                      [:a {:href (urls/link-member member-id)} member-label]
                      member-label))])
     [(table-cell :instrument [:a {:href (urls/link-coverage coverage-id)} instrument-name])
      (table-cell :category category-name)
      (table-cell :ownership (insurance-ui/ownership-badge-short tr private?))
      (table-cell :photos (if missing-photo?
                            (missing-badge tr)
                            photo-count))
      (table-cell :harmonia-id (if missing-insurer-id?
                                 (missing-badge tr)
                                 (ui2/muted harmonia-id)))
      (table-cell :workflow (insurance-ui/status-badge tr workflow-status))
      (table-cell :change (insurance-ui/change-badge tr change-status))
      (table-cell :value (ui2/money insured-value currency))
      (table-cell :cost (ui2/money cost currency))
      (table-cell :coverage-types (str/join ", " coverage-type-names))
      (table-cell :actions [:span {:class "wa-cluster wa-gap-xs"}
                            [:a {:href (urls/link-coverage coverage-id)} (tr [:action/view])]
                            [:a {:href (urls/link-coverage-edit coverage-id)} (tr [:action/edit])]])])))

(defn- coverage-row
  ([req currency group row]
   (coverage-row req currency group row nil))
  ([req currency group row attrs]
   (into [:tr attrs]
         (row-cells req currency group row))))

(defn- flat-table
  [req {:keys [filters policy rows]}]
  (let [currency     (:insurance.policy/currency policy)
        coverage-ids (mapv :coverage-id rows)]
    (ui2/table-shell
     [:table {:class "wa-table leading-condensed"}
      (table-headings (:tr req) (:group filters) coverage-ids)
      (into [:tbody]
            (map #(coverage-row req currency (:group filters) %) rows))])))

(defn- column-count
  [group]
  (case group
    :none 13
    :member 12))

(defn- member-group-id
  [{:keys [member-id member-label]}]
  (ui2/safe-dom-id (or member-id member-label)))

(defn- group-toggle-js
  [group-id]
  (str "const collapsed = el.dataset.collapsed === 'true'; "
       "el.dataset.collapsed = collapsed ? 'false' : 'true'; "
       "document.querySelectorAll(\"[data-workbench-group='" group-id "']\")"
       ".forEach(row => row.hidden = !collapsed);"))

(defn- member-heading-row
  [{:keys [tr]} group]
  (let [group-id (member-group-id group)]
    [:tr {:data-workbench-member-heading group-id}
     [:th {:scope   "rowgroup"
           :colspan (column-count :member)
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
                        :data-collapsed        "false"
                        :data-on:click         (group-toggle-js group-id)}
         [ico/Icon {::ico/library :phosphor
                    ::ico/name    :caret-right
                    :class        "insurance-workbench-member-toggle-icon"}]]
        (if-let [member-id (:member-id group)]
          [:a {:href (urls/link-member member-id)} (:member-label group)]
          (:member-label group))]
       [:span {:class "wa-caption-s wa-color-text-quiet"}
        (tr [:insurance/item-count])
        ": "
        [:span {:class "wa-font-weight-bold"}
         (:row-count group)]]]]]))

(def ^:private member-footer-cell-base-style
  (str "background: var(--wa-color-neutral-fill-quiet); "
       "border-block-start: var(--wa-border-width-s) solid var(--wa-color-surface-border); "
       "border-block-end: var(--wa-border-width-s) solid var(--wa-color-surface-border); "
       "padding-block: var(--wa-space-xs);"))

(defn- member-footer-cell-style
  [position & styles]
  (str member-footer-cell-base-style
       (case position
         :start (str " border-inline-start: var(--wa-border-width-s) solid var(--wa-color-surface-border); "
                     "border-start-start-radius: var(--wa-border-radius-m); "
                     "border-end-start-radius: var(--wa-border-radius-m);")
         :end (str " border-inline-end: var(--wa-border-width-s) solid var(--wa-color-surface-border); "
                   "border-start-end-radius: var(--wa-border-radius-m); "
                   "border-end-end-radius: var(--wa-border-radius-m);")
         nil)
       (apply str styles)))

(defn- member-footer-row
  [{:keys [tr]} currency group]
  (let [group-id (member-group-id group)]
    [:tr {:data-workbench-member-footer group-id
          :data-workbench-group         group-id}
     [:td {:colspan 8
           :style   (member-footer-cell-style
                     :start
                     " text-align: end; "
                     "padding-inline: var(--wa-space-s);")}
      [:strong {:class "wa-caption-s wa-color-text-quiet"}
       (tr [:insurance/total])]]
     [:td {:title      (tr [:insurance/value])
           :aria-label (tr [:insurance/value])
           :style      (member-footer-cell-style
                        nil
                        " text-align: end; "
                        "font-weight: var(--wa-font-weight-bold); "
                        "padding-inline: var(--wa-space-s);")}
      (ui2/money (:total-insured-value group) currency)]
     [:td {:title      (tr [:insurance/cost])
           :aria-label (tr [:insurance/cost])
           :style      (member-footer-cell-style
                        nil
                        " text-align: end; "
                        "font-weight: var(--wa-font-weight-bold); "
                        "padding-inline: var(--wa-space-s);")}
      (ui2/money (:total-cost group) currency)]
     [:td {:colspan 2
           :style   (member-footer-cell-style
                     :end
                     " padding-inline: var(--wa-space-s);")}]]))

(defn- member-group-rows
  [req currency group]
  (let [group-id (member-group-id group)]
    (concat
     [(member-heading-row req group)]
     (map #(coverage-row req
                         currency
                         :member
                         %
                         {:data-workbench-group        group-id
                          :data-workbench-coverage-row "true"})
          (:rows group))
     [(member-footer-row req currency group)])))

(defn- grouped-table
  [req {:keys [groups policy]}]
  (let [currency     (:insurance.policy/currency policy)
        coverage-ids (mapv :coverage-id (mapcat :rows groups))]
    (ui2/table-shell
     [:table {:class "wa-table leading-condensed"}
      (table-headings (:tr req) :member coverage-ids)
      (into [:tbody]
            (mapcat #(member-group-rows req currency %) groups))])))

(defn- rows-section
  [req {:keys [filters rows] :as workbench}]
  (if (seq rows)
    (case (:group filters)
      :member (grouped-table req workbench)
      :none (flat-table req workbench))
    (ui2/empty-state ((:tr req) [:insurance.workbench/empty-title])
                     ((:tr req) [:insurance.workbench/empty-body]))))

(defn page
  [{:keys [db tr] :as req}]
  (let [workbench (queries/policy-workbench db (policy-id req) (workbench-params req))
        policy    (:policy workbench)]
    (ui2/datastar-page2 {:class "full-width"}
                        [:div {:class              "insurance-workbench wa-stack wa-gap-xl"
                               :data-preserve-attr "data-signals"
                               :data-signals       (d*/->signals (selection-signals policy (:filters workbench)))}
                         (ui2/page-header
                          {:breadcrumb (page-breadcrumb req policy)
                           :title      (tr [:insurance.workbench/title])
                           :subtitle   (:insurance.policy/name policy)})
                         (workbench-toolbar req workbench)
                         (bulk-action-bar req workbench)
                         (rows-section req workbench)])))

(d*/refresh-all!)
