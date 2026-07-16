(ns app.datomic.client-backup
  "Backup/restore a datomic dev-local database

  When backing up the datomic we go through all the transactions that have happened to the db
  and write them to a file.

  Some of the transactions will be Datomic internal, which we can't transact again,
  these should be in the `ignore-attributes
  "
  (:require
   [app.datomic.shim :as datomic]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [tick.core :as t]))

(defn progress-fn
  "Return a function to be called during iteration. It will log progress
  of the process every `n` (default 500) entries with the given `message`."
  ([message] (progress-fn 500 message))
  ([n message]
   (let [prg (atom 0)]
     (fn []
       (let [p (swap! prg inc)]
         (when (zero? (mod p n))
           (prn p message)))))))

;; --------------------------------------------------
;; Backup

(def ^:private ignore-attributes
  "Internal datomic stuff that we can skip"
  #{:db.install/attribute :db.alter/attribute})

(def  old-ref-attrs #{})

(def  old-tuple-attrs {})

(defn- tuple-ref-indexes [tuple-types]
  (into #{}
        (keep-indexed (fn [idx tuple-type]
                        (when (= :db.type/ref tuple-type)
                          idx)))
        tuple-types))

(defn- tuple-ref-attrs [db]
  (into {}
        (keep (fn [[attr tuple-types]]
                (when-let [ref-indexes (not-empty (tuple-ref-indexes tuple-types))]
                  [attr ref-indexes])))
        (datomic/q '[:find ?id ?types
                     :where
                     [?attr :db/ident ?id]
                     [?attr :db/valueType :db.type/tuple]
                     [?attr :db/tupleTypes ?types]]
                   db)))

(defn- all-transactions
  "Returns all tx identifiers in time order.
  Skips the initial transactions empty databases have."
  [backup-start conn]
  (datomic/tx-range conn {:start backup-start :limit -1}))

(defn- attr-info [db attr-info-cache a]
  (get (swap! attr-info-cache
              (fn [attrs]
                (if (contains? attrs a)
                  attrs
                  (assoc attrs a (datomic/pull @db '[:db/ident :db/valueType] a)))))
       a))

(defn- output-tx [db-ref attr-info-cache {:keys [data]} ignore-attributes]
  (let [datoms (for [{:keys [e a v added]} data
                     :let                  [{:db/keys [ident]}
                                            (attr-info db-ref attr-info-cache a)

                                            ;; Turn :db/* values into idents
                                            v (if (and (str/starts-with? (str ident) ":db/")
                                                       (integer? v))
                                                (:db/ident (attr-info db-ref attr-info-cache v))
                                                v)]
                     :when (not (ignore-attributes ident))]
                 [e ident v added])
        tx-id (:tx (first data))]
    {:tx   (into {}
                 (comp
                  (filter (fn [[e _ _ _]]
                            (= e tx-id)))
                  (map (fn [[_ a v _]]
                         [a v])))
                 datoms)
     :data (vec (remove (fn [[e _ _ _]]
                          (= e tx-id))
                        datoms))}))

(defn- output-all-tx [backup-start conn out]
  (let [db                (datomic/db conn)
        attr-ident-cache  (atom {})
        out!              #(binding [*out* out] (prn %))
        ref-attrs         (into old-ref-attrs
                                (comp
                                 (map first)
                                 (remove #(str/starts-with? (str %) ":db")))
                                (datomic/q '[:find ?id
                                             :where
                                             [?attr :db/ident ?id]
                                             [?attr :db/valueType :db.type/ref]]
                                           db))
        tuple-attrs       (into old-tuple-attrs
                                (datomic/q '[:find ?id ?ta
                                             :where
                                             [?attr :db/ident ?id]
                                             [?attr :db/tupleAttrs ?ta]]
                                           db))
        tuple-ref-attrs   (tuple-ref-attrs db)
        ignore-attributes (into ignore-attributes
                                (keys tuple-attrs))
        progress!         (progress-fn "backup transactions written")]

    (out! {:ref-attrs        ref-attrs
           :tuple-attrs      tuple-attrs
           :tuple-ref-attrs  tuple-ref-attrs
           :backup-timestamp (t/inst)})
    (doseq [tx    (all-transactions backup-start conn)
            :let  [tx-map (output-tx (delay (datomic/as-of db (:t tx))) attr-ident-cache tx
                                     ignore-attributes)]
            :when (and (seq (:data tx-map))
                       (t/> (t/instant (get-in tx-map [:tx :db/txInstant]))
                            (t/instant backup-start)))]
      (out! tx-map)
      (progress!))))

;; --------------------------------------------------
;; Restore

(defn- ->mapped-id [old->new id]
  (let [s (str id)]
    (or (old->new s) s)))

(defn- rewrite-tuple-refs [old->new tuple-ref-attrs attr value]
  (if-let [ref-indexes (tuple-ref-attrs attr)]
    (mapv (fn [idx tuple-value]
            (if (contains? ref-indexes idx)
              (->mapped-id old->new tuple-value)
              tuple-value))
          (range)
          value)
    value))

(defn- prepare-restore-value [old->new ref-attrs tuple-ref-attrs attr value]
  (cond
    (ref-attrs attr)       (->mapped-id old->new value)
    (tuple-ref-attrs attr) (rewrite-tuple-refs old->new tuple-ref-attrs attr value)
    :else                  value))

(defn prepare-restore-tx
  "Prepare transaction for restore.

  Takes tx datoms, old to new id mapping and set of reference attributes.
  Returns sequence of new datoms for the restore tx.

  Looks up entity ids and reference values from the old->new mapping."
  ([tx-data old->new ref-attrs cardinality-many-attrs]
   (prepare-restore-tx tx-data old->new ref-attrs {} cardinality-many-attrs))
  ([tx-data old->new ref-attrs tuple-ref-attrs cardinality-many-attrs]
   (let [{card-many-datoms true
          card-one-datoms  false} (group-by (comp boolean cardinality-many-attrs
                                                  second)
                                            tx-data)
         ->value (partial prepare-restore-value old->new ref-attrs tuple-ref-attrs)]
     (concat
      ;; Output map tx for all cardinality one values, filtering out retractions
      ;; that have an assertion for the same attribute
      (mapcat (fn [[e datoms]]
                (let [e (->mapped-id old->new e)

                      ;; Group by assertions and retractions
                      {asserted  true
                       retracted false}
                      (group-by #(nth % 3) datoms)

                      asserted-map (when (seq asserted)
                                     (into {:db/id e}
                                           (map (fn [[_ a v _]]
                                                  [a (->value a v)]))
                                           asserted))]
                  (into (if asserted-map
                          [asserted-map]
                          [])
                        (for [[_ a v _] retracted
                              :when     (not (contains? asserted-map a))]
                          [:db/retract e a (->value a v)]))))
              (group-by first card-one-datoms))

      ;; Output add or retract clauses for any many cardinality values
      (for [[e a v add?] card-many-datoms
            :let         [e (->mapped-id old->new e)
                          v (->value a v)]]
        [(if add? :db/add :db/retract) e a v])))))

(defn read-seq
  "Lazy sequence of forms read from the given reader... don't let it escape with-open!"
  [rdr]
  (let [item (read rdr false ::eof)]
    (when (not= item ::eof)
      (lazy-seq
       (cons item
             (read-seq rdr))))))

(defn add-cardinality-many-attrs!
  "Record :db/ident values of any new attributes created in tx-data"
  [set-atom tx-data]
  (let [card-many (into #{}
                        (keep (fn [tx]
                                (when (and (map? tx)
                                           (= :db.cardinality/many (:db/cardinality tx)))
                                  (:db/ident tx))))
                        tx-data)]
    (when (seq card-many)
      (swap! set-atom set/union card-many))))

(def retry-timeout-ms 120000)
(def retry-wait-ms 5000)
(def retryable-anomaly-categories #{:cognitect.anomalies/unavailable
                                    :cognitect.anomalies/interrupted
                                    :cognitect.anomalies/busy})
(defn with-retry
  ([func]
   (with-retry (t/>> (t/instant)
                     (t/new-duration retry-timeout-ms :millis))
     func))
  ([give-up-at func]
   (loop []
     (let [[res e]
           (try
             [(func) nil]
             (catch Exception e
               [nil e]))]
       (if (nil? e)
         res
         (cond
           (t/> (t/instant) give-up-at)
           (throw (ex-info "Giving up after retry timed out" {:exception e}))

           (some-> e ex-data
                   :cognitect.anomalies/category
                   retryable-anomaly-categories)
           (do
             (Thread/sleep retry-wait-ms)
             (recur))

           :else
           (do
             (tap> [:fatal-ex e (ex-data e)])
             (throw (ex-info "Unretryable exception thrown" {:exception e})))))))))

(defn- prepare-tx-metadata [{:keys [old->new ref-attrs tuple-ref-attrs]} tx-metadata]
  (into {}
        (map (fn [[attr value]]
               [attr (prepare-restore-value old->new ref-attrs tuple-ref-attrs attr value)]))
        tx-metadata))

(defn make-tx-data [{:keys [old->new ref-attrs tuple-ref-attrs cardinality-many-attrs] :as ctx} tx]
  (into [(merge (prepare-tx-metadata ctx (:tx tx)) {:db/id "datomic.tx"})]
        (prepare-restore-tx (:data tx)
                            old->new
                            ref-attrs
                            tuple-ref-attrs
                            @cardinality-many-attrs)))

(defn do-step [{:keys [conn progress! old->new cardinality-many-attrs txs] :as ctx} tx]
  (let [tx-data            (make-tx-data ctx tx)
        {tempids :tempids} (with-retry #(datomic/transact conn {:tx-data tx-data}))]
    (add-cardinality-many-attrs! cardinality-many-attrs tx-data)
    (progress!)
    (-> ctx
        (update :tx-data conj tx-data)
        (update :tx-data-result conj tempids)
        (update :step inc)
        (assoc :old->new (merge old->new tempids))
        (assoc :txs (rest txs)))))

(defn restore-ctx [conn rdr]
  (merge
   {:tuple-ref-attrs {}}
   (select-keys (read rdr) [:backup-timestamp :ref-attrs :tuple-attrs :tuple-ref-attrs])
   {:progress!              (progress-fn "transactions restored")
    :conn                   conn
    :max-steps              ##Inf
    :step                   0
    :tx-data-result         []
    :tx-data                []
    :cardinality-many-attrs (atom (into #{}
                                        (map first)
                                        (datomic/q '[:find ?ident
                                                     :where
                                                     [?a :db/cardinality :db.cardinality/many]
                                                     [?a :db/ident ?ident]]
                                                   (datomic/db conn))))
    :txs                    (read-seq rdr)
    :old->new               {}}))

(defn sanity-check [{:keys [ref-attrs tuple-attrs tuple-ref-attrs backup-timestamp] :as ctx}]
  (assert (set? ref-attrs) "Expected set of :ref-attrs in 1st backup form")
  (assert (map? tuple-attrs) "Expected map of :tuple-attrs in 1st backup form")
  (assert (map? tuple-ref-attrs) "Expected map of :tuple-ref-attrs in 1st backup form")
  (assert (inst? backup-timestamp) "Expected :backup-timestamp in 1st backup form")
  ctx)

(defonce ^:dynamic *abort?* (atom false))

(defn should-return? [{:keys [step max-steps txs]}]
  (or @*abort?*
      (> step max-steps)
      (nil? (first txs))))

(defn restore-loop [o-ctx]
  (loop [{:keys [txs] :as ctx} o-ctx]
    (if (should-return? ctx)
      ctx
      (recur (do-step ctx (first txs))))))

(defn restore-tx-file

  "Restore a backup by running the transactions in from the reader
  to the database pointed to by `conn`. It is assumed that
  the given database is empty.

  Returns the old->new id mapping."
  [conn rdr]
  (reset! *abort?* false)
  (-> (restore-ctx conn rdr)
      (sanity-check)
      (restore-loop)))
;; --------------------------------------------------
;; Public API

(defn backup-to
  "Backup all txns after backup-start in conn to file-path "
  [conn file-path backup-start]
  (let [out (clojure.java.io/writer file-path)]
    (output-all-tx backup-start conn out)))

(defn restore-from
  "Restore an edn file of txns to conn. Assumes conn is empty."
  [conn file-path]
  (restore-tx-file conn (java.io.PushbackReader. (io/reader file-path))))
