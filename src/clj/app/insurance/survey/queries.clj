(ns app.insurance.survey.queries
  (:require
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

(defn survey-data [db policy-id member-id]
  (let [policy   (when policy-id (q/retrieve-policy db policy-id))
        member   (when member-id (q/retrieve-member db member-id))
        response (when (and policy member)
                   (or (q/open-survey-for-member-policy db member policy)
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
                           (:insurance.survey/closed-at survey) :closed
                           (:insurance.survey.response/completed-at response) :complete
                           (empty? reports) :empty
                           :else :active)
     :survey             survey
     :todo-reports       todo
     :total-reports      (count reports)
     :total-todo         (count todo)}))

(defn show-encouragement? [current-index total-todo]
  (or (= current-index 2)
      (and (pos? current-index)
           (zero? (mod current-index 4))
           (not= current-index (dec total-todo)))))
