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

(defn seed-page-shell-fixture!
  [conn member-id]
  (let [policy-id        (random-uuid)
        outsider-id      (random-uuid)
        coverage-id      (random-uuid)
        instrument-id    (random-uuid)
        category-id      (random-uuid)
        coverage-type-id (random-uuid)]
    @(d/transact
      conn
      [{:db/id            "member"
        :member/member-id member-id
        :member/name      "Ada"
        :member/email     "ada@example.test"
        :member/active?   true}
       {:member/member-id outsider-id
        :member/name      "Grace"
        :member/email     "grace@example.test"
        :member/active?   true}
       {:db/id                         "category"
        :instrument.category/category-id category-id
        :instrument.category/name      "Brass"
        :instrument.category/code      "brass"}
       {:db/id                                  "base-coverage"
        :insurance.coverage.type/type-id        coverage-type-id
        :insurance.coverage.type/name           "Basic"
        :insurance.coverage.type/description    "Base coverage"
        :insurance.coverage.type/premium-factor 1.0M}
       {:db/id                    "instrument"
        :instrument/instrument-id instrument-id
        :instrument/name          "Test Trumpet"
        :instrument/owner         "member"
        :instrument/category      "category"
        :instrument/make          "Yamaha"}
       {:db/id                           "coverage"
        :instrument.coverage/coverage-id coverage-id
        :instrument.coverage/instrument  "instrument"
        :instrument.coverage/types       ["base-coverage"]
        :instrument.coverage/private?    false
        :instrument.coverage/value       100M
        :instrument.coverage/item-count  1
        :instrument.coverage/status      :instrument.coverage.status/needs-review
        :instrument.coverage/change      :instrument.coverage.change/none
        :instrument.coverage/insurer-id  "H-123"}
       {:insurance.policy/policy-id           policy-id
        :insurance.policy/name                "Insurance 2026"
        :insurance.policy/status              :insurance.policy.status/draft
        :insurance.policy/currency            :currency/EUR
        :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
        :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
        :insurance.policy/premium-factor      0.01M
        :insurance.policy/coverage-types      ["base-coverage"]
        :insurance.policy/category-factors    [{:insurance.category.factor/category-factor-id (random-uuid)
                                                :insurance.category.factor/category           "category"
                                                :insurance.category.factor/factor             0.1M}]
        :insurance.policy/covered-instruments ["coverage"]}
       {:team/team-id   (random-uuid)
        :team/name      "Insurance Team"
        :team/team-type :team.type/insurance
        :team/members   ["member"]}])
    {:policy-id        policy-id
     :outsider-id      outsider-id
     :coverage-id      coverage-id
     :instrument-id    instrument-id
     :category-id      category-id
     :coverage-type-id coverage-type-id}))
