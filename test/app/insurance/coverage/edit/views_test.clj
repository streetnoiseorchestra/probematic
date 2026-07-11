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

(def policy
  {:insurance.policy/name           "Test"
   :insurance.policy/coverage-types []})

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
