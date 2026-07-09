(ns app.insurance.policy.settings.actions-test
  (:require
   [app.insurance.actions :as insurance.actions]
   [app.insurance.policy.settings.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr
  ([k] k)
  ([k args] [k args]))

(defn date-inst
  [date]
  (-> date t/date (t/at (t/midnight)) t/inst))

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn seed-policy!
  [conn policy-id status]
  @(d/transact conn [{:insurance.policy/policy-id       policy-id
                      :insurance.policy/name            "Insurance 2026"
                      :insurance.policy/status          status
                      :insurance.policy/currency        :EUR
                      :insurance.policy/effective-at    (date-inst "2026-01-01")
                      :insurance.policy/effective-until (date-inst "2026-12-31")
                      :insurance.policy/premium-factor  0.01M}])
  policy-id)

(defn seed-coverage-types!
  [conn policy-id]
  (let [used-type-id     (random-uuid)
        unused-type-id   (random-uuid)
        foreign-policy-id (random-uuid)
        foreign-type-id   (random-uuid)
        coverage-id       (random-uuid)]
    (seed-policy! conn foreign-policy-id :insurance.policy.status/draft)
    @(d/transact
      conn
      [{:db/id                                  "used-type"
        :insurance.coverage.type/type-id        used-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    "Base coverage"
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                                  "unused-type"
        :insurance.coverage.type/type-id        unused-type-id
        :insurance.coverage.type/name           "Unused"
        :insurance.coverage.type/description    "Unused coverage"
        :insurance.coverage.type/premium-factor 0.25M}
       {:db/id                                  "foreign-type"
        :insurance.coverage.type/type-id        foreign-type-id
        :insurance.coverage.type/name           "Foreign"
        :insurance.coverage.type/description    "Foreign coverage"
        :insurance.coverage.type/premium-factor 0.5M}
       {:db/id                           "used-coverage"
        :instrument.coverage/coverage-id coverage-id
        :instrument.coverage/types       ["used-type"]
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/coverage-types "used-type"]
       [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/coverage-types "unused-type"]
       [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/covered-instruments "used-coverage"]
       [:db/add [:insurance.policy/policy-id foreign-policy-id] :insurance.policy/coverage-types "foreign-type"]])
    {:used-type-id     used-type-id
     :unused-type-id   unused-type-id
     :foreign-policy-id foreign-policy-id
     :foreign-type-id   foreign-type-id
     :coverage-id       coverage-id}))

(defn state
  [{:keys [conn member-id]}]
  {:current-member-id member-id
   :db                (d/db conn)
   :tr                tr})

(defn policy-signals
  [policy-id overrides]
  {:insurancePolicySettings
   {:policy (merge {:policyId       (str policy-id)
                    :name           "Insurance 2027"
                    :effectiveAt    "2027-01-01"
                    :effectiveUntil "2027-12-31"
                    :premiumFactor  "0.025"
                    :currency       "EUR"}
                   overrides)}})

(defn coverage-type-signals
  [policy-id overrides]
  {:insurancePolicySettings
   {:coverageType (merge {:policyId      (str policy-id)
                          :name          "Extended"
                          :description   "Additional coverage"
                          :premiumFactor "0.75"}
                         overrides)}})

(defn transact-effect?
  [effect]
  (= :db/transact (first effect)))

(defn assoc-state-effect
  [effects]
  (some #(when (= :app.datastar/assoc-state (first %)) %)
        effects))

(defn failure-summary
  [effects]
  (let [[_ path value] (assoc-state-effect effects)]
    {:transact?      (boolean (some transact-effect? effects))
     :clear-loading? (boolean (some #{support/clear-loading} effects))
     :state-path     path
     :submitted      (select-keys value [:name
                                         :effective-at
                                         :effective-until
                                         :premium-factor
                                         :currency])
     :error-keys     (set (keys (:_error value)))
     :top-error      (get-in value [:_error :_top :error])}))

(defn coverage-type-failure-summary
  [effects]
  (let [[_ path value] (assoc-state-effect effects)]
    {:transact?      (boolean (some transact-effect? effects))
     :clear-loading? (boolean (some #{support/clear-loading} effects))
     :state-path     path
     :submitted      (select-keys value [:policy-id
                                         :type-id
                                         :name
                                         :description
                                         :premium-factor])
     :error-keys     (set (keys (:_error value)))
     :top-error      (get-in value [:_error :_top :error])}))

(deftest save-policy-details-action-test
  (testing "returns an audited policy detail transaction for an insurance team member"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-action-save")
          policy-id                           (random-uuid)
          policy-ref                          [:insurance.policy/policy-id policy-id]]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (is (= [[:db/transact
               (support/with-audit
                 [[:db/add policy-ref :insurance.policy/name "Insurance 2027"]
                  [:db/add policy-ref :insurance.policy/effective-at (date-inst "2027-01-01")]
                  [:db/add policy-ref :insurance.policy/effective-until (date-inst "2027-12-31")]
                  [:db/add policy-ref :insurance.policy/premium-factor 0.025M]
                  [:db/add policy-ref :insurance.policy/currency :EUR]]
                 member-id)
               {}]
              support/clear-loading
              [:app.datastar/assoc-state [:insurance-policy-settings :policy] false]]
             (actions/save-policy-details-action (state system)
                                                 (policy-signals policy-id {})))))))

(deftest save-policy-details-validation-test
  (testing "returns field and top-level errors without a transaction"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-action-validation")
          policy-id                           (random-uuid)
          missing-policy-id                   (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (is (= {:invalid-fields {:transact?      false
                               :clear-loading? true
                               :state-path     [:insurance-policy-settings :policy]
                               :submitted      {:name            ""
                                                :effective-at    "nope"
                                                :effective-until ""
                                                :premium-factor  "not-a-decimal"
                                                :currency        "JPY"}
                               :error-keys     #{:name
                                                 :effective-at
                                                 :effective-until
                                                 :premium-factor
                                                 :currency
                                                 :_top}
                               :top-error      [:error/form-has-errors]}
              :date-order     {:transact?      false
                               :clear-loading? true
                               :state-path     [:insurance-policy-settings :policy]
                               :submitted      {:name            "Insurance 2027"
                                                :effective-at    "2027-12-31"
                                                :effective-until "2027-01-01"
                                                :premium-factor  "0.025"
                                                :currency        "EUR"}
                               :error-keys     #{:effective-until :_top}
                               :top-error      [:error/form-has-errors]}
              :missing-policy {:transact?      false
                               :clear-loading? true
                               :state-path     [:insurance-policy-settings :policy]
                               :submitted      {:name            "Insurance 2027"
                                                :effective-at    "2027-01-01"
                                                :effective-until "2027-12-31"
                                                :premium-factor  "0.025"
                                                :currency        "EUR"}
                               :error-keys     #{:_top}
                               :top-error      [:insurance.policy-settings/error-policy-not-found]}}
             {:invalid-fields (failure-summary
                               (actions/save-policy-details-action
                                (state system)
                                (policy-signals policy-id
                                                {:name           "  "
                                                 :effectiveAt    "nope"
                                                 :effectiveUntil ""
                                                 :premiumFactor  "not-a-decimal"
                                                 :currency       "JPY"})))
              :date-order     (failure-summary
                               (actions/save-policy-details-action
                                (state system)
                                (policy-signals policy-id
                                                {:effectiveAt    "2027-12-31"
                                                 :effectiveUntil "2027-01-01"})))
              :missing-policy (failure-summary
                               (actions/save-policy-details-action
                                (state system)
                                (policy-signals missing-policy-id {})))})))))

(deftest save-policy-details-authorization-test
  (testing "rejects non-team members and frozen policies without a transaction"
    (let [{draft-conn :conn :as draft-system} (tc/new-system "insurance-settings-action-not-team")
          {frozen-conn :conn frozen-member-id :member-id :as frozen-system}
          (tc/new-system "insurance-settings-action-frozen")
          draft-policy-id  (random-uuid)
          frozen-policy-id (random-uuid)]
      (seed-policy! draft-conn draft-policy-id :insurance.policy.status/draft)
      (seed-insurance-team! frozen-conn frozen-member-id)
      (seed-policy! frozen-conn frozen-policy-id :insurance.policy.status/sent)
      (is (= {:not-team {:transact?      false
                         :clear-loading? true
                         :state-path     [:insurance-policy-settings :policy]
                         :submitted      {:name            "Insurance 2027"
                                          :effective-at    "2027-01-01"
                                          :effective-until "2027-12-31"
                                          :premium-factor  "0.025"
                                          :currency        "EUR"}
                         :error-keys     #{:_top}
                         :top-error      [:insurance.policy-settings/error-not-allowed]}
              :frozen   {:transact?      false
                         :clear-loading? true
                         :state-path     [:insurance-policy-settings :policy]
                         :submitted      {:name            "Insurance 2027"
                                          :effective-at    "2027-01-01"
                                          :effective-until "2027-12-31"
                                          :premium-factor  "0.025"
                                          :currency        "EUR"}
                         :error-keys     #{:_top}
                         :top-error      [:insurance.policy-settings/error-frozen-policy]}}
             {:not-team (failure-summary
                         (actions/save-policy-details-action
                          (state draft-system)
                          (policy-signals draft-policy-id {})))
              :frozen   (failure-summary
                         (actions/save-policy-details-action
                          (state frozen-system)
                          (policy-signals frozen-policy-id {})))})))))

(deftest coverage-type-create-update-delete-action-test
  (testing "creates, updates, and deletes policy coverage types"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-coverage-type-actions")
          policy-id                           (random-uuid)
          policy-ref                          [:insurance.policy/policy-id policy-id]]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [unused-type-id used-type-id]} (seed-coverage-types! conn policy-id)
            unused-type-ref [:insurance.coverage.type/type-id unused-type-id]
            create-effects  (actions/create-coverage-type-action
                             (state system)
                             (coverage-type-signals policy-id {}))
            [[_ create-tx create-opts] create-clear create-state] create-effects
            [new-type-tx policy-add-tx create-audit-tx] create-tx]
        (is (= {:create {:transact?      true
                         :opts           {}
                         :type-id?       true
                         :type-tx        {:insurance.coverage.type/name           "Extended"
                                          :insurance.coverage.type/description    "Additional coverage"
                                          :insurance.coverage.type/premium-factor 0.75M}
                         :policy-add     [:db/add policy-ref :insurance.policy/coverage-types]
                         :same-tempid?   true
                         :audit          [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]
                         :clear-loading? true
                         :clear-state    [:app.datastar/assoc-state
                                          [:insurance-policy-settings :coverage-type-create]
                                          false]}
                :update [[:db/transact
                          (support/with-audit
                            [[:db/add unused-type-ref :insurance.coverage.type/name "Updated"]
                             [:db/add unused-type-ref :insurance.coverage.type/description "Updated coverage"]
                             [:db/add unused-type-ref :insurance.coverage.type/premium-factor 0.5M]]
                            member-id)
                          {}]
                         support/clear-loading
                         [:app.datastar/assoc-state [:insurance-policy-settings :coverage-type] false]]
                :delete [[:db/transact
                          (support/with-audit
                            [[:db/retract policy-ref :insurance.policy/coverage-types unused-type-ref]
                             [:db/retractEntity unused-type-ref]]
                            member-id)
                          {}]
                         support/clear-loading
                         [:app.datastar/assoc-state
                          [:insurance-policy-settings :coverage-type-delete]
                          false]]
                :delete-used {:transact?      false
                              :clear-loading? true
                              :state-path     [:insurance-policy-settings :coverage-type-delete]
                              :submitted      {:type-id used-type-id}
                              :error-keys     #{:_top}
                              :top-error      [:insurance.policy-settings/error-coverage-type-in-use]}}
               {:create {:transact?      (boolean (some transact-effect? create-effects))
                         :opts           create-opts
                         :type-id?       (uuid? (:insurance.coverage.type/type-id new-type-tx))
                         :type-tx        (dissoc new-type-tx :db/id :insurance.coverage.type/type-id)
                         :policy-add     (subvec (vec policy-add-tx) 0 3)
                         :same-tempid?   (= (:db/id new-type-tx) (nth policy-add-tx 3))
                         :audit          create-audit-tx
                         :clear-loading? (= support/clear-loading create-clear)
                         :clear-state    create-state}
                :update (actions/update-coverage-type-action
                         (state system)
                         (coverage-type-signals policy-id
                                                {:typeId        (str unused-type-id)
                                                 :name          "Updated"
                                                 :description   "Updated coverage"
                                                 :premiumFactor "0.5"}))
                :delete (actions/delete-coverage-type-action
                         (state system)
                         {:targetid (str unused-type-id)})
                :delete-used (coverage-type-failure-summary
                              (actions/delete-coverage-type-action
                               (state system)
                               {:targetid (str used-type-id)}))}))))))

