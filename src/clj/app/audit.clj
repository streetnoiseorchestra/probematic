(ns app.audit
  "Normalizes trusted metadata on Datomic transaction entities.")

(def ^:private metadata-attributes
  #{:audit/action :audit/comment :audit/origin :audit/user})

(defn- transaction-entity? [entity]
  (= "datomic.tx" entity))

(defn- metadata-pairs [tx]
  (cond
    (and (map? tx) (transaction-entity? (:db/id tx)))
    (keep (fn [attribute]
            (when-let [value (get tx attribute)]
              [attribute value]))
          metadata-attributes)

    (and (vector? tx)
         (= :db/add (first tx))
         (transaction-entity? (second tx))
         (metadata-attributes (nth tx 2 nil))
         (some? (nth tx 3 nil)))
    [[(nth tx 2) (nth tx 3)]]

    :else
    []))

(defn- strip-metadata [tx]
  (cond
    (and (map? tx) (transaction-entity? (:db/id tx)))
    (let [stripped (apply dissoc tx metadata-attributes)]
      (when-not (= {:db/id "datomic.tx"} stripped)
        stripped))

    (and (vector? tx)
         (= :db/add (first tx))
         (transaction-entity? (second tx))
         (metadata-attributes (nth tx 2 nil)))
    nil

    :else
    tx))

(defn- one-value [attribute values]
  (let [values (vec (distinct values))]
    (when (< 1 (count values))
      (throw (ex-info "Conflicting transaction audit metadata"
                      {:audit/attribute attribute
                       :audit/values    values})))
    (first values)))

(defn with-metadata
  "Returns `tx-data` with one normalized transaction audit map.

  Equal explicit metadata is deduplicated. Conflicting explicit or trusted values
  cause an exception. Metadata with a `nil` value is not added.

  Metadata:

  | key              | description                            |
  |------------------|----------------------------------------|
  | `:audit/user`    | Trusted member entity ID or lookup ref |
  | `:audit/action`  | Qualified action keyword               |
  | `:audit/origin`  | Qualified origin keyword               |
  | `:audit/comment` | Audit comment string                   |"
  [tx-data metadata]
  (let [unknown (seq (remove metadata-attributes (keys metadata)))]
    (when unknown
      (throw (ex-info "Unknown transaction audit metadata"
                      {:audit/unknown-attributes (vec unknown)})))
    (let [explicit  (->> tx-data
                         (mapcat metadata-pairs)
                         (group-by first)
                         (reduce-kv (fn [result attribute pairs]
                                      (assoc result attribute
                                             (one-value attribute (map second pairs))))
                                    {}))
          requested (into {} (remove (comp nil? val)) metadata)
          combined  (reduce-kv (fn [result attribute value]
                                 (if-let [explicit-value (get explicit attribute)]
                                   (if (= explicit-value value)
                                     result
                                     (throw (ex-info "Trusted transaction audit metadata conflicts with transaction data"
                                                     {:audit/attribute      attribute
                                                      :audit/explicit-value explicit-value
                                                      :audit/trusted-value  value})))
                                   (assoc result attribute value)))
                               explicit
                               requested)
          tx-data   (into [] (keep strip-metadata) tx-data)]
      (cond-> tx-data
        (seq combined) (conj (assoc combined :db/id "datomic.tx"))))))
