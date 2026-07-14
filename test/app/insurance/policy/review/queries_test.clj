(ns app.insurance.policy.review.queries-test
  (:require
   [app.insurance.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn coverage-id
  [review instrument-name]
  (some (fn [coverage]
          (when (= instrument-name
                   (get-in coverage [:instrument.coverage/instrument :instrument/name]))
            (:instrument.coverage/coverage-id coverage)))
        (:all-coverages review)))

(defn selected-instrument-name
  [review]
  (get-in review [:selected-coverage :instrument.coverage/instrument :instrument/name]))

(defn queue-instrument-names
  [review]
  (mapv #(get-in % [:instrument.coverage/instrument :instrument/name])
        (:queue review)))

(defn seed-review-policy!
  [conn policy-id]
  (let [brass-id        (random-uuid)
        woodwind-id     (random-uuid)
        basic-type-id   (random-uuid)
        extra-type-id   (random-uuid)
        alpha-id        (random-uuid)
        bravo-id        (random-uuid)
        clarinet-id     (random-uuid)
        drum-id         (random-uuid)]
    @(d/transact
      conn
      [{:db/id                           "alice"
        :member/member-id                (random-uuid)
        :member/name                     "Alice Admin"}
       {:db/id                           "bob"
        :member/member-id                (random-uuid)
        :member/name                     "Bob Brass"}
       {:db/id                           "cara"
        :member/member-id                (random-uuid)
        :member/name                     "Cara Clarinet"}
       {:db/id                           "delta"
        :member/member-id                (random-uuid)
        :member/name                     "Delta Drums"}
       {:db/id                           "brass"
        :instrument.category/category-id brass-id
        :instrument.category/name        "Brass"
        :instrument.category/code        "brass"}
       {:db/id                           "woodwind"
        :instrument.category/category-id woodwind-id
        :instrument.category/name        "Woodwind"
        :instrument.category/code        "woodwind"}
       {:db/id                                  "basic"
        :insurance.coverage.type/type-id        basic-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                                  "extra"
        :insurance.coverage.type/type-id        extra-type-id
        :insurance.coverage.type/name           "Extra"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 0.5M}
       {:db/id                    "alpha-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Alpha Flugelhorn"
        :instrument/owner         "alice"
        :instrument/category      "brass"
        :instrument/images        [{:image/image-id (random-uuid)}]}
       {:db/id                    "bravo-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Bravo Sax"
        :instrument/owner         "bob"
        :instrument/category      "woodwind"
        :instrument/images        [{:image/image-id (random-uuid)}]}
       {:db/id                    "clarinet-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Clarinet"
        :instrument/owner         "cara"
        :instrument/category      "woodwind"}
       {:db/id                    "drum-instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Drum Kit"
        :instrument/owner         "delta"
        :instrument/category      "brass"}
       {:db/id                           "alpha-coverage"
        :instrument.coverage/coverage-id alpha-id
        :instrument.coverage/instrument  "alpha-instrument"
        :instrument.coverage/types       ["basic"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       1000M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  "H-1"
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:db/id                           "bravo-coverage"
        :instrument.coverage/coverage-id bravo-id
        :instrument.coverage/instrument  "bravo-instrument"
        :instrument.coverage/types       ["basic" "extra"]
        :instrument.coverage/private?    true
        :instrument.coverage/value       2000M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  "H-2"
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/changed}
       {:db/id                           "clarinet-coverage"
        :instrument.coverage/coverage-id clarinet-id
        :instrument.coverage/instrument  "clarinet-instrument"
        :instrument.coverage/types       ["basic"]
        :instrument.coverage/private?    true
        :instrument.coverage/value       1500M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      :instrument.coverage.status/reviewed
        :instrument.coverage/change      :instrument.coverage.change/new}
       {:db/id                           "drum-coverage"
        :instrument.coverage/coverage-id drum-id
        :instrument.coverage/instrument  "drum-instrument"
        :instrument.coverage/types       ["basic"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       3000M
        :instrument.coverage/item-count  1
        :instrument.coverage/insurer-id  ""
        :instrument.coverage/status      :instrument.coverage.status/coverage-active
        :instrument.coverage/change      :instrument.coverage.change/removed}
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
        :insurance.policy/covered-instruments ["alpha-coverage"
                                               "bravo-coverage"
                                               "clarinet-coverage"
                                               "drum-coverage"]}])
    {:alpha-id alpha-id
     :bravo-id bravo-id
     :clarinet-id clarinet-id
     :drum-id drum-id}))

(deftest policy-review-default-queue-test
  (testing "selects the first needs-review coverage by owner and instrument name"
    (let [{:keys [conn]} (tc/new-system "insurance-review-query-default")
          policy-id      (random-uuid)]
      (seed-review-policy! conn policy-id)
      (let [review (queries/policy-review (d/db conn) policy-id {})]
        (is (= :needs-review (:filter review)))
        (is (= ["Alpha Flugelhorn" "Bravo Sax"]
               (queue-instrument-names review)))
        (is (= "Alpha Flugelhorn" (selected-instrument-name review)))
        (is (= 1 (:selected-position review)))
        (is (= 2 (:queue-count review)))))))

(deftest policy-review-filter-counts-test
  (testing "counts the selectable review filters"
    (let [{:keys [conn]} (tc/new-system "insurance-review-query-counts")
          policy-id      (random-uuid)]
      (seed-review-policy! conn policy-id)
      (let [review (queries/policy-review (d/db conn) policy-id {})]
        (is (= [:needs-review :missing-insurer-id]
               (:filter-order review)))
        (is (= {:needs-review       2
                :missing-insurer-id 2}
               (:filter-counts review)))))))

(deftest policy-review-filter-and-navigation-test
  (testing "filters missing insurer ids and falls back when the selected coverage is outside the filter"
    (let [{:keys [conn]} (tc/new-system "insurance-review-query-filter")
          policy-id      (random-uuid)
          {:keys [alpha-id clarinet-id]} (seed-review-policy! conn policy-id)
          review         (queries/policy-review (d/db conn)
                                                policy-id
                                                {:filter      "missing-insurer-id"
                                                 :coverage-id (str alpha-id)})]
      (is (= ["Clarinet" "Drum Kit"] (queue-instrument-names review)))
      (is (= clarinet-id (:instrument.coverage/coverage-id (:selected-coverage review))))
      (is (nil? (:previous-coverage review)))
      (is (= "Drum Kit"
             (get-in review [:next-coverage :instrument.coverage/instrument :instrument/name])))))

  (testing "unsupported filter names fall back to todo"
    (let [{:keys [conn]} (tc/new-system "insurance-review-query-unsupported-filter")
          policy-id      (random-uuid)]
      (seed-review-policy! conn policy-id)
      (let [review (queries/policy-review (d/db conn) policy-id {:filter "changed"})]
        (is (= :needs-review (:filter review)))
        (is (= ["Alpha Flugelhorn" "Bravo Sax"]
               (queue-instrument-names review))))))

  (testing "computes previous and next links inside the active filter"
    (let [{:keys [conn]} (tc/new-system "insurance-review-query-navigation")
          policy-id      (random-uuid)
          {:keys [clarinet-id drum-id]} (seed-review-policy! conn policy-id)
          review         (queries/policy-review (d/db conn)
                                                policy-id
                                                {:filter      "missing-insurer-id"
                                                 :coverage-id (str drum-id)})]
      (is (= ["Clarinet" "Drum Kit"]
             (queue-instrument-names review)))
      (is (= drum-id (:instrument.coverage/coverage-id (:selected-coverage review))))
      (is (= clarinet-id (:instrument.coverage/coverage-id (:previous-coverage review))))
      (is (nil? (:next-coverage review)))
      (is (= 2 (:selected-position review)))
      (is (= 2 (:queue-count review))))))
