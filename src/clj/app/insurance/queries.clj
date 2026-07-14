(ns app.insurance.queries
  (:require
   [app.datomic :as d]
   [app.datomic.shim :as datomic]
   [app.datastar :as d*]
   [app.insurance.domain :as domain]
   [app.insurance.exporters :as exporters]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.set :as set]
   [clojure.string :as str]
   [datomic.api :as datomic-api]
   [tick.core :as t]))

(defn- lower
  [value]
  (str/lower-case (str value)))

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

(defn- coverage-category-id
  [coverage]
  (get-in coverage [:instrument.coverage/instrument
                    :instrument/category
                    :instrument.category/category-id]))

(defn- coverage-category-name
  [coverage]
  (get-in coverage [:instrument.coverage/instrument
                    :instrument/category
                    :instrument.category/name]))

(defn- enriched-coverages
  [policy]
  (domain/enrich-coverages policy
                           (:insurance.policy/coverage-types policy)
                           (:insurance.policy/covered-instruments policy)))

(defn- insurance-team-member?
  [db current-member-id]
  (boolean
   (when current-member-id
     (when-let [member (q/retrieve-member db current-member-id)]
       (q/insurance-team-member? db member)))))

(defn- coverage-owner-name
  [coverage]
  (get-in coverage [:instrument.coverage/instrument :instrument/owner :member/name]))

(defn- coverage-instrument-name
  [coverage]
  (get-in coverage [:instrument.coverage/instrument :instrument/name]))

(defn coverages-grouped-by-owner
  [policy]
  (->> (:insurance.policy/covered-instruments policy)
       (util/group-by-into-list
        :coverages
        #(get-in % [:instrument.coverage/instrument :instrument/owner]))
       (mapv #(update % :coverages
                      (fn [coverages]
                        (sort-by (fn [coverage]
                                   (get-in coverage
                                           [:instrument.coverage/instrument
                                            :instrument/name]))
                                 coverages))))
       (mapv #(update % :coverages
                      (fn [coverages]
                        (domain/enrich-coverages
                         policy
                         (:insurance.policy/coverage-types policy)
                         coverages))))
       (mapv (fn [{:keys [coverages] :as member}]
               (assoc member :total (domain/sum-by coverages
                                                   :instrument.coverage/cost))))
       (sort-by :member/name)
       vec))

(defn policy-editable?
  [{:insurance.policy/keys [status]}]
  (= status :insurance.policy.status/draft))

(defn image-uri
  [{:keys [system]} {:instrument/keys [instrument-id]} {:image/keys [image-id]}]
  (when image-id
    {:thumbnail (urls/absolute-link-instrument-image-thumbnail (:env system) instrument-id image-id)
     :full      (urls/absolute-link-instrument-image-full (:env system) instrument-id image-id)}))

(defn image-uris
  [req {:instrument/keys [images] :as instrument}]
  (keep #(image-uri req instrument %) images))

(defn coverage
  [db coverage-id]
  (when-let [coverage (q/retrieve-coverage db coverage-id)]
    (let [policy         (:insurance.policy/_covered-instruments coverage)
          coverage-types (:insurance.policy/coverage-types policy)]
      (first (domain/enrich-coverages policy coverage-types [coverage])))))

(defn coverage-history
  [db {:instrument.coverage/keys [coverage-id instrument]}]
  (let [instrument-id     (:instrument/instrument-id instrument)
        instrument-events (d/entity-history db :instrument/instrument-id instrument-id)
        coverage-events   (d/entity-history db :instrument.coverage/coverage-id coverage-id)]
    (->> (concat instrument-events coverage-events)
         (group-by :tx-id)
         vals
         (map (fn [txs]
                (reduce (fn [acc {:keys [audit changes timestamp tx-id]}]
                          (-> acc
                              (assoc :tx-id tx-id)
                              (assoc :timestamp timestamp)
                              (assoc :audit audit)
                              (update :changes concat changes)))
                        {}
                        txs)))
         (sort-by :timestamp)
         reverse)))

(defn instrument-categories [db]
  (mapv first
        (d/find-all db
                    :instrument.category/category-id
                    [:instrument.category/name
                     :instrument.category/category-id])))

(defn policy-totals [{:insurance.policy/keys [covered-instruments]}]
  {:total-instruments  (count covered-instruments)
   :total-needs-review (count (filter #(= :instrument.coverage.status/needs-review
                                          (:instrument.coverage/status %))
                                      covered-instruments))
   :total-changed      (count (filter #(= :instrument.coverage.change/changed
                                          (:instrument.coverage/change %))
                                      covered-instruments))
   :total-removed      (count (filter #(= :instrument.coverage.change/removed
                                          (:instrument.coverage/change %))
                                      covered-instruments))
   :total-new          (count (filter #(= :instrument.coverage.change/new
                                          (:instrument.coverage/change %))
                                      covered-instruments))})

