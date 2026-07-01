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
   [:insurance.workbench/ownership] "Ownership"
   [:insurance.workbench/ownership-band] "Band"
   [:insurance.workbench/ownership-private] "Private"
   [:band-instrument] "Band Instrument"
   [:private-instrument] "Private Instrument"
   [:action/remove] "Remove"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path _args]
   (tr path)))

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
            :group               "member"}
           (#'views/workbench-params
            {:parameters {:query {:view "todo"
                                  :group "member"}}
             :page-state {:insurance-workbench
                          {:filters {:category-ids [category-a category-b]
                                     :ownership :private}}}})))))

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
            :group               "member"}
           (#'views/workbench-params
            {:parameters {:query {:view "todo"
                                  :group "member"
                                  :category-id (str category-id)
                                  :ownership "private"}}
             :page-state {:insurance-workbench
                          {:filters {:category-ids []
                                     :ownership :all}}}})))))

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

(deftest search-form-preserves-active-category-filter
  (let [policy-id   (random-uuid)
        category-id (random-uuid)
        html        (html/->str
                     (#'views/search-form
                      {:tr tr}
                      {:policy  {:insurance.policy/policy-id policy-id}
                       :view    :todo
                       :filters {:group :member
                                 :ownership :all
                                 :member-q "Anna"
                                 :category-ids #{category-id}}}))]
    (is (= {:preserves-category? true
            :preserves-member-search? true}
           {:preserves-category?
            (and (str/includes? html "name=\"category-id\"")
                 (str/includes? html (str "value=\"" category-id "\"")))
            :preserves-member-search?
            (str/includes? html "value=\"Anna\"")}))))

(deftest table-settings-group-switch-navigates-and-preserves-toolbar-state
  (let [policy-id   (random-uuid)
        category-id (random-uuid)
        html        (html/->str
                     (#'views/table-settings-popover
                      {:tr tr}
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
    (is (= {:right-aligned-headers? true
            :right-aligned-cells?   true
            :photo-cell-aligned?    true
            :harmonia-cell-aligned? true}
           {:right-aligned-headers?
            (every? #(str/includes? html (str "text-align: end;\">" % "</th>"))
                    ["photos" "insurer-id" "value" "cost"])
            :right-aligned-cells?
            (= 4 (count (re-seq #"<td style=\"text-align: end;\"" html)))
            :photo-cell-aligned?
            (str/includes? html "<td style=\"text-align: end;\">3</td>")
            :harmonia-cell-aligned?
            (str/includes? html "<td style=\"text-align: end;\">H-123</td>")}))))
