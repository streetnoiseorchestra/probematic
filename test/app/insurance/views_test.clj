(ns app.insurance.views-test
  (:require
   [app.insurance.views :as sut]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]))

(defn tr
  ([path]
   (name (last path)))
  ([path args]
   (str (name (last path)) ": " (str/join ", " args))))

(defn seed-private-coverage-without-category-factor!
  [conn member-id policy-id]
  (let [category-id      (random-uuid)
        coverage-type-id (random-uuid)]
    @(d/transact
      conn
      [{:db/id                           "category"
        :instrument.category/category-id category-id
        :instrument.category/name        "Brass"
        :instrument.category/code        "brass"}
       {:db/id                                  "coverage-type"
        :insurance.coverage.type/type-id        coverage-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    ""
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                    "instrument"
        :instrument/instrument-id (random-uuid)
        :instrument/name          "Trumpet"
        :instrument/owner         [:member/member-id member-id]
        :instrument/category      "category"}
       {:db/id                           "coverage"
        :instrument.coverage/coverage-id (random-uuid)
        :instrument.coverage/instrument  "instrument"
        :instrument.coverage/types       ["coverage-type"]
        :instrument.coverage/private?    true
        :instrument.coverage/value       1000M
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none}
       {:insurance.policy/policy-id           policy-id
        :insurance.policy/name                "Insurance 2026"
        :insurance.policy/status              :insurance.policy.status/draft
        :insurance.policy/currency            :currency/EUR
        :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
        :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
        :insurance.policy/premium-factor      0.01M
        :insurance.policy/coverage-types      ["coverage-type"]
        :insurance.policy/covered-instruments ["coverage"]}])))

(deftest unavailable-survey-costs-render-as-em-dashes
  (testing "A survey report contains coverage whose category factor is missing."
    (let [coverage {:instrument.coverage/cost       nil
                    :instrument.coverage/instrument {:instrument/name     "Trumpet"
                                                     :instrument/category {:instrument.category/name "Brass"}}
                    :instrument.coverage/private?   true
                    :instrument.coverage/value      1000M
                    :instrument.coverage/item-count 1
                    :instrument.coverage/types      []}
          report   {:insurance.survey.report/coverage coverage}
          card     (sut/instrument-card {:tr tr} report [])
          flow     (sut/build-survey-flow {:tr tr})
          state    {:active-report report}
          go-private-text ((get-in flow [:go-private :question :secondaries 1]) state)
          confirm-text    ((get-in flow [:confirm-go-private :question :primary]) state)]
      (testing "The instrument card and payment questions remain renderable without inventing a cost."
        (is (some #{"—"} (filter string? (tree-seq coll? seq card))))
        (is (str/includes? go-private-text "—"))
        (is (str/includes? confirm-text "—"))))))

(deftest unavailable-private-costs-cannot-be-selected-for-payment
  (testing "A private coverage has no category factor and therefore no payable cost."
    (let [{:keys [conn member-id]} (tc/new-system "insurance-unavailable-payment-view")
          policy-id                (random-uuid)]
      (seed-private-coverage-without-category-factor! conn member-id policy-id)
      (let [view     (sut/insurance-notify-page
                      {:db          (d/db conn)
                       :tr          tr
                       :path-params {:policy-id policy-id}
                       :session     {:session/member {:member/member-id member-id
                                                      :member/name      "Covered Member"}}})
            checkbox (->> (l/select 'input view)
                          (filter #(= "member-ids" (:name (l/attrs %))))
                          first)
            total    (-> (l/select '[tbody td] view) last l/text)]
        (testing "The member is disabled, the unavailable total is an em dash, and no email preview is rendered."
          (is (= {:disabled true
                  :checked  nil
                  :total    "—"
                  :preview? false}
                 {:disabled (:disabled (l/attrs checkbox))
                  :checked  (:checked (l/attrs checkbox))
                  :total    total
                  :preview? (boolean (l/select-one 'div.prose view))})))))))

(deftest payment-page-empty-state
  (testing "A policy has no private coverages to notify."
    (let [{:keys [conn member-id]} (tc/new-system "insurance-empty-payment-view")
          policy-id                (random-uuid)]
      @(d/transact
        conn
        [{:insurance.policy/policy-id       policy-id
          :insurance.policy/name            "Insurance 2026"
          :insurance.policy/status          :insurance.policy.status/draft
          :insurance.policy/currency        :currency/EUR
          :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
          :insurance.policy/effective-until #inst "2026-12-31T00:00:00.000-00:00"
          :insurance.policy/premium-factor  0.01M}])
      (let [view (sut/insurance-notify-page
                  {:db          (d/db conn)
                   :tr          tr
                   :path-params {:policy-id policy-id}
                   :session     {:session/member {:member/member-id member-id}}})]
        (is (= {:message             "no-private-payments"
                :rows                0
                :select-all-disabled true
                :send-disabled       true}
               {:message             (-> (l/select-one "[data-insurance-payments-empty]" view) l/text)
                :rows                (count (l/select '[tbody tr] view))
                :select-all-disabled (:disabled (l/attrs (l/select-one "#instr-select-all" view)))
                :send-disabled       (:disabled (l/attrs (l/select-one "#send-payment" view)))}))))))

(deftest ownership-bubbles-use-request-translations
  (let [ownership-tr (fn [path]
                       ({[:insurance.workbench/ownership-band] "Band"
                         [:insurance.workbench/ownership-private] "Private"}
                        path))]
    (is (= ["Band" "Private"]
           (mapv #(-> (sut/band-or-private-bubble ownership-tr %) l/text)
                 [false true])))))