(deftest coverage-type-validation-test
  (testing "validates coverage type fields, duplicate names, and policy membership"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-coverage-type-validation")
          policy-id                           (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [foreign-type-id unused-type-id]} (seed-coverage-types! conn policy-id)]
        (is (= {:invalid-create {:transact?      false
                                 :clear-loading? true
                                 :state-path     [:insurance-policy-settings :coverage-type-create]
                                 :submitted      {:policy-id       policy-id
                                                  :name            ""
                                                  :description     "Anything"
                                                  :premium-factor  "nope"}
                                 :error-keys     #{:name :premium-factor :_top}
                                 :top-error      [:error/form-has-errors]}
                :duplicate-create {:transact?      false
                                   :clear-loading? true
                                   :state-path     [:insurance-policy-settings :coverage-type-create]
                                   :submitted      {:policy-id       policy-id
                                                    :name            "basic"
                                                    :description     "Duplicate"
                                                    :premium-factor  "0.1"}
                                   :error-keys     #{:name :_top}
                                   :top-error      [:error/form-has-errors]}
                :duplicate-update {:transact?      false
                                   :clear-loading? true
                                   :state-path     [:insurance-policy-settings :coverage-type]
                                   :submitted      {:policy-id       policy-id
                                                    :type-id         unused-type-id
                                                    :name            "BASIC"
                                                    :description     "Duplicate"
                                                    :premium-factor  "0.1"}
                                   :error-keys     #{:name :_top}
                                   :top-error      [:error/form-has-errors]}
                :foreign-update {:transact?      false
                                 :clear-loading? true
                                 :state-path     [:insurance-policy-settings :coverage-type]
                                 :submitted      {:policy-id       policy-id
                                                  :type-id         foreign-type-id
                                                  :name            "Foreign"
                                                  :description     "Foreign coverage"
                                                  :premium-factor  "0.5"}
                                 :error-keys     #{:_top}
                                 :top-error      [:insurance.policy-settings/error-coverage-type-not-found]}}
               {:invalid-create (coverage-type-failure-summary
                                 (actions/create-coverage-type-action
                                  (state system)
                                  (coverage-type-signals policy-id
                                                         {:name          " "
                                                          :description   "Anything"
                                                          :premiumFactor "nope"})))
                :duplicate-create (coverage-type-failure-summary
                                   (actions/create-coverage-type-action
                                    (state system)
                                    (coverage-type-signals policy-id
                                                           {:name          " basic "
                                                            :description   "Duplicate"
                                                            :premiumFactor "0.1"})))
                :duplicate-update (coverage-type-failure-summary
                                   (actions/update-coverage-type-action
                                    (state system)
                                    (coverage-type-signals policy-id
                                                           {:typeId        (str unused-type-id)
                                                            :name          "BASIC"
                                                            :description   "Duplicate"
                                                            :premiumFactor "0.1"})))
                :foreign-update (coverage-type-failure-summary
                                 (actions/update-coverage-type-action
                                  (state system)
                                  (coverage-type-signals policy-id
                                                         {:typeId        (str foreign-type-id)
                                                          :name          "Foreign"
                                                          :description   "Foreign coverage"
                                                          :premiumFactor "0.5"})))}))))))

