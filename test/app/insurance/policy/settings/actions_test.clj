(ns app.insurance.policy.settings.actions-test
  (:require
   [app.insurance.actions :as insurance.actions]
   [app.insurance.policy.settings.actions :as actions]
   [app.insurance.test-support :as test-support]
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
        :insurance.coverage.type/premium-factor 0.25M
        :insurance.coverage.type/icon           :phosphor/shield}
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
                          :premiumFactor "0.75"
                          :icon          "phosphor/shield"
                          :required      false
                          :addToBandInstruments false
                          :confirmationCount ""}
                         overrides)}})

(defn exporter-signals
  [policy-id exporter-id mappings]
  {:insurancePolicySettings
   {:exporter {:policyId   (str policy-id)
               :exporterId exporter-id
               :mappings   mappings}}})

(defn seed-impact-coverages!
  [conn policy-id]
  (let [type-id             (random-uuid)
        band-missing-id     (random-uuid)
        band-selected-id    (random-uuid)
        private-missing-id  (random-uuid)]
    @(d/transact
      conn
      [{:db/id                                  "impact-type"
        :insurance.coverage.type/type-id        type-id
        :insurance.coverage.type/name           "Existing optional"
        :insurance.coverage.type/description    "Existing optional coverage"
        :insurance.coverage.type/premium-factor 0.25M}
       {:db/id                           "band-missing"
        :instrument.coverage/coverage-id band-missing-id
        :instrument.coverage/private?    false
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:db/id                           "band-selected"
        :instrument.coverage/coverage-id band-selected-id
        :instrument.coverage/private?    false
        :instrument.coverage/types       ["impact-type"]
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:db/id                           "private-missing"
        :instrument.coverage/coverage-id private-missing-id
        :instrument.coverage/private?    true
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       [:db/add [:insurance.policy/policy-id policy-id]
        :insurance.policy/coverage-types
        "impact-type"]
       [:db/add [:insurance.policy/policy-id policy-id]
        :insurance.policy/covered-instruments
        "band-missing"]
       [:db/add [:insurance.policy/policy-id policy-id]
        :insurance.policy/covered-instruments
        "band-selected"]
       [:db/add [:insurance.policy/policy-id policy-id]
        :insurance.policy/covered-instruments
        "private-missing"]])
    {:type-id            type-id
     :all-coverage-ids   #{band-missing-id band-selected-id private-missing-id}
     :band-coverage-ids  #{band-missing-id band-selected-id}
     :missing-type-ids   #{band-missing-id private-missing-id}}))

(defn seed-category-factors!
  [conn policy-id]
  (let [used-category-id   (random-uuid)
        unused-category-id (random-uuid)
        new-category-id    (random-uuid)
        foreign-policy-id  (random-uuid)
        foreign-category-id (random-uuid)
        used-factor-id     (random-uuid)
        unused-factor-id   (random-uuid)
        foreign-factor-id  (random-uuid)
        coverage-id        (random-uuid)]
    (seed-policy! conn foreign-policy-id :insurance.policy.status/draft)
    @(d/transact
      conn
      [{:db/id                           "used-category"
        :instrument.category/category-id used-category-id
        :instrument.category/name        "Brass"
        :instrument.category/code        (str "brass-" policy-id)}
       {:db/id                           "unused-category"
        :instrument.category/category-id unused-category-id
        :instrument.category/name        "Woodwind"
        :instrument.category/code        (str "woodwind-" policy-id)}
       {:db/id                           "new-category"
        :instrument.category/category-id new-category-id
        :instrument.category/name        "Percussion"
        :instrument.category/code        (str "percussion-" policy-id)}
       {:db/id                           "foreign-category"
        :instrument.category/category-id foreign-category-id
        :instrument.category/name        "Foreign"
        :instrument.category/code        (str "foreign-" policy-id)}
       {:db/id                                                "used-factor"
        :insurance.category.factor/category-factor-id         used-factor-id
        :insurance.category.factor/category                   "used-category"
        :insurance.category.factor/factor                     0.10M}
       {:db/id                                                "unused-factor"
        :insurance.category.factor/category-factor-id         unused-factor-id
        :insurance.category.factor/category                   "unused-category"
        :insurance.category.factor/factor                     0.20M}
       {:db/id                                                "foreign-factor"
        :insurance.category.factor/category-factor-id         foreign-factor-id
        :insurance.category.factor/category                   "foreign-category"
        :insurance.category.factor/factor                     0.30M}
       {:db/id                    "used-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Trumpet"
        :instrument/category      "used-category"}
       {:db/id                           "used-coverage"
        :instrument.coverage/coverage-id coverage-id
        :instrument.coverage/instrument  "used-instrument"
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/category-factors "used-factor"]
       [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/category-factors "unused-factor"]
       [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/covered-instruments "used-coverage"]
       [:db/add [:insurance.policy/policy-id foreign-policy-id] :insurance.policy/category-factors "foreign-factor"]])
    {:used-category-id    used-category-id
     :unused-category-id  unused-category-id
     :new-category-id     new-category-id
     :foreign-policy-id   foreign-policy-id
     :foreign-category-id foreign-category-id
     :used-factor-id      used-factor-id
     :unused-factor-id    unused-factor-id
     :foreign-factor-id   foreign-factor-id
     :coverage-id         coverage-id}))

(defn category-factor-signals
  [policy-id overrides]
  {:insurancePolicySettings
   {:categoryFactor (merge {:policyId   (str policy-id)
                            :categoryId ""
                            :factor     "0.35"}
                           overrides)}})

(defn transact-effect?
  [effect]
  (= :db/transact (first effect)))

(defn assoc-state-effect
  [effects]
  (some #(when (= :app.datastar/assoc-state (first %)) %)
        effects))

(defn transaction-data
  [effects]
  (some #(when (transact-effect? %) (second %))
        effects))

(defn coverage-type-transaction-summary
  [effects]
  (let [tx-data        (transaction-data effects)
        entity-tx      (some #(when (and (map? %)
                                         (:insurance.coverage.type/type-id %))
                                %)
                             tx-data)
        type-ref       (or (:db/id entity-tx)
                           (some #(when (and (vector? %)
                                             (= :insurance.coverage.type/icon
                                                (nth % 2 nil)))
                                    (second %))
                                 tx-data))
        attribute-value (fn [attribute]
                          (or (get entity-tx attribute)
                              (some #(when (and (vector? %)
                                                (= :db/add (first %))
                                                (= type-ref (second %))
                                                (= attribute (nth % 2 nil)))
                                       (nth % 3 nil))
                                    tx-data)))]
    {:transact?   (boolean tx-data)
     :icon        (attribute-value :insurance.coverage.type/icon)
     :required?   (attribute-value :insurance.coverage.type/required?)
     :coverage-ids
     (->> tx-data
          (keep (fn [tx]
                  (when (and (vector? tx)
                             (= :db/add (first tx))
                             (= :instrument.coverage/types (nth tx 2 nil))
                             (= type-ref (nth tx 3 nil)))
                    (second (second tx)))))
          set)}))

