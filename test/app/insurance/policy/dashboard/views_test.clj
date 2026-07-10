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

(deftest policy-actions
  (testing "The policy dashboard is showing a draft policy."
    (let [menu            (sut/more-actions-menu {:tr tr} policy)
          menu-items      (take 2 (l/select 'wa-dropdown-item menu))
          settings-action (sut/policy-settings-action {:tr tr} policy)]
      (testing "The More actions menu links to coverage creation and policy settings."
        (is (= [{:label   "Add Instrument"
                 :value   (str "/insurance-coverage-create/" policy-id)
                 :onclick "window.location = this.value"}
                {:label   "Policy Settings"
                 :value   (str "/insurance-policy/" policy-id "/settings")
                 :onclick "window.location = this.value"}]
               (mapv (fn [item]
                       (let [attrs (l/attrs item)]
                         {:label   (l/text item)
                          :value   (:value attrs)
                          :onclick (:onclick attrs)}))
                     menu-items))))
      (testing "The policy details card links directly to policy settings."
        (is (= {:slot       "header-actions"
                :href       (str "/insurance-policy/" policy-id "/settings")
                :aria-label "Policy Settings"}
               (select-keys (l/attrs settings-action)
                            [:slot :href :aria-label])))))))

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
