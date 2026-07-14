(ns app.datomic.migrations
  (:require
   [datomic.api :as d]))

(def ^:private member-attributes
  [:member/nick :member/email :member/username])

(def ^:private uniqueness-modes
  #{:db.unique/identity :db.unique/value})

(def ^:private prepare-member-uniqueness-id
  (keyword "app.migration" "001-prepare-member-uniqueness"))

(def ^:private normalize-active-surveys-id
  (keyword "app.migration" "002-normalize-active-insurance-surveys"))

(def pre-schema-migrations
  "Ordered migrations applied before the canonical application schema."
  [{:id prepare-member-uniqueness-id
    :tx-data-fn
    'app.datomic.migrations/member-uniqueness-tx-data}])

(def post-schema-migrations
  "Ordered migrations applied after the canonical application schema."
  [{:id normalize-active-surveys-id
    :tx-data-fn
    'app.datomic.migrations/normalize-active-insurance-surveys-tx-data}])

(defn- schema-attribute [db attribute]
  (when (d/entid db attribute)
    (let [schema (d/pull db
                         '[:db/index
                           {:db/cardinality [:db/ident]}
                           {:db/unique [:db/ident]}]
                         attribute)]
      {:attribute attribute
       :cardinality (get-in schema [:db/cardinality :db/ident])
       :unique (get-in schema [:db/unique :db/ident])
       :indexed? (true? (:db/index schema))})))

(defn- duplicate-groups [db attribute]
  (->> (d/q '[:find ?entity ?value
              :in $ ?attribute
              :where
              [?entity ?attribute ?value]]
            db attribute)
       (group-by second)
       vals
       (filterv #(< 1 (count %)))))

(defn- validate-member-attribute! [db {:keys [attribute cardinality]}]
  (when-not (= :db.cardinality/one cardinality)
    (throw
     (ex-info
      "Member uniqueness attribute has unexpected cardinality"
      {:type ::schema-mismatch
       :attribute attribute
       :expected-cardinality :db.cardinality/one
       :actual-cardinality cardinality})))
  (let [duplicates (duplicate-groups db attribute)]
    (when (seq duplicates)
      (throw
       (ex-info
        "Member uniqueness attribute contains duplicate values"
        {:type ::duplicate-values
         :attribute attribute
         :duplicate-group-count (count duplicates)
         :conflicting-entity-ids (->> duplicates
                                      (mapcat #(map first %))
                                      sort
                                      vec)})))))

(defn member-uniqueness-tx-data
  "Returns transactions that prepare existing member attributes for uniqueness changes."
  [conn]
  (let [db         (d/db conn)
        attributes (into []
                         (keep #(schema-attribute db %))
                         member-attributes)]
    (run! #(validate-member-attribute! db %) attributes)
    (->> attributes
         (keep (fn [{:keys [attribute indexed? unique]}]
                 (when-not (or indexed?
                               (contains? uniqueness-modes unique))
                   {:db/id attribute
                    :db/index true})))
         vec)))

(defn- created-at-order [left right]
  (let [left-created-at  (:insurance.survey/created-at left)
        right-created-at (:insurance.survey/created-at right)]
    (cond
      (and left-created-at right-created-at)
      (compare right-created-at left-created-at)

      left-created-at
      -1

      right-created-at
      1

      :else
      0)))

(defn- survey-id-order [left right]
  (let [left-id  (:insurance.survey/survey-id left)
        right-id (:insurance.survey/survey-id right)]
    (cond
      (and left-id right-id)
      (compare (str left-id) (str right-id))

      left-id
      -1

      right-id
      1

      :else
      0)))

(defn- active-survey-order [left right]
  (let [created-order (created-at-order left right)]
    (if (zero? created-order)
      (let [id-order (survey-id-order left right)]
        (if (zero? id-order)
          (compare (:db/id left) (:db/id right))
          id-order))
      created-order)))

(defn normalize-active-insurance-surveys-tx-data
  "Returns transactions that retain only the newest active insurance survey."
  [conn]
  (let [db     (d/db conn)
        now    (java.util.Date.)
        active (->> (d/q '[:find [?survey ...]
                           :in $ ?now
                           :where
                           [?survey :insurance.survey/closes-at ?closes-at]
                           [(< ?now ?closes-at)]
                           [(missing? $ ?survey
                                      :insurance.survey/closed-at)]]
                         db now)
                    (map #(d/pull db
                                  [:db/id
                                   :insurance.survey/survey-id
                                   :insurance.survey/created-at]
                                  %))
                    (sort active-survey-order))]
    (mapv (fn [{:db/keys [id]}]
            [:db/add id :insurance.survey/closed-at now])
          (rest active))))
