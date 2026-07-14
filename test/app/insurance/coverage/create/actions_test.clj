(ns app.insurance.coverage.create.actions-test
  (:require
   [app.insurance.coverage.create.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [app.queries :as q]
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
     [:insurance/error-edit-frozen-policy] "Cannot update instrument and coverage on a policy that is not in draft status"
     [:insurance/error-invalid-number] (str (first args) " must be a whole number greater than zero.")
     [:insurance/error-invalid-coverage-type] "Please choose valid coverage types."
     [:instrument/owner] "Owner"
     [:instrument/category] "Category"
     [:instrument/name] "Instrument Name"
     [:instrument/make] "Make"
     [:insurance/item-count] "Count"
     [:insurance/value] "Value"
     [:band-private] "Band or private"
     (name (peek k)))))

(defn new-system []
  (assoc (tc/new-system "insurance-coverage-create-actions")
         :env {:app-base-url "https://example.test"}))

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn state-for [{:keys [conn env member-id]}]
  {:db                (d/db conn)
   :env               env
   :tr                tr
   :current-member-id member-id})

(defn seed-step1!
  [conn {:keys [policy-status instrument?]
         :or   {policy-status :insurance.policy.status/draft}}]
  (let [owner-id      (random-uuid)
        category-id   (random-uuid)
        instrument-id (random-uuid)
        policy-id     (random-uuid)]
    @(d/transact
      conn
      (cond->
       [{:db/id            "owner"
         :member/member-id owner-id
         :member/name      "Owner"
         :member/active?   true}
        {:db/id                           "category"
         :instrument.category/category-id category-id
         :instrument.category/name        "Brass"
         :instrument.category/code        "1"}
        {:insurance.policy/policy-id       policy-id
         :insurance.policy/name            "Policy"
         :insurance.policy/status          policy-status
         :insurance.policy/currency        :currency/EUR
         :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
         :insurance.policy/effective-until #inst "2027-01-01T00:00:00.000-00:00"
         :insurance.policy/premium-factor  0.01M}]
        instrument?
        (conj {:instrument/instrument-id instrument-id
               :instrument/name          "Old Trumpet"
               :instrument/owner         "owner"
               :instrument/category      "category"
               :instrument/make          "Old Make"
               :instrument/model         "Old Model"
               :instrument/serial-number "SN-1"
               :instrument/build-year    "1980"
               :instrument/description   "Old description"})))
    (cond-> {:owner-id    owner-id
             :category-id category-id
             :policy-id   policy-id}
      instrument? (assoc :instrument-id instrument-id))))

(defn signals-for [{:keys [owner-id category-id instrument-id policy-id]}]
  {:coverage-create {:policy-id        (str policy-id)
                     :instrument-id    (if instrument-id (str instrument-id) "")
                     :redirect         "/return"
                     :instrument-name  "New Trumpet"
                     :owner-member-id  (str owner-id)
                     :category-id      (str category-id)
                     :make             "Yamaha"
                     :model            "YTR"
                     :serial-number    "SN-2"
                     :build-year       "1999"
                     :description      "Updated description"}})

