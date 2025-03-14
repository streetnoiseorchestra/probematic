(ns app.datomic.client-backup
  "Backup/restore a datomic dev-local database

  When backing up the datomic we go through all the transactions that have happened to the db
  and write them to a file.

  Some of the transactions will be Datomic internal, which we can't transact again,
  these should be in the `ignore-attributes
  "
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [datomic.client.api :as d-client]))

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

(defn- all-transactions
  "Returns all tx identifiers in time order.
  Skips the initial transactions empty databases have."
  [backup-start conn]
  (d-client/tx-range conn {:start backup-start :limit -1}))

(defn- attr-info [db attr-info-cache a]
  (get (swap! attr-info-cache
              (fn [attrs]
                (if (contains? attrs a)
                  attrs
                  (assoc attrs a (d-client/pull @db '[:db/ident :db/valueType] a)))))
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
  (let [db                (d-client/db conn)
        attr-ident-cache  (atom {})
        out!              #(binding [*out* out] (prn %))
        ref-attrs         (into old-ref-attrs
                                (comp
                                 (map first)
                                 (remove #(str/starts-with? (str %) ":db")))
                                (d-client/q '[:find ?id
                                              :where
                                              [?attr :db/ident ?id]
                                              [?attr :db/valueType :db.type/ref]]
                                            db))
        tuple-attrs       (into old-tuple-attrs
                                (d-client/q '[:find ?id ?ta
                                              :where
                                              [?attr :db/ident ?id]
                                              [?attr :db/tupleAttrs ?ta]]
                                            db))
        ignore-attributes (into ignore-attributes
                                (keys tuple-attrs))
        progress!         (progress-fn "backup transactions written")]

    (out! {:ref-attrs        ref-attrs
           :tuple-attrs      tuple-attrs
           :backup-timestamp (java.util.Date.)})
    (doseq [tx    (all-transactions backup-start conn)
            :let  [tx-map (output-tx (delay (d-client/as-of db (:t tx))) attr-ident-cache tx
                                     ignore-attributes)]
            :when (and (seq (:data tx-map))
                       (.after (get-in tx-map [:tx :db/txInstant]) backup-start))]
      (out! tx-map)
      (progress!))))

;; --------------------------------------------------
;; Restore

(defn prepare-restore-tx
  "Prepare transaction for restore.

  Takes tx datoms, old to new id mapping and set of reference attributes.
  Returns sequence of new datoms for the restore tx.

  Looks up entity ids and reference values from the old->new mapping."
  [tx-data old->new ref-attrs cardinality-many-attrs]
  (let [->id                     #(let [s (str %)]
                                    (or (old->new s) s))
        {card-many-datoms true
         card-one-datoms  false} (group-by (comp boolean cardinality-many-attrs
                                                 second)
                                           tx-data)]
    (concat
     ;; Output map tx for all cardinality one values, filtering out retractions
     ;; that have an assertion for the same attribute
     (mapcat (fn [[e datoms]]
               (let [e (->id e)

                     ;; Group by assertions and retractions
                     {asserted  true
                      retracted false}
                     (group-by #(nth % 3) datoms)

                     asserted-map (when (seq asserted)
                                    (into {:db/id e}
                                          (map (fn [[_ a v _]]
                                                 [a (if (ref-attrs a)
                                                      (->id v) v)]))
                                          asserted))]
                 (into (if asserted-map
                         [asserted-map]
                         [])
                       (for [[_ a v _] retracted
                             :when     (not (contains? asserted-map a))]
                         [:db/retract e a (if (ref-attrs a)
                                            (->id v) v)]))))
             (group-by first card-one-datoms))

     ;; Output add or retract clauses for any many cardinality values
     (for [[e a v add?] card-many-datoms
           :let         [ref? (ref-attrs a)
                         e (->id e)
                         v (if ref?
                             (->id v)
                             v)]]
       [(if add? :db/add :db/retract) e a v]))))

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

(def retry-timeout-ms 60000)
(def retry-wait-ms 2000)
(def retryable-anomaly-categories #{:cognitect.anomalies/unavailable
                                    :cognitect.anomalies/interrupted
                                    :cognitect.anomalies/busy})
(defn with-retry
  ([func]
   (with-retry (+ (System/currentTimeMillis)
                  retry-timeout-ms)
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
           (> (System/currentTimeMillis) give-up-at)
           (throw (ex-info "Giving up after retry timed out"
                           {:exception e}))

           (some-> e ex-data
                   :cognitect.anomalies/category
                   retryable-anomaly-categories)
           (do
             (Thread/sleep retry-wait-ms)
             (recur))

           :else
           (throw (ex-info "Unretryable exception thrown"
                           {:exception e}))))))))

(defn- restore-tx-file
  "Restore a backup by running the transactions in from the reader
  to the database pointed to by `conn`. It is assumed that
  the given database is empty.

  Returns the old->new id mapping."
  [conn rdr]
  ;; Read first form which is the mapping containing info about the backup
  (let [{:keys [backup-timestamp ref-attrs tuple-attrs]} (read rdr)

        ;; Initial set of card many attributes, tx processing will add any new ones here
        cardinality-many-attrs (atom (into #{}
                                           (map first)
                                           (d-client/q '[:find ?ident
                                                         :where
                                                         [?a :db/cardinality :db.cardinality/many]
                                                         [?a :db/ident ?ident]]
                                                       (d-client/db conn))))
        progress!              (progress-fn "transactions restored")]
    (assert (set? ref-attrs) "Expected set of :ref-attrs in 1st backup form")
    (assert (map? tuple-attrs) "Expected map of :tuple-attrs in 1st backup form")
    (assert (inst? backup-timestamp) "Expected :backup-timestamp in 1st backup form")
    (loop [old->new {}

           ;; Read rest of the forms (tx data) without retaining head
           txs (read-seq rdr)]
      (if-let [tx (first txs)]
        (let [tx-data (into [(merge (:tx tx)
                                    {:db/id "datomic.tx"})]
                            (prepare-restore-tx (:data tx)
                                                old->new
                                                ref-attrs
                                                @cardinality-many-attrs))
              {tempids :tempids}
              (with-retry
                #(d-client/transact
                  conn
                  {:tx-data tx-data}))]
          (add-cardinality-many-attrs! cardinality-many-attrs tx-data)
          (progress!)
          ;; Update old->new mapping with entity ids created in this tx
          (recur (merge old->new tempids)
                 (rest txs)))
        ;; old->new
        :restored))))
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