(defn coverage-type-confirmation-summary
  [effects]
  (let [[_ path value] (assoc-state-effect effects)]
    {:transact?         (boolean (transaction-data effects))
     :state-path        path
     :impact-count      (:impact-count value)
     :confirmation-count (:confirmation-count value)
     :error-keys        (set (keys (:_error value)))
     :top-error         (get-in value [:_error :_top :error])}))

(defn exporter-failure-summary
  [effects]
  (let [[_ path value] (assoc-state-effect effects)]
    {:transact?      (boolean (transaction-data effects))
     :state-path     path
     :error-keys     (set (keys (:_error value)))
     :exporter-error (get-in value [:_error :exporter-id :error])
     :mapping-error  (get-in value [:_error :mappings :error])
     :top-error      (get-in value [:_error :_top :error])}))

(defn exporter-transaction-summary
  [effects]
  (let [tx-data (transaction-data effects)]
    {:transact?   (boolean tx-data)
     :exporter-id (some #(when (and (vector? %)
                                    (= :db/add (first %))
                                    (= :insurance.policy/exporter-id
                                       (nth % 2 nil)))
                           (nth % 3 nil))
                        tx-data)
     :mappings    (->> tx-data
                       (keep (fn [tx]
                               (when (and (map? tx)
                                          (:insurance.export.mapping/role tx))
                                 [(:insurance.export.mapping/role tx)
                                  (second
                                   (:insurance.export.mapping/coverage-type tx))])))
                       (into {}))}))

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
  (let [[_ path value] (assoc-state-effect effects)
        value          (if (map? value) value {})]
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

(defn category-factor-failure-summary
  [effects]
  (let [[_ path value] (assoc-state-effect effects)]
    {:transact?      (boolean (some transact-effect? effects))
     :clear-loading? (boolean (some #{support/clear-loading} effects))
     :state-path     path
     :submitted      (select-keys value [:policy-id
                                         :category-factor-id
                                         :category-id
                                         :factor])
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
                                          :insurance.coverage.type/premium-factor 0.75M
                                          :insurance.coverage.type/icon           :phosphor/shield}
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
                             [:db/add unused-type-ref :insurance.coverage.type/premium-factor 0.5M]
                             [:db/add unused-type-ref :insurance.coverage.type/icon :phosphor/shield]]
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
                              :premium-factor 0.25M
                              :icon           :phosphor/shield}]]
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

(deftest coverage-type-metadata-action-test
  (testing "persists registered icons and explicit required state"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-coverage-type-metadata")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [unused-type-id]} (seed-coverage-types! conn policy-id)]
        (is (= {:create {:transact?   true
                         :icon        :phosphor/shield
                         :required?   false
                         :coverage-ids #{}}
                :update {:transact?   true
                         :icon        :phosphor/star
                         :required?   false
                         :coverage-ids #{}}}
               {:create
                (coverage-type-transaction-summary
                 (actions/create-coverage-type-action
                  (state system)
                  (coverage-type-signals policy-id {})))
                :update
                (coverage-type-transaction-summary
                 (actions/update-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:typeId (str unused-type-id)
                    :icon   "phosphor/star"})))}))))))

