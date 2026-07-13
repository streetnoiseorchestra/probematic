(ns app.insurance.policy.create.actions
  (:require
   [app.form :as form]
   [app.nexus.actions :as support]
   [app.urls :as urls]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]
   [tick.core :as t]))

(def form-key :insurance-policy-create)

(defn- normalize-form
  [signals]
  (let [params (or (form-key signals) signals)]
    {:name            (or (form/trim-value (:name params)) "")
     :effective-at    (or (form/trim-value (:effective-at params)) "")
     :effective-until (or (form/trim-value (:effective-until params)) "")
     :base-factor     (or (form/trim-value (:base-factor params)) "")}))

(defn- date-value
  [value]
  (try
    (form/parse-date value)
    (catch Exception _
      nil)))

(defn- decimal-value
  [value]
  (try
    (some-> value form/optional-text bigdec)
    (catch NumberFormatException _
      nil)))

(defn- required-error
  [tr label-key]
  {:error (tr [:error/is-required] [(tr [label-key])])})

(defn validation-errors
  [tr {:keys [base-factor effective-at effective-until name]}]
  (let [start-date (date-value effective-at)
        end-date   (date-value effective-until)
        factor     (decimal-value base-factor)
        errors     (cond-> {}
                     (str/blank? name)
                     (assoc :name (required-error tr :insurance/name))

                     (nil? start-date)
                     (assoc :effective-at {:error (tr [:insurance/error-invalid-date])})

                     (nil? end-date)
                     (assoc :effective-until {:error (tr [:insurance/error-invalid-date])})

                     (and start-date end-date
                          (not (pos? (compare end-date start-date))))
                     (assoc :effective-until
                            {:error (tr [:insurance/error-effective-until-before-effective-at])})

                     (or (nil? factor) (neg? factor))
                     (assoc :base-factor
                            {:error (tr [:insurance/error-invalid-premium-factor])}))]
    (cond-> errors
      (seq errors) (assoc :_top {:error (tr [:error/form-has-errors])}))))

(defn create-policy-action
  [{:keys [current-member-id tr]} signals]
  (let [params (normalize-form signals)
        errors (validation-errors tr params)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [form-key] (assoc params :_error errors)]]
      (let [policy-id (sq/generate-squuid)
            start     (date-value (:effective-at params))
            end       (date-value (:effective-until params))]
        [[:db/transact
          (support/with-audit
            [{:insurance.policy/policy-id       policy-id
              :insurance.policy/name            (:name params)
              :insurance.policy/status          :insurance.policy.status/draft
              :insurance.policy/currency        :currency/EUR
              :insurance.policy/effective-at    (-> start (t/at (t/midnight)) t/inst)
              :insurance.policy/effective-until (-> end (t/at (t/midnight)) t/inst)
              :insurance.policy/premium-factor  (decimal-value (:base-factor params))}]
            current-member-id)
          {}]
         [:app.datastar/redirect (urls/link-policy policy-id)]]))))

(def actions
  {::create-policy #'create-policy-action})
