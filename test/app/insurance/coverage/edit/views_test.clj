(ns app.insurance.coverage.edit.views-test
  (:require
   [app.insurance.coverage.edit.views :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def translations
  {[:band-private] "Band or private"
   [:band-instrument] "Band instrument"
   [:band-instrument-description] "Played by the band."
   [:private-instrument] "Private instrument"
   [:private-instrument-description] "Paid for by the owner."
   [:insurance/coverage-for] "Coverage details for %1."
   [:insurance/coverage-types] "Coverage types"
   [:insurance/instrument-coverage] "Instrument coverage"
   [:insurance/item-count] "Count"
   [:insurance/value] "Insured value"
   [:instrument.coverage/insurer-id] "Harmonia ID"
   [:instrument.coverage/insurer-id-hint] "Enter the identifier assigned by Harmonia."})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce-kv (fn [s idx value]
                (str/replace s (str "%" (inc idx)) (str value)))
              (tr path)
              (vec args))))

(def request
  {::r/router router
   :tr        tr})

(def form-state
  {:item-count     "1"
   :value          "100"
   :private-band   "band"
   :coverage-types []
   :insurer-id     ""
   :_error         {}})

(def optional-a-id
  #uuid "00000000-0000-0000-0000-000000000101")

(def optional-b-id
  #uuid "00000000-0000-0000-0000-000000000102")

(def required-b-id
  #uuid "00000000-0000-0000-0000-000000000103")

(def optional-a
  {:insurance.coverage.type/type-id   optional-a-id
   :insurance.coverage.type/name      "Optional A"
   :insurance.coverage.type/required? false})

(def optional-b
  {:insurance.coverage.type/type-id   optional-b-id
   :insurance.coverage.type/name      "Optional B"
   :insurance.coverage.type/required? false})

(def required-a
  (assoc optional-a
         :insurance.coverage.type/name "Required A"
         :insurance.coverage.type/required? true))

(def required-b
  {:insurance.coverage.type/type-id   required-b-id
   :insurance.coverage.type/name      "Required B"
   :insurance.coverage.type/required? true})

(def policy
  {:insurance.policy/name           "Test"
   :insurance.policy/coverage-types [required-a optional-b]})

(defn coverage-choice-state
  [view]
  (let [checkboxes (l/select 'wa-checkbox view)]
    {:checked  (->> checkboxes
                    (filter #(= true (:checked (l/attrs %))))
                    (map (comp :value l/attrs))
                    set)
     :disabled (->> checkboxes
                    (filter #(= true (:disabled (l/attrs %))))
                    (map (comp :value l/attrs))
                    set)}))

(deftest coverage-form-controls
  (testing "An existing coverage is being edited."
    (let [view      (#'sut/coverage-section request form-state policy)
          ownership (l/select-one "wa-radio-group[name=private-band]" view)
          insurer   (l/select-one "wa-input[name=insurer-id]" view)
          attrs     (l/attrs ownership)]
      (testing "Ownership changes update the Datastar signal before validation."
        (is (= {:binding                "coverage-edit.private-band"
                :change-updates-signal? true}
               {:binding                (:data-bind attrs)
                :change-updates-signal? (str/starts-with? (:data-on:change attrs)
                                                          "$coverage-edit.private-band = evt.target.value;")})))
      (testing "The Harmonia identifier explains what value belongs in the field."
        (is (= "Enter the identifier assigned by Harmonia."
               (:hint (l/attrs insurer))))))))

(deftest required-coverage-type-controls
  (testing "zero, one, or multiple explicit required types are enforced in any policy order"
    (let [cases [{:label    "zero required"
                  :orders   [[optional-a optional-b]
                             [optional-b optional-a]]
                  :expected #{}}
                 {:label    "one required"
                  :orders   [[optional-b required-a]
                             [required-a optional-b]]
                  :expected #{(str optional-a-id)}}
                 {:label    "multiple required"
                  :orders   [[required-a optional-b required-b]
                             [required-b required-a optional-b]
                             [optional-b required-b required-a]]
                  :expected #{(str optional-a-id)
                              (str required-b-id)}}]]
      (doseq [{:keys [label orders expected]} cases
              coverage-types orders]
        (is (= {:checked expected
                :disabled expected}
               (->> (assoc policy
                           :insurance.policy/coverage-types coverage-types)
                    (#'sut/coverage-section
                     request
                     (assoc form-state :private-band "private"))
                    coverage-choice-state))
            (str label " with policy order "
                 (mapv :insurance.coverage.type/name coverage-types)))))))