(deftest coverage-type-icon-validation-test
  (testing "rejects icon values outside the registered sprite catalog"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-coverage-type-icon-validation")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (is (= {:transact?      false
              :clear-loading? true
              :state-path     [:insurance-policy-settings :coverage-type-create]
              :submitted      {:policy-id      policy-id
                               :name           "Extended"
                               :description    "Additional coverage"
                               :premium-factor "0.75"}
              :error-keys     #{:icon :_top}
              :top-error      [:error/form-has-errors]}
             (coverage-type-failure-summary
              (actions/create-coverage-type-action
               (state system)
               (coverage-type-signals
                policy-id
                {:icon "snoico/not-registered"}))))))))

(deftest required-coverage-type-create-confirmation-test
  (testing "requires the current exact count before adding a required type"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-required-create-confirmation")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [all-coverage-ids]} (seed-impact-coverages! conn policy-id)]
        (is (= {:prompt {:transact?          false
                         :state-path         [:insurance-policy-settings
                                              :coverage-type-create]
                         :impact-count       3
                         :confirmation-count ""
                         :error-keys         #{}
                         :top-error          nil}
                :confirmed {:transact?   true
                            :icon        :phosphor/shield
                            :required?   true
                            :coverage-ids all-coverage-ids}
                :stale {:transact?          false
                        :state-path         [:insurance-policy-settings
                                             :coverage-type-create]
                        :impact-count       3
                        :confirmation-count "2"
                        :error-keys         #{:confirmation-count :_top}
                        :top-error
                        [:insurance.policy-settings/error-stale-impact-count]}}
               {:prompt
                (coverage-type-confirmation-summary
                 (actions/create-coverage-type-action
                  (state system)
                  (coverage-type-signals policy-id {:required true})))
                :confirmed
                (coverage-type-transaction-summary
                 (actions/create-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:required          true
                    :confirmationCount "3"})))
                :stale
                (coverage-type-confirmation-summary
                 (actions/create-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:required          true
                    :confirmationCount "2"})))}))))))

