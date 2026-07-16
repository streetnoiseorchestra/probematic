(ns app.insurance.policy.workbench.views-test
  (:require
   [app.i18n :as i18n]
   [app.insurance.policy.workbench.views :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def translations
  {[:action/apply] "Apply"
   [:action/back] "Back"
   [:action/edit] "Edit"
   [:action/filter] "Filter"
   [:action/next] "Next"
   [:action/previous] "Previous"
   [:action/remove] "Remove"
   [:action/select-all] "Select all"
   [:action/view] "View"
   [:actions] "Actions"
   [:col/member] "Member"
   [:instrument/category] "Category"
   [:instrument/instrument] "Instrument"
   [:instrument.coverage/cost] "Cost"
   [:instrument.coverage.status/needs-review] "Todo"
   [:instrument.coverage.status/reviewed] "Reviewed"
   [:instrument.coverage.status/coverage-active] "Active"
   [:instrument.coverage.change/changed] "Modified"
   [:instrument.coverage.change/new] "Added"
   [:instrument.coverage.change/removed] "Removed"
   [:instrument.coverage.change/none] "No changes"
   [:insurance/cost] "Cost"
   [:insurance/coverage-types] "Coverage types"
   [:insurance/item-count] "Count"
   [:insurance/insurer-id] "Harmonia ID"
   [:insurance/ownership] "Ownership"
   [:insurance/total] "Total"
   [:insurance/value] "Versicherungswert"
   [:insurance/value-abbrev] "Value"
   [:insurance.workbench/and] "and"
   [:insurance.workbench/change-status] "Change"
   [:insurance.workbench/collapse-all] "Collapse all"
   [:insurance.workbench/columns] "Columns"
   [:insurance.workbench/deselect-all] "Deselect All"
   [:insurance.workbench/expand-all] "Expand all"
   [:insurance.workbench/filter-by] "Filter by: %1"
   [:insurance.workbench/group-member] "Group by member"
   [:insurance.workbench/mark-workflow] "Mark Workflow"
   [:insurance.workbench/member-search-placeholder] "Search members"
   [:insurance.workbench/missing] "Missing"
   [:insurance.workbench/missing-photos] "Missing photos"
   [:insurance.workbench/ownership] "Ownership"
   [:insurance.workbench/ownership-all] "All"
   [:insurance.workbench/ownership-band] "Band"
   [:insurance.workbench/ownership-private] "Private"
   [:insurance.workbench/pagination] "Pagination"
   [:insurance.workbench/pagination-summary] "%1–%2 of %3 results"
   [:insurance.workbench/photos] "Photos"
   [:insurance.workbench/rows-per-page] "Rows per page"
   [:insurance.workbench/search] "Search"
   [:insurance.workbench/select-row] "Select row"
   [:insurance.workbench/selected] "selected"
   [:insurance.workbench/set-change] "Set Change"
   [:insurance.workbench/status] "Status"
   [:insurance.workbench/table-settings] "Table settings"
   [:insurance.workbench/value-between] "is between"
   [:insurance.workbench/value-equal-to] "is equal to"
   [:insurance.workbench/value-greater-than] "is greater than"
   [:insurance.workbench/value-less-than] "is less than"
   [:insurance.workbench/value-max] "Maximum"
   [:insurance.workbench/value-min] "Minimum"
   [:insurance.workbench/value-operator] "Value operator"
   [:insurance.workbench/view] "View"
   [:insurance.workbench/workflow-status] "Workflow"
   [:private-instrument] "Private Instrument"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(def policy-id
  #uuid "00000000-0000-0000-0000-000000002001")

(def category-id
  #uuid "00000000-0000-0000-0000-000000002002")

(def coverage-type-id
  #uuid "00000000-0000-0000-0000-000000002003")

(def coverage-id
  #uuid "00000000-0000-0000-0000-000000002004")

(def member-id
  #uuid "00000000-0000-0000-0000-000000002005")

(def second-coverage-id
  #uuid "00000000-0000-0000-0000-000000002006")

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/currency  :EUR
   :insurance.policy/coverage-types
   [{:insurance.coverage.type/type-id coverage-type-id
     :insurance.coverage.type/name    "Basic"}]})

(def request
  {::r/router router
   :tr        tr})

(defn resolve-view
  [view]
  (i18n/resolve-translations tr view))

(def row
  {:category-name       "Strings"
   :coverage-id         coverage-id
   :coverage-type-names ["Worldwide touring"
                         "Locked rehearsal storage"
                         "Instrument protection"
                         "Legacy without icon"
                         "Legacy invalid icon"]
   :coverage-types      [{:insurance.coverage.type/name "Worldwide touring"
                          :insurance.coverage.type/icon :phosphor/car-profile
                          :insurance.coverage.type/cost 1M}
                         {:insurance.coverage.type/name "Locked rehearsal storage"
                          :insurance.coverage.type/icon :phosphor/warehouse
                          :insurance.coverage.type/cost 2M}
                         {:insurance.coverage.type/name "Instrument protection"
                          :insurance.coverage.type/icon :phosphor/shield
                          :insurance.coverage.type/cost 3M}
                         {:insurance.coverage.type/name "Legacy without icon"
                          :insurance.coverage.type/cost nil}
                         {:insurance.coverage.type/name "Legacy invalid icon"
                          :insurance.coverage.type/icon :phosphor/not-registered
                          :insurance.coverage.type/cost 4M}]
   :harmonia-id         "H-123"
   :instrument-name     "Violin"
   :member-id           member-id
   :member-label        "Anna"
   :missing-insurer-id? false
   :missing-photo?      false
   :photo-count         3
   :private?            false
   :workflow-status     :instrument.coverage.status/needs-review
   :change-status       :instrument.coverage.change/changed
   :insured-value       1000M
   :cost                12.34M})

(defn select-attrs
  [selector hiccup]
  (some-> (l/select-one selector hiccup)
          l/attrs))

(defn action-keyword
  [value]
  (when-let [[_ action] (and (string? value)
                             (re-find #"[?&]kw=([^&')]+)" value))]
    (keyword action)))

(defn action-keywords
  [hiccup]
  (->> (l/select '* hiccup)
       (mapcat #(vals (or (l/attrs %) {})))
       (keep action-keyword)
       set))

(defn input-values-by-binding
  [hiccup]
  (reduce (fn [values input]
            (let [{:keys [data-bind value]} (l/attrs input)]
              (if data-bind
                (update values data-bind (fnil conj []) value)
                values)))
          {}
          (l/select 'input hiccup)))

(defn hidden-fields
  [form]
  (reduce (fn [fields input]
            (let [{:keys [name value]} (l/attrs input)]
              (update fields name (fnil conj []) value)))
          {}
          (l/select "input[type=hidden]" form)))

(defn embedded-urls
  [script]
  (re-seq #"/insurance-policy/[^\" ]+" (or script "")))

(defn query-map
  [url]
  (let [[_ query] (str/split url #"\?" 2)]
    (into {}
          (map #(str/split % #"=" 2))
          (str/split (or query "") #"&"))))

(def uuid-pattern
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn referenced-ids
  [script]
  (set (re-seq uuid-pattern (or script ""))))

(defn flat-table
  ([rows]
   (flat-table :all nil rows))
  ([view table rows]
   (resolve-view
    (sut/flat-table
     {:tr tr}
     {:view    view
      :filters {:group :none}
      :table   table
      :policy  policy
      :rows    rows}))))

(defn headings
  [table]
  (mapv l/text (l/select '[thead th] table)))

(deftest active-parameters
  (testing "The URL and Datastar page state contain different active filters."
    (let [params (sut/workbench-params
                  {:parameters {:query {:view        "todo"
                                        :group       "member"
                                        :member-q    "Anna"
                                        :category-id (str (random-uuid))
                                        :ownership   "band"}}
                   :page-state {:insurance-workbench
                                {:filters {:member-q       "Zoe"
                                           :category-ids   [category-id]
                                           :ownership      :private
                                           :value-filter   {:operator :between
                                                            :min      1000M
                                                            :max      3000M}}}}})]
      (testing "The active page state takes precedence without replacing view or grouping."
        (is (= {:view           "todo"
                :group          "member"
                :member-q       "Zoe"
                :category-id    [category-id]
                :ownership      :private
                :value-operator :between
                :value-min      1000M
                :value-max      3000M}
               (select-keys params
                            [:view :group :member-q :category-id :ownership
                             :value-operator :value-min :value-max])))))
    (testing "Explicitly cleared page-state filters override stale URL values."
      (is (= {:member-q    nil
              :category-id []
              :ownership   :all}
             (select-keys
              (sut/workbench-params
               {:parameters {:query {:member-q    "Anna"
                                     :category-id (str category-id)
                                     :ownership   "private"}}
                :page-state {:insurance-workbench
                             {:filters {:member-q     nil
                                        :category-ids []
                                        :ownership    :all}}}})
              [:member-q :category-id :ownership]))))))

(deftest filter-controls
  (testing "The policy has one category and one coverage type available for filtering."
    (let [view [:div
                (sut/ownership-select tr :private)
                (sut/category-select
                 tr
                 [{:category-id category-id :category-name "Akkordeon"}]
                 #{category-id})
                (sut/coverage-type-select tr policy #{coverage-type-id})
                (sut/missing-photos-switch tr true)
                (sut/missing-harmonia-id-switch tr false)
                (sut/workflow-status-select tr #{:needs-review :reviewed})
                (sut/change-status-select tr #{:changed :new})]
          bindings (input-values-by-binding view)]
      (testing "Multi-value filters bind native checkboxes to signal arrays."
        (is (= {"insuranceWorkbench.filterDraft.categoryIds"
                [(str category-id)]
                "insuranceWorkbench.filterDraft.coverageTypeIds"
                [(str coverage-type-id)]
                "insuranceWorkbench.filterDraft.workflowStatuses"
                ["needs-review" "reviewed" "coverage-active"]
                "insuranceWorkbench.filterDraft.changeStatuses"
                ["changed" "new" "removed" "none"]}
               bindings)))
      (testing "Ownership is an accessible single-value selection."
        (is (= {:value     "private"
                :aria-label "Ownership"
                :data-bind "insuranceWorkbench.filterDraft.ownership"}
               (select-keys (select-attrs 'wa-select view)
                            [:value :aria-label :data-bind]))))
      (testing "Missing-photo and missing-ID filters bind switches to boolean signals."
        (is (= [{:label   "Missing photos"
                 :binding "insuranceWorkbench.filterDraft.missingPhotos"
                 :checked true}
                {:label   "Missing"
                 :binding "insuranceWorkbench.filterDraft.missingHarmoniaId"
                 :checked nil}]
               (mapv (fn [switch]
                       (let [attrs (l/attrs switch)]
                         {:label   (l/text switch)
                          :binding (:data-bind__prop.checked__event.change attrs)
                          :checked (:checked attrs)}))
                     (l/select 'wa-switch view)))))
      (testing "Workflow and change options retain their translated labels."
        (is (= ["Todo" "Reviewed" "Active" "Modified" "Added" "Removed" "No changes"]
               (mapv l/text (l/select 'wa-badge view))))))))

(deftest filter-editor
  (testing "The value filter editor is open."
    (let [view   (sut/filter-editor-shell
                  request
                  :value
                  (sut/value-filter-control tr))
          inputs (l/select 'input view)
          buttons (l/select :app.ui2.button/button view)]
      (testing "The editor identifies the field being filtered."
        (is (= ["Filter by: Versicherungswert"]
               (mapv l/text (l/select 'strong view)))))
      (testing "The operator and numeric inputs are accessible and signal-bound."
        (is (= {:operator {:aria-label "Value operator"
                           :data-bind  "insuranceWorkbench.filterDraft.valueOperator"}
                :inputs   [{:aria-label "Versicherungswert"
                            :data-bind  "insuranceWorkbench.filterDraft.value"}
                           {:aria-label "Minimum"
                            :data-bind  "insuranceWorkbench.filterDraft.valueMin"}
                           {:aria-label "Maximum"
                            :data-bind  "insuranceWorkbench.filterDraft.valueMax"}]}
               {:operator (select-keys (select-attrs 'wa-select view)
                                       [:aria-label :data-bind])
                :inputs   (mapv #(select-keys (l/attrs %) [:aria-label :data-bind])
                                inputs)})))
      (testing "Back exits the editor and Apply submits the draft filter."
        (is (= {:back-label "Back"
                :apply-text "Apply"
                :actions    #{:apply-filter}}
               {:back-label (:aria-label (l/attrs (first buttons)))
                :apply-text (l/text (second buttons))
                :actions    (action-keywords view)}))))))

(deftest selection-state
  (testing "The workbench has active filters and server-controlled column visibility."
    (let [signals (sut/selection-signals
                   policy
                   {:category-ids        [category-id]
                    :coverage-type-ids   [coverage-type-id]
                    :ownership           :private
                    :missing-photos?     true
                    :missing-harmonia-id? true
                    :workflow-statuses   [:needs-review :reviewed]
                    :change-statuses     [:changed :new]
                    :group               :member}
                   {:columns {:cost false
                              :harmonia-id false}}
                   :all)]
      (testing "Filter arrays and booleans are initialized from server state."
        (is (= {:categoryIds       [(str category-id)]
                :coverageTypeIds   [(str coverage-type-id)]
                :ownership         "private"
                :missingPhotos     true
                :missingHarmoniaId true
                :workflowStatuses  ["needs-review" "reviewed"]
                :changeStatuses    ["changed" "new"]}
               (select-keys (get-in signals [:insuranceWorkbench :filterDraft])
                            [:categoryIds :coverageTypeIds :ownership
                             :missingPhotos :missingHarmoniaId
                             :workflowStatuses :changeStatuses]))))
      (testing "Column signals use the server-rendered table state."
        (is (= {"actions" true
                "category" true
                "cost" false
                "coverage-types" true
                "harmonia-id" false
                "instrument" true
                "member" true
                "ownership" true
                "photos" false
                "status" true
                "value" true}
               (get-in signals [:insuranceWorkbench :table :columns])))))))

(deftest toolbar
  (testing "The Todo workbench has an active member search and category filter."
    (let [view (sut/workbench-toolbar
                request
                {:policy               policy
                 :view                 :todo
                 :pagination           {:page-size 20}
                 :available-categories [{:category-id category-id
                                         :category-name "Akkordeon"}]
                 :filters              {:group        :member
                                        :ownership    :all
                                        :member-q     "Anna"
                                        :category-ids #{category-id}}})
          forms (l/select 'form view)
          view-select (l/select-one "wa-select[name=view]" view)
          search-input (l/select-one "wa-input[name=member-q]" view)]
      (testing "Desktop navigation and the mobile view selector expose the active view."
        (is (= {:nav-label "View"
                :select    {:value "todo" :aria-label "View"}}
               {:nav-label (:aria-label (select-attrs 'nav view))
                :select    (select-keys (l/attrs view-select)
                                        [:value :aria-label])})))
      (testing "The mobile selector preserves the active toolbar state."
        (is (= {"group"       ["member"]
                "page"        ["1"]
                "page-size"   ["20"]
                "member-q"    ["Anna"]
                "category-id" [(str category-id)]
                "ownership"   ["all"]}
               (hidden-fields (first forms)))))
      (testing "Member search is prefilled, signal-bound, and posts after typing pauses."
        (is (= {:value      "Anna"
                :with-clear true
                :data-bind  "insuranceWorkbench.memberQ"
                :actions    #{:set-member-search-phrase}}
               (merge (select-keys (l/attrs search-input)
                                   [:value :with-clear :data-bind])
                      {:actions (action-keywords (second forms))})))))))

(deftest active-filters
  (testing "Private ownership and the Akkordeon category are active filters."
    (let [view (sut/workbench-toolbar
                request
                {:policy               policy
                 :view                 :todo
                 :pagination           {:page-size 20}
                 :available-categories [{:category-id category-id
                                         :category-name "Akkordeon"}]
                 :filters              {:group        :member
                                        :ownership    :private
                                        :category-ids #{category-id}}})
          tags (l/select 'wa-tag view)]
      (testing "Each active filter is presented as a removable, labelled chip."
        (is (= [{:text "Ownership Private" :with-remove true}
                {:text "Category Akkordeon" :with-remove true}]
               (mapv (fn [tag]
                       {:text        (l/text tag)
                        :with-remove (:with-remove (l/attrs tag))})
                     tags))))
      (testing "Selecting a chip opens its editor and removing it applies the change."
        (is (= {:fields  ["ownership" "category"]
                :actions #{:apply-filter}}
               {:fields  (mapv (fn [tag]
                                 (some->> (l/select-one 'dl tag)
                                          l/attrs
                                          :data-on:click
                                          (re-find #"filterEditor\.field = '([^']+)'")
                                          second))
                               tags)
                :actions (action-keywords tags)}))))))

(deftest table-settings
  (testing "The Todo table is grouped by member and has Cost disabled."
    (let [view (sut/table-settings-popover
                request
                {:policy  policy
                 :view    :todo
                 :filters {:group        :member
                           :ownership    :private
                           :member-q     "Anna"
                           :category-ids #{category-id}}
                 :table   {:columns {:cost false}}})
          group-switch (l/select-one 'wa-switch view)
          cost-toggle (l/select-one
                       "wa-checkbox[data-workbench-column-toggle=cost]"
                       view)]
      (testing "Changing grouping preserves the active toolbar filters."
        (let [urls (embedded-urls (:data-on:change (l/attrs group-switch)))]
          (is (= [{"member-q"    "Anna"
                   "category-id" (str category-id)
                   "ownership"   "private"
                   "group"       "member"}
                  {"member-q"    "Anna"
                   "category-id" (str category-id)
                   "ownership"   "private"
                   "group"       "none"}]
                 (mapv #(select-keys (query-map %)
                                     ["member-q" "category-id" "ownership" "group"])
                       urls)))))
      (testing "Column toggles reflect server state and submit the changed column."
        (is (= {:column  "cost"
                :checked nil
                :actions #{:toggle-table-column}}
               {:column  (:data-workbench-column-toggle (l/attrs cost-toggle))
                :checked (:checked (l/attrs cost-toggle))
                :actions (action-keywords cost-toggle)}))))))

(deftest row-content
  (testing "A workbench row has ownership, workflow, change, and coverage-type data."
    (let [status-view   (sut/row-cell-content {:tr tr} :EUR row :status)
          coverage-view (sut/row-cell-content {:tr tr} :EUR row :coverage-types)]
      (testing "Ownership uses short labels."
        (is (= ["Band" "Private"]
               [(-> (sut/row-cell-content {:tr tr} :EUR row :ownership)
                    resolve-view
                    l/text)
                (-> (sut/row-cell-content
                     {:tr tr} :EUR (assoc row :private? true) :ownership)
                    resolve-view
                    l/text)])))
      (testing "Workflow and change are accessible icons with matching tooltips."
        (is (= {:icons [{:kind "workflow" :label "Todo"}
                        {:kind "change" :label "Modified"}]
                :tooltips ["Todo" "Modified"]}
               {:icons (mapv (fn [icon]
                               (let [attrs (l/attrs icon)]
                                 {:kind  (:data-workbench-status-icon attrs)
                                  :label (:aria-label attrs)}))
                             (l/select "[data-workbench-status-icon]" status-view))
                :tooltips (mapv l/text (l/select 'wa-tooltip status-view))})))
      (testing "Coverage types keep their labels and expose each known or unavailable cost."
        (is (= {:icons    [{:kind "phosphor/car-profile"
                            :label "Worldwide touring"
                            :tabindex 0}
                           {:kind "phosphor/warehouse"
                            :label "Locked rehearsal storage"
                            :tabindex 0}
                           {:kind "phosphor/shield"
                            :label "Instrument protection"
                            :tabindex 0}]
                :tooltips [{:label "Worldwide touring"
                            :trigger "click hover focus"}
                           {:label "Locked rehearsal storage"
                            :trigger "click hover focus"}
                           {:label "Instrument protection"
                            :trigger "click hover focus"}]
                :unknown  ["Legacy without icon" "Legacy invalid icon"]
                :costs    ["1,00 €" "2,00 €" "3,00 €" "&mdash;" "4,00 €"]}
               {:icons    (mapv (fn [icon]
                                  (let [attrs (l/attrs icon)]
                                    {:kind  (:data-insurance-coverage-type-icon attrs)
                                     :label (:aria-label attrs)
                                     :tabindex (:tabindex attrs)}))
                                (l/select "[data-insurance-coverage-type-icon]"
                                          coverage-view))
                :tooltips (mapv (fn [tooltip]
                                  {:label   (l/text tooltip)
                                   :trigger (:trigger (l/attrs tooltip))})
                                (l/select 'wa-tooltip coverage-view))
                :unknown  (mapv l/text
                                (l/select "[data-insurance-coverage-type-label]"
                                          coverage-view))
                :costs    (mapv l/text
                                (l/select "[data-workbench-coverage-type-cost]"
                                          coverage-view))}))))))

(deftest table-columns
  (testing "The server chooses visible columns from the active view and overrides."
    (testing "The All view uses its default columns."
      (is (= ["" "Status" "Member" "Instrument" "Category" "Ownership"
              "Value" "Cost" "Coverage types" "Actions"]
             (headings (flat-table [row])))))
    (testing "Legacy server overrides can hide an otherwise visible column."
      (is (= ["" "Status" "Member" "Instrument" "Category" "Ownership"
              "Value" "Coverage types" "Actions"]
             (headings (flat-table
                        :all
                        {:columns {:cost false
                                   :harmonia-id false}}
                        [row])))))
    (testing "The Missing ID view uses its own preset."
      (is (= ["" "Status" "Member" "Instrument" "Category" "Harmonia ID" "Actions"]
             (headings (flat-table :missing-id nil [row])))))
    (testing "View-scoped overrides apply only to the active preset."
      (is (= ["" "Status" "Member" "Instrument" "Category" "Actions"]
             (headings (flat-table
                        :missing-id
                        {:columns-by-view {:missing-id {:harmonia-id false}}}
                        [row])))))))

(deftest flat-table-total
  (testing "The ungrouped table includes a known-cost aggregate."
    (let [table (sut/flat-table
                 {:tr tr}
                 {:view    :all
                  :filters {:group :none}
                  :table   nil
                  :policy  policy
                  :rows    [row]
                  :totals  {:total-insured-value 1000M
                            :total-cost          12.34M}})]
      (is (= {:label  "Total"
              :values ["1.000,00 €" "12,34 €"]}
             {:label  (-> (l/select-one '[tfoot th] table) l/text)
              :values (->> (l/select '[tfoot td] table)
                           (map l/text)
                           (remove str/blank?)
                           vec)})))))

(deftest row-selection
  (testing "The table contains two selectable coverage rows."
    (let [table (flat-table
                 [row (assoc row
                             :coverage-id second-coverage-id
                             :instrument-name "Cello")])
          header (l/select-one
                  "wa-checkbox[data-workbench-select-all=true]"
                  table)
          row-checkboxes (rest (l/select 'wa-checkbox table))]
      (testing "The header checkbox selects or clears both row IDs."
        (is (= #{(str coverage-id) (str second-coverage-id)}
               (referenced-ids (:data-on:change (l/attrs header))))))
      (testing "The header tracks checked and indeterminate state for both IDs."
        (is (= #{(str coverage-id) (str second-coverage-id)}
               (referenced-ids (:data-effect (l/attrs header))))))
      (testing "Each row checkbox tracks its own coverage ID."
        (is (= [#{(str coverage-id)} #{(str second-coverage-id)}]
               (mapv #(referenced-ids (:data-effect (l/attrs %)))
                     row-checkboxes)))))))

(deftest row-actions
  (testing "A table row represents an existing coverage."
    (let [actions-view (sut/row-cell-content {:tr tr} :EUR row :actions)
          table        (flat-table [row])
          action-buttons (->> (l/select :app.ui2.button/button actions-view)
                              (filter #(contains? (:class (l/attrs %))
                                                  "insurance-workbench-row-action-button")))]
      (testing "The sticky action column is present in the heading and row."
        (is (= {:headings 1 :cells 1}
               {:headings (count (l/select 'th.insurance-workbench-row-actions-cell table))
                :cells    (count (l/select 'td.insurance-workbench-row-actions-cell table))})))
      (testing "View and Edit are direct compact links."
        (is (= [{:label "View"
                 :href  (str "/insurance-coverage/" coverage-id "/")}
                {:label "Edit"
                 :href  (str "/insurance-coverage-edit/" coverage-id "/")}]
               (mapv (fn [button]
                       {:label (:aria-label (l/attrs button))
                        :href  (:href (l/attrs button))})
                     action-buttons))))
      (testing "Desktop and mobile overflow menus offer the same destinations."
        (is (= {{:label "View"
                 :value (str "/insurance-coverage/" coverage-id "/")} 2
                {:label "Edit"
                 :value (str "/insurance-coverage-edit/" coverage-id "/")} 2}
               (frequencies
                (map (fn [item]
                       {:label (l/text item)
                        :value (:value (l/attrs item))})
                     (l/select 'wa-dropdown-item actions-view)))))))))

(deftest bulk-actions
  (testing "The editable grouped table has rows but no current selection."
    (let [grouped (sut/bulk-action-bar
                   request
                   {:editable? true
                    :filters   {:group :member}
                    :rows      [{}]})
          flat    (sut/bulk-action-bar
                   request
                   {:editable? true
                    :filters   {:group :none}
                    :rows      [{}]})]
      (testing "Selection and status actions remain visible but disabled at zero selections."
        (is (= {:labels   ["Deselect All" "Mark Workflow" "Set Change"
                           "Expand all" "Collapse all"]
                :disabled [true true true]}
               {:labels   (mapv l/text
                                (l/select :app.ui2.button/button grouped))
                :disabled (->> (l/select :app.ui2.button/button grouped)
                               (keep #(get (l/attrs %) :disabled))
                               vec)})))
      (testing "Workflow and change menus submit their matching bulk actions."
        (is (= {:values  ["todo" "reviewed" "active"
                          "changed" "new" "removed" "none"]
                :actions #{:bulk-mark-workflow :bulk-set-change}}
               {:values  (mapv #(get (l/attrs %) :value)
                               (l/select 'wa-dropdown-item grouped))
                :actions (action-keywords grouped)})))
      (testing "Intersection state controls the sticky bulk bar."
        (is (= {:enter "$insuranceWorkbench.bulkActionStuck = false"
                :exit  "$insuranceWorkbench.bulkActionStuck = el.getBoundingClientRect().top < 0"}
               (let [attrs (select-attrs
                            '.insurance-workbench-bulk-action-sentinel
                            grouped)]
                 {:enter (:data-on-intersect attrs)
                  :exit  (:data-on-intersect__exit attrs)}))))
      (testing "Expansion controls are offered only for grouped rows."
        (is (= ["Deselect All" "Mark Workflow" "Set Change"]
               (mapv l/text (l/select :app.ui2.button/button flat))))))))

(deftest pagination
  (testing "The first page shows 20 of 85 results and has another page."
    (let [view (sut/rows-section
                {:tr tr}
                {:policy     policy
                 :view       :all
                 :filters    {:group :none
                              :ownership :all}
                 :pagination {:page          1
                              :page-size     20
                              :page-sizes    [20 50 100]
                              :total-results 85
                              :total-pages   5
                              :range-start   1
                              :range-end     20
                              :has-prev?     false
                              :has-next?     true
                              :prev-page     nil
                              :next-page     2}
                 :rows       [row]})
          dropdown (l/select-one
                    "wa-dropdown[data-workbench-page-size=true]"
                    view)
          items (l/select 'wa-dropdown-item dropdown)
          nav (l/select-one "nav[aria-label=Pagination]" view)
          buttons (l/select :app.ui2.button/button nav)]
      (testing "The page-size menu identifies the current size and available choices."
        (is (= {:summary "1–20 of 85 results"
                :values  ["20" "50" "100"]
                :icon-visibility [nil "visibility: hidden;" "visibility: hidden;"]}
               {:summary (-> (l/select-one :app.ui2.button/button dropdown) l/text)
                :values  (mapv #(get (l/attrs %) :value) items)
                :icon-visibility
                (mapv #(some-> (l/select-one :app.ui2.icon/icon %) l/attrs :style)
                      items)})))
      (testing "Selecting a page size resets the page and preserves workbench state."
        (is (= #{{"page" "1" "page-size" "20"}
                 {"page" "1" "page-size" "50"}
                 {"page" "1" "page-size" "100"}}
               (->> (embedded-urls (:data-on:wa-select (l/attrs dropdown)))
                    (map #(select-keys (query-map %) ["page" "page-size"]))
                    set))))
      (testing "Previous is disabled and Next links to page two."
        (is (= {:previous {:aria-label "Previous" :disabled true}
                :next     {:aria-label "Next"
                           :href (str "/insurance-policy/" policy-id
                                      "/workbench?view=all&ownership=all&group=none&page=2&page-size=20")}}
               {:previous (select-keys (l/attrs (first buttons))
                                       [:aria-label :disabled])
                :next     (select-keys (l/attrs (last buttons))
                                       [:aria-label :href])}))))))

(deftest sticky-bars
  (testing "Pagination and bulk actions slide into view when they become sticky."
    (let [css (slurp "resources/public/css/pages/insurance.css")]
      (testing "Scroll-state containers and transitions are defined."
        (is (= {:pagination-container true
                :pagination-query     true
                :pagination-transition true
                :pagination-slide     true
                :bulk-transition       true
                :bulk-slide            true
                :reduced-motion        true}
               {:pagination-container
                (boolean (re-find #"(?s)\.insurance-workbench-pagination \{.*container-type: scroll-state;" css))
                :pagination-query
                (boolean (re-find #"(?s)@container scroll-state\(stuck: bottom\)" css))
                :pagination-transition
                (boolean (re-find #"(?s)\.insurance-workbench-pagination__inner \{.*transition:" css))
                :pagination-slide
                (boolean (re-find #"(?s)@container scroll-state\(stuck: bottom\).*\.insurance-workbench-pagination__inner \{.*transform: translateY\(0\);" css))
                :bulk-transition
                (boolean (re-find #"(?s)\.insurance-workbench-bulk-action-bar \{.*transition:" css))
                :bulk-slide
                (boolean (re-find #"(?s)\.insurance-workbench-bulk-action-bar--stuck \{.*transform: translateY\(0\);" css))
                :reduced-motion
                (boolean (re-find #"(?s)@media \(prefers-reduced-motion: reduce\).*\.insurance-workbench-pagination__inner,.*\.insurance-workbench-bulk-action-bar" css))}))))))
