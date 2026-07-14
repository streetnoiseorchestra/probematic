(ns app.insurance.policy.workbench.queries-test
  (:require
   [app.insurance.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn seed-workbench-policy!
  [conn policy-id]
  (let [brass-id     (random-uuid)
        woodwind-id  (random-uuid)
        basic-type-id (random-uuid)
        extra-type-id (random-uuid)
        anna-id      (random-uuid)
        zoe-id       (random-uuid)
        alto-id      (random-uuid)
        bass-id      (random-uuid)
        cornet-id    (random-uuid)
        drum-id      (random-uuid)
        euphonium-id (random-uuid)]
    @(d/transact
      conn
      [{:db/id            "anna"
        :member/member-id anna-id
        :member/name      "Anna Alto"
        :member/nick      "alto-ally"
        :member/email     "anna.alto@example.test"
        :member/username  "aalto"}
       {:db/id            "zoe"
        :member/member-id zoe-id
        :member/name      "Zoe Zebra"
        :member/nick      "zebra-zo"
        :member/email     "zoe.zebra@example.test"
        :member/username  "zzebra"}
       {:db/id                           "brass"
        :instrument.category/category-id brass-id
        :instrument.category/name        "Brass"
        :instrument.category/code        "brass-workbench"}
       {:db/id                           "woodwind"
        :instrument.category/category-id woodwind-id
        :instrument.category/name        "Woodwind"
        :instrument.category/code        "woodwind-workbench"}
       {:db/id                                  "basic"
        :insurance.coverage.type/type-id        basic-type-id
        :insurance.coverage.type/name           "Worldwide touring"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                                  "extra"
        :insurance.coverage.type/type-id        extra-type-id
        :insurance.coverage.type/name           "Locked rehearsal storage"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 0.5M}
       {:db/id                    "alto-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Alto Horn"
        :instrument/owner         "anna"
        :instrument/category      "brass"
        :instrument/images        [{:image/image-id (random-uuid)}]}
       {:db/id                    "bass-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Bass Clarinet"
        :instrument/owner         "anna"
        :instrument/category      "woodwind"}
       {:db/id                    "cornet-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Cornet"
        :instrument/owner         "zoe"
        :instrument/category      "brass"}
       {:db/id                    "drum-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Drum Kit"
        :instrument/owner         "zoe"
        :instrument/category      "brass"
        :instrument/images        [{:image/image-id (random-uuid)}]}
       {:db/id                    "euphonium-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Euphonium"
        :instrument/owner         "zoe"
        :instrument/category      "woodwind"
        :instrument/images        [{:image/image-id (random-uuid)}]}
       {:db/id                           "alto-coverage"
        :instrument.coverage/coverage-id alto-id
        :instrument.coverage/instrument  "alto-instrument"
        :instrument.coverage/types       ["basic"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       1000M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  "H-100"
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:db/id                           "bass-coverage"
        :instrument.coverage/coverage-id bass-id
        :instrument.coverage/instrument  "bass-instrument"
        :instrument.coverage/types       ["basic" "extra"]
        :instrument.coverage/private?    true
        :instrument.coverage/value       2000M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/new}
       {:db/id                           "cornet-coverage"
        :instrument.coverage/coverage-id cornet-id
        :instrument.coverage/instrument  "cornet-instrument"
        :instrument.coverage/types       ["extra"]
        :instrument.coverage/private?    true
        :instrument.coverage/value       3000M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  ""
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/changed}
       {:db/id                           "drum-coverage"
        :instrument.coverage/coverage-id drum-id
        :instrument.coverage/instrument  "drum-instrument"
        :instrument.coverage/types       ["basic"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       4000M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  "H-400"
        :instrument.coverage/status      :instrument.coverage.status/coverage-active
        :instrument.coverage/change      :instrument.coverage.change/removed}
       {:db/id                           "euphonium-coverage"
        :instrument.coverage/coverage-id euphonium-id
        :instrument.coverage/instrument  "euphonium-instrument"
        :instrument.coverage/types       ["extra"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       500M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  "H-500"
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:insurance.policy/policy-id           policy-id
        :insurance.policy/name                "Insurance 2026"
        :insurance.policy/status              :insurance.policy.status/draft
        :insurance.policy/currency            :currency/EUR
        :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
        :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
        :insurance.policy/premium-factor      0.01M
        :insurance.policy/coverage-types      ["basic" "extra"]
        :insurance.policy/category-factors    [{:insurance.category.factor/category-factor-id (random-uuid)
                                                :insurance.category.factor/category           "brass"
                                                :insurance.category.factor/factor             0.01M}
                                               {:insurance.category.factor/category-factor-id (random-uuid)
                                                :insurance.category.factor/category           "woodwind"
                                                :insurance.category.factor/factor             0.02M}]
        :insurance.policy/covered-instruments ["alto-coverage"
                                               "bass-coverage"
                                               "cornet-coverage"
                                               "drum-coverage"
                                               "euphonium-coverage"]}])
    {:brass-id      brass-id
     :woodwind-id   woodwind-id
     :basic-type-id basic-type-id
     :extra-type-id extra-type-id
     :anna-id       anna-id
     :zoe-id        zoe-id
     :alto-id       alto-id
     :bass-id       bass-id
     :cornet-id     cornet-id
     :drum-id       drum-id
     :euphonium-id  euphonium-id}))

(defn configure-workbench-icons!
  [conn basic-type-id extra-type-id]
  (if (d/entid (d/db conn) :insurance.coverage.type/icon)
    (do
      @(d/transact
        conn
        [[:db/add [:insurance.coverage.type/type-id basic-type-id]
          :insurance.coverage.type/icon :phosphor/car-profile]
         [:db/add [:insurance.coverage.type/type-id extra-type-id]
          :insurance.coverage.type/icon :phosphor/warehouse]])
      :accepted)
    :missing))

(defn workbench
  [conn policy-id params]
  (queries/policy-workbench (d/db conn) policy-id params))

(defn row-names
  [workbench]
  (mapv :instrument-name (:rows workbench)))

(defn group-summary
  [workbench]
  (mapv #(select-keys % [:member-id :member-label :row-count])
        (:groups workbench)))

(defn category-names
  [workbench]
  (mapv :category-name (:available-categories workbench)))

(deftest default-workbench-flat-list-and-summarizes-test
  (testing "defaults to all coverages as a flat list with stable sorting and summaries"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-default")
          policy-id      (random-uuid)
          {:keys [basic-type-id extra-type-id]}
          (seed-workbench-policy! conn policy-id)
          icon-status (configure-workbench-icons!
                       conn
                       basic-type-id
                       extra-type-id)
          result      (workbench conn policy-id {})]
      (is (= {:icon-status              :accepted
              :view                     :all
              :filters                  {:member-q nil
                                         :category-ids #{}
                                         :coverage-type-ids #{}
                                         :ownership :all
                                         :missing-photos? false
                                         :missing-harmonia-id? false
                                         :workflow-statuses #{}
                                         :change-statuses #{}
                                         :value-filter nil
                                         :group :none}
              :editable?                true
              :available-category-names ["Brass" "Woodwind"]
              :row-names                ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
              :first-row-coverage-types
              [{:insurance.coverage.type/name "Worldwide touring"
                :insurance.coverage.type/icon :phosphor/car-profile
                :insurance.coverage.type/cost 0.1M}]
              :groups                   []
              :summary-counts           {:all 5
                                         :todo 2
                                         :missing-id 2
                                         :missing-photos 2
                                         :private 2
                                         :changed 3
                                         :new 1
                                         :removed 1}
              :totals                   {:total-instruments 5
                                         :total-insured-value 10500M
                                         :missing-photo-count 2
                                         :missing-insurer-id-count 2
                                         :private-count 2
                                         :band-count 3}}
             {:icon-status              icon-status
              :view                     (:view result)
              :filters                  (:filters result)
              :editable?                (:editable? result)
              :available-category-names (category-names result)
              :row-names                (row-names result)
              :first-row-coverage-types
              (mapv #(select-keys % [:insurance.coverage.type/name
                                     :insurance.coverage.type/icon
                                     :insurance.coverage.type/cost])
                    (:coverage-types (first (:rows result))))
              :groups                   (group-summary result)
              :summary-counts           (:summary-counts result)
              :totals                   (select-keys (:totals result)
                                                     [:total-instruments
                                                      :total-insured-value
                                                      :missing-photo-count
                                                      :missing-insurer-id-count
                                                      :private-count
                                                      :band-count])})))))

(deftest review-filter-aliases-test
  (testing "normalizes review queue compatibility filters"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-review-filter")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:todo-view       {:view :todo
                                :row-names ["Alto Horn" "Cornet"]}
              :missing-id-view {:view :missing-id
                                :row-names ["Bass Clarinet" "Cornet"]}}
             {:todo-view       (let [result (workbench conn policy-id {:review-filter "todo"})]
                                 {:view (:view result)
                                  :row-names (row-names result)})
              :missing-id-view (let [result (workbench conn policy-id {:review-filter "missing-id"})]
                                 {:view (:view result)
                                  :row-names (row-names result)})})))))

