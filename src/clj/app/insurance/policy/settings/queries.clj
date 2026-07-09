(ns app.insurance.policy.settings.queries
  (:require
   [app.insurance.coverage.queries :as coverage.queries]
   [app.queries :as q]
   [clojure.string :as str]))

(def supported-currencies
  [:EUR :USD])

(defn- lower
  [value]
  (str/lower-case (str value)))

(defn- coverage-item-count
  [{:instrument.coverage/keys [item-count]}]
  (or item-count 1))

(defn- coverage-insured-value
  [{:instrument.coverage/keys [value] :as coverage}]
  (* (or value 0M) (coverage-item-count coverage)))

(defn- category-id
  [coverage]
  (get-in coverage [:instrument.coverage/instrument
                    :instrument/category
                    :instrument.category/category-id]))

(defn- category-name
  [coverage]
  (get-in coverage [:instrument.coverage/instrument
                    :instrument/category
                    :instrument.category/name]))

(defn- coverage-type-ids
  [coverage]
  (set (map :insurance.coverage.type/type-id
            (:instrument.coverage/types coverage))))

(defn- category-factor-category-id
  [category-factor]
  (get-in category-factor [:insurance.category.factor/category
                           :instrument.category/category-id]))

(defn- category-factor-category-name
  [category-factor]
  (get-in category-factor [:insurance.category.factor/category
                           :instrument.category/name]))

(defn- category-factor-by-category-id
  [policy]
  (->> (:insurance.policy/category-factors policy)
       (map (juxt category-factor-category-id identity))
       (into {})))

(defn- safe-coverage-type-cost
  [coverage category-factor policy-premium-factor coverage-type]
  (* (coverage-insured-value coverage)
     category-factor
     (or policy-premium-factor 0M)
     (:insurance.coverage.type/premium-factor coverage-type)))

(defn- coverage-safe-costs
  [policy category-factors coverage]
  (let [category-id     (category-id coverage)
        category-factor (get-in category-factors
                                [category-id :insurance.category.factor/factor])
        type-costs      (if category-factor
                          (mapv (fn [{:insurance.coverage.type/keys [type-id] :as coverage-type}]
                                  {:type-id type-id
                                   :cost    (safe-coverage-type-cost
                                             coverage
                                             category-factor
                                             (:insurance.policy/premium-factor policy)
                                             coverage-type)})
                                (:instrument.coverage/types coverage))
                          [])]
    {:coverage                 coverage
     :category-id              category-id
     :category-name            (category-name coverage)
     :insured-value            (coverage-insured-value coverage)
     :missing-category-factor? (boolean (and category-id (not category-factor)))
     :type-costs               type-costs
     :total-cost               (reduce + 0M (map :cost type-costs))}))

(defn- row-sort-key
  [name-key id-key row]
  [(lower (name-key row))
   (str (id-key row))])

