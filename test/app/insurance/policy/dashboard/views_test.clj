(ns app.insurance.policy.dashboard.views-test
  (:require
   [app.i18n :as i18n]
   [app.insurance.policy.dashboard.views :as sut]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as j]
   [lookup.core :as l]))

(def tr
  (i18n/tr-with (i18n/read-langs) [:en]))

(defn resolve-view
  [view]
  (i18n/resolve-translations tr view))

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
    (let [full-policy (merge policy
                             {:insurance.policy/name            "Orchestra"
                              :insurance.policy/status          :insurance.policy.status/draft
                              :insurance.policy/premium-factor  1M
                              :insurance.policy/effective-at    nil
                              :insurance.policy/effective-until nil})
          metric  (sut/metric-card {:icon :bank :label "Value" :value "€100"})
          summary (sut/dashboard-card {:title "Summary"} [:p "Body"])
          details (#'sut/policy-details-section
                   {:tr tr}
                   (assoc full-policy
                          :insurance-team-member? true
                          :policy full-policy))]
      (is (= [:app.ui2.card/card
              :app.ui2.card/card
              :app.ui2.card/card]
             (mapv first [metric summary details]))))))

(deftest dashboard-card-actions-remain-visible-when-unavailable
  (testing "Card actions preserve their position and become native disabled buttons when unavailable."
    (let [full-policy (merge policy
                             {:insurance.policy/name            "Orchestra"
                              :insurance.policy/status          :insurance.policy.status/draft
                              :insurance.policy/premium-factor  1M
                              :insurance.policy/effective-at    nil
                              :insurance.policy/effective-until nil})
          policy-details (#'sut/policy-details-section
                          {:tr tr}
                          (assoc full-policy
                                 :insurance-team-member? false
                                 :policy full-policy))
          member-responses (sut/survey-progress-section
                            {:insurance-team-member? false
                             :policy policy
                             :survey-progress {:completed-count 1
                                               :waiting-count   2
                                               :total-count     3}})
          review-complete (sut/review-status-section
                           {:insurance-team-member? true
                            :policy policy
                            :totals {:total-instruments 3}
                            :status-counts {:instrument.coverage.status/covered 3}})
          health-complete (sut/health-checklist-section
                           {:insurance-team-member? true
                            :policy policy
                            :totals {:missing-photo-count            0
                                     :missing-insurer-id-count       0
                                     :missing-category-factor-count  0}})
          action-summary (fn [view]
                           (let [action (l/select-one button/Button (resolve-view view))
                                 attrs  (l/attrs action)]
                             {:href       (:href attrs)
                              :disabled   (:disabled attrs)
                              :slot       (:slot attrs)
                              :appearance (:appearance attrs)
                              :variant    (:variant attrs)
                              :title      (:title attrs)
                              :aria-label (:aria-label attrs)
                              :icon       (-> (l/select-one ico/Icon action)
                                              l/attrs
                                              ::ico/name)}))]
      (is (= {:policy-details
              {:href       nil
               :disabled   true
               :slot       "header-actions"
               :appearance "plain"
               :variant    "brand"
               :title      "Policy Settings"
               :aria-label "Policy Settings"
               :icon       :gear}
              :member-responses
              {:href       nil
               :disabled   true
               :slot       "header-actions"
               :appearance "plain"
               :variant    "brand"
               :title      "Manage surveys"
               :aria-label "Manage surveys"
               :icon       :clipboard-text}
              :review-status
              {:href       nil
               :disabled   true
               :slot       "header-actions"
               :appearance "plain"
               :variant    "brand"
               :title      "Review"
               :aria-label "Review"
               :icon       :hand-pointing}
              :health-checklist
              {:href       nil
               :disabled   true
               :slot       "header-actions"
               :appearance "plain"
               :variant    "brand"
               :title      "Review"
               :aria-label "Review"
               :icon       :hand-pointing}}
             {:policy-details    (action-summary policy-details)
              :member-responses  (action-summary member-responses)
              :review-status     (action-summary review-complete)
              :health-checklist  (action-summary health-complete)})))))

