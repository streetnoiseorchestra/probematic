(ns app.insurance.policy.workbench.actions-test
  (:require
   [app.insurance.policy.workbench.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn merge-signals-response [signals]
  [:app.datastar/respond-sse
   [[:app.datastar.sse/merge-signals signals]]])

(defn tr
  ([k] k)
  ([k args] [k args]))

(deftest set-member-search-phrase-action-test
  (is (= [[:app.datastar/assoc-state [:insurance-workbench :filters :member-q] "Anna"]]
         (actions/set-member-search-phrase-action
          {}
          {:insuranceWorkbench {:memberQ "Anna"}})))

  (is (= [[:app.datastar/assoc-state [:insurance-workbench :filters :member-q] nil]]
         (actions/set-member-search-phrase-action
          {}
          {:insuranceWorkbench {:memberQ "   "}}))))

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn seed-action-policy!
  [conn {:keys [policy-id policy-status coverages]}]
  (let [category-id      (random-uuid)
        coverage-type-id (random-uuid)
        owner-id         (random-uuid)
        suffix           (str policy-id)
        coverages        (or coverages [{:coverage-id (random-uuid)}])
        coverage-tempids (mapv (fn [idx] (str "coverage-" suffix "-" idx))
                               (range (count coverages)))]
    @(d/transact
      conn
      (vec
       (concat
        [{:db/id            (str "owner-" suffix)
          :member/member-id owner-id
          :member/name      (str "Owner " suffix)}
         {:db/id                           (str "category-" suffix)
          :instrument.category/category-id category-id
          :instrument.category/name        (str "Category " suffix)
          :instrument.category/code        (str "category-" suffix)}
         {:db/id                                  (str "coverage-type-" suffix)
          :insurance.coverage.type/type-id        coverage-type-id
          :insurance.coverage.type/name           "Basic"
          :insurance.coverage.type/description    ""
          :insurance.coverage.type/premium-factor 1.0M}]
        (map-indexed
         (fn [idx {:keys [coverage-id status change private?]}]
           {:db/id                           (coverage-tempids idx)
            :instrument.coverage/coverage-id coverage-id
            :instrument.coverage/instrument  (str "instrument-" suffix "-" idx)
            :instrument.coverage/types       [(str "coverage-type-" suffix)]
            :instrument.coverage/private?    (boolean private?)
            :instrument.coverage/value       1000M
            :instrument.coverage/item-count  1
            :instrument.coverage/status      (or status :instrument.coverage.status/needs-review)
            :instrument.coverage/change      (or change :instrument.coverage.change/none)})
         coverages)
        (map-indexed
         (fn [idx _coverage]
           {:db/id                    (str "instrument-" suffix "-" idx)
            :instrument/instrument-id (random-uuid)
            :instrument/name          (str "Instrument " idx)
            :instrument/owner         (str "owner-" suffix)
            :instrument/category      (str "category-" suffix)})
         coverages)
        [{:insurance.policy/policy-id           policy-id
          :insurance.policy/name                (str "Insurance " suffix)
          :insurance.policy/status              (or policy-status :insurance.policy.status/draft)
          :insurance.policy/currency            :currency/EUR
          :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
          :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
          :insurance.policy/premium-factor      0.01M
          :insurance.policy/coverage-types      [(str "coverage-type-" suffix)]
          :insurance.policy/category-factors    [{:insurance.category.factor/category-factor-id (random-uuid)
                                                  :insurance.category.factor/category           (str "category-" suffix)
                                                  :insurance.category.factor/factor             0.01M}]
          :insurance.policy/covered-instruments coverage-tempids}]))))
  (mapv :coverage-id coverages))

(defn state
  [{:keys [conn member-id]}]
  {:current-member-id member-id
   :db                (d/db conn)
   :tr                tr})

(defn workbench-signals
  [{:keys [policy-id selected-ids target-change-status target-status]}]
  (cond-> {:insuranceWorkbench {:policyId             (str policy-id)
                                :selectedCoverageIds (mapv str selected-ids)}}
    target-status
    (assoc-in [:insuranceWorkbench :targetWorkflowStatus] (name target-status))

    target-change-status
    (assoc-in [:insuranceWorkbench :targetChangeStatus] (name target-change-status))))

(defn apply-filter-signals
  [{:keys [category-ids change-statuses coverage-type-ids field missing-harmonia-id?
           missing-photos? ownership value value-max value-min value-operator
           workflow-statuses]}]
  {:insuranceWorkbench {:filterEditor {:field (name (or field :category))}
                        :filterDraft  {:categoryIds       (mapv str category-ids)
                                       :ownership         (name (or ownership :all))
                                       :coverageTypeIds   (mapv str coverage-type-ids)
                                       :missingPhotos     (boolean missing-photos?)
                                       :missingHarmoniaId (boolean missing-harmonia-id?)
                                       :workflowStatuses  (mapv name workflow-statuses)
                                       :changeStatuses    (mapv name change-statuses)
                                       :valueOperator     (name (or value-operator :greater-than))
                                       :value             value
                                       :valueMin          value-min
                                       :valueMax          value-max}}})

(defn db-transact-effect
  [effects]
  (some #(when (and (vector? %) (= :db/transact (first %))) %)
        effects))

(defn transaction-statuses
  [effects]
  (->> (second (db-transact-effect effects))
       (filter #(= :instrument.coverage/status (nth % 2 nil)))
       (mapv (fn [[_ lookup-ref _ status]] [lookup-ref status]))))

(defn transaction-changes
  [effects]
  (->> (second (db-transact-effect effects))
       (filter #(= :instrument.coverage/change (nth % 2 nil)))
       (mapv (fn [[_ lookup-ref _ change]] [lookup-ref change]))))

(deftest bulk-update-workflow-status-action-success-test
  (testing "returns one audited transaction effect for selected coverages"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-success")
          policy-id                           (random-uuid)
          coverage-a                          (random-uuid)
          coverage-b                          (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-action-policy! conn {:policy-id policy-id
                                 :coverages [{:coverage-id coverage-a}
                                             {:coverage-id coverage-b}]})
      (is (= [[:db/transact
               [[:db/add
                 [:instrument.coverage/coverage-id coverage-a]
                 :instrument.coverage/status
                 :instrument.coverage.status/reviewed]
                [:db/add
                 [:instrument.coverage/coverage-id coverage-b]
                 :instrument.coverage/status
                 :instrument.coverage.status/reviewed]
                [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {}]
              [:app.datastar/assoc-state [:insurance-workbench :error] nil]
              [:app.datastar/respond-sse
               [support/clear-loading-event
                [:app.datastar.sse/remove-signals
                 ["insuranceWorkbench.selectedCoverageIds"]]]]]
             (actions/bulk-update-workflow-status-action
              (state system)
              (workbench-signals {:policy-id policy-id
                                  :selected-ids [coverage-a coverage-b]
                                  :target-status :reviewed})))))))

(deftest bulk-update-workflow-status-action-target-statuses-test
  (testing "supports todo, reviewed, and active target workflow statuses"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-statuses")
          policy-id                           (random-uuid)
          coverage-id                         (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-action-policy! conn {:policy-id policy-id
                                 :coverages [{:coverage-id coverage-id}]})
      (is (= {:todo     [[[:instrument.coverage/coverage-id coverage-id]
                          :instrument.coverage.status/needs-review]]
              :reviewed [[[:instrument.coverage/coverage-id coverage-id]
                          :instrument.coverage.status/reviewed]]
              :active   [[[:instrument.coverage/coverage-id coverage-id]
                          :instrument.coverage.status/coverage-active]]}
             (into {}
                   (for [target-status [:todo :reviewed :active]]
                     [target-status
                      (transaction-statuses
                       (actions/bulk-update-workflow-status-action
                        (state system)
                        (workbench-signals {:policy-id policy-id
                                            :selected-ids [coverage-id]
                                            :target-status target-status})))])))))))

(deftest bulk-update-statuses-action-updates-workflow-and-change-statuses
  (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-combined-statuses")
        policy-id                           (random-uuid)
        coverage-a                          (random-uuid)
        coverage-b                          (random-uuid)]
    (seed-insurance-team! conn member-id)
    (seed-action-policy! conn {:policy-id policy-id
                               :coverages [{:coverage-id coverage-a}
                                           {:coverage-id coverage-b}]})
    (let [effects (actions/bulk-update-statuses-action
                   (state system)
                   (workbench-signals {:policy-id            policy-id
                                       :selected-ids         [coverage-a coverage-b]
                                       :target-status        :active
                                       :target-change-status :changed}))]
      (is (= [[[:instrument.coverage/coverage-id coverage-a]
               :instrument.coverage.status/coverage-active]
              [[:instrument.coverage/coverage-id coverage-b]
               :instrument.coverage.status/coverage-active]]
             (transaction-statuses effects)))
      (is (= [[[:instrument.coverage/coverage-id coverage-a]
               :instrument.coverage.change/changed]
              [[:instrument.coverage/coverage-id coverage-b]
               :instrument.coverage.change/changed]]
             (transaction-changes effects))))))

(deftest bulk-update-statuses-action-can-update-only-change-status
  (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-change-only")
        policy-id                           (random-uuid)
        coverage-id                         (random-uuid)]
    (seed-insurance-team! conn member-id)
    (seed-action-policy! conn {:policy-id policy-id
                               :coverages [{:coverage-id coverage-id}]})
    (let [effects (actions/bulk-update-statuses-action
                   (state system)
                   (workbench-signals {:policy-id            policy-id
                                       :selected-ids         [coverage-id]
                                       :target-status        :keep
                                       :target-change-status :none}))]
      (is (empty? (transaction-statuses effects)))
      (is (= [[[:instrument.coverage/coverage-id coverage-id]
               :instrument.coverage.change/none]]
             (transaction-changes effects))))))

(deftest bulk-mark-workflow-action-ignores-change-target
  (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-mark-workflow")
        policy-id                           (random-uuid)
        coverage-id                         (random-uuid)]
    (seed-insurance-team! conn member-id)
    (seed-action-policy! conn {:policy-id policy-id
                               :coverages [{:coverage-id coverage-id}]})
    (let [effects (actions/bulk-mark-workflow-action
                   (state system)
                   (workbench-signals {:policy-id            policy-id
                                       :selected-ids         [coverage-id]
                                       :target-status        :reviewed
                                       :target-change-status :changed}))]
      (is (= [[[:instrument.coverage/coverage-id coverage-id]
               :instrument.coverage.status/reviewed]]
             (transaction-statuses effects)))
      (is (empty? (transaction-changes effects))))))