(deftest optional-coverage-type-band-backfill-confirmation-test
  (testing "confirms and adds an optional type only to band instruments"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-optional-band-confirmation")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [band-coverage-ids]} (seed-impact-coverages! conn policy-id)]
        (is (= {:prompt {:transact?          false
                         :state-path         [:insurance-policy-settings
                                              :coverage-type-create]
                         :impact-count       2
                         :confirmation-count ""
                         :error-keys         #{}
                         :top-error          nil}
                :confirmed {:transact?   true
                            :icon        :phosphor/shield
                            :required?   false
                            :coverage-ids band-coverage-ids}}
               {:prompt
                (coverage-type-confirmation-summary
                 (actions/create-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:addToBandInstruments true})))
                :confirmed
                (coverage-type-transaction-summary
                 (actions/create-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:addToBandInstruments true
                    :confirmationCount    "2"})))}))))))

(deftest optional-to-required-coverage-type-confirmation-test
  (testing "recounts missing coverages before making an existing type required"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-required-update-confirmation")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [missing-type-ids type-id]}
            (seed-impact-coverages! conn policy-id)]
        (is (= {:prompt {:transact?          false
                         :state-path         [:insurance-policy-settings
                                              :coverage-type]
                         :impact-count       2
                         :confirmation-count ""
                         :error-keys         #{}
                         :top-error          nil}
                :confirmed {:transact?   true
                            :icon        :phosphor/shield
                            :required?   true
                            :coverage-ids missing-type-ids}}
               {:prompt
                (coverage-type-confirmation-summary
                 (actions/update-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:typeId   (str type-id)
                    :required true})))
                :confirmed
                (coverage-type-transaction-summary
                 (actions/update-coverage-type-action
                  (state system)
                  (coverage-type-signals
                   policy-id
                   {:typeId           (str type-id)
                    :required         true
                    :confirmationCount "2"})))}))))))

(deftest required-to-optional-coverage-type-test
  (testing "keeps existing coverage links when a type becomes optional"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-required-to-optional")
          policy-id  (random-uuid)
          type-id    (random-uuid)
          coverage-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (let [seed-result
            (try
              (test-support/seed-policy!
               conn
               policy-id
               {:coverage-types
                [{:type-id        type-id
                  :name           "Required"
                  :description    "Required coverage"
                  :premium-factor 1.0M
                  :icon           :phosphor/shield
                  :required?      true}]})
              :accepted
              (catch Exception _
                :rejected))]
        (is (= :accepted seed-result))
        (when (= :accepted seed-result)
          @(d/transact
            conn
            [{:db/id                           "required-coverage"
              :instrument.coverage/coverage-id coverage-id
              :instrument.coverage/types
              [[:insurance.coverage.type/type-id type-id]]
              :instrument.coverage/private?    true
              :instrument.coverage/status
              :instrument.coverage.status/reviewed
              :instrument.coverage/change
              :instrument.coverage.change/none}
             [:db/add [:insurance.policy/policy-id policy-id]
              :insurance.policy/covered-instruments
              "required-coverage"]])
          (let [effects (actions/update-coverage-type-action
                         (state system)
                         (coverage-type-signals
                          policy-id
                          {:typeId   (str type-id)
                           :required false}))
                tx-data (transaction-data effects)]
            (is (= {:metadata {:transact?   true
                               :icon        :phosphor/shield
                               :required?   false
                               :coverage-ids #{}}
                    :coverage-retractions []}
                   {:metadata (coverage-type-transaction-summary effects)
                    :coverage-retractions
                    (filterv (fn [tx]
                               (and (vector? tx)
                                    (= :db/retract (first tx))
                                    (= :instrument.coverage/types
                                       (nth tx 2 nil))))
                             tx-data)}))))))))