(defn transact-effect [effects]
  (first (filter #(= :db/transact (first %)) effects)))

(defn tx-data [effects]
  (second (transact-effect effects)))

(defn tx-set [effects]
  (set (tx-data effects)))

(defn redirects [effects]
  (filterv #(= :app.datastar/redirect (first %)) effects))

(defn instrument-tx [effects]
  (first (filter :instrument/instrument-id (tx-data effects))))

(deftest save-instrument-step-action-creates-instrument-and-redirects-test
  (testing "valid step 1 signals create an instrument and redirect to step 2"
    (let [{:keys [conn member-id] :as system} (new-system)
          fixture (dissoc (seed-step1! conn {}) :instrument-id)
          effects (actions/save-instrument-step-action (state-for system) (signals-for fixture))
          tx      (instrument-tx effects)
          created-id (:instrument/instrument-id tx)]
      (is (= {:transact-effects 1
              :opts             {}
              :redirects        [[:app.datastar/redirect
                                  (urls/link-coverage-create2 (:policy-id fixture) created-id "/return")]]
              :clear-loading?   false
              :audit?           true
              :created-id?      true}
             {:transact-effects (count (filter #(= :db/transact (first %)) effects))
              :opts             (nth (transact-effect effects) 2)
              :redirects        (redirects effects)
              :clear-loading?   (contains? (set effects) support/clear-loading)
              :audit?           (contains? (tx-set effects)
                                           [:db/add "datomic.tx" :audit/user [:member/member-id member-id]])
              :created-id?      (uuid? created-id)}))
      (is (= {:db/id                    "instrument"
              :instrument/name          "New Trumpet"
              :instrument/owner         [:member/member-id (:owner-id fixture)]
              :instrument/category      [:instrument.category/category-id (:category-id fixture)]
              :instrument/make          "Yamaha"
              :instrument/model         "YTR"
              :instrument/serial-number "SN-2"
              :instrument/build-year    "1999"
              :instrument/description   "Updated description"}
             (dissoc tx :instrument/instrument-id)))
      (is (contains? (tx-set effects)
                     [:db/add "instrument" :instrument/images-share-url
                      (urls/absolute-link-instrument-public (:env system) created-id)])))))

(deftest save-instrument-step-action-updates-existing-instrument-test
  (testing "valid step 1 signals update the requested instrument and redirect with the same id"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step1! conn {:instrument? true})
          effects (actions/save-instrument-step-action (state-for system) (signals-for fixture))]
      (is (= {:instrument {:instrument/instrument-id (:instrument-id fixture)
                           :instrument/name          "New Trumpet"
                           :instrument/owner         [:member/member-id (:owner-id fixture)]
                           :instrument/category      [:instrument.category/category-id (:category-id fixture)]
                           :instrument/make          "Yamaha"
                           :instrument/model         "YTR"
                           :instrument/serial-number "SN-2"
                           :instrument/build-year    "1999"
                           :instrument/description   "Updated description"}
              :redirects  [[:app.datastar/redirect
                            (urls/link-coverage-create2 (:policy-id fixture) (:instrument-id fixture) "/return")]]}
             {:instrument (select-keys (instrument-tx effects)
                                       [:instrument/instrument-id
                                        :instrument/name
                                        :instrument/owner
                                        :instrument/category
                                        :instrument/make
                                        :instrument/model
                                        :instrument/serial-number
                                        :instrument/build-year
                                        :instrument/description])
              :redirects  (redirects effects)}))
      (is (contains? (tx-set effects)
                     [:db/add [:instrument/instrument-id (:instrument-id fixture)]
                      :instrument/images-share-url
                      (urls/absolute-link-instrument-public (:env system) (:instrument-id fixture))])))))

(deftest save-instrument-step-action-validates-required-fields-test
  (testing "required fields return errors and no transaction"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step1! conn {})
          signals (-> (signals-for fixture)
                      (assoc-in [:coverage-create :owner-member-id] "")
                      (assoc-in [:coverage-create :category-id] "")
                      (assoc-in [:coverage-create :instrument-name] "")
                      (assoc-in [:coverage-create :make] ""))
          effects (actions/save-instrument-step-action (state-for system) signals)]
      (is (= {:transact? false
              :errors    {:owner-member-id {:error "Owner is required."}
                          :category-id     {:error "Category is required."}
                          :instrument-name {:error "Instrument Name is required."}
                          :make            {:error "Make is required."}
                          :_top            {:error "Please fix the errors in the form."}}}
             {:transact? (boolean (transact-effect effects))
              :errors    (get-in (first (filter #(= :app.datastar/assoc-state (first %)) effects)) [2 :_error])})))))

(deftest save-instrument-step-action-rejects-frozen-policy-test
  (testing "non-draft policies return a top-level form error and no transaction"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step1! conn {:policy-status :insurance.policy.status/active})
          effects (actions/save-instrument-step-action (state-for system) (signals-for fixture))]
      (is (= {:transact? false
              :errors    {:_top {:error "Cannot update instrument and coverage on a policy that is not in draft status"}}}
             {:transact? (boolean (transact-effect effects))
              :errors    (get-in (first (filter #(= :app.datastar/assoc-state (first %)) effects)) [2 :_error])})))))

(deftest validate-instrument-field-action-test
  (testing "one representative step 1 field is validated into page state"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step1! conn {})
          signals (-> (signals-for fixture)
                      (assoc-in [:coverage-create :instrument-name] "")
                      (assoc-in [:coverage-create :validate-field] "instrument-name"))]
      (is (= [[:app.datastar/merge-state [:coverage-create]
               (dissoc (:coverage-create signals) :validate-field)]
              [:app.datastar/assoc-state
               [:coverage-create :_error :instrument-name]
               {:error "Instrument Name is required."}]]
             (actions/validate-instrument-field-action (state-for system) signals))))))

(defn seed-step3!
  [conn {:keys [coverage-types? policy-status]
         :or   {coverage-types? true
                policy-status  :insurance.policy.status/draft}}]
  (let [owner-id      (random-uuid)
        category-id   (random-uuid)
        instrument-id (random-uuid)
        policy-id     (random-uuid)
        type-a-id     (random-uuid)
        type-b-id     (random-uuid)
        owner          {:db/id            "coverage-owner"
                        :member/member-id owner-id
                        :member/name      "Coverage Owner"
                        :member/active?   true}
        category       {:db/id                           "coverage-category"
                        :instrument.category/category-id category-id
                        :instrument.category/name        "Brass"
                        :instrument.category/code        "1"}
        instrument     {:instrument/instrument-id instrument-id
                        :instrument/name          "Test Trumpet"
                        :instrument/owner         "coverage-owner"
                        :instrument/category      "coverage-category"
                        :instrument/make          "Yamaha"
                        :instrument/model         "YTR-8335"}
        type-a          {:db/id                                  "coverage-type-a"
                         :insurance.coverage.type/type-id        type-a-id
                         :insurance.coverage.type/name           "Base"
                         :insurance.coverage.type/description    "Base coverage"
                         :insurance.coverage.type/premium-factor 0.01M}
        type-b          {:db/id                                  "coverage-type-b"
                         :insurance.coverage.type/type-id        type-b-id
                         :insurance.coverage.type/name           "Extended"
                         :insurance.coverage.type/description    "Extended coverage"
                         :insurance.coverage.type/premium-factor 0.02M}
        policy          (cond-> {:insurance.policy/policy-id       policy-id
                                 :insurance.policy/name            "Coverage Policy"
                                 :insurance.policy/status          policy-status
                                 :insurance.policy/currency        :currency/EUR
                                 :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
                                 :insurance.policy/effective-until #inst "2027-01-01T00:00:00.000-00:00"
                                 :insurance.policy/premium-factor  0.01M}
                          coverage-types?
                          (assoc :insurance.policy/coverage-types
                                 ["coverage-type-a" "coverage-type-b"]))
        tx-data         (cond-> [owner category instrument]
                          coverage-types? (into [type-a type-b])
                          true (conj policy))]
    @(d/transact conn tx-data)
    (let [policy-type-ids (mapv :insurance.coverage.type/type-id
                                (:insurance.policy/coverage-types
                                 (q/retrieve-policy (d/db conn) policy-id)))]
      {:owner-id       owner-id
       :category-id    category-id
       :instrument-id  instrument-id
       :policy-id      policy-id
       :policy-type-ids policy-type-ids
       :base-type-id   (first policy-type-ids)
       :extra-type-id  (second policy-type-ids)})))

(defn coverage-signals
  [{:keys [instrument-id policy-id]}]
  {:coverage-create {:policy-id      (str policy-id)
                     :instrument-id  (str instrument-id)
                     :redirect       "/return"
                     :item-count     "2"
                     :value          "1500"
                     :private-band   "band"
                     :coverage-types []
                     :insurer-id     " H-42 "}})

(defn coverage-tx [effects]
  (first (filter :instrument.coverage/coverage-id (tx-data effects))))

(defn added-coverage-type-ids [transactions]
  (->> transactions
       (keep (fn [tx]
               (when (and (vector? tx)
                          (= :db/add (first tx))
                          (= "covered_instrument" (second tx))
                          (= :instrument.coverage/types (nth tx 2 nil)))
                 (second (nth tx 3 nil)))))
       set))

(defn coverage-type-ids [effects]
  (added-coverage-type-ids (tx-data effects)))

(defn coverage-type
  [type-id name required?]
  {:insurance.coverage.type/type-id   type-id
   :insurance.coverage.type/name      name
   :insurance.coverage.type/required? required?})

(defn private-coverage-type-ids
  [policy-types selected-type-ids]
  (let [instrument-id (random-uuid)
        policy-id     (random-uuid)]
    (-> (actions/create-coverage-tx-data
         {:insurance-team-member? false
          :instrument-id          instrument-id
          :policy-id              policy-id
          :policy                 {:insurance.policy/policy-id policy-id
                                   :insurance.policy/coverage-types policy-types}}
         {:coverage-types (mapv str selected-type-ids)
          :item-count     "1"
          :private-band   "private"
          :value          "100"}
         (random-uuid))
        added-coverage-type-ids)))

(defn form-errors [effects]
  (get-in (first (filter #(= :app.datastar/assoc-state (first %)) effects))
          [2 :_error]))

(deftest create-coverage-action-creates-band-coverage-with-all-policy-types-test
  (testing "band coverage ignores submitted type ids and uses every policy type"
    (let [{:keys [conn member-id] :as system} (new-system)
          _           (seed-insurance-team! conn member-id)
          fixture     (seed-step3! conn {})
          unknown-id  (random-uuid)
          signals     (assoc-in (coverage-signals fixture)
                                [:coverage-create :coverage-types]
                                [(str unknown-id)])
          effects     (actions/create-coverage-action (state-for system) signals)
          coverage    (coverage-tx effects)
          coverage-id (:instrument.coverage/coverage-id coverage)]
      (is (= {:transact-count 1
              :opts           {}
              :coverage       {:db/id                           "covered_instrument"
                               :instrument.coverage/coverage-id coverage-id
                               :instrument.coverage/instrument  [:instrument/instrument-id (:instrument-id fixture)]
                               :instrument.coverage/value       1500M
                               :instrument.coverage/item-count  2
                               :instrument.coverage/status      :instrument.coverage.status/needs-review
                               :instrument.coverage/change      :instrument.coverage.change/new
                               :instrument.coverage/private?    false
                               :instrument.coverage/insurer-id  "H-42"}
              :coverage-types (set (:policy-type-ids fixture))
              :policy-link?   true
              :audit?         true
              :clear-loading? true
              :redirects      [[:app.datastar/redirect "/return"]]}
             {:transact-count (count (filter #(= :db/transact (first %)) effects))
              :opts           (nth (transact-effect effects) 2)
              :coverage       coverage
              :coverage-types (coverage-type-ids effects)
              :policy-link?   (contains? (tx-set effects)
                                         [:db/add
                                          [:insurance.policy/policy-id (:policy-id fixture)]
                                          :insurance.policy/covered-instruments
                                          "covered_instrument"])
              :audit?         (contains? (tx-set effects)
                                         [:db/add "datomic.tx" :audit/user [:member/member-id member-id]])
              :clear-loading? (contains? (set effects) support/clear-loading)
              :redirects      (redirects effects)}))
      (is (uuid? coverage-id)))))

(deftest create-coverage-action-protects-harmonia-id-test
  (testing "An ordinary member creates coverage without authority to set a Harmonia ID."
    (let [{:keys [conn] :as system} (new-system)
          fixture          (seed-step3! conn {})
          signals           (coverage-signals fixture)
          absent-effects    (actions/create-coverage-action
                             (state-for system)
                             (update signals :coverage-create dissoc :insurer-id))
          submitted-effects (actions/create-coverage-action (state-for system) signals)]
      (testing "Coverage creation still works when the Harmonia ID signal is absent."
        (is (= {:transact?  true
                :insurer-id nil}
               {:transact?  (some? (transact-effect absent-effects))
                :insurer-id (:instrument.coverage/insurer-id
                             (coverage-tx absent-effects))})))
      (testing "A manually submitted Harmonia ID is ignored for an ordinary member."
        (is (= {:transact?   true
                :insurer-id? false}
               {:transact?   (some? (transact-effect submitted-effects))
                :insurer-id? (contains? (coverage-tx submitted-effects)
                                        :instrument.coverage/insurer-id)}))))))

(deftest create-private-coverage-merges-explicit-required-types-test
  (testing "private coverage supports zero, one, or multiple required types in any order"
    (let [optional-a-id (random-uuid)
          optional-b-id (random-uuid)
          required-a-id (random-uuid)
          required-b-id (random-uuid)
          optional-a    (coverage-type optional-a-id "Optional A" false)
          optional-b    (coverage-type optional-b-id "Optional B" false)
          required-a    (coverage-type required-a-id "Required A" true)
          required-b    (coverage-type required-b-id "Required B" true)
          cases         [{:label    "zero required types"
                          :orders   [[optional-a optional-b]
                                     [optional-b optional-a]]
                          :selected []
                          :expected #{}}
                         {:label    "one required type"
                          :orders   [[optional-a required-a optional-b]
                                     [optional-b optional-a required-a]
                                     [required-a optional-b optional-a]]
                          :selected [optional-b-id]
                          :expected #{required-a-id optional-b-id}}
                         {:label    "multiple required types"
                          :orders   [[required-a optional-a required-b]
                                     [optional-a required-b required-a]
                                     [required-b required-a optional-a]]
                          :selected [optional-a-id]
                          :expected #{required-a-id required-b-id optional-a-id}}]]
      (doseq [{:keys [label orders selected expected]} cases
              policy-types orders]
        (is (= expected
               (private-coverage-type-ids policy-types selected))
            (str label " with policy order "
                 (mapv :insurance.coverage.type/name policy-types)))))))

(deftest create-coverage-action-rejects-invalid-coverage-type-test
  (testing "private coverage rejects type ids outside the current policy"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step3! conn {})
          signals (-> (coverage-signals fixture)
                      (assoc-in [:coverage-create :private-band] "private")
                      (assoc-in [:coverage-create :coverage-types] [(str (random-uuid))]))
          effects (actions/create-coverage-action (state-for system) signals)]
      (is (= {:transact? false
              :errors    {:coverage-types {:error "Please choose valid coverage types."}
                          :_top            {:error "Please fix the errors in the form."}}}
             {:transact? (boolean (transact-effect effects))
              :errors    (form-errors effects)})))))

(deftest create-coverage-action-validates-number-fields-test
  (testing "blank, zero, negative, fractional, and non-numeric values are rejected"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step3! conn {})]
      (doseq [[field invalid-value label] [[:item-count "" "Count"]
                                           [:item-count "0" "Count"]
                                           [:item-count "-1" "Count"]
                                           [:item-count "1.5" "Count"]
                                           [:item-count "many" "Count"]
                                           [:value "" "Value"]
                                           [:value "0" "Value"]
                                           [:value "-1" "Value"]
                                           [:value "1.5" "Value"]
                                           [:value "many" "Value"]]]
        (testing (str (name field) " rejects " (pr-str invalid-value))
          (let [signals (assoc-in (coverage-signals fixture)
                                  [:coverage-create field]
                                  invalid-value)
                effects (actions/create-coverage-action (state-for system) signals)]
            (is (= {:transact? false
                    :field-error {:error (str label " must be a whole number greater than zero.")}}
                   {:transact?   (boolean (transact-effect effects))
                    :field-error (get (form-errors effects) field)}))))))))

(deftest create-coverage-action-rejects-invalid-ownership-test
  (testing "ownership must be exactly band or private"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step3! conn {})
          signals (assoc-in (coverage-signals fixture)
                            [:coverage-create :private-band]
                            "borrowed")
          effects (actions/create-coverage-action (state-for system) signals)]
      (is (= {:transact? false
              :errors    {:private-band {:error "Band or private is required."}
                          :_top         {:error "Please fix the errors in the form."}}}
             {:transact? (boolean (transact-effect effects))
              :errors    (form-errors effects)})))))

(deftest create-coverage-action-rejects-invalid-context-test
  (testing "invalid ids, missing instruments, frozen policies, and policies without coverage types do not transact"
    (let [{:keys [conn] :as system} (new-system)
          missing-fixture  (seed-step3! conn {})
          frozen-fixture   (seed-step3! conn {:policy-status :insurance.policy.status/active})
          no-types-fixture (seed-step3! conn {:coverage-types? false})
          cases             [{:label   "malformed policy id"
                              :signals (assoc-in (coverage-signals missing-fixture)
                                                 [:coverage-create :policy-id]
                                                 "not-a-uuid")
                              :error   "Not Found"}
                             {:label   "missing instrument"
                              :signals (assoc-in (coverage-signals missing-fixture)
                                                 [:coverage-create :instrument-id]
                                                 (str (random-uuid)))
                              :error   "Not Found"}
                             {:label   "frozen policy"
                              :signals (coverage-signals frozen-fixture)
                              :error   "Cannot update instrument and coverage on a policy that is not in draft status"}
                             {:label   "policy without coverage types"
                              :signals (coverage-signals no-types-fixture)
                              :error   "Please choose valid coverage types."}]]
      (doseq [{:keys [label signals error]} cases]
        (testing label
          (let [effects (actions/create-coverage-action (state-for system) signals)]
            (is (= {:transact? false
                    :top-error error}
                   {:transact? (boolean (transact-effect effects))
                    :top-error (get-in (form-errors effects) [:_top :error])}))))))))

(deftest validate-coverage-field-action-test
  (testing "one representative step 3 field is validated into page state"
    (let [{:keys [conn] :as system} (new-system)
          fixture (seed-step3! conn {})
          signals (-> (coverage-signals fixture)
                      (assoc-in [:coverage-create :item-count] "0")
                      (assoc-in [:coverage-create :validate-field] "item-count"))
          params  (-> (:coverage-create signals)
                      (dissoc :validate-field)
                      (assoc :insurer-id "H-42"))]
      (is (= [[:app.datastar/merge-state [:coverage-create] params]
              [:app.datastar/assoc-state
               [:coverage-create :_error :item-count]
               {:error "Count must be a whole number greater than zero."}]]
             (actions/validate-coverage-field-action (state-for system) signals))))))
