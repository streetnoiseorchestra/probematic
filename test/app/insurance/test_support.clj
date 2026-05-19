(ns app.insurance.test-support
  (:require
   [datomic.api :as d]))

(def created-at #inst "2026-03-01T00:00:00.000-00:00")
(def closes-at #inst "2026-04-01T00:00:00.000-00:00")

(defn policy-tx
  [policy-id]
  {:insurance.policy/policy-id       policy-id
   :insurance.policy/name            "Insurance 2026"
   :insurance.policy/status          :insurance.policy.status/draft
   :insurance.policy/currency        :currency/EUR
   :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
   :insurance.policy/effective-until #inst "2026-12-31T00:00:00.000-00:00"
   :insurance.policy/premium-factor  0.01M})

(defn seed-policy!
  [conn policy-id]
  @(d/transact conn [(policy-tx policy-id)])
  policy-id)

(defn survey-tx
  [{:keys [member-id policy-id closed-at survey-name]}]
  (cond->
   {:insurance.survey/survey-id     (random-uuid)
    :insurance.survey/survey-name   (or survey-name "Insurance survey")
    :insurance.survey/created-at    created-at
    :insurance.survey/closes-at     closes-at
    :insurance.survey/responses     [{:insurance.survey.response/response-id (random-uuid)
                                      :insurance.survey.response/member      [:member/member-id member-id]}]}
    policy-id (assoc :insurance.survey/policy [:insurance.policy/policy-id policy-id])
    closed-at (assoc :insurance.survey/closed-at closed-at)))

(defn seed-survey!
  [conn opts]
  @(d/transact conn [(survey-tx opts)]))