(defn- coverage-type-row
  [safe-costs coverages coverage-type]
  (let [type-id (:insurance.coverage.type/type-id coverage-type)]
    {:type-id        type-id
     :name           (:insurance.coverage.type/name coverage-type)
     :description    (or (:insurance.coverage.type/description coverage-type) "")
     :premium-factor (:insurance.coverage.type/premium-factor coverage-type)
     :usage-count    (count (filter #(contains? (coverage-type-ids %) type-id) coverages))
     :current-cost   (reduce +
                             0M
                             (for [safe-cost safe-costs
                                   type-cost (:type-costs safe-cost)
                                   :when (= type-id (:type-id type-cost))]
                               (:cost type-cost)))
     :used?          (boolean (some #(contains? (coverage-type-ids %) type-id) coverages))}))

(defn- coverage-type-rows
  [policy safe-costs coverages]
  (->> (:insurance.policy/coverage-types policy)
       (mapv (partial coverage-type-row safe-costs coverages))
       (sort-by (partial row-sort-key :name :type-id))
       vec))

(defn- category-factor-row
  [safe-costs coverages category-factor]
  (let [factor-category-id (category-factor-category-id category-factor)
        used-coverages    (filter #(= factor-category-id (category-id %)) coverages)
        current-cost      (reduce +
                                  0M
                                  (map :total-cost
                                       (filter #(= factor-category-id (:category-id %)) safe-costs)))]
    {:category-factor-id (:insurance.category.factor/category-factor-id category-factor)
     :category-id        factor-category-id
     :category-name      (category-factor-category-name category-factor)
     :factor             (:insurance.category.factor/factor category-factor)
     :usage-count        (count used-coverages)
     :current-cost       current-cost
     :used?              (boolean (seq used-coverages))}))

(defn- category-factor-rows
  [policy safe-costs coverages]
  (->> (:insurance.policy/category-factors policy)
       (mapv (partial category-factor-row safe-costs coverages))
       (sort-by (partial row-sort-key :category-name :category-factor-id))
       vec))

(defn- available-category-row
  [category]
  {:category-id   (:instrument.category/category-id category)
   :category-name (:instrument.category/name category)})

(defn- available-category-rows
  [db]
  (->> (coverage.queries/instrument-categories db)
       (mapv available-category-row)
       (sort-by (partial row-sort-key :category-name :category-id))
       vec))

(defn- unused-category-rows
  [available-categories category-factors]
  (let [used-category-ids (set (map category-factor-category-id category-factors))]
    (->> available-categories
         (remove #(contains? used-category-ids (:category-id %)))
         vec)))

(defn- current-totals
  [safe-costs coverages]
  {:total-instruments   (count coverages)
   :total-insured-value (reduce + 0M (map coverage-insured-value coverages))
   :total-cost          (reduce + 0M (map :total-cost safe-costs))})

(defn- missing-category-factor-rows
  [safe-costs]
  (->> safe-costs
       (filter :missing-category-factor?)
       (map #(select-keys % [:category-id :category-name]))
       (reduce (fn [by-id {:keys [category-id] :as row}]
                 (assoc by-id category-id row))
               {})
       vals
       (sort-by (partial row-sort-key :category-name :category-id))
       vec))

(defn- warnings
  [safe-costs]
  (let [missing-categories (missing-category-factor-rows safe-costs)]
    (if (seq missing-categories)
      [{:type           :missing-category-factors
        :category-ids   (mapv :category-id missing-categories)
        :category-names (mapv :category-name missing-categories)}]
      [])))

(defn- insurance-team-member?
  [db current-member-id]
  (boolean
   (when current-member-id
     (when-let [member (q/retrieve-member db current-member-id)]
       (q/insurance-team-member? db member)))))

(defn- policy-details
  [policy policy-id]
  {:policy-id       policy-id
   :name            (:insurance.policy/name policy)
   :effective-at    (:insurance.policy/effective-at policy)
   :effective-until (:insurance.policy/effective-until policy)
   :premium-factor  (:insurance.policy/premium-factor policy)
   :currency        (or (:insurance.policy/currency policy) :EUR)
   :status          (:insurance.policy/status policy)})

(defn policy-settings
  ([db policy-id]
   (policy-settings db policy-id {}))
  ([db policy-id {:keys [current-member-id]}]
   (let [policy               (q/retrieve-policy db policy-id)
         coverages            (vec (:insurance.policy/covered-instruments policy))
         category-factors     (:insurance.policy/category-factors policy)
         category-factor-map  (category-factor-by-category-id policy)
         safe-costs           (mapv (partial coverage-safe-costs policy category-factor-map)
                                    coverages)
         available-categories (available-category-rows db)
         policy-editable?     (coverage.queries/policy-editable? policy)
         team-member?         (insurance-team-member? db current-member-id)]
     {:policy                  policy
      :policy-details          (policy-details policy policy-id)
      :editable?               (boolean (and policy-editable? team-member?))
      :policy-editable?        policy-editable?
      :insurance-team-member?  team-member?
      :supported-currencies    supported-currencies
      :coverage-type-rows      (coverage-type-rows policy safe-costs coverages)
      :category-factor-rows    (category-factor-rows policy safe-costs coverages)
      :available-categories    available-categories
      :unused-categories       (unused-category-rows available-categories category-factors)
      :current-totals          (current-totals safe-costs coverages)
      :warnings                (warnings safe-costs)})))