(deftest save-exporter-action-test
  (testing "saves a versioned exporter and policy-owned role mappings"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-save-exporter")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [unused-type-id used-type-id]}
            (seed-coverage-types! conn policy-id)]
        (is (= {:transact?   true
                :exporter-id :insurance.exporter/inventory-xls-v1
                :mappings    {:overnight-vehicle    used-type-id
                              :unattended-building unused-type-id}}
               (exporter-transaction-summary
                (actions/save-exporter-action
                 (state system)
                 (exporter-signals
                  policy-id
                  "insurance.exporter/inventory-xls-v1"
                  [{:role           "overnight-vehicle"
                    :coverageTypeId (str used-type-id)}
                   {:role           "unattended-building"
                    :coverageTypeId (str unused-type-id)}])))))))))

(deftest save-exporter-validation-test
  (testing "validates exporter IDs, required roles, uniqueness, and policy ownership"
    (let [{:keys [conn member-id] :as system}
          (tc/new-system "insurance-settings-exporter-validation")
          policy-id (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [foreign-type-id unused-type-id used-type-id]}
            (seed-coverage-types! conn policy-id)
            summarize
            (fn [exporter-id mappings]
              (exporter-failure-summary
               (actions/save-exporter-action
                (state system)
                (exporter-signals policy-id exporter-id mappings))))]
        (is (= {:unknown
                {:transact?      false
                 :state-path     [:insurance-policy-settings :exporter]
                 :error-keys     #{:exporter-id :_top}
                 :exporter-error
                 [:insurance.policy-settings/error-invalid-exporter]
                 :mapping-error  nil
                 :top-error      [:error/form-has-errors]}
                :incomplete
                {:transact?      false
                 :state-path     [:insurance-policy-settings :exporter]
                 :error-keys     #{:mappings :_top}
                 :exporter-error nil
                 :mapping-error
                 [:insurance.policy-settings/error-incomplete-exporter-mapping]
                 :top-error      [:error/form-has-errors]}
                :foreign
                {:transact?      false
                 :state-path     [:insurance-policy-settings :exporter]
                 :error-keys     #{:mappings :_top}
                 :exporter-error nil
                 :mapping-error
                 [:insurance.policy-settings/error-invalid-exporter-mapping]
                 :top-error      [:error/form-has-errors]}
                :duplicate
                {:transact?      false
                 :state-path     [:insurance-policy-settings :exporter]
                 :error-keys     #{:mappings :_top}
                 :exporter-error nil
                 :mapping-error
                 [:insurance.policy-settings/error-duplicate-exporter-role]
                 :top-error      [:error/form-has-errors]}}
               {:unknown (summarize "insurance.exporter/unknown" [])
                :incomplete
                (summarize
                 "insurance.exporter/inventory-xls-v1"
                 [{:role           "overnight-vehicle"
                   :coverageTypeId (str used-type-id)}])
                :foreign
                (summarize
                 "insurance.exporter/inventory-xls-v1"
                 [{:role           "overnight-vehicle"
                   :coverageTypeId (str used-type-id)}
                  {:role           "unattended-building"
                   :coverageTypeId (str foreign-type-id)}])
                :duplicate
                (summarize
                 "insurance.exporter/inventory-xls-v1"
                 [{:role           "overnight-vehicle"
                   :coverageTypeId (str used-type-id)}
                  {:role           "overnight-vehicle"
                   :coverageTypeId (str unused-type-id)}
                  {:role           "unattended-building"
                   :coverageTypeId (str unused-type-id)}])}))))))