(deftest bulk-set-change-action-ignores-workflow-target
  (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-set-change")
        policy-id                           (random-uuid)
        coverage-id                         (random-uuid)]
    (seed-insurance-team! conn member-id)
    (seed-action-policy! conn {:policy-id policy-id
                               :coverages [{:coverage-id coverage-id}]})
    (let [effects (actions/bulk-set-change-action
                   (state system)
                   (workbench-signals {:policy-id            policy-id
                                       :selected-ids         [coverage-id]
                                       :target-status        :active
                                       :target-change-status :none}))]
      (is (empty? (transaction-statuses effects)))
      (is (= [[[:instrument.coverage/coverage-id coverage-id]
               :instrument.coverage.change/none]]
             (transaction-changes effects))))))

(defn rejection-summary
  [effects]
  {:has-transaction? (boolean (db-transact-effect effects))
   :effects          effects})

(deftest bulk-update-workflow-status-action-validation-test
  (testing "rejects invalid requests without a transaction effect"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-workbench-action-validation")
          policy-id                           (random-uuid)
          frozen-policy-id                    (random-uuid)
          foreign-policy-id                   (random-uuid)
          coverage-id                         (random-uuid)
          frozen-coverage-id                  (random-uuid)
          foreign-coverage-id                 (random-uuid)
          missing-coverage-id                 (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-action-policy! conn {:policy-id policy-id
                                 :coverages [{:coverage-id coverage-id}]})
      (seed-action-policy! conn {:policy-id frozen-policy-id
                                 :policy-status :insurance.policy.status/active
                                 :coverages [{:coverage-id frozen-coverage-id}]})
      (seed-action-policy! conn {:policy-id foreign-policy-id
                                 :coverages [{:coverage-id foreign-coverage-id}]})
      (is (= {:empty-selection {:has-transaction? false
                                :effects [support/clear-loading
                                          [:app.datastar/assoc-state
                                           [:insurance-workbench :error]
                                           {:error [:insurance.workbench/error-empty-selection]}]]}
              :invalid-status  {:has-transaction? false
                                :effects [support/clear-loading
                                          [:app.datastar/assoc-state
                                           [:insurance-workbench :error]
                                           {:error [:insurance.workbench/error-invalid-target-status]}]]}
              :not-allowed     {:has-transaction? false
                                :effects [support/clear-loading
                                          [:app.datastar/assoc-state
                                           [:insurance-workbench :error]
                                           {:error [:insurance.workbench/error-not-allowed]}]]}
              :frozen-policy   {:has-transaction? false
                                :effects [support/clear-loading
                                          [:app.datastar/assoc-state
                                           [:insurance-workbench :error]
                                           {:error [:insurance.workbench/error-frozen-policy]}]]}
              :missing-coverage {:has-transaction? false
                                 :effects [support/clear-loading
                                           [:app.datastar/assoc-state
                                            [:insurance-workbench :error]
                                            {:error [:insurance.workbench/error-coverage-not-found]}]]}
              :foreign-coverage {:has-transaction? false
                                 :effects [support/clear-loading
                                           [:app.datastar/assoc-state
                                            [:insurance-workbench :error]
                                            {:error [:insurance.workbench/error-coverage-not-in-policy]}]]}}
             {:empty-selection
              (rejection-summary
               (actions/bulk-update-workflow-status-action
                (state system)
                (workbench-signals {:policy-id policy-id
                                    :selected-ids []
                                    :target-status :reviewed})))
              :invalid-status
              (rejection-summary
               (actions/bulk-update-workflow-status-action
                (state system)
                {:insuranceWorkbench {:policyId (str policy-id)
                                      :selectedCoverageIds [(str coverage-id)]
                                      :targetWorkflowStatus "archived"}}))
              :not-allowed
              (let [{other-conn :conn :as other-system} (tc/new-system "insurance-workbench-action-not-allowed")]
                (seed-action-policy! other-conn {:policy-id policy-id
                                                 :coverages [{:coverage-id coverage-id}]})
                (rejection-summary
                 (actions/bulk-update-workflow-status-action
                  (state other-system)
                  (workbench-signals {:policy-id policy-id
                                      :selected-ids [coverage-id]
                                      :target-status :reviewed}))))
              :frozen-policy
              (rejection-summary
               (actions/bulk-update-workflow-status-action
                (state system)
                (workbench-signals {:policy-id frozen-policy-id
                                    :selected-ids [frozen-coverage-id]
                                    :target-status :reviewed})))
              :missing-coverage
              (rejection-summary
               (actions/bulk-update-workflow-status-action
                (state system)
                (workbench-signals {:policy-id policy-id
                                    :selected-ids [missing-coverage-id]
                                    :target-status :reviewed})))
              :foreign-coverage
              (rejection-summary
               (actions/bulk-update-workflow-status-action
                (state system)
                (workbench-signals {:policy-id policy-id
                                    :selected-ids [coverage-id foreign-coverage-id]
                                    :target-status :reviewed})))})))))