(deftest view-precedence-and-predefined-views-test
  (testing "view takes precedence over review-filter and each first-slice view filters rows"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-views")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:precedence {:view :private
                           :row-names ["Bass Clarinet" "Cornet"]}
              :views      {:all            ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
                           :todo           ["Alto Horn" "Cornet"]
                           :missing-id     ["Bass Clarinet" "Cornet"]
                           :missing-photos ["Bass Clarinet" "Cornet"]
                           :private        ["Bass Clarinet" "Cornet"]
                           :changed        ["Bass Clarinet" "Cornet" "Drum Kit"]
                           :new            ["Bass Clarinet"]
                           :removed        ["Drum Kit"]}}
             {:precedence (let [result (workbench conn policy-id {:view "private"
                                                                  :review-filter "todo"})]
                            {:view (:view result)
                             :row-names (row-names result)})
              :views      (into {}
                                (for [view [:all :todo :missing-id :missing-photos
                                            :private :changed :new :removed]]
                                  [view (row-names (workbench conn policy-id {:view (name view)}))]))})))))

(deftest view-presets-expose-filter-and-column-defaults-test
  (testing "preset views are backed by the same filter and column concepts as manual configuration"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-view-presets")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:todo       {:preset-filters {:workflow-statuses #{:needs-review}}
                           :column-ids     [:status
                                            :member
                                            :instrument
                                            :category
                                            :photos
                                            :harmonia-id
                                            :value
                                            :actions]
                           :row-names      ["Alto Horn" "Cornet"]}
              :missing-id {:preset-filters {:missing-harmonia-id? true}
                           :column-ids     [:status
                                            :member
                                            :instrument
                                            :category
                                            :harmonia-id
                                            :actions]
                           :row-names      ["Bass Clarinet" "Cornet"]}
              :changed    {:preset-filters {:change-statuses #{:changed :new :removed}}
                           :column-ids     [:status
                                            :member
                                            :instrument
                                            :category
                                            :value
                                            :cost
                                            :actions]
                           :row-names      ["Bass Clarinet" "Cornet" "Drum Kit"]}}
             (into {}
                   (for [view [:todo :missing-id :changed]
                         :let [result (workbench conn policy-id {:view (name view)})]]
                     [view {:preset-filters (:preset-filters result)
                            :column-ids     (:default-column-ids result)
                            :row-names      (row-names result)}])))))))

