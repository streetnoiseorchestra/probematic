(ns app.insurance.policy.workbench.views-test
  (:require
   [app.html :as html]
   [app.insurance.policy.workbench.views :as views]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def translations
  {[:instrument/category] "Category"
   [:insurance/value] "Versicherungswert"
   [:insurance/cost] "Cost"
   [:insurance/item-count] "Count"
   [:insurance/total] "Total"
   [:insurance.workbench/filter-by] "Filter by: %1"
   [:insurance.workbench/ownership] "Ownership"
   [:insurance.workbench/view] "View"
   [:insurance.workbench/ownership-band] "Band"
   [:insurance.workbench/ownership-private] "Private"
   [:insurance.workbench/selected] "selected"
   [:insurance.workbench/mark-workflow] "Mark Workflow"
   [:insurance.workbench/set-change] "Set Change"
   [:insurance.workbench/status] "Status"
   [:insurance.workbench/deselect-all] "Deselect All"
   [:insurance.workbench/expand-all] "Expand all"
   [:insurance.workbench/collapse-all] "Collapse all"
   [:insurance.workbench/value-operator] "Value operator"
   [:insurance.workbench/value-greater-than] "is greater than"
   [:insurance.workbench/value-less-than] "is less than"
   [:insurance.workbench/value-equal-to] "is equal to"
   [:insurance.workbench/value-between] "is between"
   [:insurance.workbench/value-min] "Minimum"
   [:insurance.workbench/value-max] "Maximum"
   [:insurance.workbench/pagination-summary] "%1–%2 of %3 results"
   [:insurance.workbench/rows-per-page] "Rows per page"
   [:instrument.coverage.status/needs-review] "Todo"
   [:instrument.coverage.status/reviewed] "Reviewed"
   [:instrument.coverage.status/coverage-active] "Active"
   [:instrument.coverage.change/changed] "Modified"
   [:instrument.coverage.change/new] "Added"
   [:instrument.coverage.change/removed] "Removed"
   [:instrument.coverage.change/none] "No changes"
   [:band-instrument] "Band Instrument"
   [:private-instrument] "Private Instrument"
   [:action/apply] "Apply"
   [:action/back] "Back"
   [:action/edit] "Edit"
   [:action/next] "Next"
   [:action/previous] "Previous"
   [:action/remove] "Remove"
   [:action/view] "View"
   [:actions] "Actions"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(deftest workbench-params-uses-active-category-filter-from-page-state
  (let [category-a (random-uuid)
        category-b (random-uuid)]
    (is (= {:view                "todo"
            :review-filter       nil
            :member-q            nil
            :category-id         [category-a category-b]
            :coverage-type-id    nil
            :ownership           :private
            :missing-photos      nil
            :missing-harmonia-id nil
            :workflow-status     nil
            :change-status       nil
            :value-operator      nil
            :value               nil
            :value-min           nil
            :value-max           nil
            :group               "member"}
           (#'views/workbench-params
            {:parameters {:query {:view "todo"
                                  :group "member"}}
             :page-state {:insurance-workbench
                          {:filters {:category-ids [category-a category-b]
                                     :ownership :private}}}})))))

(deftest workbench-params-uses-active-member-search-from-page-state
  (is (= {:active-search "Zoe"
          :cleared-search nil}
         {:active-search
          (:member-q
           (#'views/workbench-params
            {:parameters {:query {:view     "todo"
                                  :member-q "Anna"}}
             :page-state {:insurance-workbench
                          {:filters {:member-q "Zoe"}}}}))
          :cleared-search
          (:member-q
           (#'views/workbench-params
            {:parameters {:query {:view     "todo"
                                  :member-q "Anna"}}
             :page-state {:insurance-workbench
                          {:filters {:member-q nil}}}}))})))

(deftest empty-category-filter-in-page-state-overrides-category-query-param
  (let [category-id (random-uuid)]
    (is (= {:view                "todo"
            :review-filter       nil
            :member-q            nil
            :category-id         []
            :coverage-type-id    nil
            :ownership           :all
            :missing-photos      nil
            :missing-harmonia-id nil
            :workflow-status     nil
            :change-status       nil
            :value-operator      nil
            :value               nil
            :value-min           nil
            :value-max           nil
            :group               "member"}
           (#'views/workbench-params
            {:parameters {:query {:view "todo"
                                  :group "member"
                                  :category-id (str category-id)
                                  :ownership "private"}}
             :page-state {:insurance-workbench
                          {:filters {:category-ids []
                                     :ownership :all}}}})))))

(deftest workbench-params-uses-active-value-filter-from-page-state
  (is (= {:view                "todo"
          :review-filter       nil
          :member-q            nil
          :category-id         nil
          :coverage-type-id    nil
          :ownership           nil
          :missing-photos      nil
          :missing-harmonia-id nil
          :workflow-status     nil
          :change-status       nil
          :value-operator      :between
          :value               nil
          :value-min           1000M
          :value-max           3000M
          :group               "member"}
         (#'views/workbench-params
          {:parameters {:query {:view  "todo"
                                :group "member"}}
           :page-state {:insurance-workbench
                        {:filters {:value-filter {:operator :between
                                                  :min      1000M
                                                  :max      3000M}}}}}))))

(deftest category-filter-control-renders-checkbox-list
  (let [category-id (random-uuid)
        html        (html/->str
                     (#'views/category-select
                      tr
                      [{:category-id category-id
                        :category-name "Akkordeon"}]
                      #{}))]
    (is (= {:has-category-checkbox? true
            :binds-checkboxes-to-draft? true
            :uses-custom-checkbox-js? false
            :uses-wa-select? false}
           {:has-category-checkbox?
            (and (str/includes? html "<input")
                 (str/includes? html "type=\"checkbox\"")
                 (str/includes? html "Akkordeon")
                 (str/includes? html (str "value=\"" category-id "\"")))
            :binds-checkboxes-to-draft?
            (str/includes? html "data-bind=\"insuranceWorkbench.filterDraft.categoryIds\"")
            :uses-custom-checkbox-js?
            (or (str/includes? html "evt.target.checked")
                (str/includes? html "el.checked ="))
            :uses-wa-select?
            (str/includes? html "<wa-select")}))))

(deftest extended-filter-controls-use-checkboxes-and-switches
  (let [coverage-type-id (random-uuid)
        html             (html/->str
                          [:div
                           (#'views/coverage-type-select
                            tr
                            {:insurance.policy/coverage-types
                             [{:insurance.coverage.type/type-id coverage-type-id
                               :insurance.coverage.type/name "Basic"}]}
                            #{coverage-type-id})
                           (#'views/missing-photos-switch tr true)
                           (#'views/missing-harmonia-id-switch tr false)
                           (#'views/workflow-status-select tr #{:needs-review :reviewed})
                           (#'views/change-status-select tr #{:changed :new})])]
    (is (= {:coverage-types-checkboxes? true
            :missing-filters-switches? true
            :workflow-checkboxes? true
            :workflow-label-icons? true
            :change-checkboxes? true
            :change-label-icons? true
            :badge-icons-use-start-slot? true}
           {:coverage-types-checkboxes?
            (and (str/includes? html "Basic")
                 (str/includes? html "data-bind=\"insuranceWorkbench.filterDraft.coverageTypeIds\"")
                 (str/includes? html (str "value=\"" coverage-type-id "\"")))
            :missing-filters-switches?
            (and (str/includes? html "<wa-switch")
                 (str/includes? html "data-bind__prop.checked__event.change=\"insuranceWorkbench.filterDraft.missingPhotos\"")
                 (str/includes? html "data-bind__prop.checked__event.change=\"insuranceWorkbench.filterDraft.missingHarmoniaId\""))
            :workflow-checkboxes?
            (and (str/includes? html "data-bind=\"insuranceWorkbench.filterDraft.workflowStatuses\"")
                 (str/includes? html "value=\"needs-review\"")
                 (str/includes? html "value=\"reviewed\""))
            :workflow-label-icons?
            (and (str/includes? html "circle-question-outline")
                 (str/includes? html "var(--sno-dashboard-insurance-todo-needs-review-color")
                 (str/includes? html "circle-dot-outline"))
            :change-checkboxes?
            (and (str/includes? html "data-bind=\"insuranceWorkbench.filterDraft.changeStatuses\"")
                 (str/includes? html "value=\"changed\"")
                 (str/includes? html "value=\"new\""))
            :change-label-icons?
            (and (str/includes? html "circle-exclamation")
                 (str/includes? html "var(--wa-color-warning-fill-loud)")
                 (str/includes? html "circle-plus-solid"))
            :badge-icons-use-start-slot?
            (str/includes? html "slot=\"start\"")}))))

(deftest filter-editors-use-filter-by-title-and-unlabelled-controls
  (let [value-html     (html/->str
                        (#'views/filter-editor-shell
                         {::r/router router
                          :tr        tr}
                         :value
                         (#'views/value-filter-control tr)))
        ownership-html (html/->str
                        (#'views/filter-editor-shell
                         {::r/router router
                          :tr        tr}
                         :ownership
                         (#'views/ownership-select tr :private)))]
    (is (= {:value-title?                 true
            :value-select-unlabelled?     true
            :value-inputs-native?         true
            :value-input-unlabelled?      true
            :value-controls-accessible?   true
            :value-arrow-icon?            true
            :value-inputs-bind-native?    true
            :value-inputs-share-icon-row? true
            :ownership-title?             true
            :ownership-select-unlabelled? true
            :ownership-select-accessible? true}
           {:value-title?
            (str/includes? value-html ">Filter by: Versicherungswert</strong>")
            :value-select-unlabelled?
            (not (str/includes? value-html " label=\"Value operator\""))
            :value-inputs-native?
            (and (str/includes? value-html "<input")
                 (not (str/includes? value-html "<wa-input")))
            :value-input-unlabelled?
            (not (str/includes? value-html " label=\"Versicherungswert\""))
            :value-controls-accessible?
            (and (str/includes? value-html "aria-label=\"Value operator\"")
                 (str/includes? value-html "aria-label=\"Versicherungswert\""))
            :value-arrow-icon?
            (str/includes? value-html "arrow-bend-down-right")
            :value-inputs-bind-native?
            (and (str/includes? value-html "data-bind=\"insuranceWorkbench.filterDraft.value\"")
                 (str/includes? value-html "data-bind=\"insuranceWorkbench.filterDraft.valueMin\"")
                 (str/includes? value-html "data-bind=\"insuranceWorkbench.filterDraft.valueMax\"")
                 (not (str/includes? value-html "data-on:input=\"$insuranceWorkbench.filterDraft.value")))
            :value-inputs-share-icon-row?
            (and (str/includes? value-html "grid-template-columns: auto minmax(0, 1fr)")
                 (str/includes? value-html "grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr)"))
            :ownership-title?
            (str/includes? ownership-html ">Filter by: Ownership</strong>")
            :ownership-select-unlabelled?
            (not (str/includes? ownership-html " label=\"Ownership\""))
            :ownership-select-accessible?
            (str/includes? ownership-html "aria-label=\"Ownership\"")}))))

(deftest selection-signals-predefines-active-filter-arrays
  (let [category-id      (random-uuid)
        coverage-type-id (random-uuid)
        signals          (#'views/selection-signals
                          {:insurance.policy/policy-id (random-uuid)}
                          {:category-ids #{category-id}
                           :coverage-type-ids #{coverage-type-id}
                           :ownership :private
                           :missing-photos? true
                           :missing-harmonia-id? true
                           :workflow-statuses #{:needs-review :reviewed}
                           :change-statuses #{:changed :new}
                           :group :member})]
    (is (= {:categoryIds       [(str category-id)]
            :coverageTypeIds   [(str coverage-type-id)]
            :missingPhotos     true
            :missingHarmoniaId true
            :workflowStatuses  ["needs-review" "reviewed"]
            :changeStatuses    ["changed" "new"]}
           (select-keys (get-in signals [:insuranceWorkbench :filterDraft])
                        [:categoryIds
                         :coverageTypeIds
                         :missingPhotos
                         :missingHarmoniaId
                         :workflowStatuses
                         :changeStatuses])))))

(deftest selection-signals-use-server-table-column-state
  (let [signals (#'views/selection-signals
                 {:insurance.policy/policy-id (random-uuid)}
                 {:group :member}
                 {:columns {:cost false
                            :harmonia-id false}})]
    (is (= {"actions" true
            "category" true
            "cost" false
            "coverage-types" true
            "harmonia-id" false
            "instrument" true
            "member" true
            "ownership" true
            "photos" true
            "status" true
            "value" true}
           (get-in signals [:insuranceWorkbench :table :columns])))))

(deftest search-form-typeahead-posts-member-search-and-preserves-active-category-filter
  (let [policy-id   (random-uuid)
        category-id (random-uuid)
        html        (html/->str
                     (#'views/search-form
                      {::r/router router
                       :tr        tr}
                      {:policy  {:insurance.policy/policy-id policy-id}
                       :view    :todo
                       :filters {:group :member
                                 :ownership :all
                                 :member-q "Anna"
                                 :category-ids #{category-id}}}))]
    (is (= {:preserves-category?      true
            :preserves-member-search? true
            :binds-member-search?     true
            :posts-typeahead?         true
            :uses-clear?              true}
           {:preserves-category?
            (and (str/includes? html "name=\"category-id\"")
                 (str/includes? html (str "value=\"" category-id "\"")))
            :preserves-member-search?
            (str/includes? html "value=\"Anna\"")
            :binds-member-search?
            (str/includes? html "data-bind=\"insuranceWorkbench.memberQ\"")
            :posts-typeahead?
            (and (str/includes? html "data-on:input__debounce.250ms")
                 (str/includes? html "set-member-search-phrase"))
            :uses-clear?
            (str/includes? html "with-clear")}))))

(deftest toolbar-renders-responsive-view-select-and-flanked-search-controls
  (let [policy-id   (random-uuid)
        category-id (random-uuid)
        html        (html/->str
                     (#'views/workbench-toolbar
                      {::r/router router
                       :tr        tr}
                      {:policy               {:insurance.policy/policy-id policy-id}
                       :view                 :todo
                       :available-categories []
                       :filters              {:group :member
                                              :ownership :all
                                              :member-q "Anna"
                                              :category-ids #{category-id}}}))]
    (is (= {:view-buttons-nav?           true
            :mobile-view-select?         true
            :view-select-has-no-label?   true
            :view-select-preserves-state? true
            :search-row-flanked?         true
            :search-actions-grouped?     true}
           {:view-buttons-nav?
            (and (str/includes? html "insurance-workbench-view-switcher")
                 (str/includes? html "<nav aria-label=\"View\"")
                 (str/includes? html "data-workbench-view=\"todo\""))
            :mobile-view-select?
            (and (str/includes? html "<wa-select")
                 (str/includes? html "name=\"view\"")
                 (str/includes? html "aria-label=\"View\"")
                 (str/includes? html "data-on:change=\"evt.target.closest(&apos;form&apos;).requestSubmit()\""))
            :view-select-has-no-label?
            (not (str/includes? html " label=\"View\""))
            :view-select-preserves-state?
            (and (str/includes? html "name=\"member-q\"")
                 (str/includes? html "value=\"Anna\"")
                 (str/includes? html "name=\"category-id\"")
                 (str/includes? html (str "value=\"" category-id "\"")))
            :search-row-flanked?
            (str/includes? html "class=\"wa-flank:end wa-gap-2xs\"")
            :search-actions-grouped?
            (str/includes? html "class=\"wa-cluster wa-gap-2xs\"")}))))

(deftest table-settings-group-switch-navigates-and-preserves-toolbar-state
  (let [policy-id   (random-uuid)
        category-id (random-uuid)
        html        (html/->str
                     (#'views/table-settings-popover
                      {::r/router router
                       :tr        tr}
                      {:policy  {:insurance.policy/policy-id policy-id}
                       :view    :todo
                       :filters {:group :member
                                 :ownership :private
                                 :member-q "Anna"
                                 :category-ids #{category-id}}}))]
    (is (= {:navigates-on-change? true
            :can-switch-to-flat-list? true
            :can-switch-back-to-member-groups? true
            :preserves-category? true
            :preserves-member-search? true}
           {:navigates-on-change?
            (str/includes? html "window.location.href")
            :can-switch-to-flat-list?
            (str/includes? html "group=none")
            :can-switch-back-to-member-groups?
            (str/includes? html "group=member")
            :preserves-category?
            (str/includes? html (str "category-id=" category-id))
            :preserves-member-search?
            (str/includes? html "member-q=Anna")}))))

(deftest table-settings-column-toggles-post-round-trip-action
  (let [policy-id (random-uuid)
        html      (html/->str
                   (#'views/table-settings-popover
                    {::r/router router
                     :tr        tr}
                    {:policy  {:insurance.policy/policy-id policy-id}
                     :view    :todo
                     :filters {:group :member
                               :ownership :all}
                     :table   {:columns {:cost false}}}))
        cost-start (or (str/index-of html "data-workbench-column-toggle=\"cost\"") 0)
        cost-tag   (subs html cost-start (inc (or (str/index-of html ">" cost-start) cost-start)))]
    (is (= {:renders-column-toggle?      true
            :posts-toggle-action?        true
            :sets-column-signal?         true
            :sets-visibility-signal?     true
            :does-not-hide-front-end?     true
            :unchecked-from-server-state? true}
           {:renders-column-toggle?
            (str/includes? html "data-workbench-column-toggle=\"cost\"")
            :posts-toggle-action?
            (str/includes? html "kw=toggle-table-column")
            :sets-column-signal?
            (str/includes? html "$insuranceWorkbench.table.column = &apos;cost&apos;")
            :sets-visibility-signal?
            (str/includes? html "$insuranceWorkbench.table.columnVisible = evt.target.checked")
            :does-not-hide-front-end?
            (not (str/includes? html "table.columns["))
            :unchecked-from-server-state?
            (not (str/includes? cost-tag " checked"))}))))

(deftest active-category-filter-renders-removable-editor-chip
  (let [category-id (random-uuid)
        html        (html/->str
                     (#'views/workbench-toolbar
                      {::r/router router
                       :tr        tr}
                      {:policy               {:insurance.policy/policy-id (random-uuid)}
                       :view                 :todo
                       :available-categories [{:category-id category-id
                                               :category-name "Akkordeon"}]
                       :filters              {:group :member
                                              :ownership :all
                                              :category-ids #{category-id}}}))]
    (is (= {:renders-bar? true
            :renders-category-tag? true
            :renders-divider? true
            :opens-category-editor? true
            :uses-chip-editor-context? true
            :hides-back-button-in-chip-context? true
            :controls-popover-with-signals? true
            :fades-on-remove? true
            :removes-category-filter? true}
           {:renders-bar?
            (and (str/includes? html "data-workbench-active-filters=\"true\"")
                 (str/includes? html "var(--wa-color-neutral-fill-quiet)"))
            :renders-category-tag?
            (and (str/includes? html "<wa-tag")
                 (str/includes? html "with-remove")
                 (str/includes? html "data-on:wa-remove")
                 (str/includes? html "Category")
                 (str/includes? html "Akkordeon"))
            :renders-divider?
            (str/includes? html "class=\"sno-divider\"")
            :opens-category-editor?
            (str/includes? html "$insuranceWorkbench.filterEditor.field = &apos;category&apos;")
            :uses-chip-editor-context?
            (str/includes? html "$insuranceWorkbench.filterEditor.source = &apos;chip&apos;")
            :hides-back-button-in-chip-context?
            (str/includes? html "data-show=\"$insuranceWorkbench.filterEditor.source !== &apos;chip&apos;\"")
            :controls-popover-with-signals?
            (and (str/includes? html "$insuranceWorkbench.filterPopover.anchor = &apos;insurance-workbench-filter-chip-category&apos;")
                 (str/includes? html "$insuranceWorkbench.filterPopover.open = true")
                 (str/includes? html "data-attr:open=\"$insuranceWorkbench.filterPopover.open\"")
                 (str/includes? html "document.getElementById($insuranceWorkbench.filterPopover.anchor)"))
            :fades-on-remove?
            (and (str/includes? html "transition: opacity")
                 (str/includes? html "el.style.opacity = &apos;0&apos;")
                 (str/includes? html "el.style.pointerEvents = &apos;none&apos;"))
            :removes-category-filter?
            (and (str/includes? html "evt.stopPropagation()")
                 (str/includes? html "$insuranceWorkbench.filterPopover.open = false")
                 (str/includes? html "$insuranceWorkbench.filterDraft.categoryIds = ($insuranceWorkbench.filterDraft.categoryIds || []).map(() =&gt; &apos;&apos;)")
                 (str/includes? html "@post")
                 (str/includes? html "kw=apply-filter"))}))))

(deftest active-ownership-filter-renders-removable-editor-chip
  (let [html (html/->str
              (#'views/workbench-toolbar
               {::r/router router
                :tr        tr}
               {:policy               {:insurance.policy/policy-id (random-uuid)}
                :view                 :todo
                :available-categories []
                :filters              {:group :member
                                       :ownership :private
                                       :category-ids #{}}}))]
    (is (= {:renders-ownership-tag? true
            :opens-ownership-editor? true
            :removes-ownership-filter? true}
           {:renders-ownership-tag?
            (and (str/includes? html "<wa-tag")
                 (str/includes? html "Ownership")
                 (str/includes? html "Private"))
            :opens-ownership-editor?
            (and (str/includes? html "$insuranceWorkbench.filterEditor.field = &apos;ownership&apos;")
                 (str/includes? html "$insuranceWorkbench.filterPopover.anchor = &apos;insurance-workbench-filter-chip-ownership&apos;"))
            :removes-ownership-filter?
            (and (str/includes? html "$insuranceWorkbench.filterDraft.ownership = &apos;all&apos;")
                 (str/includes? html "kw=apply-filter"))}))))

(deftest workbench-table-uses-short-ownership-labels
  (let [coverage-id (random-uuid)
        rows        [{:category-name       "Strings"
                      :coverage-id         coverage-id
                      :coverage-type-names ["Basic"]
                      :harmonia-id         "H-123"
                      :instrument-name     "Violin"
                      :missing-insurer-id? false
                      :missing-photo?      false
                      :photo-count         3
                      :private?            false
                      :workflow-status     :instrument.coverage.status/needs-review
                      :change-status       :instrument.coverage.change/changed
                      :insured-value       1000M
                      :cost                12.34M}
                     {:category-name       "Strings"
                      :coverage-id         (random-uuid)
                      :coverage-type-names ["Basic"]
                      :harmonia-id         "H-124"
                      :instrument-name     "Cello"
                      :missing-insurer-id? false
                      :missing-photo?      false
                      :photo-count         1
                      :private?            true
                      :workflow-status     :instrument.coverage.status/reviewed
                      :change-status       :instrument.coverage.change/none
                      :insured-value       2000M
                      :cost                23.45M}]
        html        (html/->str
                     (#'views/flat-table
                      {:tr tr}
                      {:filters {:group :member}
                       :policy  {:insurance.policy/currency :EUR}
                       :rows    rows}))]
    (is (= {:uses-short-band-label?    true
            :uses-short-private-label? true
            :omits-long-labels?        true}
           {:uses-short-band-label?
            (str/includes? html ">Band</wa-badge>")
            :uses-short-private-label?
            (str/includes? html ">Private</wa-badge>")
            :omits-long-labels?
            (not (or (str/includes? html "Band Instrument")
                     (str/includes? html "Private Instrument")))}))))

(deftest workbench-table-collapses-workflow-and-change-into-status-icon-column
  (let [coverage-id       (random-uuid)
        html              (html/->str
                           (#'views/flat-table
                            {:tr tr}
                            {:filters {:group :none}
                             :policy  {:insurance.policy/currency :EUR}
                             :rows    [{:category-name       "Strings"
                                        :coverage-id         coverage-id
                                        :coverage-type-names ["Basic"]
                                        :harmonia-id         "H-123"
                                        :instrument-name     "Violin"
                                        :member-id           (random-uuid)
                                        :member-label        "Anna"
                                        :missing-insurer-id? false
                                        :missing-photo?      false
                                        :photo-count         3
                                        :private?            false
                                        :workflow-status     :instrument.coverage.status/needs-review
                                        :change-status       :instrument.coverage.change/changed
                                        :insured-value       1000M
                                        :cost                12.34M}]}))
        select-pos        (str/index-of html "data-workbench-select-all")
        status-pos        (str/index-of html ">Status</th>")
        member-pos        (str/index-of html ">member</th>")
        status-cell-start (str/index-of html "class=\"insurance-workbench-status-cell\"")
        status-cell-end   (some->> status-cell-start
                                   (str/index-of html "</td>"))
        status-cell-html  (when (and status-cell-start status-cell-end)
                            (subs html status-cell-start status-cell-end))]
    (is (= {:status-column-after-selection? true
            :omits-separate-status-columns? true
            :renders-workflow-icon?         true
            :renders-change-icon?           true
            :uses-tooltips?                 true
            :omits-badges-in-status-cell?   true}
           {:status-column-after-selection?
            (and select-pos status-pos member-pos (< select-pos status-pos member-pos))
            :omits-separate-status-columns?
            (not (or (str/includes? html ">workflow</th>")
                     (str/includes? html ">change</th>")))
            :renders-workflow-icon?
            (and (str/includes? (or status-cell-html "") "data-workbench-status-icon=\"workflow\"")
                 (str/includes? (or status-cell-html "") "circle-question-outline"))
            :renders-change-icon?
            (and (str/includes? (or status-cell-html "") "data-workbench-status-icon=\"change\"")
                 (str/includes? (or status-cell-html "") "circle-exclamation"))
            :uses-tooltips?
            (and (str/includes? (or status-cell-html "") "<wa-tooltip")
                 (str/includes? (or status-cell-html "") ">Todo</wa-tooltip>")
                 (str/includes? (or status-cell-html "") ">Modified</wa-tooltip>"))
            :omits-badges-in-status-cell?
            (not (str/includes? (or status-cell-html "") "<wa-badge"))}))))

(deftest workbench-coverage-type-column-renders-known-types-as-icons
  (let [coverage-id (random-uuid)
        html        (html/->str
                     (#'views/row-cell-content
                      {:tr tr}
                      :EUR
                      {:coverage-id         coverage-id
                       :coverage-type-names ["Grundschutz" "Nachzeit im Auto" "Proberaum"]}
                      :coverage-types))]
    (is (= {:renders-grundschutz-icon?    true
            :renders-auto-icon?           true
            :renders-proberaum-icon?      true
            :labels-icons-accessibly?     true
            :uses-tooltips?               true
            :omits-comma-list?            true
            :omits-wrapper-cluster?       true
            :omits-unused-custom-classes? true}
           {:renders-grundschutz-icon?
            (and (str/includes? html "data-workbench-coverage-type-icon=\"grundschutz\"")
                 (str/includes? html "phosphor-shield"))
            :renders-auto-icon?
            (and (str/includes? html "data-workbench-coverage-type-icon=\"nachzeit-im-auto\"")
                 (str/includes? html "phosphor-car-profile"))
            :renders-proberaum-icon?
            (and (str/includes? html "data-workbench-coverage-type-icon=\"proberaum\"")
                 (str/includes? html "phosphor-warehouse"))
            :labels-icons-accessibly?
            (and (str/includes? html "aria-label=\"Grundschutz\"")
                 (str/includes? html "aria-label=\"Nachzeit im Auto\"")
                 (str/includes? html "aria-label=\"Proberaum\""))
            :uses-tooltips?
            (and (str/includes? html ">Grundschutz</wa-tooltip>")
                 (str/includes? html ">Nachzeit im Auto</wa-tooltip>")
                 (str/includes? html ">Proberaum</wa-tooltip>"))
            :omits-comma-list?
            (not (str/includes? html "Grundschutz, Nachzeit im Auto, Proberaum"))
            :omits-wrapper-cluster?
            (not (str/includes? html "wa-cluster wa-gap-2xs wa-align-items-center"))
            :omits-unused-custom-classes?
            (not (str/includes? html "class=\"insurance-workbench-coverage-type"))}))))

(deftest workbench-coverage-type-column-keeps-unknown-type-labels
  (let [html (html/->str
              (#'views/row-cell-content
               {:tr tr}
               :EUR
               {:coverage-id         (random-uuid)
                :coverage-type-names ["Basic"]}
               :coverage-types))]
    (is (= {:shows-unknown-label? true
            :does-not-invent-icon? true}
           {:shows-unknown-label?
            (str/includes? html ">Basic</span>")
            :does-not-invent-icon?
            (not (str/includes? html "data-workbench-coverage-type-icon"))}))))

(deftest workbench-row-actions-render-stripe-style-sticky-button-group
  (let [coverage-id (random-uuid)
        html        (html/->str
                     (#'views/flat-table
                      {:tr tr}
                      {:filters {:group :none}
                       :policy  {:insurance.policy/currency :EUR}
                       :rows    [{:category-name       "Strings"
                                  :coverage-id         coverage-id
                                  :coverage-type-names ["Basic"]
                                  :harmonia-id         "H-123"
                                  :instrument-name     "Violin"
                                  :member-id           (random-uuid)
                                  :member-label        "Anna"
                                  :missing-insurer-id? false
                                  :missing-photo?      false
                                  :photo-count         3
                                  :private?            false
                                  :workflow-status     :instrument.coverage.status/needs-review
                                  :change-status       :instrument.coverage.change/changed
                                  :insured-value       1000M
                                  :cost                12.34M}]}))]
    (is (= {:has-sticky-action-heading? true
            :has-sticky-action-cell? true
            :has-ellipsis-trigger? true
            :uses-horizontal-button-group? true
            :renders-two-action-buttons? true
            :buttons-are-xs-filled-wa-buttons? true
            :links-existing-actions? true
            :uses-icon-sprite-actions? true
            :has-action-tooltips? true
            :keeps-ellipsis-in-hover-button-group? true
            :has-desktop-overflow-dropdown? true
            :has-small-viewport-dropdown? true
            :dropdown-items-have-icon-and-text? true}
           {:has-sticky-action-heading?
            (str/includes? html "<th class=\"insurance-workbench-row-actions-cell\"")
            :has-sticky-action-cell?
            (str/includes? html "<td class=\"insurance-workbench-row-actions-cell\"")
            :has-ellipsis-trigger?
            (and (str/includes? html "insurance-workbench-row-actions-trigger")
                 (str/includes? html "snoico-ellipsis"))
            :uses-horizontal-button-group?
            (and (str/includes? html "<wa-button-group")
                 (str/includes? html "orientation=\"horizontal\""))
            :renders-two-action-buttons?
            (= 2 (count (re-seq #"insurance-workbench-row-action-button" html)))
            :buttons-are-xs-filled-wa-buttons?
            (and (= 2 (count (re-seq #"insurance-workbench-row-action-button" html)))
                 (<= 2 (count (re-seq #"size=\"xs\"" html)))
                 (<= 2 (count (re-seq #"appearance=\"filled\"" html))))
            :links-existing-actions?
            (and (str/includes? html (str "href=\"/insurance-coverage/" coverage-id "/\""))
                 (str/includes? html (str "href=\"/insurance-coverage-edit/" coverage-id "/\"")))
            :uses-icon-sprite-actions?
            (and (str/includes? html "phosphor-eye")
                 (str/includes? html "phosphor-pencil-simple"))
            :has-action-tooltips?
            (and (str/includes? html ">View</wa-tooltip>")
                 (str/includes? html ">Edit</wa-tooltip>"))
            :keeps-ellipsis-in-hover-button-group?
            (let [group-start (str/index-of html "<wa-button-group")
                  group-end   (some->> group-start
                                       (str/index-of html "</wa-button-group>"))
                  group-html  (when (and group-start group-end)
                                (subs html group-start group-end))
                  positions   (map #(some-> group-html (str/index-of %))
                                   ["phosphor-eye"
                                    "phosphor-pencil-simple"
                                    "insurance-workbench-row-actions-trigger--group"
                                    "snoico-ellipsis"])]
              (and group-html
                   (every? some? positions)
                   (apply < positions)))
            :has-desktop-overflow-dropdown?
            (let [group-start (str/index-of html "<wa-button-group")
                  group-end   (some->> group-start
                                       (str/index-of html "</wa-button-group>"))
                  group-html  (when (and group-start group-end)
                                (subs html group-start group-end))]
              (and group-html
                   (str/includes? group-html "insurance-workbench-row-actions-dropdown--group")
                   (= 2 (count (re-seq #"<wa-dropdown-item" group-html)))
                   (str/includes? group-html "onclick=\"window.location = this.value\"")
                   (str/includes? group-html (str "value=\"/insurance-coverage/" coverage-id "/\""))
                   (str/includes? group-html (str "value=\"/insurance-coverage-edit/" coverage-id "/\""))))
            :has-small-viewport-dropdown?
            (let [dropdown-start (str/index-of html "insurance-workbench-row-actions-dropdown--mobile")
                  dropdown-end   (some->> dropdown-start
                                          (str/index-of html "</wa-dropdown>"))
                  dropdown-html  (when (and dropdown-start dropdown-end)
                                   (subs html dropdown-start dropdown-end))]
              (and dropdown-html
                   (= 2 (count (re-seq #"<wa-dropdown-item" dropdown-html)))
                   (str/includes? dropdown-html "onclick=\"window.location = this.value\"")
                   (str/includes? dropdown-html (str "value=\"/insurance-coverage/" coverage-id "/\""))
                   (str/includes? dropdown-html (str "value=\"/insurance-coverage-edit/" coverage-id "/\""))))
            :dropdown-items-have-icon-and-text?
            (and (str/includes? html "slot=\"icon\"")
                 (str/includes? html ">View</wa-dropdown-item>")
                 (str/includes? html ">Edit</wa-dropdown-item>"))}))))
(deftest workbench-table-omits-hidden-columns-from-server-render
  (let [coverage-id (random-uuid)
        html        (html/->str
                     (#'views/flat-table
                      {:tr tr}
                      {:filters {:group :none}
                       :table   {:columns {:cost false
                                           :harmonia-id false}}
                       :policy  {:insurance.policy/currency :EUR}
                       :rows    [{:category-name       "Strings"
                                  :coverage-id         coverage-id
                                  :coverage-type-names ["Basic"]
                                  :harmonia-id         "H-123"
                                  :instrument-name     "Violin"
                                  :member-id           (random-uuid)
                                  :member-label        "Anna"
                                  :missing-insurer-id? false
                                  :missing-photo?      false
                                  :photo-count         3
                                  :private?            false
                                  :workflow-status     :instrument.coverage.status/needs-review
                                  :change-status       :instrument.coverage.change/changed
                                  :insured-value       1000M
                                  :cost                12.34M}]}))]
    (is (= {:keeps-selection-column? true
            :keeps-visible-column?   true
            :omits-cost-header?      true
            :omits-cost-cell?        true
            :omits-harmonia-header?  true
            :omits-harmonia-cell?    true}
           {:keeps-selection-column?
            (str/includes? html "data-workbench-select-all=\"true\"")
            :keeps-visible-column?
            (and (str/includes? html ">Violin</a>")
                 (str/includes? html "1.000,00"))
            :omits-cost-header?
            (not (str/includes? html ">cost</th>"))
            :omits-cost-cell?
            (not (str/includes? html "12,34"))
            :omits-harmonia-header?
            (not (str/includes? html ">insurer-id</th>"))
            :omits-harmonia-cell?
            (not (str/includes? html "H-123"))}))))

(deftest workbench-table-uses-active-view-column-defaults
  (let [coverage-id (random-uuid)
        html        (html/->str
                     (#'views/flat-table
                      {:tr tr}
                      {:view    :missing-id
                       :filters {:group :none}
                       :policy  {:insurance.policy/currency :EUR}
                       :rows    [{:category-name       "Strings"
                                  :coverage-id         coverage-id
                                  :coverage-type-names ["Basic"]
                                  :harmonia-id         ""
                                  :instrument-name     "Violin"
                                  :member-id           (random-uuid)
                                  :member-label        "Anna"
                                  :missing-insurer-id? true
                                  :missing-photo?      false
                                  :photo-count         3
                                  :private?            false
                                  :workflow-status     :instrument.coverage.status/needs-review
                                  :change-status       :instrument.coverage.change/changed
                                  :insured-value       1000M
                                  :cost                12.34M}]}))]
    (is (= {:shows-preset-column? true
            :keeps-selection-column? true
            :hides-cost-column? true
            :hides-value-column? true
            :hides-coverage-types-column? true}
           {:shows-preset-column?
            (and (str/includes? html ">insurer-id</th>")
                 (str/includes? html "missing"))
            :keeps-selection-column?
            (str/includes? html "data-workbench-select-all=\"true\"")
            :hides-cost-column?
            (and (not (str/includes? html ">cost</th>"))
                 (not (str/includes? html "12,34")))
            :hides-value-column?
            (not (str/includes? html ">Versicherungswert</th>"))
            :hides-coverage-types-column?
            (and (not (str/includes? html ">coverage-types</th>"))
                 (not (str/includes? html ">Basic</td>")))}))))

(deftest workbench-table-column-overrides-are-scoped-to-active-view
  (let [coverage-id (random-uuid)
        html        (html/->str
                     (#'views/flat-table
                      {:tr tr}
                      {:view    :missing-id
                       :filters {:group :none}
                       :table   {:columns-by-view {:missing-id {:harmonia-id false}}}
                       :policy  {:insurance.policy/currency :EUR}
                       :rows    [{:category-name       "Strings"
                                  :coverage-id         coverage-id
                                  :coverage-type-names ["Basic"]
                                  :harmonia-id         "H-123"
                                  :instrument-name     "Violin"
                                  :member-id           (random-uuid)
                                  :member-label        "Anna"
                                  :missing-insurer-id? false
                                  :missing-photo?      false
                                  :photo-count         3
                                  :private?            false
                                  :workflow-status     :instrument.coverage.status/needs-review
                                  :change-status       :instrument.coverage.change/changed
                                  :insured-value       1000M
                                  :cost                12.34M}]}))]
    (is (= {:hides-overridden-preset-column? true
            :does-not-render-legacy-global-toggle-js? true}
           {:hides-overridden-preset-column?
            (and (not (str/includes? html ">insurer-id</th>"))
                 (not (str/includes? html "H-123")))
            :does-not-render-legacy-global-toggle-js?
            (not (str/includes? html "table.columns["))}))))

(deftest workbench-table-selection-heading-selects-all-rows
  (let [coverage-a (random-uuid)
        coverage-b (random-uuid)
        html       (html/->str
                    (#'views/flat-table
                     {:tr tr}
                     {:filters {:group :member}
                      :policy  {:insurance.policy/currency :EUR}
                      :rows    [{:category-name       "Strings"
                                 :coverage-id         coverage-a
                                 :coverage-type-names ["Basic"]
                                 :harmonia-id         "H-123"
                                 :instrument-name     "Violin"
                                 :missing-insurer-id? false
                                 :missing-photo?      false
                                 :photo-count         3
                                 :private?            false
                                 :workflow-status     :instrument.coverage.status/needs-review
                                 :change-status       :instrument.coverage.change/changed
                                 :insured-value       1000M
                                 :cost                12.34M}
                                {:category-name       "Strings"
                                 :coverage-id         coverage-b
                                 :coverage-type-names ["Basic"]
                                 :harmonia-id         "H-124"
                                 :instrument-name     "Cello"
                                 :missing-insurer-id? false
                                 :missing-photo?      false
                                 :photo-count         1
                                 :private?            true
                                 :workflow-status     :instrument.coverage.status/reviewed
                                 :change-status       :instrument.coverage.change/none
                                 :insured-value       2000M
                                 :cost                23.45M}]}))]
    (is (= {:header-checkbox?               true
            :no-selection-text?             true
            :selects-all-row-ids?           true
            :click-checked-deselects-all?   true
            :click-indeterminate-selects-all? true
            :syncs-header-checked?          true
            :syncs-header-indeterminate?    true
            :syncs-row-checkboxes?          true}
           {:header-checkbox?
            (str/includes? html "data-workbench-select-all=\"true\"")
            :no-selection-text?
            (not (str/includes? html ">selection</th>"))
            :selects-all-row-ids?
            (and (str/includes? html (str coverage-a))
                 (str/includes? html (str coverage-b)))
            :click-checked-deselects-all?
            (str/includes? html "? [] : [")
            :click-indeterminate-selects-all?
            (not (str/includes? html "evt.target.checked ?"))
            :syncs-header-checked?
            (str/includes? html "el.checked = [")
            :syncs-header-indeterminate?
            (and (str/includes? html "el.indeterminate = [")
                 (str/includes? html ".some(id =&gt; ($insuranceWorkbench.selectedCoverageIds || []).includes(id))")
                 (str/includes? html "&amp;&amp; !["))
            :syncs-row-checkboxes?
            (str/includes? html "data-effect=\"el.checked = ($insuranceWorkbench.selectedCoverageIds || []).includes")}))))

(deftest grouped-table-renders-member-sums-in-footer
  (let [member-id  (random-uuid)
        coverage-a (random-uuid)
        coverage-b (random-uuid)
        group      {:member-id           member-id
                    :member-label        "Anna"
                    :row-count           2
                    :total-insured-value 6000M
                    :total-cost          36M
                    :rows                [{:category-name       "Strings"
                                           :coverage-id         coverage-a
                                           :coverage-type-names ["Basic"]
                                           :harmonia-id         "H-123"
                                           :instrument-name     "Violin"
                                           :missing-insurer-id? false
                                           :missing-photo?      false
                                           :photo-count         3
                                           :private?            false
                                           :workflow-status     :instrument.coverage.status/needs-review
                                           :change-status       :instrument.coverage.change/changed
                                           :insured-value       1000M
                                           :cost                12M}
                                          {:category-name       "Strings"
                                           :coverage-id         coverage-b
                                           :coverage-type-names ["Basic"]
                                           :harmonia-id         "H-124"
                                           :instrument-name     "Cello"
                                           :missing-insurer-id? false
                                           :missing-photo?      false
                                           :photo-count         1
                                           :private?            true
                                           :workflow-status     :instrument.coverage.status/reviewed
                                           :change-status       :instrument.coverage.change/none
                                           :insured-value       5000M
                                           :cost                24M}]}
        html       (html/->str
                    (#'views/grouped-table
                     {:tr tr}
                     {:policy {:insurance.policy/currency :EUR}
                      :groups [group]}))
        footer-start     (str/index-of html "data-workbench-member-footer=")
        footer-html      (when footer-start
                           (subs html
                                 footer-start
                                 (min (count html) (+ footer-start 2000))))
        footer-includes? (fn [needle]
                           (boolean (and footer-html
                                         (str/includes? footer-html needle))))
        footer-matches?  (fn [pattern]
                           (boolean (and footer-html
                                         (re-find pattern footer-html))))]
    (is (= {:heading-keeps-item-count? true
            :heading-renders-caret-toggle? true
            :heading-links-member-name? true
            :group-uses-own-tbody?     true
            :renders-footer?           true
            :footer-collapses?         true
            :footer-uses-one-css-class-with-data-attributes? true
            :footer-omits-generated-footer-classes? true
            :footer-omits-inline-base-style? true
            :footer-puts-sums-in-value-and-cost-columns? true
            :footer-labels-are-tooltips? true
            :footer-shows-value-sum?   true
            :footer-shows-cost-sum?    true
            :rows-use-collapsible-class? true}
           {:heading-keeps-item-count?
            (str/includes? html "Count: <span class=\"wa-font-weight-bold\">2</span>")
            :heading-renders-caret-toggle?
            (and (str/includes? html "insurance-workbench-member-toggle")
                 (str/includes? html "aria-expanded=\"true\"")
                 (str/includes? html "caret-right")
                 (str/includes? html "insurance-workbench-member-toggle-icon"))
            :heading-links-member-name?
            (str/includes? html (str "<a href=\"/member/" member-id "\">Anna</a>"))
            :group-uses-own-tbody?
            (and (str/includes? html "<tbody data-workbench-member-group=")
                 (not (str/includes? html "insurance-workbench-member-group")))
            :renders-footer?
            (some? footer-start)
            :footer-collapses?
            (str/includes? html "class=\"insurance-workbench-collapsible-row\" data-workbench-member-footer=")
            :footer-uses-one-css-class-with-data-attributes?
            (and (footer-includes? "insurance-workbench-member-footer-cell")
                 (footer-includes? "data-workbench-member-footer-edge=\"start\"")
                 (footer-includes? "data-workbench-member-footer-edge=\"end\"")
                 (footer-includes? "data-workbench-member-footer-cell=\"total-label\"")
                 (footer-includes? "data-workbench-member-footer-cell=\"total-value\""))
            :footer-omits-generated-footer-classes?
            (not (re-find #"insurance-workbench-member-footer-cell--" footer-html))
            :footer-omits-inline-base-style?
            (not (footer-includes? "border-block-start: var(--wa-border-width-s) solid var(--wa-color-surface-border)"))
            :footer-puts-sums-in-value-and-cost-columns?
            (footer-matches? #"(?s)colspan=\"7\".*6\.000,00.*36,00.*colspan=\"2\"")
            :footer-labels-are-tooltips?
            (and (footer-includes? "title=\"Versicherungswert\"")
                 (footer-includes? "title=\"Cost\"")
                 (not (footer-includes? ">Versicherungswert<"))
                 (not (footer-includes? ">Cost<")))
            :footer-shows-value-sum?
            (footer-includes? "6.000,00")
            :footer-shows-cost-sum?
            (footer-includes? "36,00")
            :rows-use-collapsible-class?
            (and (str/includes? html "insurance-workbench-collapsible-row")
                 (not (str/includes? html "insurance-workbench-row-collapse")))}))))
(deftest bulk-action-bar-is-stable-and-supports-workflow-and-change-updates
  (let [html (html/->str
              (#'views/bulk-action-bar
               {::r/router router
                :tr        tr}
               {:editable? true
                :filters   {:group :member}
                :rows      [{}]}))]
    (is (= {:always-visible?                         true
            :shows-zero-selected?                    true
            :uses-semantic-action-groups?            true
            :uses-dropdowns?                         true
            :omits-wa-selects?                       true
            :has-workflow-trigger?                   true
            :has-change-trigger?                     true
            :workflow-items-have-icons?              true
            :workflow-items-have-colors?             true
            :change-items-have-icons?                true
            :change-items-have-colors?               true
            :workflow-action-posts-only-workflow?    true
            :change-action-posts-only-change?        true
            :posts-mark-and-set-actions?             true
            :has-deselect-all?                       true
            :has-expansion-actions?                  true
            :uses-intersection-sentinel-when-sticky? true
            :selection-actions-disabled?             true}
           {:always-visible?
            (not (str/includes? html "data-show="))
            :shows-zero-selected?
            (and (str/includes? html ">0</span>")
                 (str/includes? html "selected"))
            :uses-semantic-action-groups?
            (and (str/includes? html "<section")
                 (str/includes? html "<p><strong")
                 (str/includes? html "<menu><li"))
            :uses-dropdowns?
            (str/includes? html "<wa-dropdown")
            :omits-wa-selects?
            (not (str/includes? html "<wa-select"))
            :has-workflow-trigger?
            (and (str/includes? html "Mark Workflow")
                 (str/includes? html "value=\"todo\"")
                 (str/includes? html "value=\"reviewed\"")
                 (str/includes? html "value=\"active\""))
            :has-change-trigger?
            (and (str/includes? html "Set Change")
                 (str/includes? html "value=\"changed\"")
                 (str/includes? html "value=\"new\"")
                 (str/includes? html "value=\"removed\"")
                 (str/includes? html "value=\"none\""))
            :workflow-items-have-icons?
            (and (str/includes? html "circle-question-outline")
                 (str/includes? html "circle-dot-outline")
                 (str/includes? html "circle-check-outline"))
            :workflow-items-have-colors?
            (and (str/includes? html "var(--sno-dashboard-insurance-todo-needs-review-color")
                 (str/includes? html "var(--sno-gig-row-gray-400)")
                 (str/includes? html "var(--wa-color-success-fill-loud)"))
            :change-items-have-icons?
            (and (str/includes? html "circle-exclamation")
                 (str/includes? html "circle-plus-solid")
                 (str/includes? html "circle-xmark-outline")
                 (str/includes? html "minus"))
            :change-items-have-colors?
            (and (str/includes? html "var(--wa-color-warning-fill-loud)")
                 (str/includes? html "var(--wa-color-success-fill-loud)")
                 (str/includes? html "var(--wa-color-danger-fill-loud)")
                 (str/includes? html "var(--wa-color-neutral-fill-loud)"))
            :workflow-action-posts-only-workflow?
            (and (str/includes? html "$insuranceWorkbench.targetWorkflowStatus = evt.detail.item.value")
                 (str/includes? html "$insuranceWorkbench.targetChangeStatus = &apos;keep&apos;"))
            :change-action-posts-only-change?
            (and (str/includes? html "$insuranceWorkbench.targetChangeStatus = evt.detail.item.value")
                 (str/includes? html "$insuranceWorkbench.targetWorkflowStatus = &apos;keep&apos;"))
            :posts-mark-and-set-actions?
            (and (str/includes? html "kw=bulk-mark-workflow")
                 (str/includes? html "kw=bulk-set-change"))
            :has-deselect-all?
            (and (str/includes? html "Deselect All")
                 (str/includes? html "$insuranceWorkbench.selectedCoverageIds = []"))
            :has-expansion-actions?
            (and (str/includes? html "Expand all")
                 (str/includes? html "Collapse all")
                 (str/includes? html "setAttribute(&apos;aria-expanded&apos;, &apos;true&apos;)")
                 (str/includes? html "setAttribute(&apos;aria-expanded&apos;, &apos;false&apos;)")
                 (not (str/includes? html "row.hidden")))
            :uses-intersection-sentinel-when-sticky?
            (and (str/includes? html "data-class:insurance-workbench-bulk-action-bar--sticky")
                 (str/includes? html "insurance-workbench-bulk-action-sentinel")
                 (str/includes? html "data-on-intersect=")
                 (str/includes? html "data-on-intersect__exit=")
                 (str/includes? html "$insuranceWorkbench.bulkActionStuck")
                 (str/includes? html "insurance-workbench-bulk-action-bar--stuck")
                 (not (str/includes? html "data-on:scroll__window")))
            :selection-actions-disabled?
            (str/includes? html "($insuranceWorkbench.selectedCoverageIds || []).length === 0")}))))

(deftest bulk-action-bar-omits-expansion-actions-outside-grouped-table
  (let [html (html/->str
              (#'views/bulk-action-bar
               {::r/router router
                :tr        tr}
               {:editable? true
                :filters   {:group :none}}))]
    (is (= {:omits-expand-all?   true
            :omits-collapse-all? true
            :omits-expansion-js? true}
           {:omits-expand-all?
            (not (str/includes? html "Expand all"))
            :omits-collapse-all?
            (not (str/includes? html "Collapse all"))
            :omits-expansion-js?
            (not (str/includes? html "aria-expanded"))}))))

(deftest workbench-table-right-aligns-numeric-and-harmonia-columns
  (let [coverage-id (random-uuid)
        html        (html/->str
                     (#'views/flat-table
                      {:tr tr}
                      {:filters {:group :none}
                       :policy  {:insurance.policy/currency :EUR}
                       :rows    [{:category-name       "Strings"
                                  :coverage-id         coverage-id
                                  :coverage-type-names ["Basic"]
                                  :harmonia-id         "H-123"
                                  :instrument-name     "Violin"
                                  :member-id           (random-uuid)
                                  :member-label        "Anna"
                                  :missing-insurer-id? false
                                  :missing-photo?      false
                                  :photo-count         3
                                  :private?            false
                                  :workflow-status     :instrument.coverage.status/needs-review
                                  :change-status       :instrument.coverage.change/changed
                                  :insured-value       1000M
                                  :cost                12.34M}]}))]
    (is (= {:right-aligned-headers?       true
            :right-aligned-cells?         true
            :photo-cell-aligned?          true
            :harmonia-cell-aligned?       true
            :harmonia-header-nowrap-class? true}
           {:right-aligned-headers?
            (every? #(re-find (re-pattern (str "<th[^>]*style=\"[^\"]*text-align: end;[^\"]*\"[^>]*>"
                                               %
                                               "</th>"))
                              html)
                    ["photos" "insurer-id" "Versicherungswert" "cost"])
            :right-aligned-cells?
            (= 4 (count (re-seq #"<td style=\"text-align: end;\"" html)))
            :photo-cell-aligned?
            (str/includes? html "<td style=\"text-align: end;\">3</td>")
            :harmonia-cell-aligned?
            (str/includes? html "<td style=\"text-align: end;\">H-123</td>")
            :harmonia-header-nowrap-class?
            (boolean (re-find #"<th[^>]*class=\"[^\"]*insurance-workbench-table-heading--nowrap[^\"]*\"[^>]*>insurer-id</th>"
                              html))}))))

(deftest rows-section-renders-pagination-controls-with-page-size-dropdown
  (let [policy-id   (random-uuid)
        coverage-id (random-uuid)
        html        (html/->str
                     (#'views/rows-section
                      {:tr tr}
                      {:policy     {:insurance.policy/policy-id policy-id
                                    :insurance.policy/currency :EUR}
                       :view       :all
                       :filters    {:group :none
                                    :ownership :all}
                       :pagination {:page 1
                                    :page-size 20
                                    :page-sizes [20 50 100]
                                    :total-results 85
                                    :total-pages 5
                                    :range-start 1
                                    :range-end 20
                                    :has-prev? false
                                    :has-next? true
                                    :prev-page nil
                                    :next-page 2}
                       :rows       [{:category-name       "Strings"
                                     :coverage-id         coverage-id
                                     :coverage-type-names ["Basic"]
                                     :harmonia-id         "H-123"
                                     :instrument-name     "Violin"
                                     :member-id           (random-uuid)
                                     :member-label        "Anna"
                                     :missing-insurer-id? false
                                     :missing-photo?      false
                                     :photo-count         3
                                     :private?            false
                                     :workflow-status     :instrument.coverage.status/needs-review
                                     :change-status       :instrument.coverage.change/changed
                                     :insured-value       1000M
                                     :cost                12.34M}]}))]
    (is (= {:renders-summary-trigger?           true
            :uses-dropdown?                     true
            :labels-page-size-menu?             true
            :renders-page-size-items?           true
            :marks-current-size-with-icon?      true
            :reserves-icon-slot-for-each-size?  true
            :hides-unselected-icons-inline?     true
            :omits-checkbox-items?              true
            :uses-custom-check-icon?            true
            :omits-inline-title-style?          true
            :uses-justify-utility?              true
            :navigates-to-page-size?            true
            :previous-disabled?                 true
            :next-link?                         true
            :sticky-pagination-shell?           true
            :pagination-uses-inner-wrapper?     true
            :omits-pagination-js?               true
            :omits-static-pagination-shadow?    true
            :omits-pagination-padding?          true
            :omits-inline-pagination-position?  true}
           {:renders-summary-trigger?
            (str/includes? html "1–20 of 85 results")
            :uses-dropdown?
            (str/includes? html "<wa-dropdown")
            :labels-page-size-menu?
            (str/includes? html "Rows per page")
            :renders-page-size-items?
            (and (str/includes? html "value=\"20\"")
                 (str/includes? html "value=\"50\"")
                 (str/includes? html "value=\"100\""))
            :marks-current-size-with-icon?
            (and (str/includes? html "slot=\"icon\"")
                 (str/includes? html "phosphor-check"))
            :reserves-icon-slot-for-each-size?
            (let [dropdown-start (str/index-of html "data-workbench-page-size")
                  dropdown-end   (some->> dropdown-start
                                          (str/index-of html "</wa-dropdown>"))
                  dropdown-html  (when (and dropdown-start dropdown-end)
                                   (subs html dropdown-start dropdown-end))]
              (= 3 (count (re-seq #"slot=\"icon\"" (or dropdown-html "")))))
            :hides-unselected-icons-inline?
            (str/includes? html "visibility: hidden;")
            :omits-checkbox-items?
            (not (str/includes? html "type=\"checkbox\""))
            :uses-custom-check-icon?
            (str/includes? html "slot=\"icon\"")
            :omits-inline-title-style?
            (not (str/includes? html "padding-inline: var(--wa-space-xs)"))
            :uses-justify-utility?
            (str/includes? html "wa-justify-content-end")
            :navigates-to-page-size?
            (and (str/includes? html "page-size=50")
                 (str/includes? html "page=1"))
            :previous-disabled?
            (str/includes? html "aria-label=\"Previous\" disabled")
            :next-link?
            (and (str/includes? html "aria-label=\"Next\"")
                 (str/includes? html "page=2"))
            :sticky-pagination-shell?
            (let [pagination-start (str/index-of html "data-workbench-pagination=\"true\"")
                  pagination-html  (when pagination-start
                                     (subs html
                                           pagination-start
                                           (min (count html) (+ pagination-start 2000))))]
              (str/includes? (or pagination-html "") "insurance-workbench-pagination"))
            :pagination-uses-inner-wrapper?
            (let [pagination-start (str/index-of html "data-workbench-pagination=\"true\"")
                  pagination-html  (when pagination-start
                                     (subs html
                                           pagination-start
                                           (min (count html) (+ pagination-start 2000))))]
              (str/includes? (or pagination-html "") "insurance-workbench-pagination__inner"))
            :omits-pagination-js?
            (let [pagination-start (str/index-of html "data-workbench-pagination=\"true\"")
                  pagination-html  (when pagination-start
                                     (subs html
                                           pagination-start
                                           (min (count html) (+ pagination-start 2000))))]
              (and (not (str/includes? (or pagination-html "") "data-effect="))
                   (not (str/includes? (or pagination-html "") "data-on:scroll__window"))
                   (not (str/includes? (or pagination-html "") "insurance-workbench-pagination--stuck"))))
            :omits-static-pagination-shadow?
            (let [pagination-start (str/index-of html "data-workbench-pagination=\"true\"")
                  pagination-html  (when pagination-start
                                     (subs html
                                           pagination-start
                                           (min (count html) (+ pagination-start 2000))))]
              (not (str/includes? (or pagination-html "") "box-shadow")))
            :omits-pagination-padding?
            (let [pagination-start (str/index-of html "data-workbench-pagination=\"true\"")
                  pagination-html  (when pagination-start
                                     (subs html
                                           pagination-start
                                           (min (count html) (+ pagination-start 2000))))]
              (not (str/includes? (or pagination-html "") "padding-block")))
            :omits-inline-pagination-position?
            (let [pagination-start (str/index-of html "data-workbench-pagination=\"true\"")
                  pagination-html  (when pagination-start
                                     (subs html
                                           pagination-start
                                           (min (count html) (+ pagination-start 2000))))]
              (not (str/includes? (or pagination-html "") "position: sticky")))}))))

(deftest sticky-workbench-bars-use-slide-transitions
  (let [css (slurp "resources/public/css/pages/insurance.css")]
    (is (= {:pagination-scroll-state-container? true
            :pagination-scroll-state-query?     true
            :pagination-inner-transitions?      true
            :pagination-slides-when-stuck?      true
            :bulk-bar-transitions?              true
            :bulk-bar-slides-when-stuck?        true
            :reduced-motion-disables?           true}
           {:pagination-scroll-state-container?
            (boolean (re-find #"(?s)\.insurance-workbench-pagination \{.*container-type: scroll-state;" css))
            :pagination-scroll-state-query?
            (boolean (re-find #"(?s)@container scroll-state\(stuck: bottom\)" css))
            :pagination-inner-transitions?
            (boolean (re-find #"(?s)\.insurance-workbench-pagination__inner \{.*transition:" css))
            :pagination-slides-when-stuck?
            (boolean (re-find #"(?s)@container scroll-state\(stuck: bottom\).*\.insurance-workbench-pagination__inner \{.*transform: translateY\(0\);" css))
            :bulk-bar-transitions?
            (boolean (re-find #"(?s)\.insurance-workbench-bulk-action-bar \{.*transition:" css))
            :bulk-bar-slides-when-stuck?
            (boolean (re-find #"(?s)\.insurance-workbench-bulk-action-bar--stuck \{.*transform: translateY\(0\);" css))
            :reduced-motion-disables?
            (boolean (re-find #"(?s)@media \(prefers-reduced-motion: reduce\).*\.insurance-workbench-pagination__inner,.*\.insurance-workbench-bulk-action-bar" css))}))))
