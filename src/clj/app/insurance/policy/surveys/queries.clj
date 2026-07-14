(ns app.insurance.policy.surveys.queries
  (:require
   [app.insurance.domain :as domain]
   [app.insurance.queries :as insurance.queries]
   [app.queries :as q]
   [clojure.set :as set]
   [datomic.api :as d]))

(defn members-for-survey
  [db policy]
  (let [covered-members       (insurance.queries/coverages-grouped-by-owner policy)
        covered-member-ids    (set (map :member/member-id covered-members))
        active-members        (q/active-members db)
        uncovered-member-ids  (set/difference
                               (set (map :member/member-id active-members))
                               covered-member-ids)]
    (->> active-members
         (filter #(contains? uncovered-member-ids (:member/member-id %)))
         (map #(assoc % :coverages [] :total 0))
         (concat covered-members)
         (sort-by :member/name)
         vec)))

(defn survey-belongs-to-policy?
  [survey policy-id]
  (= policy-id
     (get-in survey [:insurance.survey/policy :insurance.policy/policy-id])))

(defn response-belongs-to-policy?
  [response policy-id]
  (survey-belongs-to-policy? (:survey response) policy-id))

(defn active-survey-exists-at?
  ([db now]
   (active-survey-exists-at? db now nil))
  ([db now excluded-survey-id]
   (boolean
    (some #(not= excluded-survey-id %)
          (d/q '[:find [?survey-id ...]
                 :in $ ?now
                 :where
                 [?survey :insurance.survey/survey-id ?survey-id]
                 [?survey :insurance.survey/closes-at ?closes-at]
                 [(< ?now ?closes-at)]
                 [(missing? $ ?survey :insurance.survey/closed-at)]]
               db now)))))

(defn policy-surveys
  ([db policy-id current-member-id]
   (policy-surveys db policy-id current-member-id (java.util.Date.)))
  ([db policy-id current-member-id now]
   (let [policy      (q/retrieve-policy db policy-id)
         member      (when current-member-id
                       (q/retrieve-member db current-member-id))
         surveys     (->> (q/surveys-for-policy db policy)
                          (sort-by :insurance.survey/created-at #(compare %2 %1))
                          vec)
         open        (filterv #(domain/survey-open-at? now %) surveys)
         closed      (->> surveys
                          (remove #(domain/survey-open-at? now %))
                          (sort-by #(or (:insurance.survey/closed-at %)
                                        (:insurance.survey/closes-at %)
                                        (:insurance.survey/created-at %))
                                   #(compare %2 %1))
                          vec)
         active      (first open)
         response-rows
         (mapv (fn [{:insurance.survey.response/keys
                     [completed-at coverage-reports member response-id]
                     :as response}]
                 (let [{:keys [completed open]}
                       (domain/summarize-member-reports coverage-reports)]
                   {:completed-count completed
                    :completed?      (some? completed-at)
                    :member          member
                    :open-count      open
                    :response        response
                    :response-id     response-id
                    :total-count     (count coverage-reports)}))
               (:insurance.survey/responses active))]
     {:active-survey      active
      :authorized?       (boolean (and member
                                       (q/insurance-team-member? db member)))
      :closed-surveys    closed
      :open-survey-count (count open)
      :policy            policy
      :response-rows     response-rows})))
