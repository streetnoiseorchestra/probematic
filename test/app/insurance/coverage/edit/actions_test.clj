(ns app.insurance.coverage.edit.actions-test
  (:require
   [app.insurance.coverage.edit.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn tr
  ([k]
   (tr k nil))
  ([k args]
   (case k
     [:error/form-has-errors] "Please fix the errors in the form."
     [:error/is-required] (str (first args) " is required.")
     [:error/not-found-title] "Not Found"
     [:error/not-allowed] "Not allowed"
     [:insurance/error-edit-frozen-policy] "Cannot update instrument and coverage on a policy that is not in draft status"
     [:insurance/error-invalid-coverage-type] "Please choose valid coverage types."
     [:insurance/error-invalid-number] (str (first args) " must be a whole number greater than zero.")
     [:instrument/owner] "Owner"
     [:instrument/category] "Category"
     [:instrument/name] "Instrument Name"
     [:instrument/make] "Make"
     [:insurance/item-count] "Count"
     [:insurance/value] "Value"
     [:band-private] "Band/Private"
     (name (peek k)))))

(defn new-system []
  (assoc (tc/new-system "insurance-coverage-edit-actions")
         :env {:app-base-url "https://example.test"}))

(defn state-for [{:keys [conn env member-id]}]
  {:db                (d/db conn)
   :env               env
   :tr                tr
   :current-member-id member-id})

(defn seed-coverage!
  [conn {:keys [policy-status coverage-status coverage-change private? coverage-type-tempids]
         :or   {policy-status        :insurance.policy.status/draft
                coverage-status      :instrument.coverage.status/reviewed
                coverage-change      :instrument.coverage.change/none
                private?             true
                coverage-type-tempids ["base-type"]}}]
  (let [owner-id      (random-uuid)
        alt-owner-id  (random-uuid)
        category-id   (random-uuid)
        alt-category-id (random-uuid)
        instrument-id (random-uuid)
        coverage-id   (random-uuid)
        policy-id     (random-uuid)
        base-type-id  (random-uuid)
        extra-type-id (random-uuid)]
    @(d/transact
      conn
      [{:db/id            "owner"
        :member/member-id owner-id
        :member/name      "Owner"
        :member/active?   true}
       {:db/id            "alt-owner"
        :member/member-id alt-owner-id
        :member/name      "Other Owner"
        :member/active?   true}
       {:db/id                         "category"
        :instrument.category/category-id category-id
        :instrument.category/name      "Brass"
        :instrument.category/code      "1"}
       {:db/id                         "alt-category"
        :instrument.category/category-id alt-category-id
        :instrument.category/name      "Woodwind"
        :instrument.category/code      "2"}
       {:db/id                                  "base-type"
        :insurance.coverage.type/type-id        base-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    "Base coverage"
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                                  "extra-type"
        :insurance.coverage.type/type-id        extra-type-id
        :insurance.coverage.type/name           "Overnight"
        :insurance.coverage.type/description    "Overnight coverage"
        :insurance.coverage.type/premium-factor 0.2M}
       {:db/id                    "instrument"
        :instrument/instrument-id instrument-id
        :instrument/name          "Old Trumpet"
        :instrument/owner         "owner"
        :instrument/category      "category"
        :instrument/make          "Old Make"
        :instrument/model         "Old Model"
        :instrument/serial-number "SN-1"
        :instrument/build-year    "1980"
        :instrument/description   "Old description"}
       {:db/id                           "coverage"
        :instrument.coverage/coverage-id coverage-id
        :instrument.coverage/instrument  "instrument"
        :instrument.coverage/types       coverage-type-tempids
        :instrument.coverage/private?    private?
        :instrument.coverage/value       100M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      coverage-status
        :instrument.coverage/change      coverage-change
        :instrument.coverage/insurer-id  "OLD-1"}
       {:insurance.policy/policy-id           policy-id
        :insurance.policy/name                "Policy"
        :insurance.policy/status              policy-status
        :insurance.policy/currency            :currency/EUR
        :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
        :insurance.policy/effective-until     #inst "2027-01-01T00:00:00.000-00:00"
        :insurance.policy/premium-factor      0.01M
        :insurance.policy/coverage-types      ["base-type" "extra-type"]
        :insurance.policy/covered-instruments ["coverage"]}])
    {:owner-id        owner-id
     :alt-owner-id    alt-owner-id
     :category-id     category-id
     :alt-category-id alt-category-id
     :instrument-id   instrument-id
     :coverage-id     coverage-id
     :policy-id       policy-id
     :base-type-id    base-type-id
     :extra-type-id   extra-type-id}))

(defn seed-insurance-team-member! [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn signals-for [{:keys [owner-id category-id instrument-id coverage-id policy-id base-type-id extra-type-id]}]
  {:coverage-edit {:policy-id        (str policy-id)
                   :coverage-id      (str coverage-id)
                   :instrument-id    (str instrument-id)
                   :instrument-name  "New Trumpet"
                   :owner-member-id  (str owner-id)
                   :category-id      (str category-id)
                   :make             "Yamaha"
                   :model            "YTR"
                   :serial-number    "SN-2"
                   :build-year       "1999"
                   :description      "Updated description"
                   :item-count       "2"
                   :value            "1200"
                   :private-band     "private"
                   :coverage-types   [(str base-type-id) (str extra-type-id)]
                   :insurer-id       "107641"}})

(defn transact-effect [effects]
  (first (filter #(= :db/transact (first %)) effects)))

(defn tx-data [effects]
  (second (transact-effect effects)))

(defn tx-set [effects]
  (set (tx-data effects)))

(defn redirects [effects]
  (filterv #(= :app.datastar/redirect (first %)) effects))

(deftest update-instrument-coverage-action-test
  (testing "a valid update returns one transaction and redirects to the coverage detail page"
    (let [{:keys [conn member-id] :as system} (new-system)
          fixture (seed-coverage! conn {})
          effects (actions/update-instrument-coverage-action (state-for system) (signals-for fixture))]
      (is (= {:transact-effects 1
              :redirects        [[:app.datastar/redirect (urls/link-coverage (:coverage-id fixture))]]
              :clear-loading?   true
              :audit?           true}
             {:transact-effects (count (filter #(= :db/transact (first %)) effects))
              :redirects        (redirects effects)
              :clear-loading?   (contains? (set effects) support/clear-loading)
              :audit?           (contains? (tx-set effects)
                                           [:db/add "datomic.tx" :audit/user [:member/member-id member-id]])}))))

  (testing "the update transaction includes instrument and coverage fields"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {})
          effects (actions/update-instrument-coverage-action (state-for system) (signals-for fixture))]
      (is (= {:instrument {:instrument/instrument-id (:instrument-id fixture)
                           :instrument/name          "New Trumpet"
                           :instrument/owner         [:member/member-id (:owner-id fixture)]
                           :instrument/category      [:instrument.category/category-id (:category-id fixture)]
                           :instrument/make          "Yamaha"
                           :instrument/model         "YTR"
                           :instrument/serial-number "SN-2"
                           :instrument/build-year    "1999"
                           :instrument/description   "Updated description"}
              :coverage-txs #{[:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)] :instrument.coverage/value 1200M]
                              [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)] :instrument.coverage/item-count 2]
                              [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)] :instrument.coverage/private? true]
                              [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)] :instrument.coverage/insurer-id "107641"]}}
             {:instrument (first (filter :instrument/instrument-id (tx-data effects)))
              :coverage-txs (set (filter #(and (vector? %)
                                               (= [:instrument.coverage/coverage-id (:coverage-id fixture)] (second %))
                                               (#{:instrument.coverage/value
                                                  :instrument.coverage/item-count
                                                  :instrument.coverage/private?
                                                  :instrument.coverage/insurer-id} (nth % 2)))
                                         (tx-data effects)))}))))

  (testing "upstream-visible changes mark existing coverage as needing review and changed"
    (let [cases [{:label "owner" :change-fn (fn [signals fixture]
                                              (assoc-in signals [:coverage-edit :owner-member-id] (str (:alt-owner-id fixture))))}
                 {:label "category" :change-fn (fn [signals fixture]
                                                 (assoc-in signals [:coverage-edit :category-id] (str (:alt-category-id fixture))))}
                 {:label "value" :change-fn (fn [signals _fixture]
                                              (assoc-in signals [:coverage-edit :value] "1300"))}
                 {:label "item count" :change-fn (fn [signals _fixture]
                                                   (assoc-in signals [:coverage-edit :item-count] "3"))}
                 {:label "private-band" :change-fn (fn [signals _fixture]
                                                     (assoc-in signals [:coverage-edit :private-band] "band"))}
                 {:label "coverage types" :change-fn (fn [signals fixture]
                                                       (assoc-in signals [:coverage-edit :coverage-types] [(str (:base-type-id fixture))]))}]
          results (mapv (fn [{:keys [label change-fn]}]
                          (let [{:keys [conn] :as system} (new-system)
                                fixture (seed-coverage! conn {:coverage-type-tempids ["base-type" "extra-type"]})
                                signals (change-fn (signals-for fixture) fixture)
                                effects (actions/update-instrument-coverage-action
                                         (state-for system)
                                         signals)]
                            {:label label
                             :status? (contains? (tx-set effects)
                                                 [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)]
                                                  :instrument.coverage/status
                                                  :instrument.coverage.status/needs-review])
                             :change? (contains? (tx-set effects)
                                                 [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)]
                                                  :instrument.coverage/change
                                                  :instrument.coverage.change/changed])}))
                        cases)]
      (is (= (mapv #(assoc % :status? true :change? true)
                   (mapv #(select-keys % [:label]) cases))
             results))))

  (testing "coverage that is already new keeps the new change marker"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {:coverage-change :instrument.coverage.change/new})
          effects (actions/update-instrument-coverage-action
                   (state-for system)
                   (assoc-in (signals-for fixture) [:coverage-edit :value] "1300"))]
      (is (= #{[:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)]
                :instrument.coverage/status
                :instrument.coverage.status/needs-review]
               [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)]
                :instrument.coverage/change
                :instrument.coverage.change/new]}
             (set (filter #(and (vector? %)
                                (= [:instrument.coverage/coverage-id (:coverage-id fixture)] (second %))
                                (#{:instrument.coverage/status :instrument.coverage/change} (nth % 2)))
                          (tx-data effects)))))))

  (testing "band instruments receive all policy coverage types"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {:coverage-type-tempids ["base-type"]})
          effects (actions/update-instrument-coverage-action
                   (state-for system)
                   (assoc-in (signals-for fixture) [:coverage-edit :private-band] "band"))]
      (is (contains? (tx-set effects)
                     [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)]
                      :instrument.coverage/types
                      [:insurance.coverage.type/type-id (:extra-type-id fixture)]]))))

  (testing "private instruments receive selected policy types and the first policy type"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {:coverage-type-tempids ["extra-type"]})
          effects (actions/update-instrument-coverage-action
                   (state-for system)
                   (-> (signals-for fixture)
                       (assoc-in [:coverage-edit :coverage-types] [(str (:extra-type-id fixture))])))]
      (is (contains? (tx-set effects)
                     [:db/add [:instrument.coverage/coverage-id (:coverage-id fixture)]
                      :instrument.coverage/types
                      [:insurance.coverage.type/type-id (:base-type-id fixture)]]))))

  (testing "non-draft policies return a top-level form error and no transaction"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {:policy-status :insurance.policy.status/active})
          effects (actions/update-instrument-coverage-action (state-for system) (signals-for fixture))]
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:coverage-edit]
               (assoc (:coverage-edit (signals-for fixture))
                      :_error {:_top {:error "Cannot update instrument and coverage on a policy that is not in draft status"}})]]
             effects))))

  (testing "validation errors are stored under coverage-edit and do not transact"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {})
          signals (-> (signals-for fixture)
                      (assoc-in [:coverage-edit :instrument-name] "")
                      (assoc-in [:coverage-edit :value] "0"))
          effects (actions/update-instrument-coverage-action (state-for system) signals)]
      (is (= {:transact? false
              :errors    {:instrument-name {:error "Instrument Name is required."}
                          :value           {:error "Value must be a whole number greater than zero."}
                          :_top            {:error "Please fix the errors in the form."}}}
             {:transact? (boolean (transact-effect effects))
              :errors    (get-in (first (filter #(= :app.datastar/assoc-state (first %)) effects)) [2 :_error])}))))

  (testing "validate-coverage-field-action validates one representative field"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {})
          signals (-> (signals-for fixture)
                      (assoc-in [:coverage-edit :instrument-name] "")
                      (assoc-in [:coverage-edit :validate-field] "instrument-name"))]
      (is (= [[:app.datastar/merge-state [:coverage-edit]
               (dissoc (:coverage-edit signals) :validate-field)]
              [:app.datastar/assoc-state
               [:coverage-edit :_error :instrument-name]
               {:error "Instrument Name is required."}]]
             (actions/validate-coverage-field-action (state-for system) signals))))))

(deftest delete-instrument-coverage-action-test
  (testing "delete returns no transaction for a non-insurance-team member"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-coverage! conn {})
          effects (actions/delete-instrument-coverage-action
                   (state-for system)
                   {:targetid (str (:coverage-id fixture))})]
      (is (= {:transact? false
              :clear-loading? true}
             {:transact?      (boolean (transact-effect effects))
              :clear-loading? (contains? (set effects) support/clear-loading)}))))

  (testing "delete returns a retract transaction and policy redirect for an insurance-team member"
    (let [{:keys [conn member-id] :as system} (new-system)
          fixture (seed-coverage! conn {})]
      (seed-insurance-team-member! conn member-id)
      (let [effects (actions/delete-instrument-coverage-action
                     (state-for system)
                     {:targetid (str (:coverage-id fixture))})]
        (is (= {:tx-data [[[:db/retractEntity [:instrument.coverage/coverage-id (:coverage-id fixture)]]
                           [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                          {}]
                :redirects [[:app.datastar/redirect (urls/link-policy (:policy-id fixture))]]}
               {:tx-data   (rest (transact-effect effects))
                :redirects (redirects effects)}))))))