(defn policies [db]
  (mapv #(merge % (policy-totals %))
        (q/policies db)))

(defn active-policy [db]
  (q/insurance-policy-effective-as-of db (t/inst) q/policy-pattern))

(defn member-coverages [db member active-policy]
  (when (and member active-policy)
    (q/instruments-for-member-covered-by db member active-policy q/instrument-coverage-detail-pattern)))

(defn insurance-team-members [db]
  (:team/members (q/retrieve-team-type db :team.type/insurance)))

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
     {:active-report (first todo)
      :current-index (when (seq reports)
                       (inc (- (count reports) (count todo))))
      :policy        policy
      :response      response
      :status        (cond
                       (nil? response) :unavailable
                       (not (domain/survey-open-at? now survey)) :closed
                       (:insurance.survey.response/completed-at response) :complete
                       (empty? reports) :empty
                       :else :active)
      :survey        survey
      :todo-reports  todo
      :total-reports (count reports)
      :total-todo    (count todo)})))

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

(defn- coverage-cost-total
  [coverages]
  (or (domain/sum-by coverages :instrument.coverage/cost) 0M))

(defn- missing-category-factor-count
  [coverages]
  (->> coverages
       (filter :instrument.coverage/missing-category-factor?)
       (keep coverage-category-id)
       set
       count))

(defn- count-by
  [ks f xs]
  (merge (zipmap ks (repeat 0))
         (frequencies (map f xs))))

(defn- recent-change-item
  [{:instrument.coverage/keys [change coverage-id] :as coverage}]
  {:coverage-id     coverage-id
   :change          change
   :instrument-name (coverage-instrument-name coverage)
   :owner-name      (coverage-owner-name coverage)
   :coverage        coverage})

