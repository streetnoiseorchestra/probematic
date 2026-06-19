(ns app.insurance.policy.dashboard.queries
  (:require
   [app.insurance.domain :as domain]
   [app.queries :as q]
   [clojure.string :as str]))

(def coverage-statuses
  [:instrument.coverage.status/needs-review
   :instrument.coverage.status/reviewed
   :instrument.coverage.status/coverage-active])

(def coverage-changes
  [:instrument.coverage.change/changed
   :instrument.coverage.change/new
   :instrument.coverage.change/removed
   :instrument.coverage.change/none])

(def dashboard-change-statuses
  (remove #{:instrument.coverage.change/none} coverage-changes))

(defn- coverage-item-count
  [{:instrument.coverage/keys [item-count]}]
  (or item-count 1))

(defn- coverage-insured-value
  [{:instrument.coverage/keys [value] :as coverage}]
  (* (or value 0M) (coverage-item-count coverage)))

(defn- missing-photo?
  [coverage]
  (not (seq (get-in coverage [:instrument.coverage/instrument :instrument/images]))))

(defn- missing-insurer-id?
  [{:instrument.coverage/keys [insurer-id]}]
  (str/blank? (str insurer-id)))

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
       (filter (comp (set dashboard-change-statuses) :instrument.coverage/change))
       (sort-by (juxt owner-name instrument-name))
       (mapv recent-change-item)))

(defn policy-dashboard
  [db policy-id]
  (let [policy           (q/retrieve-policy db policy-id)
        coverages        (enriched-coverages policy)
        total-instruments (count coverages)]
    {:policy         policy
     :coverages      coverages
     :totals         {:total-instruments        total-instruments
                      :total-insured-value      (reduce + 0M (map coverage-insured-value coverages))
                      :total-cost               (or (domain/sum-by coverages :instrument.coverage/cost) 0M)
                      :missing-photo-count      (count (filter missing-photo? coverages))
                      :missing-insurer-id-count (count (filter missing-insurer-id? coverages))
                      :private-count            (count (filter :instrument.coverage/private? coverages))
                      :band-count               (count (remove :instrument.coverage/private? coverages))}
     :status-counts  (count-by coverage-statuses :instrument.coverage/status coverages)
     :change-counts  (count-by coverage-changes :instrument.coverage/change coverages)
     :recent-changes (recent-changes coverages)}))