(deftest member-search-test
  (testing "matches member name, nickname, username, and email case-insensitively"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-member-search")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:by-member-name ["Alto Horn" "Bass Clarinet"]
              :by-nickname    ["Alto Horn" "Bass Clarinet"]
              :by-username    ["Cornet" "Drum Kit" "Euphonium"]
              :by-email       ["Cornet" "Drum Kit" "Euphonium"]
              :blank-search   ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]}
             {:by-member-name (row-names (workbench conn policy-id {:member-q "anna"}))
              :by-nickname    (row-names (workbench conn policy-id {:member-q "ALTO-ALLY"}))
              :by-username    (row-names (workbench conn policy-id {:member-q "ZZEBRA"}))
              :by-email       (row-names (workbench conn policy-id {:member-q "zoe.zebra@example"}))
              :blank-search   (row-names (workbench conn policy-id {:member-q "   "}))})))))

(deftest category-filtering-test
  (testing "accepts single, repeated, and comma-separated category id query values"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-categories")
          policy-id      (random-uuid)
          {:keys [brass-id woodwind-id]} (seed-workbench-policy! conn policy-id)]
      (is (= {:single          {:category-ids #{brass-id}
                                :row-names ["Alto Horn" "Cornet" "Drum Kit"]}
              :repeated        {:category-ids #{brass-id woodwind-id}
                                :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]}
              :comma-separated {:category-ids #{brass-id woodwind-id}
                                :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]}}
             {:single          (let [result (workbench conn policy-id {:category-id (str brass-id)})]
                                 {:category-ids (get-in result [:filters :category-ids])
                                  :row-names (row-names result)})
              :repeated        (let [result (workbench conn policy-id {:category-id [(str brass-id) (str woodwind-id)]})]
                                 {:category-ids (get-in result [:filters :category-ids])
                                  :row-names (row-names result)})
              :comma-separated (let [result (workbench conn policy-id {:category-id (str brass-id "," woodwind-id)})]
                                 {:category-ids (get-in result [:filters :category-ids])
                                  :row-names (row-names result)})})))))