(defn- recent-changes
  [coverages]
  (->> coverages
       (filter (comp (set domain/active-instrument-coverage-changes) :instrument.coverage/change))
       (sort-by (juxt coverage-owner-name coverage-instrument-name))
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

(defn- member-payment-data
  [{:keys [coverages] :as member}]
  (let [private-coverages (filterv :instrument.coverage/private? coverages)]
    (when (seq private-coverages)
      (let [unavailable-coverages
            (filterv :instrument.coverage/missing-category-factor?
                     private-coverages)
            private-cost-total
            (-> (or (domain/sum-by private-coverages
                                   :instrument.coverage/cost)
                    0M)
                double
                (* 100)
                Math/round
                int)]
        {:member                         member
         :private-coverages              private-coverages
         :count-private                  (count private-coverages)
         :private-cost-total             private-cost-total
         :unavailable-private-cost-count (count unavailable-coverages)
         :private-costs-available?       (empty? unavailable-coverages)
         :missing-category-names         (->> unavailable-coverages
                                              (keep coverage-category-name)
                                              distinct
                                              sort
                                              vec)}))))

(defn notification-data
  [db policy-id current-member-id]
  (let [{:insurance.policy/keys [effective-at effective-until] :as policy}
        (q/retrieve-policy db policy-id)
        current-member (when current-member-id
                         (q/retrieve-member db current-member-id))
        authorized? (boolean (and current-member
                                  (q/insurance-team-member? db current-member)))]
    {:policy       policy
     :time-range   (format "%s - %s" (t/year effective-at) (t/year effective-until))
     :sender-name  (:member/name current-member)
     :authorized?  authorized?
     :members-data (if authorized?
                     (->> (coverages-grouped-by-owner policy)
                          (keep member-payment-data)
                          vec)
                     [])}))

(defn select-members
  [members-data member-ids]
  (let [selected (filterv #(contains? member-ids
                                      (get-in % [:member :member/member-id]))
                          members-data)]
    {:to-send     (filterv :private-costs-available? selected)
     :unavailable (filterv (complement :private-costs-available?) selected)}))

(def default-filter :needs-review)

(def filter-order
  [:needs-review
   :missing-insurer-id])

(defn- review-owner-name
  [coverage]
  (or (coverage-owner-name coverage) ""))

(defn- review-instrument-name
  [coverage]
  (or (coverage-instrument-name coverage) ""))

(defn- coverage-sort-key
  [coverage]
  [(str/lower-case (review-owner-name coverage))
   (str/lower-case (review-instrument-name coverage))
   (str (:instrument.coverage/coverage-id coverage))])

(defn- sorted-coverages
  [coverages]
  (sort-by coverage-sort-key coverages))

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

(defn- review-maybe-uuid
  [value]
  (try
    (when value
      (util/ensure-uuid! value))
    (catch Exception _
      nil)))

(defn- selected-coverage-id
  [params]
  (review-maybe-uuid (:coverage-id params)))

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

(defn- review-totals
  [coverages]
  {:total-instruments   (count coverages)
   :total-insured-value (reduce + 0M (map coverage-insured-value coverages))
   :total-cost          (or (domain/sum-by coverages :instrument.coverage/cost) 0M)})

(defn- detailed-selected-coverage
  [db policy-id selected]
  (when selected
    (let [coverage-id (:instrument.coverage/coverage-id selected)
          detailed    (coverage db coverage-id)]
      (if (= policy-id (get-in detailed [:insurance.policy/_covered-instruments :insurance.policy/policy-id]))
        detailed
        selected))))

(defn policy-review
  [db policy-id params]
  (let [policy        (q/retrieve-policy db policy-id)
        filter        (filter-key params)
        all-coverages (vec (sorted-coverages (enriched-coverages policy)))
        queue         (filter-coverages filter all-coverages)
        selected      (selected-coverage queue (selected-coverage-id params))]
    {:policy            policy
     :filter            filter
     :filter-order      filter-order
     :filter-counts     (filter-counts all-coverages)
     :all-coverages     all-coverages
     :totals            (review-totals all-coverages)
     :queue             queue
     :queue-count       (count queue)
     :selected-coverage (detailed-selected-coverage db policy-id selected)
     :selected-position (selected-position queue selected)
     :previous-coverage (neighbor queue selected -1)
     :next-coverage     (neighbor queue selected 1)}))

(def supported-currencies
  [:EUR :USD])

(defn- coverage-type-ids
  [coverage]
  (set (map :insurance.coverage.type/type-id
            (:instrument.coverage/types coverage))))

(defn coverage-type-impact-coverage-ids
  [policy {:keys [scope type-id]}]
  (let [coverages        (:insurance.policy/covered-instruments policy)
        scoped-coverages (case scope
                           :all coverages
                           :band (remove :instrument.coverage/private? coverages)
                           (throw (ex-info "Unknown coverage impact scope"
                                           {:scope scope})))]
    (->> scoped-coverages
         (remove #(and type-id
                       (contains? (coverage-type-ids %) type-id)))
         (sort-by (comp str :instrument.coverage/coverage-id))
         (mapv :instrument.coverage/coverage-id))))

(defn- coverage-counts
  [coverages]
  (reduce (fn [counts coverage]
            (-> counts
                (update :total inc)
                (update (if (:instrument.coverage/private? coverage)
                          :private
                          :band)
                        inc)))
          {:total 0
           :private 0
           :band 0}
          coverages))

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
  (let [category-id     (coverage-category-id coverage)
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
     :category-name            (coverage-category-name coverage)
     :insured-value            (coverage-insured-value coverage)
     :missing-category-factor? (boolean (and category-id (not category-factor)))
     :type-costs               type-costs
     :total-cost               (reduce + 0M (map :cost type-costs))}))

