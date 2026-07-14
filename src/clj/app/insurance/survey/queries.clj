(ns app.insurance.survey.queries
  (:require
   [app.insurance.domain :as domain]
   [app.queries :as q]))

(defn- response-for-member [survey member-id]
  (when-let [response (some #(when (= member-id
                                      (get-in % [:insurance.survey.response/member
                                                 :member/member-id]))
                               %)
                            (:insurance.survey/responses survey))]
    (assoc response :survey (dissoc survey :insurance.survey/responses))))

(defn- latest-policy-response [db policy member-id]
  (->> (q/surveys-for-policy db policy)
       (sort-by :insurance.survey/created-at #(compare %2 %1))
       (keep #(response-for-member % member-id))
       first))

(defn- open-policy-response
  [db policy member-id now]
  (->> (q/surveys-for-policy db policy)
       (filter #(domain/survey-open-at? now %))
       (sort-by :insurance.survey/created-at #(compare %2 %1))
       (keep #(response-for-member % member-id))
       first))

(defn survey-data
  ([db policy-id member-id]
   (survey-data db policy-id member-id (java.util.Date.)))
  ([db policy-id member-id now]
   (let [policy   (when policy-id (q/retrieve-policy db policy-id))
         member   (when member-id (q/retrieve-member db member-id))
         response (when (and policy member)
                    (or (open-policy-response db policy member-id now)
                        (latest-policy-response db policy member-id)))
         reports  (vec (:insurance.survey.response/coverage-reports response))
         todo     (filterv #(nil? (:insurance.survey.report/completed-at %)) reports)
         survey   (:survey response)]
     {:active-report      (first todo)
      :current-index      (when (seq reports)
                            (inc (- (count reports) (count todo))))
      :policy             policy
      :response           response
      :status             (cond
                            (nil? response) :unavailable
                            (not (domain/survey-open-at? now survey)) :closed
                            (:insurance.survey.response/completed-at response) :complete
                            (empty? reports) :empty
                            :else :active)
      :survey             survey
      :todo-reports       todo
      :total-reports      (count reports)
      :total-todo         (count todo)})))

(defn pending-responses-for-member
  ([db member]
   (pending-responses-for-member db member (java.util.Date.)))
  ([db member now]
   (->> (q/policies db)
        (keep #(open-policy-response db % (:member/member-id member) now))
        (remove :insurance.survey.response/completed-at)
        (mapv (fn [{:insurance.survey.response/keys
                    [coverage-reports response-id]
                    :keys [survey]}]
                (let [policy (:insurance.survey/policy survey)]
                  {:closes-at   (:insurance.survey/closes-at survey)
                   :policy-id   (:insurance.policy/policy-id policy)
                   :policy-name (:insurance.policy/name policy)
                   :response-id response-id
                   :survey-id   (:insurance.survey/survey-id survey)
                   :todo-count  (count (remove :insurance.survey.report/completed-at
                                               coverage-reports))
                   :total-count (count coverage-reports)})))
        (sort-by :closes-at)
        vec)))

(defn show-milestone? [current-index total-todo]
  (or (= current-index 2)
      (and (pos? current-index)
           (zero? (mod current-index 4))
           (not= current-index (dec total-todo)))))
