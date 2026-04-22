(ns app.ui2.forms-test
  (:require
   [clojure.test :refer [deftest is]]))

(defn resolve-var [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _
      nil)))

(defn call [sym & args]
  (when-let [v (resolve-var sym)]
    (apply @v args)))

(deftest values-from-signals-extracts-only-requested-fields
  (let [values (call 'app.ui2.forms/values-from-signals
                     [:team-name :team-type]
                     :team
                     {:team {:team-name "Booking"
                             :team-type "finance"
                             :team-id   "ignored"}})]
    (is (some? values) "values-from-signals should exist")
    (when values
      (is (= {:team-name "Booking"
              :team-type "finance"}
             values)))))

(deftest merge-errors-defaults-to-all-fields
  (let [effects (call 'app.ui2.forms/merge-errors
                      {:team {:touched {:team-name 0
                                        :team-type 1}}}
                      :team
                      {:team-name "Required"
                       :_top      "Nope"})]
    (is (some? effects) "merge-errors should exist")
    (when effects
      (is (= [[:app.datastar/merge-signals
               {:team {:error {:team-name "Required"
                               :team-type nil
                               :_top      "Nope"}}}]]
             effects)))))

(deftest merge-errors-can-limit-to-touched-fields
  (let [effects (call 'app.ui2.forms/merge-errors
                      {:team {:touched {:team-name 0
                                        :team-type 1}}}
                      :team
                      {:team-type "Required"}
                      {:only :touched})]
    (is (some? effects) "merge-errors should exist")
    (when effects
      (is (= [[:app.datastar/merge-signals
               {:team {:error {:team-type "Required"
                               :_top      nil}}}]]
             effects)))))

(deftest remove-untouched-errors-discards-errors-for-untouched-fields
  (let [errors (call 'app.ui2.forms/remove-untouched-errors
                     :team
                     {:team {:touched {:team-name 0
                                       :team-type 1}}}
                     {:team-name "Required"
                      :team-type "Required"})]
    (is (some? errors) "remove-untouched-errors should exist")
    (when errors
      (is (= {:team-type "Required"}
             errors)))))
