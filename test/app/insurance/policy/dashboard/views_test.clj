(ns app.insurance.policy.dashboard.views-test
  (:require
   [app.html :as html]
   [app.insurance.policy.dashboard.views :as views]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def translations
  {[:insurance.dashboard/coverage-mix] "Coverage mix"
   [:insurance.dashboard/coverage-mix-subtitle] "Band and private instruments in this policy."
   [:insurance.dashboard/band-instruments] "Band instruments"
   [:insurance.dashboard/private-instruments] "Private instruments"
   [:insurance.dashboard/review-status] "Review status"
   [:insurance.dashboard/health-checklist] "Health Checklist"
   [:insurance.dashboard/health-checklist-subtitle] "Required data before sending changes."
   [:insurance.dashboard/continue-reviewing] "Review"
   [:insurance.dashboard/review-complete] "%1 of %2 instruments reviewed"
   [:insurance.dashboard/health-checks-complete] "%1 of %2 checks passed"
   [:insurance.dashboard/missing-photos] "Missing photos"
   [:insurance.dashboard/missing-insurer-ids] "Missing insurer IDs"
   [:insurance/item-count] "Count"
   [:insurance/cost] "Cost"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(deftest dashboard-card-renders-title-and-subtitle-in-web-awesome-header-slot
  (let [title-only-html    (html/->str
                            (#'views/dashboard-card
                             {:title "Review status"}
                             [:p "Body content"]))
        title-subtitle-html (html/->str
                             (#'views/dashboard-card
                              {:title    "Health Checklist"
                               :subtitle "Required data before sending changes."}
                              [:p "Body content"]))]
    (is (str/includes? title-only-html "with-header"))
    (is (str/includes? title-only-html "slot=\"header\""))
    (is (str/includes? title-only-html ">Review status</h2>"))
    (is (str/includes? title-subtitle-html "slot=\"header\""))
    (is (str/includes? title-subtitle-html ">Health Checklist</h2>"))
    (is (str/includes? title-subtitle-html ">Required data before sending changes.</p>"))))

(deftest review-dashboard-cards-render-icon-only-review-header-action
  (let [policy-id   #uuid "00000000-0000-0000-0000-000000000123"
        policy      {:insurance.policy/policy-id policy-id}
        review-url  (str "/insurance-policy/" policy-id "/review")
        review-html (html/->str
                     (#'views/review-status-section
                      {:tr tr}
                      {:policy        policy
                       :totals        {:total-instruments 3}
                       :status-counts {:instrument.coverage.status/covered      2
                                       :instrument.coverage.status/needs-review 1}}))
        health-html (html/->str
                     (#'views/health-checklist-section
                      {:tr tr}
                      {:policy policy
                       :totals {:missing-photo-count      1
                                :missing-insurer-id-count 0}}))]
    (doseq [card-html [review-html health-html]]
      (is (str/includes? card-html "with-header-actions"))
      (is (str/includes? card-html "slot=\"header-actions\""))
      (is (str/includes? card-html (str "href=\"" review-url "\"")))
      (is (str/includes? card-html "aria-label=\"Review\""))
      (is (re-find #"(?s)slot=\"header-actions\"[^>]*>\s*<svg" card-html)))))
(deftest coverage-mix-section-renders-raw-axis-chart-and-count-cost-captions
  (let [html (html/->str
              (#'views/coverage-mix-section
               {:tr tr}
               {:policy {:insurance.policy/currency :EUR}
                :totals {:band-count        2
                         :private-count     1
                         :band-cost         12.34M
                         :private-cost      5M
                         :total-instruments 3}}))]
    (is (str/includes? html "<wa-chart"))
    (is (str/includes? html "data-signals"))
    (is (str/includes? html "coverageMixChartJson"))
    (is (str/includes? html "$insuranceDashboard.coverageMixChartJson"))
    (is (not (str/includes? html "coverageMixChartVersion")))
    (is (str/includes? html "labels"))
    (is (str/includes? html "xAxisID"))
    (is (str/includes? html "count"))
    (is (str/includes? html "cost"))
    (is (str/includes? html "[2,null]"))
    (is (str/includes? html "[null,12.34]"))
    (is (str/includes? html "12,34"))
    (is (str/includes? html "var(--wa-color-success-fill-loud)"))
    (is (str/includes? html "var(--wa-color-warning-fill-loud)"))
    (is (str/includes? html "€"))))