(deftest ownership-filtering-test
  (testing "supports all, band, and private ownership filters"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-ownership")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:all     ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
              :band    ["Alto Horn" "Drum Kit" "Euphonium"]
              :private ["Bass Clarinet" "Cornet"]}
             (into {}
                   (for [ownership [:all :band :private]]
                     [ownership (row-names (workbench conn policy-id {:ownership (name ownership)}))])))))))

(deftest extended-filtering-test
  (testing "supports coverage type, missing field, workflow, and change filters"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-extended-filters")
          policy-id      (random-uuid)
          {:keys [basic-type-id extra-type-id]} (seed-workbench-policy! conn policy-id)]
      (is (= {:coverage-type-single   {:coverage-type-ids #{basic-type-id}
                                       :row-names ["Alto Horn" "Bass Clarinet" "Drum Kit"]}
              :coverage-type-multiple {:coverage-type-ids #{basic-type-id extra-type-id}
                                       :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]}
              :missing-photos         ["Bass Clarinet" "Cornet"]
              :missing-harmonia-id    ["Bass Clarinet" "Cornet"]
              :workflow-statuses      {:workflow-statuses #{:needs-review :reviewed}
                                       :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Euphonium"]}
              :workflow-keywords      {:workflow-statuses #{:needs-review :reviewed}
                                       :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Euphonium"]}
              :change-statuses        {:change-statuses #{:changed :new}
                                       :row-names ["Bass Clarinet" "Cornet"]}
              :change-keywords        {:change-statuses #{:changed :new}
                                       :row-names ["Bass Clarinet" "Cornet"]}}
             {:coverage-type-single
              (let [result (workbench conn policy-id {:coverage-type-id (str basic-type-id)})]
                {:coverage-type-ids (get-in result [:filters :coverage-type-ids])
                 :row-names (row-names result)})
              :coverage-type-multiple
              (let [result (workbench conn policy-id {:coverage-type-id [(str basic-type-id) (str extra-type-id)]})]
                {:coverage-type-ids (get-in result [:filters :coverage-type-ids])
                 :row-names (row-names result)})
              :missing-photos
              (row-names (workbench conn policy-id {:missing-photos true}))
              :missing-harmonia-id
              (row-names (workbench conn policy-id {:missing-harmonia-id true}))
              :workflow-statuses
              (let [result (workbench conn policy-id {:workflow-status ["needs-review" "reviewed"]})]
                {:workflow-statuses (get-in result [:filters :workflow-statuses])
                 :row-names (row-names result)})
              :workflow-keywords
              (let [result (workbench conn policy-id {:workflow-status [:needs-review :reviewed]})]
                {:workflow-statuses (get-in result [:filters :workflow-statuses])
                 :row-names (row-names result)})
              :change-statuses
              (let [result (workbench conn policy-id {:change-status ["changed" "new"]})]
                {:change-statuses (get-in result [:filters :change-statuses])
                 :row-names (row-names result)})
              :change-keywords
              (let [result (workbench conn policy-id {:change-status [:changed :new]})]
                {:change-statuses (get-in result [:filters :change-statuses])
                 :row-names (row-names result)})})))))

(deftest value-filtering-test
  (testing "supports numeric insured-value filters"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-value-filters")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:greater-than {:value-filter {:operator :greater-than
                                            :value    2500M}
                             :row-names ["Cornet" "Drum Kit"]}
              :less-than    {:value-filter {:operator :less-than
                                            :value    1000M}
                             :row-names ["Euphonium"]}
              :equal-to     {:value-filter {:operator :equal-to
                                            :value    2000M}
                             :row-names ["Bass Clarinet"]}
              :between      {:value-filter {:operator :between
                                            :min      1000M
                                            :max      3000M}
                             :row-names ["Alto Horn" "Bass Clarinet" "Cornet"]}
              :invalid      {:value-filter nil
                             :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]}}
             {:greater-than
              (let [result (workbench conn policy-id {:value-operator "greater-than"
                                                      :value "2500"})]
                {:value-filter (get-in result [:filters :value-filter])
                 :row-names (row-names result)})
              :less-than
              (let [result (workbench conn policy-id {:value-operator "less-than"
                                                      :value "1000"})]
                {:value-filter (get-in result [:filters :value-filter])
                 :row-names (row-names result)})
              :equal-to
              (let [result (workbench conn policy-id {:value-operator "equal-to"
                                                      :value "2000"})]
                {:value-filter (get-in result [:filters :value-filter])
                 :row-names (row-names result)})
              :between
              (let [result (workbench conn policy-id {:value-operator "between"
                                                      :value-min "1000"
                                                      :value-max "3000"})]
                {:value-filter (get-in result [:filters :value-filter])
                 :row-names (row-names result)})
              :invalid
              (let [result (workbench conn policy-id {:value-operator "between"
                                                      :value-min "nope"
                                                      :value-max "3000"})]
                {:value-filter (get-in result [:filters :value-filter])
                 :row-names (row-names result)})})))))

