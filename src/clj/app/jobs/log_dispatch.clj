(ns app.jobs.log-dispatch
  "Copies committed job intent from Datomic into Dollop's SQLite database."
  (:require
   [clojure.edn :as edn]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [datomic.api :as d]
   [malli.core :as m]
   [s-exp.drip :as drip]
   [sqlite4clj.core :as sqlite]))

(def JobIntents
  [:vector [:tuple [:string {:min 1}] :map :map]])

(defn- validate-jobs! [jobs]
  (when-not (m/validate JobIntents jobs)
    (throw (ex-info "Invalid durable job intent" {})))
  jobs)

(>defn intent-tx
  "Returns transaction data that records `jobs` with a business change.

  Each job is `[kind arguments options]`, using Dollop's insert options.
  Arguments and options must round-trip through EDN. This function does not
  insert jobs into SQLite; include its result in the business transaction."
  [jobs]
  [JobIntents => [:vector :any]]
  (validate-jobs! jobs)
  (if (seq jobs)
    (let [intent {:version 1 :jobs jobs}
          text   (binding [*print-length*   nil  *print-level* nil   *print-meta* false
                           *print-readably* true *print-dup*   false]
                   (pr-str intent))]
      (when-not (= intent (edn/read-string text))
        (throw (ex-info "Job intent does not round-trip through EDN" {})))
      [{:db/id "datomic.tx" :audit/jobs text}])
    []))

(defn- source-id [db]
  (:app.log/source-id (d/entity db :app.log/source)))

(defn- ensure-source-id! [conn]
  (or (source-id (d/db conn))
      (let [id (random-uuid)]
        (try
          @(d/transact conn
                       [[:db.fn/cas :app.log/source :app.log/source-id nil id]
                        {:db/id        "datomic.tx"
                         :audit/action ::initialize
                         :audit/origin :app.origin/system}])
          id
          (catch Exception e
            ;; Another initializer may have won the compare-and-swap.
            (or (source-id (d/db conn)) (throw e)))))))

(defn- cursor [tx]
  (first (sqlite/q tx ["SELECT source_id, last_t FROM app_datomic_log_cursor WHERE id = 1"])))

(defn- check-source! [[stored-id last-t] id basis-t]
  (when-not (and (= stored-id (str id)) (<= last-t basis-t))
    (throw (ex-info "Log cursor does not match the source database"
                    {:cursor-t last-t :basis-t basis-t})))
  last-t)

(>defn initialize!
  "Initializes the log cursor in the job database before the frame loop starts.

  `client` must be the Dollop client used by the workers. On first use,
  `cutover-t` is required: transactions at or before it produce no jobs.
  Restarts preserve the saved cursor; passing nil does not reset it.
  Rejects a different source database or a cursor ahead of its current basis.
  The real application schema must already be installed in `conn`."
  [conn client cutover-t]
  [:any :any [:maybe nat-int?] => nat-int?]
  (drip/with-tx [tx client]
    (sqlite/q tx ["CREATE TABLE IF NOT EXISTS app_datomic_log_cursor (id INTEGER PRIMARY KEY CHECK (id = 1), source_id TEXT NOT NULL, last_t INTEGER NOT NULL CHECK (last_t >= 0))"])
    (when (and (nil? (cursor tx)) (nil? cutover-t))
      (throw (ex-info "First log startup requires an explicit cutover transaction" {}))))
  (let [id      (ensure-source-id! conn)
        basis-t (d/basis-t (d/db conn))]
    (drip/with-tx [tx client]
      (if-let [saved (cursor tx)]
        (check-source! saved id basis-t)
        (do
          (when (> cutover-t basis-t)
            (throw (ex-info "Cutover transaction is ahead of the source database"
                            {:cutover-t cutover-t :basis-t basis-t})))
          (sqlite/q tx ["INSERT INTO app_datomic_log_cursor (id, source_id, last_t) VALUES (1, ?, ?)"
                        (str id) cutover-t])
          cutover-t)))))

(defn- transaction-jobs [db t]
  (if-let [text (:audit/jobs (d/entity (d/as-of db t) (d/t->tx t)))]
    (let [{:keys [version jobs]} (edn/read-string text)]
      (when-not (= 1 version)
        (throw (ex-info "Unsupported durable job intent version" {:source-t t})))
      (validate-jobs! jobs))
    []))

(>defn dispatch-pending!
  "Copies up to `limit` committed transactions into the job database.

  Call during the writer phase, never during rendering. Each source transaction
  gets its own SQLite transaction: all its jobs and the cursor commit together.
  An exception stops this pass without advancing past the failed transaction.
  Transactions without jobs still advance the cursor. Returns the last saved t.

  Adds the server-owned `:source-t` to job arguments. Uses transactional Dollop
  insertion; workers discover committed jobs through their normal polling.
  This does not make external delivery exactly-once or replay old transactions
  when application code changes."
  [conn client limit]
  [:any :any pos-int? => nat-int?]
  (let [db           (d/db conn)
        basis-t      (d/basis-t db)
        id           (source-id db)
        start-t      (drip/with-tx [tx client]
                       (let [saved (cursor tx)]
                         (when-not saved
                           (throw (ex-info "Log cursor is not initialized" {})))
                         (check-source! saved id basis-t)))
        transactions (take limit (d/tx-range (d/log conn) (inc start-t) (inc basis-t)))]
    (reduce
     (fn [previous-t {:keys [t]}]
       (let [jobs (transaction-jobs db t)]
         (drip/with-tx [tx client]
           (let [[_ saved-t :as saved] (cursor tx)]
             (check-source! saved id basis-t)
             (when-not (= previous-t saved-t)
               (throw (ex-info "Another log consumer advanced the cursor" {})))
             (doseq [[kind args opts] jobs]
               (drip/insert-job! client tx kind (assoc args :source-t t) opts))
             (sqlite/q tx ["UPDATE app_datomic_log_cursor SET last_t = ? WHERE id = 1" t])))
         t))
     start-t
     transactions)))