(deftest apply-filter-action-stores-active-category-filter-in-page-state
  (let [category-a (random-uuid)
        category-b (random-uuid)]
    (is (= [[:app.datastar/assoc-state
             [:insurance-workbench :filters :category-ids]
             [category-a category-b]]
            (merge-signals-response
             {:insuranceWorkbench {:filterEditor  {:field ""
                                                   :source nil
                                                   :appliedField "category"}
                                   :filterPopover {:open false}}})]
           (actions/apply-filter-action
            {}
            (apply-filter-signals {:category-ids [category-a category-b]}))))))

(deftest apply-filter-action-stores-active-ownership-filter-in-page-state
  (is (= [[:app.datastar/assoc-state
           [:insurance-workbench :filters :ownership]
           :private]
          (merge-signals-response
           {:insuranceWorkbench {:filterEditor  {:field ""
                                                 :source nil
                                                 :appliedField "ownership"}
                                 :filterPopover {:open false}}})]
         (actions/apply-filter-action
          {}
          (apply-filter-signals {:field :ownership
                                 :ownership :private})))))

(deftest apply-filter-action-stores-extended-filters-in-page-state
  (let [type-a (random-uuid)
        type-b (random-uuid)]
    (is (= {:coverage-types [:app.datastar/assoc-state
                             [:insurance-workbench :filters :coverage-type-ids]
                             [type-a type-b]]
            :photos         [:app.datastar/assoc-state
                             [:insurance-workbench :filters :missing-photos?]
                             true]
            :harmonia-id    [:app.datastar/assoc-state
                             [:insurance-workbench :filters :missing-harmonia-id?]
                             true]
            :workflow       [:app.datastar/assoc-state
                             [:insurance-workbench :filters :workflow-statuses]
                             [:needs-review :reviewed]]
            :change         [:app.datastar/assoc-state
                             [:insurance-workbench :filters :change-statuses]
                             [:changed :new]]}
           {:coverage-types
            (first (actions/apply-filter-action
                    {}
                    (apply-filter-signals {:field :coverage-types
                                           :coverage-type-ids [type-a type-b]})))
            :photos
            (first (actions/apply-filter-action
                    {}
                    (apply-filter-signals {:field :photos
                                           :missing-photos? true})))
            :harmonia-id
            (first (actions/apply-filter-action
                    {}
                    (apply-filter-signals {:field :harmonia-id
                                           :missing-harmonia-id? true})))
            :workflow
            (first (actions/apply-filter-action
                    {}
                    (apply-filter-signals {:field :workflow
                                           :workflow-statuses [:needs-review :reviewed]})))
            :change
            (first (actions/apply-filter-action
                    {}
                    (apply-filter-signals {:field :change
                                           :change-statuses [:changed :new]})))}))))

