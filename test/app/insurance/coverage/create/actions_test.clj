(ns app.insurance.coverage.create.actions-test
  (:require
   [app.insurance.coverage.create.actions :as actions]
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
     [:insurance/error-edit-frozen-policy] "Cannot update instrument and coverage on a policy that is not in draft status"
     [:instrument/owner] "Owner"
     [:instrument/category] "Category"
     [:instrument/name] "Instrument Name"
     [:instrument/make] "Make"
     (name (peek k)))))

(defn new-system []
  (assoc (tc/new-system "insurance-coverage-create-actions")
         :env {:app-base-url "https://example.test"}))

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
              :clear-loading?   true
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
