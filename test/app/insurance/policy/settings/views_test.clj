(ns app.insurance.policy.settings.views-test
  (:require
   [app.html :as html]
   [app.insurance.policy.settings.views :as views]
   [app.urls :as urls]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def translations
  {[:action/back] "Back"
   [:action/save] "Save"
   [:actions] "Actions"
   [:insurance/category-factors] "Category factors"
   [:insurance/cost] "Cost"
   [:insurance/coverage-type-description] "Description"
   [:insurance/coverage-types] "Coverage types"
   [:insurance/currency] "Currency"
   [:insurance/effective-at] "Effective At"
   [:insurance/effective-until] "Effective Until"
   [:insurance/name] "Policy Name"
   [:insurance/premium-base-factor] "Premium Base Factor"
   [:insurance/premium-factor] "Premium Factor"
   [:insurance.dashboard/coverage-workbench] "Table"
   [:insurance.dashboard/policy-details] "Policy details"
   [:insurance.dashboard/policy-settings] "Policy Settings"
   [:insurance.dashboard/policy-status] "Policy status"
   [:insurance.dashboard/review-queue] "Review"
   [:insurance.dashboard/total-insured-value] "Total insured value"
   [:insurance.dashboard/total-instruments] "Total instruments"
   [:insurance.dashboard/policy-cost] "Policy cost"
   [:insurance.policy-settings/back-to-dashboard] "Back to dashboard"
   [:insurance.policy-settings/category-factors-subtitle] "Factors by instrument category."
   [:insurance.policy-settings/coverage-types-subtitle] "Insurance options available on this policy."
   [:insurance.policy-settings/current-cost] "Current estimated cost"
   [:insurance.policy-settings/current-totals] "Current totals"
   [:insurance.policy-settings/current-totals-subtitle] "Safe totals from the current configuration."
   [:insurance.policy-settings/error-frozen-policy] "This policy is not draft, so settings cannot be changed."
   [:insurance.policy-settings/error-not-allowed] "You are not allowed to change policy settings."
   [:insurance.policy-settings/missing-category-factors-body] "Covered instruments use categories without category factors: %1."
   [:insurance.policy-settings/missing-category-factors-title] "Missing category factors"
   [:insurance.policy-settings/no-category-factors] "No category factors configured."
   [:insurance.policy-settings/no-coverage-types] "No coverage types configured."
   [:insurance.policy-settings/policy-details-subtitle] "Edit policy metadata used for cost calculations."
   [:insurance.policy-settings/read-only-title] "Settings are read-only"
   [:insurance.policy-settings/status-read-only] "Policy status is read-only here."
   [:insurance.policy-settings/subtitle] "Configure settings for %1."
   [:insurance.policy-settings/usage] "Usage"
   [:insurance.policy.status/draft] "Draft"
   [:nav/insurance] "Insurance"})

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

(def settings
  {:policy                 {:insurance.policy/policy-id policy-id
                            :insurance.policy/name      "Insurance 2027"}
   :policy-details         {:policy-id       policy-id
                            :name            "Insurance 2027"
                            :effective-at    #inst "2027-01-01T00:00:00.000-00:00"
                            :effective-until #inst "2027-12-31T00:00:00.000-00:00"
                            :premium-factor  0.025M
                            :currency        :currency/EUR
                            :status          :insurance.policy.status/draft}
   :editable?              true
   :policy-editable?       true
   :insurance-team-member? true
   :supported-currencies   [:currency/EUR]
   :coverage-type-rows     [{:type-id        #uuid "00000000-0000-0000-0000-000000000201"
                             :name           "Basic"
                             :description    "Base coverage"
                             :premium-factor 1.0M
                             :usage-count    2
                             :current-cost   12.5M}]
   :category-factor-rows   [{:category-factor-id #uuid "00000000-0000-0000-0000-000000000301"
                             :category-name      "Brass"
                             :factor             0.10M
                             :usage-count        2
                             :current-cost       12.5M}]
   :current-totals         {:total-instruments   3
                            :total-insured-value 7000M
                            :total-cost          12.5M}
   :warnings               [{:type           :missing-category-factors
                             :category-names ["Woodwind"]}]})

(def req
  {:tr        tr
   ::r/router router})

(deftest current-member-id-falls-back-to-session
  (let [member-id #uuid "00000000-0000-0000-0000-000000000456"]
    (is (= member-id
           (#'views/current-member-id
            {:session {:session/member {:member/member-id member-id}}})))
    (is (= member-id
           (#'views/current-member-id
            {:current-member-id member-id
             :session           {:session/member {:member/member-id (random-uuid)}}})))))

(deftest settings-page-content-renders-policy-details-and-read-side-sections
  (let [html (html/->str (#'views/settings-page-content req settings))]
    (is (str/includes? html "Policy Settings"))
    (is (str/includes? html (urls/link-policy policy-id)))
    (is (not (str/includes? html (urls/link-policy-review policy-id))))
    (is (not (str/includes? html (urls/link-policy-workbench policy-id))))
    (is (str/includes? html "data-signals"))
    (is (str/includes? html "insurancePolicySettings"))
    (is (str/includes? html "kw=save-policy-details"))
    (is (str/includes? html "data-bind=\"insurancePolicySettings.policy.name\""))
    (is (str/includes? html "data-bind=\"insurancePolicySettings.policy.effectiveAt\""))
    (is (str/includes? html "data-bind=\"insurancePolicySettings.policy.effectiveUntil\""))
    (is (str/includes? html "data-bind=\"insurancePolicySettings.policy.premiumFactor\""))
    (is (str/includes? html "type=\"number\""))
    (is (str/includes? html "min=\"0\""))
    (is (str/includes? html "step=\"any\""))
    (is (not (str/includes? html "step=\"0.000001\"")))
    (is (str/includes? html "data-bind=\"insurancePolicySettings.policy.currency\""))
    (is (str/includes? html "<select"))
    (is (str/includes? html "value=\"EUR\" selected"))
    (is (str/includes? html "Policy status"))
    (is (not (str/includes? html "insurancePolicySettings.policy.status")))
    (is (str/includes? html "Current totals"))
    (is (str/includes? html "Missing category factors"))
    (is (str/includes? html "Woodwind"))
    (is (str/includes? html "Coverage types"))
    (is (str/includes? html "Basic"))
    (is (str/includes? html "Category factors"))
    (is (str/includes? html "Brass"))))

(deftest policy-details-section-renders-validation-errors-from-page-state
  (let [html (html/->str
              (#'views/policy-details-section
               (assoc req :page-state {:insurance-policy-settings
                                       {:policy {:name            ""
                                                 :effective-at    "not-a-date"
                                                 :effective-until "2027-01-01"
                                                 :premium-factor  "bad"
                                                 :currency        "USD"
                                                 :_error          {:_top           {:error "Fix the form."}
                                                                   :name           {:error "Name is required."}
                                                                   :effective-at   {:error "Invalid date."}
                                                                   :premium-factor {:error "Invalid factor."}}}}})
               settings))]
    (is (str/includes? html "Fix the form."))
    (is (str/includes? html "Name is required."))
    (is (str/includes? html "Invalid date."))
    (is (str/includes? html "Invalid factor."))
    (is (str/includes? html "value=\"not-a-date\""))
    (is (str/includes? html "value=\"bad\""))))

(deftest read-only-settings-disable-policy-detail-controls
  (let [html (html/->str
              (#'views/policy-details-section
               req
               (assoc settings
                      :editable? false
                      :insurance-team-member? false)))]
    (is (str/includes? html "Settings are read-only"))
    (is (str/includes? html "You are not allowed to change policy settings."))
    (is (str/includes? html "disabled"))))