(deftest apply-filter-action-stores-value-filter-in-page-state
  (is (= {:greater-than [[:app.datastar/assoc-state
                          [:insurance-workbench :filters :value-filter]
                          {:operator :greater-than
                           :value    1000M}]
                         (merge-signals-response
                          {:insuranceWorkbench {:filterEditor  {:field ""
                                                                :source nil
                                                                :appliedField "value"}
                                                :filterPopover {:open false}}})]
          :between      [[:app.datastar/assoc-state
                          [:insurance-workbench :filters :value-filter]
                          {:operator :between
                           :min      1000M
                           :max      3000M}]
                         (merge-signals-response
                          {:insuranceWorkbench {:filterEditor  {:field ""
                                                                :source nil
                                                                :appliedField "value"}
                                                :filterPopover {:open false}}})]
          :blank        [[:app.datastar/assoc-state
                          [:insurance-workbench :filters :value-filter]
                          nil]
                         (merge-signals-response
                          {:insuranceWorkbench {:filterEditor  {:field ""
                                                                :source nil
                                                                :appliedField "value"}
                                                :filterPopover {:open false}}})]}
         {:greater-than
          (actions/apply-filter-action
           {}
           (apply-filter-signals {:field :value
                                  :value-operator :greater-than
                                  :value "1000"}))
          :between
          (actions/apply-filter-action
           {}
           (apply-filter-signals {:field :value
                                  :value-operator :between
                                  :value-min "1000"
                                  :value-max "3000"}))
          :blank
          (actions/apply-filter-action
           {}
           (apply-filter-signals {:field :value
                                  :value-operator :less-than
                                  :value ""}))})))