(deftest dashboard-card-actions-link-only-to-meaningful-destinations
  (testing "Insurance-team members receive links only while the card action has work to perform."
    (let [full-policy (merge policy
                             {:insurance.policy/name            "Orchestra"
                              :insurance.policy/status          :insurance.policy.status/draft
                              :insurance.policy/premium-factor  1M
                              :insurance.policy/effective-at    nil
                              :insurance.policy/effective-until nil})
          policy-details (#'sut/policy-details-section
                          {:tr tr}
                          (assoc full-policy
                                 :insurance-team-member? true
                                 :policy full-policy))
          member-responses (sut/survey-progress-section
                            {:insurance-team-member? true
                             :policy policy
                             :survey-progress {:completed-count 1
                                               :waiting-count   2
                                               :total-count     3}})
          review-open (sut/review-status-section
                       {:insurance-team-member? true
                        :policy policy
                        :totals {:total-instruments 3}
                        :status-counts {:instrument.coverage.status/covered      2
                                        :instrument.coverage.status/needs-review 1}})
          health-open (sut/health-checklist-section
                       {:insurance-team-member? true
                        :policy policy
                        :totals {:missing-photo-count            1
                                 :missing-insurer-id-count       0
                                 :missing-category-factor-count  0}})
          action-summary (fn [view]
                           (let [attrs (select-attrs button/Button view)]
                             {:href     (:href attrs)
                              :disabled (:disabled attrs)}))]
      (is (= {:policy-details   {:href     (str "/insurance-policy/" policy-id "/settings")
                                 :disabled nil}
              :member-responses {:href     (str "/insurance-policy/" policy-id "/surveys")
                                 :disabled nil}
              :review-status    {:href     (str "/insurance-policy/" policy-id "/review")
                                 :disabled nil}
              :health-checklist {:href     (str "/insurance-policy/" policy-id "/review")
                                 :disabled nil}}
             {:policy-details   (action-summary policy-details)
              :member-responses (action-summary member-responses)
              :review-status    (action-summary review-open)
              :health-checklist (action-summary health-open)})))))

(deftest missing-category-factor-health-check
  (testing "The policy is missing a category factor used by one or more coverages."
    (let [totals        {:missing-photo-count            1
                         :missing-insurer-id-count       0
                         :missing-category-factor-count  1}
          team-view     (resolve-view
                         (sut/health-checklist-section
                          {:policy                 policy
                           :totals                 totals
                           :insurance-team-member? true}))
          ordinary-view (resolve-view
                         (sut/health-checklist-section
                          {:policy                 policy
                           :totals                 totals
                           :insurance-team-member? false}))
          settings-link (l/select-one 'a team-view)]
      (testing "The health checklist includes the missing category factor and incomplete-check count."
        (is (= {:labels  ["Missing photos" "Missing Harmonia IDs" "Missing category factors"]
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
    (let [view   (resolve-view
                  (sut/coverage-mix-section
                   {:tr tr}
                   {:policy policy
                    :totals {:band-count        2
                             :private-count     1
                             :band-cost         12.34M
                             :private-cost      5M
                             :total-instruments 3}}))
          chart  (l/select-one 'wa-chart view)
          config (chart-config view)]
      (testing "The chart is described for assistive technology."
        (is (= {:description       "Band and private instruments in this policy."
                :without-legend    true
                :without-animation true}
               (select-keys (l/attrs chart)
                            [:description :without-legend :without-animation]))))
      (testing "Count and cost are plotted as separate band and private series."
        (is (= {:labels   ["Number of items" "Cost"]
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
    (let [view (resolve-view
                (#'sut/recent-changes-section
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
                    :owner-name      "Bea"}]}))]
      (testing "Each individual cost remains visible without inventing a zero."
        (is (= [{:label "Cost" :value "&mdash;"}
                {:label "Cost" :value "0,77 €"}]
               (mapv (fn [cost]
                       {:label (-> (l/select-one 'dt cost) l/text)
                        :value (-> (l/select-one 'dd cost) l/text)})
                     (l/select "[data-dashboard-coverage-cost]" view))))))))
