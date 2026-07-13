(ns app.insurance.policy.create.actions-test
  (:require
   [app.insurance.policy.create.actions :as actions]
   [app.nexus.actions :as support]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(defn tr [[key] & [args]]
  (case key
    :error/is-required (str (first args) " is required.")
    :error/form-has-errors "Please fix the errors in the form."
    :insurance/name "Policy name"
    :insurance/effective-at "Effective from"
    :insurance/effective-until "Effective until"
    :insurance/premium-base-factor "Base premium factor"
    :insurance/error-invalid-date "Enter a valid date."
    :insurance/error-effective-until-before-effective-at "The end date must be after the start date."
    :insurance/error-invalid-premium-factor "Enter a non-negative premium factor."
    (name key)))

(deftest create-policy-action-test
  (testing "a valid form returns an audited transaction and policy redirect"
    (let [member-id                    (random-uuid)
          [[_ tx-data opts] redirect] (actions/create-policy-action
                                       {:current-member-id member-id
                                        :tr                tr}
                                       {:insurance-policy-create
                                        {:name            "Insurance 2027"
                                         :effective-at    "2027-01-01"
                                         :effective-until "2027-12-31"
                                         :base-factor     "0.00447"}})
          policy                      (first tx-data)
          policy-id                   (:insurance.policy/policy-id policy)]
      (is (= {} opts))
      (is (= {:insurance.policy/policy-id       policy-id
              :insurance.policy/name            "Insurance 2027"
              :insurance.policy/status          :insurance.policy.status/draft
              :insurance.policy/currency        :currency/EUR
              :insurance.policy/effective-at    (-> (t/date "2027-01-01") (t/at (t/midnight)) t/inst)
              :insurance.policy/effective-until (-> (t/date "2027-12-31") (t/at (t/midnight)) t/inst)
              :insurance.policy/premium-factor  0.00447M}
             policy))
      (is (= [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]
             (last tx-data)))
      (is (= [:app.datastar/redirect (str "/insurance-policy/" policy-id "/")]
             redirect))))

  (testing "an invalid form remains on the page with field errors"
    (is (= [support/clear-loading
            [:app.datastar/assoc-state
             [:insurance-policy-create]
             {:name            ""
              :effective-at    "bad"
              :effective-until "2026-01-01"
              :base-factor     "-1"
              :_error
              {:_top            {:error "Please fix the errors in the form."}
               :name            {:error "Policy name is required."}
               :effective-at    {:error "Enter a valid date."}
               :base-factor     {:error "Enter a non-negative premium factor."}}}]]
           (actions/create-policy-action
            {:tr tr}
            {:insurance-policy-create
             {:name            " "
              :effective-at    "bad"
              :effective-until "2026-01-01"
              :base-factor     "-1"}})))))