(deftest grouping-modes-test
  (testing "member grouping and flat list modes return stable row order and group metadata"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-grouping")
          policy-id      (random-uuid)
          {:keys [anna-id zoe-id]} (seed-workbench-policy! conn policy-id)]
      (is (= {:member {:group :member
                       :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
                       :groups [{:member-id anna-id
                                 :member-label "Anna Alto"
                                 :row-count 2}
                                {:member-id zoe-id
                                 :member-label "Zoe Zebra"
                                 :row-count 3}]}
              :none   {:group :none
                       :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
                       :groups []}}
             {:member (let [result (workbench conn policy-id {:group "member"})]
                        {:group (get-in result [:filters :group])
                         :row-names (row-names result)
                         :groups (group-summary result)})
              :none   (let [result (workbench conn policy-id {:group "none"})]
                        {:group (get-in result [:filters :group])
                         :row-names (row-names result)
                         :groups (group-summary result)})})))))

(deftest pagination-test
  (testing "defaults to 20 rows per page and paginates after filtering and sorting"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-pagination")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:default {:pagination {:page 1
                                     :page-size 20
                                     :total-results 5
                                     :total-pages 1
                                     :range-start 1
                                     :range-end 5
                                     :has-prev? false
                                     :has-next? false}
                        :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
                        :total-instruments 5}
              :page-2  {:pagination {:page 2
                                     :page-size 2
                                     :total-results 5
                                     :total-pages 3
                                     :range-start 3
                                     :range-end 4
                                     :has-prev? true
                                     :has-next? true}
                        :row-names ["Cornet" "Drum Kit"]
                        :total-instruments 5}
              :clamped {:pagination {:page 3
                                     :page-size 2
                                     :total-results 5
                                     :total-pages 3
                                     :range-start 5
                                     :range-end 5
                                     :has-prev? true
                                     :has-next? false}
                        :row-names ["Euphonium"]
                        :total-instruments 5}
              :invalid {:pagination {:page 1
                                     :page-size 20
                                     :total-results 5
                                     :total-pages 1
                                     :range-start 1
                                     :range-end 5
                                     :has-prev? false
                                     :has-next? false}
                        :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]
                        :total-instruments 5}}
             (letfn [(shape [result]
                       {:pagination (select-keys (:pagination result)
                                                 [:page
                                                  :page-size
                                                  :total-results
                                                  :total-pages
                                                  :range-start
                                                  :range-end
                                                  :has-prev?
                                                  :has-next?])
                        :row-names (row-names result)
                        :total-instruments (get-in result [:totals :total-instruments])})]
               {:default (shape (workbench conn policy-id {}))
                :page-2  (shape (workbench conn policy-id {:page "2" :page-size "2"}))
                :clamped (shape (workbench conn policy-id {:page "99" :page-size "2"}))
                :invalid (shape (workbench conn policy-id {:page "nope" :page-size "999"}))}))))))

(deftest unsupported-parameters-fall-back-safely-test
  (testing "unsupported values and invalid UUID filters do not crash page data generation"
    (let [{:keys [conn]} (tc/new-system "insurance-workbench-query-fallback")
          policy-id      (random-uuid)]
      (seed-workbench-policy! conn policy-id)
      (is (= {:view :all
              :filters {:member-q nil
                        :category-ids #{}
                        :coverage-type-ids #{}
                        :ownership :all
                        :missing-photos? false
                        :missing-harmonia-id? false
                        :workflow-statuses #{}
                        :change-statuses #{}
                        :value-filter nil
                        :group :none}
              :row-names ["Alto Horn" "Bass Clarinet" "Cornet" "Drum Kit" "Euphonium"]}
             (let [result (workbench conn
                                     policy-id
                                     {:view "unknown"
                                      :group "category"
                                      :ownership "corporate"
                                      :category-id ["not-a-uuid" "also-not-a-uuid"]})]
               {:view (:view result)
                :filters (:filters result)
                :row-names (row-names result)}))))))
