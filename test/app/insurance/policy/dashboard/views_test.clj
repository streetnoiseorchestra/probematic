(ns app.insurance.policy.dashboard.views-test
  (:require
   [app.insurance.policy.dashboard.views :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as j]
   [lookup.core :as l]))

(def translations
  {[:insurance.dashboard/coverage-mix]               "Coverage mix"
   [:insurance.dashboard/coverage-mix-subtitle]      "Band and private instruments in this policy."
   [:insurance.dashboard/band-instruments]           "Band instruments"
   [:insurance.dashboard/private-instruments]        "Private instruments"
   [:insurance.dashboard/review-status]              "Review status"
   [:insurance.dashboard/health-checklist]            "Health Checklist"
   [:insurance.dashboard/health-checklist-subtitle]   "Required data before sending changes."
   [:insurance.dashboard/continue-reviewing]          "Review"
   [:insurance.dashboard/review-complete]             "%1 of %2 instruments reviewed"
   [:insurance.dashboard/health-checks-complete]      "%1 of %2 checks passed"
   [:insurance.dashboard/missing-photos]              "Missing photos"
   [:insurance.dashboard/missing-insurer-ids]         "Missing insurer IDs"
   [:insurance.policy-settings/missing-category-factors-title] "Missing category factors"
   [:insurance.dashboard/recent-changes]              "Recent changes"
   [:insurance.dashboard/recent-changes-subtitle]     "Current changes."
   [:instrument.coverage/cost]                        "Cost"
   [:insurance/item-count]                            "Count"
   [:insurance/cost]                                  "Cost"
   [:instrument.coverage/create-button]               "Add Instrument"
   [:insurance.dashboard/policy-settings]             "Policy Settings"
   [:insurance.dashboard/opens-later]                 "Coming soon"
   [:action/more-actions]                             "More actions"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(def policy-id
  #uuid "00000000-0000-0000-0000-000000000123")

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/currency  :EUR})

(defn select-attrs
  [selector hiccup]
  (some-> (l/select-one selector hiccup)
          l/attrs))

(defn chart-config
  [view]
  (let [signals-json (-> (select-attrs "div[data-signals]" view)
                         :data-signals)
        signals      (j/read-value signals-json)]
    (-> (get-in signals ["insuranceDashboard" "coverageMixChartJson"])
        j/read-value)))

(deftest dashboard-card
  (testing "A dashboard card can have a title with an optional subtitle."
    (let [title-only (sut/dashboard-card
                      {:title "Review status"}
                      [:p "Body content"])
          with-subtitle (sut/dashboard-card
                         {:title    "Health Checklist"
                          :subtitle "Required data before sending changes."}
                         [:p "Body content"])]
      (testing "A title without a subtitle occupies the card header slot."
        (let [heading (l/select-one 'h2 title-only)]
          (is (= {:text "Review status"
                  :slot "header"}
                 {:text (l/text heading)
                  :slot (:slot (l/attrs heading))}))))
      (testing "A title and subtitle are grouped in the card header slot."
        (let [header (l/select-one "div[slot=header]" with-subtitle)]
          (is (= {:text     "Health Checklist Required data before sending changes."
                  :headings ["Health Checklist"]
                  :captions ["Required data before sending changes."]}
                 {:text     (l/text header)
                  :headings (mapv l/text (l/select 'h2 header))
                  :captions (mapv l/text (l/select 'p header))})))))))

(deftest dashboard-uses-card-chassis
  (testing "Every policy dashboard card uses the native Card chassis alias."
    (let [metric  (sut/metric-card {:icon :bank :label "Value" :value "€100"})
          summary (sut/dashboard-card {:title "Summary"} [:p "Body"])
          details (#'sut/policy-details-section
                   {:tr tr}
                   (merge policy
                          {:insurance.policy/name            "Orchestra"
                           :insurance.policy/status          :insurance.policy.status/draft
                           :insurance.policy/premium-factor  1M
                           :insurance.policy/effective-at    nil
                           :insurance.policy/effective-until nil}))]
      (is (= [:app.ui2.card/card
              :app.ui2.card/card
              :app.ui2.card/card]
             (mapv first [metric summary details]))))))

(deftest review-actions
  (testing "A draft policy has outstanding review and health-check work."
    (let [review-view (sut/review-status-section
                       {:tr tr}
                       {:policy        policy
                        :totals        {:total-instruments 3}
                        :status-counts {:instrument.coverage.status/covered      2
                                        :instrument.coverage.status/needs-review 1}})
          health-view (sut/health-checklist-section
                       {:tr tr}
                       {:policy policy
                        :totals {:missing-photo-count      1
                                 :missing-insurer-id-count 0}})]
      (testing "Both cards offer an accessible link to the review queue."
        (is (= [{:slot       "header-actions"
                 :href       (str "/insurance-policy/" policy-id "/review")
                 :aria-label "Review"}
                {:slot       "header-actions"
                 :href       (str "/insurance-policy/" policy-id "/review")
                 :aria-label "Review"}]
               (mapv #(select-keys
                       (select-attrs :app.ui2.button/button %)
                       [:slot :href :aria-label])
                     [review-view health-view])))))))

(deftest missing-category-factor-health-check
  (testing "The policy is missing a category factor used by one or more coverages."
    (let [totals        {:missing-photo-count            1
                         :missing-insurer-id-count       0
                         :missing-category-factor-count  1}
          team-view     (sut/health-checklist-section
                         {:tr tr}
                         {:policy                 policy
                          :totals                 totals
                          :insurance-team-member? true})
          ordinary-view (sut/health-checklist-section
                         {:tr tr}
                         {:policy                 policy
                          :totals                 totals
                          :insurance-team-member? false})
          settings-link (l/select-one 'a team-view)]
      (testing "The health checklist includes the missing category factor and incomplete-check count."
        (is (= {:labels  ["Missing photos" "Missing insurer IDs" "Missing category factors"]
                :counts  ["1" "0" "1"]
                :summary "1 of 3 checks passed"}
               {:labels  (mapv l/text (l/select '[dl dt] team-view))
                :counts  (mapv l/text (l/select '[dl dd] team-view))
                :summary (-> (l/select-one 'div.wa-text-end team-view) l/text)})))
      (testing "Only an insurance-team member receives a settings link for resolving it."
        (is (= {:team-link     {:label "Missing category factors"
                                :href  (str "/insurance-policy/" policy-id "/settings")}
                :ordinary-link nil}
               {:team-link     {:label (l/text settings-link)
                                :href  (:href (l/attrs settings-link))}
                :ordinary-link (l/select-one 'a ordinary-view)}))))))

(deftest coverage-mix
  (testing "The policy contains two band instruments and one private instrument."
    (let [view   (sut/coverage-mix-section
                  {:tr tr}
                  {:policy policy
                   :totals {:band-count        2
                            :private-count     1
                            :band-cost         12.34M
                            :private-cost      5M
                            :total-instruments 3}})
          chart  (l/select-one 'wa-chart view)
          config (chart-config view)]
      (testing "The chart is described for assistive technology."
        (is (= {:description       "Band and private instruments in this policy."
                :without-legend    true
                :without-animation true}
               (select-keys (l/attrs chart)
                            [:description :without-legend :without-animation]))))
      (testing "Count and cost are plotted as separate band and private series."
        (is (= {:labels   ["Count" "Cost"]
                :datasets [{"label"           "Band instruments"
                            "data"            [2 nil]
                            "measure"         "count"
                            "xAxisID"         "count"
                            "backgroundColor" "var(--wa-color-success-fill-loud)"}
                           {"label"           "Private instruments"
                            "data"            [1 nil]
                            "measure"         "count"
                            "xAxisID"         "count"
                            "backgroundColor" "var(--wa-color-warning-fill-loud)"}
                           {"label"           "Band instruments"
                            "data"            [nil 12.34]
                            "measure"         "cost"
                            "xAxisID"         "cost"
                            "backgroundColor" "var(--wa-color-success-fill-loud)"}
                           {"label"           "Private instruments"
                            "data"            [nil 5]
                            "measure"         "cost"
                            "xAxisID"         "cost"
                            "backgroundColor" "var(--wa-color-warning-fill-loud)"}]}
               {:labels   (get-in config ["data" "labels"])
                :datasets (mapv #(select-keys
                                  %
                                  ["label" "data" "measure" "xAxisID" "backgroundColor"])
                                (get-in config ["data" "datasets"]))})))
      (testing "The raw counts and formatted costs remain visible below the chart."
        (is (= {:labels ["Band instruments" "Private instruments"]
                :values ["2" "12,34 €" "1" "5,00 €"]}
               {:labels (mapv l/text (l/select '[dl dt] view))
                :values (mapv l/text (l/select '[dl dd > span > span] view))}))))))

(deftest recent-change-costs
  (testing "Recent changes include one priced and one unavailable coverage."
    (let [view (#'sut/recent-changes-section
                {:tr tr}
                {:policy policy
                 :recent-changes
                 [{:change          :instrument.coverage.change/new
                   :coverage        {:instrument.coverage/coverage-id (random-uuid)
                                     :instrument.coverage/cost        nil}
                   :instrument-name "Missing factor"
                   :owner-name      "Ada"}
                  {:change          :instrument.coverage.change/changed
                   :coverage        {:instrument.coverage/coverage-id (random-uuid)
                                     :instrument.coverage/cost        0.77M}
                   :instrument-name "Priced"
                   :owner-name      "Bea"}]})]
      (testing "Each individual cost remains visible without inventing a zero."
        (is (= [{:label "Cost" :value "&mdash;"}
                {:label "Cost" :value "0,77 €"}]
               (mapv (fn [cost]
                       {:label (-> (l/select-one 'dt cost) l/text)
                        :value (-> (l/select-one 'dd cost) l/text)})
                     (l/select "[data-dashboard-coverage-cost]" view))))))))
