(ns app.sqlite
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sqlite4clj.core :as d]
            [sqlite4clj.impl.api :as api]))

(defn start [{:keys [filename config]}]
  (when (str/blank? filename)
    (throw (ex-info "SQLite filename is required" {})))
  (when-not (= ":memory:" filename)
    (io/make-parents filename))
  (d/init-db! filename config))

(defn stop
  "Closes the database after all callers have stopped using it. Call once."
  [db]
  (let [pools  (if (identical? (:reader db) (:writer db))
                 [(:writer db)]
                 [(:reader db) (:writer db)])
        codes  (for [pool pools
                     _    (:connections pool)]
                 (let [conn (.take ^java.util.concurrent.BlockingQueue (:conn-pool pool))]
                  ;; sqlite4clj does not finalize its cached statements on close.
                   (doseq [cached (vals @(:stmt-cache conn))]
                     (api/finalize (:stmt (force cached))))
                   (api/close (:pdb conn))))
        errors (into [] (remove zero?) codes)]
    (when (seq errors)
      (throw (ex-info "Could not close SQLite connections" {:codes errors})))))