(deftest category-factor-create-update-delete-action-test
  (testing "creates, updates, and deletes policy category factors"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-category-factor-actions")
          policy-id                           (random-uuid)
          policy-ref                          [:insurance.policy/policy-id policy-id]]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [new-category-id unused-category-id unused-factor-id used-factor-id]} (seed-category-factors! conn policy-id)
            unused-factor-ref [:insurance.category.factor/category-factor-id unused-factor-id]
            create-effects    (actions/create-category-factor-action
                               (state system)
                               (category-factor-signals policy-id
                                                        {:categoryId (str new-category-id)}))
            [[_ create-tx create-opts] create-clear create-state] create-effects
            [new-factor-tx policy-add-tx create-audit-tx] create-tx]
        (is (= {:create {:transact?      true
                         :opts           {}
                         :factor-id?     true
                         :factor-tx      {:insurance.category.factor/category [:instrument.category/category-id new-category-id]
                                          :insurance.category.factor/factor   0.35M}
                         :policy-add     [:db/add policy-ref :insurance.policy/category-factors]
                         :same-tempid?   true
                         :audit          [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]
                         :clear-loading? true
                         :clear-state    [:app.datastar/assoc-state
                                          [:insurance-policy-settings :category-factor-create]
                                          false]}
                :update [[:db/transact
                          (support/with-audit
                            [[:db/add unused-factor-ref :insurance.category.factor/factor 0.45M]]
                            member-id)
                          {}]
                         support/clear-loading
                         [:app.datastar/assoc-state [:insurance-policy-settings :category-factor] false]]
                :delete [[:db/transact
                          (support/with-audit
                            [[:db/retract policy-ref :insurance.policy/category-factors unused-factor-ref]
                             [:db/retractEntity unused-factor-ref]]
                            member-id)
                          {}]
                         support/clear-loading
                         [:app.datastar/assoc-state
                          [:insurance-policy-settings :category-factor-delete]
                          false]]
                :delete-used {:transact?      false
                              :clear-loading? true
                              :state-path     [:insurance-policy-settings :category-factor-delete]
                              :submitted      {:category-factor-id used-factor-id}
                              :error-keys     #{:_top}
                              :top-error      [:insurance.policy-settings/error-category-factor-in-use]}}
               {:create {:transact?      (boolean (some transact-effect? create-effects))
                         :opts           create-opts
                         :factor-id?     (uuid? (:insurance.category.factor/category-factor-id new-factor-tx))
                         :factor-tx      (dissoc new-factor-tx :db/id :insurance.category.factor/category-factor-id)
                         :policy-add     (subvec (vec policy-add-tx) 0 3)
                         :same-tempid?   (= (:db/id new-factor-tx) (nth policy-add-tx 3))
                         :audit          create-audit-tx
                         :clear-loading? (= support/clear-loading create-clear)
                         :clear-state    create-state}
                :update (actions/update-category-factor-action
                         (state system)
                         (category-factor-signals policy-id
                                                  {:categoryFactorId (str unused-factor-id)
                                                   :categoryId       (str unused-category-id)
                                                   :factor           "0.45"}))
                :delete (actions/delete-category-factor-action
                         (state system)
                         {:targetid (str unused-factor-id)})
                :delete-used (category-factor-failure-summary
                              (actions/delete-category-factor-action
                               (state system)
                               {:targetid (str used-factor-id)}))}))))))

