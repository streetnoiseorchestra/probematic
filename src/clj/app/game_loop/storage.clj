(ns app.game-loop.storage
  "Probematic's Datomic snapshots and scoped SQLite reads for the game loop."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [datomic.api :as d]
   [sqlite4clj.core :as sqlite]))

(defn with-read-dbs
  "Calls `f` with a map of borrowed SQLite read transactions.

  `dbs` maps names to values from [[sqlite4clj.core/init-db!]]. All reads
  must finish inside `f`; connections return to their pools on exit."
  [dbs f]
  (if-let [[k db] (first dbs)]
    (sqlite/with-read-tx [tx (:reader db)]
      (with-read-dbs (next dbs) #(f (assoc % k tx))))
    (f {})))

(>defn render-hooks
  "Returns frame capture and worker resource functions for Probematic.

  `datomic-conn` is required. `dbs` maps names to caller-owned sqlite4clj
  databases. Frames contain one shared Datomic `:db`; workers receive
  borrowed SQLite transactions under `::read-dbs`. Those transactions
  must not escape the render callback. Stop the loop before closing stores."
  [datomic-conn dbs]
  [[:fn #(instance? datomic.Connection %)]
   [:map-of :keyword
    [:map
     [:reader
      [:map [:conn-pool [:fn #(instance? java.util.concurrent.BlockingQueue %)]]]]]]
   =>
   [:map
    [:capture-frame [:fn ifn?]]
    [:with-render-context [:fn ifn?]]]]
  {:capture-frame       (fn [ctx] (assoc ctx :db (d/db datomic-conn)))
   :with-render-context (fn [frame-ctx render!]
                          (with-read-dbs dbs
                            (fn [read-dbs]
                              (render! (assoc frame-ctx ::read-dbs read-dbs)))))})

(comment
  (require '[app.datomic.system :as datomic-system]
           '[app.game-loop :as game]
           '[app.sqlite :as app-sqlite]
           '[clojure.main :as main])

  (def demo-uri (str "datomic:mem://game-loop-demo-" (random-uuid)))
  (d/create-database demo-uri)
  (def demo-conn (d/connect demo-uri))
  (datomic-system/prepare-database! demo-conn)

  ;; In-memory demo; use a file-backed database to exercise parallel SQLite reads.
  (def demo-db (sqlite/init-db! ":memory:" {:pool-size 1}))
  (sqlite/with-write-tx [tx (:writer demo-db)]
    (sqlite/q tx ["CREATE TABLE counter (value INTEGER)"])
    (sqlite/q tx ["INSERT INTO counter VALUES (0)"]))
  (def demo-output (atom []))
  (def demo
    (game/start-batch-loop!
     {:app.game-loop/conns       (java.util.concurrent.ConcurrentHashMap.)
      :app.game-loop/render-pool (game/start-render-pool {:pool-size 1})}
     (assoc (render-hooks demo-conn {:main demo-db})
            :process-batch!
            (fn [_ amounts]
              (doseq [amount amounts]
                (try
                  (sqlite/with-write-tx [tx (:writer demo-db)]
                    (sqlite/q tx ["UPDATE counter SET value = value + ?" amount]))
                  (catch Exception e
                    (main/repl-caught e))))))))
  (.put ^java.util.concurrent.ConcurrentHashMap (:app.game-loop/conns demo) :counter
        (game/render-callback
         ;; render-fn
         (fn [ctx] (pr-str (sqlite/q (get-in ctx [::read-dbs :main]) ["SELECT value FROM counter"])))
         ;; send-fn
         (fn [html] (swap! demo-output conj html) true)))

  ((:app.game-loop/submit! demo) 1)
  ((:app.game-loop/submit! demo) 2)
  @demo-output

  ((:app.game-loop/stop! demo))
  (.close ^java.util.concurrent.ExecutorService (:app.game-loop/render-pool demo))
  (app-sqlite/stop demo-db)
  (d/release demo-conn)
  (d/delete-database demo-uri)
  :rcf)