(deftest coverage-type-create-validation-keeps-dialog-open-test
  (testing "keeps the create dialog open so validation errors are visible"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-coverage-type-create-open")
          policy-id                           (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (seed-coverage-types! conn policy-id)
      (let [[_ _ value] (assoc-state-effect
                         (actions/create-coverage-type-action
                          (state system)
                          (coverage-type-signals policy-id
                                                 {:name          " "
                                                  :premiumFactor "bad"})))]
        (is (= {:open       true
                :error-keys #{:name :premium-factor :_top}}
               {:open       (:open value)
                :error-keys (set (keys (:_error value)))}))))))

(deftest coverage-type-authorization-test
  (testing "rejects coverage type mutations for non-team members and frozen policies"
    (let [{draft-conn :conn :as draft-system} (tc/new-system "insurance-settings-coverage-type-not-team")
          {frozen-conn :conn frozen-member-id :member-id :as frozen-system}
          (tc/new-system "insurance-settings-coverage-type-frozen")
          draft-policy-id  (random-uuid)
          frozen-policy-id (random-uuid)]
      (seed-policy! draft-conn draft-policy-id :insurance.policy.status/draft)
      (seed-insurance-team! frozen-conn frozen-member-id)
      (seed-policy! frozen-conn frozen-policy-id :insurance.policy.status/sent)
      (let [{:keys [unused-type-id]} (seed-coverage-types! frozen-conn frozen-policy-id)]
        (is (= {:create-not-team {:transact?      false
                                  :clear-loading? true
                                  :state-path     [:insurance-policy-settings :coverage-type-create]
                                  :submitted      {:policy-id       draft-policy-id
                                                   :name            "Extended"
                                                   :description     "Additional coverage"
                                                   :premium-factor  "0.75"}
                                  :error-keys     #{:_top}
                                  :top-error      [:insurance.policy-settings/error-not-allowed]}
                :update-frozen {:transact?      false
                                :clear-loading? true
                                :state-path     [:insurance-policy-settings :coverage-type]
                                :submitted      {:policy-id       frozen-policy-id
                                                 :type-id         unused-type-id
                                                 :name            "Updated"
                                                 :description     "Updated coverage"
                                                 :premium-factor  "0.5"}
                                :error-keys     #{:_top}
                                :top-error      [:insurance.policy-settings/error-frozen-policy]}
                :delete-frozen {:transact?      false
                                :clear-loading? true
                                :state-path     [:insurance-policy-settings :coverage-type-delete]
                                :submitted      {:type-id unused-type-id}
                                :error-keys     #{:_top}
                                :top-error      [:insurance.policy-settings/error-frozen-policy]}}
               {:create-not-team (coverage-type-failure-summary
                                  (actions/create-coverage-type-action
                                   (state draft-system)
                                   (coverage-type-signals draft-policy-id {})))
                :update-frozen (coverage-type-failure-summary
                                (actions/update-coverage-type-action
                                 (state frozen-system)
                                 (coverage-type-signals frozen-policy-id
                                                        {:typeId        (str unused-type-id)
                                                         :name          "Updated"
                                                         :description   "Updated coverage"
                                                         :premiumFactor "0.5"})))
                :delete-frozen (coverage-type-failure-summary
                                (actions/delete-coverage-type-action
                                 (state frozen-system)
                                 {:targetid (str unused-type-id)}))}))))))

(deftest coverage-type-dialog-actions-test
  (testing "opens and closes create and edit coverage type dialog state"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-coverage-type-dialogs")
          policy-id                           (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [unused-type-id]} (seed-coverage-types! conn policy-id)]
        (is (= {:open-create [support/clear-loading
                              [:app.datastar/assoc-state
                               [:insurance-policy-settings :coverage-type-create]
                               {:open           true
                                :policy-id      policy-id
                                :name           ""
                                :description    ""
                                :premium-factor ""}]]
                :close-create [support/clear-loading
                               [:app.datastar/assoc-state
                                [:insurance-policy-settings :coverage-type-create]
                                false]]
                :open-edit [support/clear-loading
                            [:app.datastar/assoc-state
                             [:insurance-policy-settings :coverage-type]
                             {:policy-id      policy-id
                              :type-id        unused-type-id
                              :name           "Unused"
                              :description    "Unused coverage"
                              :premium-factor 0.25M}]]
                :close-edit [support/clear-loading
                             [:app.datastar/assoc-state
                              [:insurance-policy-settings :coverage-type]
                              false]]}
               {:open-create (actions/open-coverage-type-create-action
                              (state system)
                              {:targetid (str policy-id)})
                :close-create (actions/close-coverage-type-create-action (state system) {})
                :open-edit (actions/open-coverage-type-edit-action
                            (state system)
                            {:targetid (str unused-type-id)})
                :close-edit (actions/close-coverage-type-edit-action (state system) {})}))))))

(deftest settings-actions-are-registered-test
  (is (every? #(contains? insurance.actions/actions %)
              [:app.insurance.policy.settings.actions/save-policy-details
               :app.insurance.policy.settings.actions/open-coverage-type-create
               :app.insurance.policy.settings.actions/close-coverage-type-create
               :app.insurance.policy.settings.actions/create-coverage-type
               :app.insurance.policy.settings.actions/open-coverage-type-edit
               :app.insurance.policy.settings.actions/close-coverage-type-edit
               :app.insurance.policy.settings.actions/update-coverage-type
               :app.insurance.policy.settings.actions/delete-coverage-type])))