(deftest category-factor-validation-test
  (testing "validates category factor fields, duplicate categories, and policy membership"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-category-factor-validation")
          policy-id                           (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [foreign-factor-id unused-factor-id used-category-id]} (seed-category-factors! conn policy-id)]
        (is (= {:invalid-create {:transact?      false
                                 :clear-loading? true
                                 :state-path     [:insurance-policy-settings :category-factor-create]
                                 :submitted      {:policy-id   policy-id
                                                  :category-id nil
                                                  :factor      "nope"}
                                 :error-keys     #{:category-id :factor :_top}
                                 :top-error      [:error/form-has-errors]}
                :duplicate-create {:transact?      false
                                   :clear-loading? true
                                   :state-path     [:insurance-policy-settings :category-factor-create]
                                   :submitted      {:policy-id   policy-id
                                                    :category-id used-category-id
                                                    :factor      "0.1"}
                                   :error-keys     #{:category-id :_top}
                                   :top-error      [:error/form-has-errors]}
                :invalid-update {:transact?      false
                                 :clear-loading? true
                                 :state-path     [:insurance-policy-settings :category-factor]
                                 :submitted      {:policy-id           policy-id
                                                  :category-factor-id  unused-factor-id
                                                  :category-id         used-category-id
                                                  :factor              "-1"}
                                 :error-keys     #{:factor :_top}
                                 :top-error      [:error/form-has-errors]}
                :foreign-update {:transact?      false
                                 :clear-loading? true
                                 :state-path     [:insurance-policy-settings :category-factor]
                                 :submitted      {:policy-id           policy-id
                                                  :category-factor-id  foreign-factor-id
                                                  :category-id         used-category-id
                                                  :factor              "0.5"}
                                 :error-keys     #{:_top}
                                 :top-error      [:insurance.policy-settings/error-category-factor-not-found]}}
               {:invalid-create (category-factor-failure-summary
                                 (actions/create-category-factor-action
                                  (state system)
                                  (category-factor-signals policy-id
                                                           {:categoryId ""
                                                            :factor     "nope"})))
                :duplicate-create (category-factor-failure-summary
                                   (actions/create-category-factor-action
                                    (state system)
                                    (category-factor-signals policy-id
                                                             {:categoryId (str used-category-id)
                                                              :factor     "0.1"})))
                :invalid-update (category-factor-failure-summary
                                 (actions/update-category-factor-action
                                  (state system)
                                  (category-factor-signals policy-id
                                                           {:categoryFactorId (str unused-factor-id)
                                                            :categoryId       (str used-category-id)
                                                            :factor           "-1"})))
                :foreign-update (category-factor-failure-summary
                                 (actions/update-category-factor-action
                                  (state system)
                                  (category-factor-signals policy-id
                                                           {:categoryFactorId (str foreign-factor-id)
                                                            :categoryId       (str used-category-id)
                                                            :factor           "0.5"})))}))))))

