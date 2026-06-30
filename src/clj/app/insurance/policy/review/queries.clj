(ns app.insurance.policy.review.queries
  (:require
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.domain :as domain]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]))

(def default-filter :needs-review)

(def filter-order
  [:needs-review
   :missing-insurer-id])

(defn- coverage-item-count
  [{:instrument.coverage/keys [item-count]}]
  (or item-count 1))

(defn- coverage-insured-value
  [{:instrument.coverage/keys [value] :as coverage}]
  (* (or value 0M) (coverage-item-count coverage)))

(defn- missing-insurer-id?
  [{:instrument.coverage/keys [insurer-id]}]
  (str/blank? (str insurer-id)))

(defn- owner-name
  [coverage]
  (or (get-in coverage [:instrument.coverage/instrument :instrument/owner :member/name])
      ""))

(defn- instrument-name
  [coverage]
  (or (get-in coverage [:instrument.coverage/instrument :instrument/name])
      ""))

(defn- coverage-sort-key
  [coverage]
  [(str/lower-case (owner-name coverage))
   (str/lower-case (instrument-name coverage))
   (str (:instrument.coverage/coverage-id coverage))])

(defn- sorted-coverages
  [coverages]
  (sort-by coverage-sort-key coverages))

(defn- enriched-coverages
  [policy]
  (sorted-coverages
   (domain/enrich-coverages policy
                            (:insurance.policy/coverage-types policy)
                            (:insurance.policy/covered-instruments policy))))

(def filter-predicates
  {:needs-review       #(= :instrument.coverage.status/needs-review
                           (:instrument.coverage/status %))
   :missing-insurer-id missing-insurer-id?})

(defn- filter-key
  [params]
  (let [raw-filter (:filter params)
        candidate  (cond
                     (keyword? raw-filter) raw-filter
                     (seq (str raw-filter)) (keyword raw-filter)
                     :else default-filter)]
    (if (contains? filter-predicates candidate)
      candidate
      default-filter)))

(defn- maybe-uuid
  [value]
  (try
    (when value
      (util/ensure-uuid! value))
    (catch Exception _
      nil)))

(defn- selected-coverage-id
  [params]
  (maybe-uuid (:coverage-id params)))

(defn- filter-coverages
  [filter coverages]
  (filterv (filter-predicates filter) coverages))

(defn- find-coverage
  [coverages coverage-id]
  (some #(when (= coverage-id (:instrument.coverage/coverage-id %)) %) coverages))

(defn- selected-coverage
  [queue coverage-id]
  (or (and coverage-id (find-coverage queue coverage-id))
      (first queue)))

(defn- selected-position
  [queue selected]
  (when selected
    (some (fn [[idx coverage]]
            (when (= (:instrument.coverage/coverage-id selected)
                     (:instrument.coverage/coverage-id coverage))
              (inc idx)))
          (map-indexed vector queue))))

(defn- neighbor
  [queue selected offset]
  (when-let [position (selected-position queue selected)]
    (get queue (+ (dec position) offset))))

(defn- filter-counts
  [coverages]
  (into {}
        (for [filter filter-order]
          [filter (count (filter-coverages filter coverages))])))

(defn- totals
  [coverages]
  {:total-instruments   (count coverages)
   :total-insured-value (reduce + 0M (map coverage-insured-value coverages))
   :total-cost          (or (domain/sum-by coverages :instrument.coverage/cost) 0M)})

(defn- detailed-selected-coverage
  [db policy-id selected]
  (when selected
    (let [coverage-id (:instrument.coverage/coverage-id selected)
          detailed    (coverage.queries/coverage db coverage-id)]
      (if (= policy-id (get-in detailed [:insurance.policy/_covered-instruments :insurance.policy/policy-id]))
        detailed
        selected))))

(defn policy-review
  [db policy-id params]
  (let [policy         (q/retrieve-policy db policy-id)
        filter         (filter-key params)
        all-coverages  (vec (enriched-coverages policy))
        queue          (filter-coverages filter all-coverages)
        selected       (selected-coverage queue (selected-coverage-id params))]
    {:policy              policy
     :filter              filter
     :filter-order        filter-order
     :filter-counts       (filter-counts all-coverages)
     :all-coverages       all-coverages
     :totals              (totals all-coverages)
     :queue               queue
     :queue-count         (count queue)
     :selected-coverage   (detailed-selected-coverage db policy-id selected)
     :selected-position   (selected-position queue selected)
     :previous-coverage   (neighbor queue selected -1)
     :next-coverage       (neighbor queue selected 1)}))
