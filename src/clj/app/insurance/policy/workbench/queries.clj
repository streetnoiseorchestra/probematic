(ns app.insurance.policy.workbench.queries
  (:require
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.domain :as domain]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]))

(def supported-views
  [:all
   :todo
   :missing-id
   :missing-photos
   :private
   :changed
   :new
   :removed])

(def supported-groups
  #{:member :none})

(def review-filter->view
  {:todo       :todo
   :missing-id :missing-id})

(defn- parameter-value
  [params k]
  (or (get params k)
      (get params (name k))))

(defn- supported-value
  [supported default value]
  (let [candidate (domain/simple-keyword value)]
    (if (contains? supported candidate)
      candidate
      default)))

(defn- normalized-view
  [params]
  (let [view          (domain/simple-keyword (parameter-value params :view))
        review-filter (domain/simple-keyword (parameter-value params :review-filter))]
    (cond
      (contains? (set supported-views) view) view
      (contains? review-filter->view review-filter) (review-filter->view review-filter)
      :else :all)))

(defn- normalized-member-q
  [params]
  (let [value (some-> (parameter-value params :member-q) str str/trim)]
    (when-not (str/blank? value)
      value)))

(defn- split-id-value
  [value]
  (cond
    (nil? value) nil
    (keyword? value) [value]
    (sequential? value) (mapcat split-id-value value)
    :else (str/split (str value) #",")))

(defn- maybe-uuid
  [value]
  (try
    (let [value (str/trim (str value))]
      (when-not (str/blank? value)
        (util/ensure-uuid! value)))
    (catch Exception _
      nil)))

(defn- normalized-ids
  [params & ks]
  (->> (some #(parameter-value params %) ks)
       split-id-value
       (keep maybe-uuid)
       set))

(defn- normalized-category-ids
  [params]
  (normalized-ids params :category-id :category-ids))

(defn- normalized-coverage-type-ids
  [params]
  (normalized-ids params :coverage-type-id :coverage-type-ids))

(defn- normalized-simple-values
  [params supported & ks]
  (->> (some #(parameter-value params %) ks)
       split-id-value
       (keep (fn [value]
               (let [value (domain/simple-keyword value)]
                 (when (contains? supported value)
                   value))))
       set))

(defn- truthy-param?
  [value]
  (or (true? value)
      (= "true" value)
      (= :true value)
      (= :missing (domain/simple-keyword value))))

(defn- normalized-value-filter
  [params]
  (domain/normalize-value-filter
   {:operator (parameter-value params :value-operator)
    :value    (parameter-value params :value)
    :min      (parameter-value params :value-min)
    :max      (parameter-value params :value-max)}))

(defn- normalized-filters
  [params]
  {:member-q             (normalized-member-q params)
   :category-ids         (normalized-category-ids params)
   :coverage-type-ids    (normalized-coverage-type-ids params)
   :ownership            (supported-value domain/coverage-ownership-set
                                          :all
                                          (parameter-value params :ownership))
   :missing-photos?      (truthy-param? (or (parameter-value params :missing-photos)
                                            (parameter-value params :photos)))
   :missing-harmonia-id? (truthy-param? (or (parameter-value params :missing-harmonia-id)
                                            (parameter-value params :harmonia-id)))
   :workflow-statuses    (normalized-simple-values params
                                                   domain/simple-instrument-coverage-status-set
                                                   :workflow-status
                                                   :workflow-statuses)
   :change-statuses      (normalized-simple-values params
                                                   domain/simple-instrument-coverage-change-set
                                                   :change-status
                                                   :change-statuses)
   :value-filter         (normalized-value-filter params)
   :group                (supported-value supported-groups
                                          :member
                                          (parameter-value params :group))})

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

(defn- coverage-owner-id
  [coverage]
  (get-in coverage [:instrument.coverage/instrument :instrument/owner :member/member-id]))

(defn- member-lookup
  [db coverages]
  (->> coverages
       (keep coverage-owner-id)
       distinct
       (map (fn [member-id]
              [member-id (q/retrieve-member db member-id)]))
       (into {})))

(def unknown-member-label
  "Unknown member")

(def unknown-category-label
  "Unknown category")

(defn- owner-label
  [member]
  (or (:member/name member)
      unknown-member-label))

(defn- category-label
  [category]
  (or (:instrument.category/name category)
      unknown-category-label))

(defn- row
  [members coverage]
  (let [instrument      (:instrument.coverage/instrument coverage)
        category        (:instrument/category instrument)
        member-id       (coverage-owner-id coverage)
        member          (or (get members member-id)
                            (:instrument/owner instrument))
        private?        (boolean (:instrument.coverage/private? coverage))
        coverage-types  (:instrument.coverage/types coverage)
        insured-value   (coverage-insured-value coverage)]
    {:coverage-id         (:instrument.coverage/coverage-id coverage)
     :coverage            coverage
     :member-id           member-id
     :member-name         (:member/name member)
     :member-username     (:member/username member)
     :member-label        (owner-label member)
     :instrument-id       (:instrument/instrument-id instrument)
     :instrument-name     (:instrument/name instrument)
     :category-id         (:instrument.category/category-id category)
     :category-name       (category-label category)
     :ownership           (if private? :private :band)
     :private?            private?
     :photo-count         (count (:instrument/images instrument))
     :missing-photo?      (missing-photo? coverage)
     :harmonia-id         (:instrument.coverage/insurer-id coverage)
     :missing-insurer-id? (missing-insurer-id? coverage)
     :workflow-status     (:instrument.coverage/status coverage)
     :change-status       (:instrument.coverage/change coverage)
     :insured-value       insured-value
     :cost                (:instrument.coverage/cost coverage)
     :coverage-type-ids   (set (map :insurance.coverage.type/type-id coverage-types))
     :coverage-type-names (mapv :insurance.coverage.type/name coverage-types)}))

(defn- lower
  [value]
  (str/lower-case (str value)))

(defn- row-sort-key
  [{:keys [coverage-id instrument-name member-label]}]
  [(lower member-label)
   (lower instrument-name)
   (str coverage-id)])

(defn- sorted-rows
  [rows]
  (sort-by row-sort-key rows))

(defn- enriched-coverages
  [policy]
  (domain/enrich-coverages policy
                           (:insurance.policy/coverage-types policy)
                           (:insurance.policy/covered-instruments policy)))

(defn- view-predicate
  [view]
  (case view
    :all            (constantly true)
    :todo           #(= :instrument.coverage.status/needs-review (:workflow-status %))
    :missing-id     :missing-insurer-id?
    :missing-photos :missing-photo?
    :private        :private?
    :changed        #(contains? (set domain/active-instrument-coverage-changes) (:change-status %))
    :new            #(= :instrument.coverage.change/new (:change-status %))
    :removed        #(= :instrument.coverage.change/removed (:change-status %))
    (constantly true)))

(defn- member-match?
  [member-q {:keys [member-label member-username]}]
  (let [needle (lower member-q)]
    (or (str/includes? (lower member-label) needle)
        (str/includes? (lower member-username) needle))))

(defn- coverage-type-match?
  [coverage-type-ids row]
  (or (empty? coverage-type-ids)
      (boolean (some coverage-type-ids (:coverage-type-ids row)))))

(defn- workflow-status-match?
  [workflow-statuses row]
  (or (empty? workflow-statuses)
      (contains? workflow-statuses (domain/simple-keyword (:workflow-status row)))))

(defn- change-status-match?
  [change-statuses row]
  (or (empty? change-statuses)
      (contains? change-statuses (domain/simple-keyword (:change-status row)))))

(defn- quick-filter-predicate
  [{:keys [category-ids coverage-type-ids change-statuses member-q missing-harmonia-id?
           missing-photos? ownership value-filter workflow-statuses]}]
  (fn [{:keys [category-id] :as row}]
    (and
     (or (nil? member-q) (member-match? member-q row))
     (or (empty? category-ids) (contains? category-ids category-id))
     (coverage-type-match? coverage-type-ids row)
     (or (not missing-photos?) (:missing-photo? row))
     (or (not missing-harmonia-id?) (:missing-insurer-id? row))
     (workflow-status-match? workflow-statuses row)
     (change-status-match? change-statuses row)
     (domain/value-filter-match? value-filter (:insured-value row))
     (case ownership
       :all true
       :band (= :band (:ownership row))
       :private (= :private (:ownership row))
       true))))

(defn- visible-rows
  [view filters rows]
  (->> rows
       (filter (view-predicate view))
       (filter (quick-filter-predicate filters))
       sorted-rows
       vec))

(defn- group-key
  [{:keys [member-id member-label]}]
  (or member-id member-label))

(defn- row-total
  [rows k]
  (reduce + 0M (map #(or (k %) 0M) rows)))

(defn- member-groups
  [rows]
  (->> rows
       (group-by group-key)
       vals
       (map (fn [group-rows]
              (let [group-rows (vec (sorted-rows group-rows))
                    sample     (first group-rows)]
                {:member-id           (:member-id sample)
                 :member-label        (:member-label sample)
                 :row-count           (count group-rows)
                 :total-insured-value (row-total group-rows :insured-value)
                 :total-cost          (row-total group-rows :cost)
                 :rows                group-rows})))
       (sort-by (comp lower :member-label))
       vec))

(defn- grouped-rows
  [group rows]
  (case group
    :member (member-groups rows)
    :none []))

(defn- available-categories
  [rows]
  (->> rows
       (keep (fn [{:keys [category-id category-name]}]
               (when category-id
                 {:category-id category-id
                  :category-name category-name})))
       (reduce (fn [acc {:keys [category-id] :as category}]
                 (if (contains? acc category-id)
                   acc
                   (assoc acc category-id category)))
               {})
       vals
       (sort-by (comp lower :category-name))
       vec))

(defn- count-where
  [pred rows]
  (count (filter pred rows)))

(defn- summary-counts
  [rows]
  {:all            (count rows)
   :todo           (count-where (view-predicate :todo) rows)
   :missing-id     (count-where (view-predicate :missing-id) rows)
   :missing-photos (count-where (view-predicate :missing-photos) rows)
   :private        (count-where (view-predicate :private) rows)
   :changed        (count-where (view-predicate :changed) rows)
   :new            (count-where (view-predicate :new) rows)
   :removed        (count-where (view-predicate :removed) rows)})

(defn- totals
  [rows]
  {:total-instruments        (count rows)
   :total-insured-value      (row-total rows :insured-value)
   :total-cost               (row-total rows :cost)
   :missing-photo-count      (count-where :missing-photo? rows)
   :missing-insurer-id-count (count-where :missing-insurer-id? rows)
   :private-count            (count-where :private? rows)
   :band-count               (count (remove :private? rows))})

(defn policy-workbench
  [db policy-id params]
  (let [policy     (q/retrieve-policy db policy-id)
        view       (normalized-view params)
        filters    (normalized-filters params)
        coverages  (enriched-coverages policy)
        members    (member-lookup db coverages)
        all-rows   (vec (sorted-rows (map #(row members %) coverages)))
        rows       (visible-rows view filters all-rows)]
    {:policy               policy
     :view                 view
     :views                supported-views
     :filters              filters
     :available-categories (available-categories all-rows)
     :summary-counts       (summary-counts all-rows)
     :rows                 rows
     :groups               (grouped-rows (:group filters) rows)
     :totals               (totals rows)
     :editable?            (coverage.queries/policy-editable? policy)}))