(deftest category-factor-create-validation-keeps-dialog-open-test
  (testing "keeps the create dialog open so validation errors are visible"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-category-factor-create-open")
          policy-id                           (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (seed-category-factors! conn policy-id)
      (let [[_ _ value] (assoc-state-effect
                         (actions/create-category-factor-action
                          (state system)
                          (category-factor-signals policy-id
                                                   {:categoryId ""
                                                    :factor     "bad"})))]
        (is (= {:open       true
                :error-keys #{:category-id :factor :_top}}
               {:open       (:open value)
                :error-keys (set (keys (:_error value)))}))))))

(deftest category-factor-authorization-test
  (testing "rejects category factor mutations for non-team members and frozen policies"
    (let [{draft-conn :conn :as draft-system} (tc/new-system "insurance-settings-category-factor-not-team")
          {frozen-conn :conn frozen-member-id :member-id :as frozen-system}
          (tc/new-system "insurance-settings-category-factor-frozen")
          draft-policy-id  (random-uuid)
          frozen-policy-id (random-uuid)]
      (seed-policy! draft-conn draft-policy-id :insurance.policy.status/draft)
      (seed-insurance-team! frozen-conn frozen-member-id)
      (seed-policy! frozen-conn frozen-policy-id :insurance.policy.status/sent)
      (let [{draft-new-category-id :new-category-id} (seed-category-factors! draft-conn draft-policy-id)
            {:keys [unused-factor-id used-category-id]} (seed-category-factors! frozen-conn frozen-policy-id)]
        (is (= {:create-not-team {:transact?      false
                                  :clear-loading? true
                                  :state-path     [:insurance-policy-settings :category-factor-create]
                                  :submitted      {:policy-id   draft-policy-id
                                                   :category-id draft-new-category-id
                                                   :factor      "0.35"}
                                  :error-keys     #{:_top}
                                  :top-error      [:insurance.policy-settings/error-not-allowed]}
                :update-frozen {:transact?      false
                                :clear-loading? true
                                :state-path     [:insurance-policy-settings :category-factor]
                                :submitted      {:policy-id           frozen-policy-id
                                                 :category-factor-id  unused-factor-id
                                                 :category-id         used-category-id
                                                 :factor              "0.5"}
                                :error-keys     #{:_top}
                                :top-error      [:insurance.policy-settings/error-frozen-policy]}
                :delete-frozen {:transact?      false
                                :clear-loading? true
                                :state-path     [:insurance-policy-settings :category-factor-delete]
                                :submitted      {:category-factor-id unused-factor-id}
                                :error-keys     #{:_top}
                                :top-error      [:insurance.policy-settings/error-frozen-policy]}}
               {:create-not-team (category-factor-failure-summary
                                  (actions/create-category-factor-action
                                   (state draft-system)
                                   (category-factor-signals draft-policy-id
                                                            {:categoryId (str draft-new-category-id)})))
                :update-frozen (category-factor-failure-summary
                                (actions/update-category-factor-action
                                 (state frozen-system)
                                 (category-factor-signals frozen-policy-id
                                                          {:categoryFactorId (str unused-factor-id)
                                                           :categoryId       (str used-category-id)
                                                           :factor           "0.5"})))
                :delete-frozen (category-factor-failure-summary
                                (actions/delete-category-factor-action
                                 (state frozen-system)
                                 {:targetid (str unused-factor-id)}))}))))))

(deftest category-factor-dialog-actions-test
  (testing "opens and closes create and edit category factor dialog state"
    (let [{:keys [conn member-id] :as system} (tc/new-system "insurance-settings-category-factor-dialogs")
          policy-id                           (random-uuid)]
      (seed-insurance-team! conn member-id)
      (seed-policy! conn policy-id :insurance.policy.status/draft)
      (let [{:keys [unused-category-id unused-factor-id]} (seed-category-factors! conn policy-id)]
        (is (= {:open-create [support/clear-loading
                              [:app.datastar/assoc-state
                               [:insurance-policy-settings :category-factor-create]
                               {:open        true
                                :policy-id   policy-id
                                :category-id ""
                                :factor      ""}]]
                :close-create [support/clear-loading
                               [:app.datastar/assoc-state
                                [:insurance-policy-settings :category-factor-create]
                                false]]
                :open-edit [support/clear-loading
                            [:app.datastar/assoc-state
                             [:insurance-policy-settings :category-factor]
                             {:policy-id          policy-id
                              :category-factor-id unused-factor-id
                              :category-id        unused-category-id
                              :category-name      "Woodwind"
                              :factor             0.20M}]]
                :close-edit [support/clear-loading
                             [:app.datastar/assoc-state
                              [:insurance-policy-settings :category-factor]
                              false]]}
               {:open-create (actions/open-category-factor-create-action
                              (state system)
                              {:targetid (str policy-id)})
                :close-create (actions/close-category-factor-create-action (state system) {})
                :open-edit (actions/open-category-factor-edit-action
                            (state system)
                            {:targetid (str unused-factor-id)})
                :close-edit (actions/close-category-factor-edit-action (state system) {})}))))))

(deftest settings-actions-are-registered-test
  (is (every? #(contains? insurance.actions/actions %)
              [:app.insurance.policy.settings.actions/save-policy-details
               :app.insurance.policy.settings.actions/open-coverage-type-create
               :app.insurance.policy.settings.actions/close-coverage-type-create
               :app.insurance.policy.settings.actions/create-coverage-type
               :app.insurance.policy.settings.actions/open-coverage-type-edit
               :app.insurance.policy.settings.actions/close-coverage-type-edit
               :app.insurance.policy.settings.actions/update-coverage-type
               :app.insurance.policy.settings.actions/delete-coverage-type
               :app.insurance.policy.settings.actions/save-exporter
               :app.insurance.policy.settings.actions/open-category-factor-create
               :app.insurance.policy.settings.actions/close-category-factor-create
               :app.insurance.policy.settings.actions/create-category-factor
               :app.insurance.policy.settings.actions/open-category-factor-edit
               :app.insurance.policy.settings.actions/close-category-factor-edit
               :app.insurance.policy.settings.actions/update-category-factor
               :app.insurance.policy.settings.actions/delete-category-factor])))