(deftest toggle-table-column-action-stores-column-visibility-in-view-scoped-page-state
  (is (= {:hide [[:app.datastar/assoc-state
                  [:insurance-workbench :table :columns-by-view :missing-id :cost]
                  false]]
          :show [[:app.datastar/assoc-state
                  [:insurance-workbench :table :columns-by-view :missing-id :cost]
                  true]]}
         {:hide
          (actions/toggle-table-column-action
           {}
           {:insuranceWorkbench {:table {:view "missing-id"
                                         :column "cost"
                                         :columnVisible false}}})
          :show
          (actions/toggle-table-column-action
           {}
           {:insuranceWorkbench {:table {:view "missing-id"
                                         :column "cost"
                                         :columnVisible true}}})})))

(deftest toggle-table-column-action-rejects-unsupported-columns
  (is (= {:selection-column []
          :unknown-column []}
         {:selection-column
          (actions/toggle-table-column-action
           {}
           {:insuranceWorkbench {:table {:column "selection"
                                         :columnVisible false}}})
          :unknown-column
          (actions/toggle-table-column-action
           {}
           {:insuranceWorkbench {:table {:column "internal"
                                         :columnVisible false}}})})))

(deftest toggle-table-column-action-normalizes-string-visibility
  (is (= [[:app.datastar/assoc-state
           [:insurance-workbench :table :columns-by-view :all :harmonia-id]
           false]]
         (actions/toggle-table-column-action
          {}
          {:insuranceWorkbench {:table {:column "harmonia-id"
                                        :columnVisible "false"}}}))))
