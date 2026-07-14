(ns app.insurance.policy.dashboard.queries
  (:require
   [app.datomic :as d]
   [app.datomic.shim :as datomic]
   [app.datastar :as d*]
   [app.insurance.domain :as domain]
   [app.queries :as q]
   [clojure.string :as str]))

(defn- coverage-item-count
  [{:instrument.coverage/keys [item-count]}]
  (or item-count 1))

(defn- coverage-insured-value
  [{:instrument.coverage/keys [value] :as coverage}]
  (* (or value 0M) (coverage-item-count coverage)))

(defn- coverage-cost-total
  [coverages]
  (or (domain/sum-by coverages :instrument.coverage/cost) 0M))

(defn- missing-photo?
  [coverage]
  (not (seq (get-in coverage [:instrument.coverage/instrument :instrument/images]))))

(defn- missing-insurer-id?
  [{:instrument.coverage/keys [insurer-id]}]
  (str/blank? (str insurer-id)))

(defn- coverage-category-id
  [coverage]
  (get-in coverage [:instrument.coverage/instrument
                    :instrument/category
                    :instrument.category/category-id]))

(defn- missing-category-factor-count
  [coverages]
  (->> coverages
       (filter :instrument.coverage/missing-category-factor?)
       (keep coverage-category-id)
       set
       count))

(defn- insurance-team-member?
  [db current-member-id]
  (boolean
   (when current-member-id
     (when-let [member (q/retrieve-member db current-member-id)]
       (q/insurance-team-member? db member)))))

(defn- count-by
  [ks f xs]
  (merge (zipmap ks (repeat 0))
         (frequencies (map f xs))))

(defn- enriched-coverages
  [policy]
  (domain/enrich-coverages policy
                           (:insurance.policy/coverage-types policy)
                           (:insurance.policy/covered-instruments policy)))

(defn- owner-name
  [coverage]
  (get-in coverage [:instrument.coverage/instrument :instrument/owner :member/name]))

(defn- instrument-name
  [coverage]
  (get-in coverage [:instrument.coverage/instrument :instrument/name]))

(defn- recent-change-item
  [{:instrument.coverage/keys [change coverage-id] :as coverage}]
  {:coverage-id     coverage-id
   :change          change
   :instrument-name (instrument-name coverage)
   :owner-name      (owner-name coverage)
   :coverage        coverage})

(defn- recent-changes
  [coverages]
  (->> coverages
       (filter (comp (set domain/active-instrument-coverage-changes) :instrument.coverage/change))
       (sort-by (juxt owner-name instrument-name))
       (mapv recent-change-item)))

(defn- open-survey-progress
  [db policy now]
  (when-let [survey-eid
             (->> (datomic/q '[:find ?survey ?created-at
                               :in $ ?policy ?now
                               :where
                               [?survey :insurance.survey/policy ?policy]
                               [?survey :insurance.survey/created-at ?created-at]
                               [?survey :insurance.survey/closes-at ?closes-at]
                               [(> ?closes-at ?now)]
                               [(missing? $ ?survey :insurance.survey/closed-at)]]
                             db
                             (d/ref policy)
                             now)
                  (sort-by second #(compare %2 %1))
                  ffirst)]
    (let [responses       (:insurance.survey/responses
                           (datomic/pull
                            db
                            [{:insurance.survey/responses
                              [:insurance.survey.response/response-id
                               :insurance.survey.response/completed-at]}]
                            survey-eid))
          completed-count (count (filter :insurance.survey.response/completed-at
                                         responses))
          total-count     (count responses)]
      {:completed-count completed-count
       :waiting-count   (- total-count completed-count)
       :total-count     total-count})))

(defn policy-dashboard
  ([db policy-id]
   (policy-dashboard db policy-id {}))
  ([db policy-id {:keys [current-member-id now]
                  :or   {now (java.util.Date.)}}]
   (let [policy            (q/retrieve-policy db policy-id)
         coverages         (enriched-coverages policy)
         band-coverages    (filterv (complement :instrument.coverage/private?) coverages)
         private-coverages (filterv :instrument.coverage/private? coverages)
         total-instruments (count coverages)]
     {:policy                  policy
      :coverages               coverages
      :insurance-team-member? (insurance-team-member? db current-member-id)
      :totals                  {:total-instruments             total-instruments
                                :total-insured-value           (reduce + 0M (map coverage-insured-value coverages))
                                :total-cost                    (coverage-cost-total coverages)
                                :missing-photo-count           (count (filter missing-photo? coverages))
                                :missing-insurer-id-count      (count (filter missing-insurer-id? coverages))
                                :missing-category-factor-count (missing-category-factor-count coverages)
                                :private-count                 (+ 0 (count private-coverages))
                                :band-count                    (count band-coverages)
                                :private-cost                  (coverage-cost-total private-coverages)
                                :band-cost                     (coverage-cost-total band-coverages)}
      :status-counts           (count-by domain/instrument-coverage-statuses :instrument.coverage/status coverages)
      :change-counts           (count-by domain/instrument-coverage-changes :instrument.coverage/change coverages)
      :survey-progress         (open-survey-progress db policy now)
      :recent-changes          (recent-changes coverages)})))

(d*/refresh-all!)