(defn- settings-row-sort-key
  [name-key id-key row]
  [(lower (name-key row))
   (str (id-key row))])

(defn- coverage-type-row
  [safe-costs coverages coverage-type]
  (let [type-id           (:insurance.coverage.type/type-id coverage-type)
        missing-coverages (remove #(contains? (coverage-type-ids %) type-id)
                                  coverages)]
    {:type-id        type-id
     :name           (:insurance.coverage.type/name coverage-type)
     :description    (or (:insurance.coverage.type/description coverage-type) "")
     :premium-factor (:insurance.coverage.type/premium-factor coverage-type)
     :icon            (:insurance.coverage.type/icon coverage-type)
     :required?       (boolean (:insurance.coverage.type/required? coverage-type))
     :missing-coverage-counts (coverage-counts missing-coverages)
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
       (sort-by (partial settings-row-sort-key :name :type-id))
       vec))

(defn- category-factor-row
  [safe-costs coverages category-factor]
  (let [factor-category-id (category-factor-category-id category-factor)
        used-coverages    (filter #(= factor-category-id (coverage-category-id %)) coverages)
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
       (sort-by (partial settings-row-sort-key :category-name :category-factor-id))
       vec))

(defn- available-category-row
  [category]
  {:category-id   (:instrument.category/category-id category)
   :category-name (:instrument.category/name category)})

(defn- available-category-rows
  [db]
  (->> (instrument-categories db)
       (mapv available-category-row)
       (sort-by (partial settings-row-sort-key :category-name :category-id))
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
       (sort-by (partial settings-row-sort-key :category-name :category-id))
       vec))

(defn- warnings
  [safe-costs]
  (let [missing-categories (missing-category-factor-rows safe-costs)]
    (if (seq missing-categories)
      [{:type           :missing-category-factors
        :category-ids   (mapv :category-id missing-categories)
        :category-names (mapv :category-name missing-categories)}]
      [])))

(defn- policy-details
  [policy policy-id]
  {:policy-id       policy-id
   :name            (:insurance.policy/name policy)
   :effective-at    (:insurance.policy/effective-at policy)
   :effective-until (:insurance.policy/effective-until policy)
   :premium-factor  (:insurance.policy/premium-factor policy)
   :currency        (or (:insurance.policy/currency policy) :EUR)
   :status          (:insurance.policy/status policy)})

(defn- exporter-options
  []
  (mapv (fn [{:keys [roles] :as descriptor}]
          (assoc (select-keys descriptor [:exporter-id :label-key])
                 :role-rows
                 (mapv #(select-keys % [:role :label-key :required?])
                       roles)))
        (exporters/descriptors)))

(defn- exporter-configuration
  [policy]
  (let [{:keys [descriptor exporter-id role->coverage-type status]}
        (exporters/policy-configuration policy)]
    {:exporter-id exporter-id
     :status      status
     :role-rows
     (mapv (fn [{:keys [role] :as role-descriptor}]
             (let [coverage-type (get role->coverage-type role)]
               (cond-> (select-keys role-descriptor
                                    [:role :label-key :required?])
                 coverage-type
                 (assoc :coverage-type-id
                        (:insurance.coverage.type/type-id coverage-type)
                        :coverage-type-name
                        (:insurance.coverage.type/name coverage-type)))))
           (:roles descriptor))}))

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
         policy-editable?     (policy-editable? policy)
         team-member?         (insurance-team-member? db current-member-id)]
     {:policy                 policy
      :policy-details         (policy-details policy policy-id)
      :editable?              (boolean (and policy-editable? team-member?))
      :policy-editable?       policy-editable?
      :insurance-team-member? team-member?
      :supported-currencies   supported-currencies
      :coverage-type-rows     (coverage-type-rows policy safe-costs coverages)
      :coverage-counts        (coverage-counts coverages)
      :category-factor-rows   (category-factor-rows policy safe-costs coverages)
      :available-categories   available-categories
      :unused-categories      (unused-category-rows available-categories category-factors)
      :exporter-options       (exporter-options)
      :exporter-configuration (exporter-configuration policy)
      :current-totals         (current-totals safe-costs coverages)
      :warnings               (warnings safe-costs)})))

