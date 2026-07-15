(ns app.datomic.migrations.pre001-prepare-member-uniqueness
  "Prepares legacy member attributes for value uniqueness.

  ## Why This Migration Exists

  The legacy schema stores `:member/nick` without an AVET index and stores
  `:member/email` and `:member/username` with `:db.unique/identity`. This
  release changes all three attributes to `:db.unique/value`. Datomic requires
  existing attributes to be indexed and their current values to be unique
  before that schema transition can succeed.

  ## What It Does

  The migration inspects each existing attribute, rejects unexpected
  cardinality or duplicate values, and emits `:db/index true` only when no
  AVET index is already available. Attributes that do not exist are left for
  the canonical schema transaction.

  ## How It Runs

  Stork invokes [[tx-data]] before the canonical schema transaction and
  atomically records its marker with the returned indexing datoms. The
  lifecycle in [[app.datomic.system]] then waits for schema synchronization."
  (:require
   [datomic.api :as d]))

(defn- schema-attribute
  "Returns the schema state for `attribute`, or `nil` when it is not installed."
  [db attribute]
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

(defn- duplicate-groups
  "Returns entity/value groups whose value occurs more than once for `attribute`."
  [db attribute]
  (->> (d/q '[:find ?entity ?value
              :in $ ?attribute
              :where
              [?entity ?attribute ?value]]
            db attribute)
       (group-by second)
       vals
       (filterv #(< 1 (count %)))))

(defn- validate-member-attribute!
  "Rejects a member attribute with unexpected cardinality or duplicate values."
  [db {:keys [attribute cardinality]}]
  (when-not (= :db.cardinality/one cardinality)
    (throw
     (ex-info
      "Member uniqueness attribute has unexpected cardinality"
      {:type :app.datomic.migrations/schema-mismatch
       :attribute attribute
       :expected-cardinality :db.cardinality/one
       :actual-cardinality cardinality})))
  (let [duplicates (duplicate-groups db attribute)]
    (when (seq duplicates)
      (throw
       (ex-info
        "Member uniqueness attribute contains duplicate values"
        {:type :app.datomic.migrations/duplicate-values
         :attribute attribute
         :duplicate-group-count (count duplicates)
         :conflicting-entity-ids (->> duplicates
                                      (mapcat #(map first %))
                                      sort
                                      vec)})))))

(defn tx-data
  "Returns transactions that prepare existing member attributes for uniqueness changes."
  [conn]
  (let [db         (d/db conn)
        attributes (into []
                         (keep #(schema-attribute db %))
                         [:member/nick :member/email :member/username])]
    (run! #(validate-member-attribute! db %) attributes)
    (->> attributes
         (keep (fn [{:keys [attribute indexed? unique]}]
                 (when-not (or indexed?
                               (contains? #{:db.unique/identity
                                            :db.unique/value}
                                          unique))
                   {:db/id attribute
                    :db/index true})))
         vec)))