(defn members-for-survey
  [db policy]
  (let [covered-members      (coverages-grouped-by-owner policy)
        covered-member-ids   (set (map :member/member-id covered-members))
        active-members       (q/active-members db)
        uncovered-member-ids (set/difference
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
          (datomic-api/q '[:find [?survey-id ...]
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
   (let [policy  (q/retrieve-policy db policy-id)
         member  (when current-member-id
                   (q/retrieve-member db current-member-id))
         surveys (->> (q/surveys-for-policy db policy)
                      (sort-by :insurance.survey/created-at #(compare %2 %1))
                      vec)
         open    (filterv #(domain/survey-open-at? now %) surveys)
         closed  (->> surveys
                      (remove #(domain/survey-open-at? now %))
                      (sort-by #(or (:insurance.survey/closed-at %)
                                    (:insurance.survey/closes-at %)
                                    (:insurance.survey/created-at %))
                               #(compare %2 %1))
                      vec)
         active  (first open)
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

(def all-column-ids
  [:status
   :member
   :instrument
   :category
   :ownership
   :photos
   :harmonia-id
   :value
   :cost
   :coverage-types
   :actions])

(def workbench-view-presets
  {:all            {:filters {}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :ownership
                              :value
                              :cost
                              :coverage-types
                              :actions]}
   :todo           {:filters {:workflow-statuses #{:needs-review}}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :photos
                              :harmonia-id
                              :value
                              :actions]}
   :missing-id     {:filters {:missing-harmonia-id? true}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :harmonia-id
                              :actions]}
   :missing-photos {:filters {:missing-photos? true}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :photos
                              :actions]}
   :private        {:filters {:ownership :private}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :ownership
                              :value
                              :cost
                              :actions]}
   :changed        {:filters {:change-statuses #{:changed :new :removed}}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :value
                              :cost
                              :actions]}
   :new            {:filters {:change-statuses #{:new}}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :value
                              :cost
                              :actions]}
   :removed        {:filters {:change-statuses #{:removed}}
                    :columns [:status
                              :member
                              :instrument
                              :category
                              :value
                              :cost
                              :actions]}})

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

(def default-page-size
  20)

(def page-size-options
  [20 50 100])

(def max-page-size
  (apply max page-size-options))

(def review-filter->view
  {:todo       :todo
   :missing-id :missing-id})

(defn- parameter-value
  [params k]
  (or (get params k)
      (get params (name k))))

(defn- scalar-value
  [value]
  (if (sequential? value)
    (first value)
    value))

(defn- positive-integer
  [value]
  (try
    (let [value (some-> value scalar-value str str/trim)
          value (when-not (str/blank? value)
                  (Long/parseLong value))]
      (when (and value (pos? value))
        value))
    (catch Exception _
      nil)))

(defn- normalized-page-size
  [params]
  (let [page-size (positive-integer (parameter-value params :page-size))]
    (if (and page-size (<= page-size max-page-size))
      page-size
      default-page-size)))

(defn- normalized-page
  [params]
  (or (positive-integer (parameter-value params :page))
      1))

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
                                          :none
                                          (parameter-value params :group))})

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
  (let [instrument     (:instrument.coverage/instrument coverage)
        category       (:instrument/category instrument)
        member-id      (coverage-owner-id coverage)
        member         (or (get members member-id)
                           (:instrument/owner instrument))
        private?       (boolean (:instrument.coverage/private? coverage))
        coverage-types (:instrument.coverage/types coverage)
        insured-value  (coverage-insured-value coverage)]
    {:coverage-id         (:instrument.coverage/coverage-id coverage)
     :coverage            coverage
     :member-id           member-id
     :member-name         (:member/name member)
     :member-username     (:member/username member)
     :member-nick         (:member/nick member)
     :member-email        (:member/email member)
     :member-avatar       member
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
     :coverage-type-names (mapv :insurance.coverage.type/name coverage-types)
     :coverage-types      (mapv #(select-keys
                                  %
                                  (conj q/coverage-type-pattern
                                        :insurance.coverage.type/cost))
                                coverage-types)}))

(defn- workbench-row-sort-key
  [{:keys [coverage-id instrument-name member-label]}]
  [(lower member-label)
   (lower instrument-name)
   (str coverage-id)])

(defn view-preset
  [view]
  (get workbench-view-presets view (:all workbench-view-presets)))

(defn view-preset-filters
  [view]
  (:filters (view-preset view)))

(defn default-column-ids
  [view]
  (:columns (view-preset view)))

(defn- member-match?
  [member-q {:keys [member-email member-label member-nick member-username]}]
  (let [needle (lower member-q)]
    (boolean
     (some #(str/includes? (lower %) needle)
           [member-label member-nick member-username member-email]))))

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

(defn- sorted-rows
  [rows]
  (vec (sort-by workbench-row-sort-key rows)))

(defn- visible-rows
  [view filters rows]
  (let [preset-filters (view-preset-filters view)]
    (sorted-rows
     (eduction
      (comp (filter (quick-filter-predicate preset-filters))
            (filter (quick-filter-predicate filters)))
      rows))))

(defn- pagination
  [params total-results]
  (let [page-size      (normalized-page-size params)
        total-pages    (max 1 (long (Math/ceil (/ total-results (double page-size)))))
        requested-page (normalized-page params)
        page            (min requested-page total-pages)
        offset          (* (dec page) page-size)
        range-start     (if (pos? total-results) (inc offset) 0)
        range-end       (min total-results (+ offset page-size))]
    {:page          page
     :page-size     page-size
     :page-sizes    page-size-options
     :total-results total-results
     :total-pages   total-pages
     :range-start   range-start
     :range-end     range-end
     :offset        offset
     :has-prev?     (> page 1)
     :has-next?     (< page total-pages)
     :prev-page     (when (> page 1) (dec page))
     :next-page     (when (< page total-pages) (inc page))}))

(defn- page-rows
  [rows {:keys [offset range-end]}]
  (subvec (vec rows) offset range-end))

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
              (let [group-rows (sorted-rows group-rows)
                    sample     (first group-rows)]
                {:member-id           (:member-id sample)
                 :member-label        (:member-label sample)
                 :member-avatar       (:member-avatar sample)
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

(def initial-summary-counts
  (zipmap supported-views (repeat 0)))

(defn- update-summary-counts
  [counts row]
  (reduce
   (fn [counts view]
     (if ((quick-filter-predicate (view-preset-filters view)) row)
       (update counts view inc)
       counts))
   counts
   supported-views))

(defn- summary-counts
  [rows]
  (reduce update-summary-counts initial-summary-counts rows))

(defn- workbench-totals
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
  (let [policy         (q/retrieve-policy db policy-id)
        view           (normalized-view params)
        filters        (normalized-filters params)
        preset-filters (view-preset-filters view)
        coverages      (enriched-coverages policy)
        members        (member-lookup db coverages)
        all-rows       (mapv #(row members %) coverages)
        visible-rows   (visible-rows view filters all-rows)
        pagination     (pagination params (count visible-rows))
        rows           (page-rows visible-rows pagination)]
    {:policy               policy
     :view                 view
     :views                supported-views
     :preset-filters       preset-filters
     :default-column-ids   (default-column-ids view)
     :filters              filters
     :available-categories (available-categories all-rows)
     :summary-counts       (summary-counts all-rows)
     :pagination           pagination
     :rows                 rows
     :groups               (grouped-rows (:group filters) rows)
     :totals               (workbench-totals visible-rows)
     :editable?            (policy-editable? policy)}))

(d*/refresh-all!)
