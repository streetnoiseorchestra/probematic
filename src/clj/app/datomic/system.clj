(ns app.datomic.system
  (:require
   [app.datomic.migrations :as migrations]
   [app.datomic.shim :as shim]
   [com.brunobonacci.mulog :as μ]
   [com.fulcrologic.guardrails.malli.core :refer [>defn-]]
   [datomic.api :as d]
   [datomic.client.api :as dc]
   [datomic.local :as dl]
   [dev.gethop.stork :as stork]))

(def ^:private DatomicDbUriSchema
  [:re {:error/message "should be a Datomic database URI"} #"datomic:.*"])

(def ^:private DatomicConnectionSchema
  [:fn #(instance? datomic.Connection %)])

(def ^:private schema-sync-timeout-ms
  (* 5 60 1000))

(>defn- ensure-and-connect [db-uri]
        [DatomicDbUriSchema => DatomicConnectionSchema]
        (when (d/create-database db-uri)
          (μ/log ::db-created :msg "Datomic database created"))
        (d/connect db-uri))

(defn- sync-schema! [conn phase]
  (let [basis-t (d/basis-t (d/db conn))
        result  (deref (d/sync-schema conn basis-t)
                       schema-sync-timeout-ms
                       ::timed-out)]
    (when (= ::timed-out result)
      (throw
       (ex-info
        "Timed out waiting for Datomic schema synchronization"
        {:phase phase
         :timeout-ms schema-sync-timeout-ms})))
    result))

(defn- install-migrations! [conn migrations]
  (doseq [{:keys [id] :as migration} migrations]
    (let [migration (assoc migration
                           :stork.setting/sync-schema-timeout
                           schema-sync-timeout-ms)
          result    (stork/ensure-installed conn migration)
          status    (if (or (nil? result)
                            (= ::stork/already-installed result))
                      :skipped
                      :applied)]
      (μ/log ::migration-complete
             :migration-id id
             :status status))))

(defn prepare-database!
  "Prepares `conn` for the authoritative Peer runtime.

  This function blocks until the metadata schema, pre-schema migrations,
  canonical schema, and post-schema migrations complete. It returns the final
  database value and propagates failures to the caller."
  [conn]
  (μ/log ::metadata-schema-start
         :msg "Datomic installing application schema metadata")
  @(d/transact conn (stork/read-resource "schema-meta.edn"))
  (μ/log ::metadata-schema-complete
         :msg "Datomic application schema metadata installed")

  (μ/log ::pre-schema-start
         :msg "Datomic starting pre-schema migrations")
  (install-migrations! conn migrations/pre-schema-migrations)
  (sync-schema! conn :pre-schema)
  (μ/log ::pre-schema-complete
         :msg "Datomic pre-schema migrations completed")

  (μ/log ::canonical-schema-start
         :msg "Datomic installing canonical application schema")
  @(d/transact conn (stork/read-resource "schema.edn"))
  (sync-schema! conn :canonical-schema)
  (μ/log ::canonical-schema-complete
         :msg "Datomic canonical application schema installed")

  (μ/log ::post-schema-start
         :msg "Datomic starting post-schema migrations")
  (install-migrations! conn migrations/post-schema-migrations)
  (μ/log ::post-schema-complete
         :msg "Datomic post-schema migrations completed")

  (let [db (d/db conn)]
    (μ/log ::database-prepared
           :msg "Datomic database preparation completed"
           :basis-t (d/basis-t db))
    db))

(defn start-peer [{:keys [peer]}]
  (assert (:db-uri peer))
  (let [conn (ensure-and-connect (:db-uri peer))]
    (try
      (prepare-database! conn)
      (μ/log ::db-connected :msg "Datomic database started successfully")
      (assoc peer :conn conn)
      (catch Throwable throwable
        (try
          (d/release conn)
          (catch Throwable release-error
            (.addSuppressed throwable release-error)))
        (throw throwable)))))

(defn stop-peer [config]
  (d/release (:conn config)))

(defn start-client [{:keys [client]}]
  (let [db-name (select-keys client [:db-name])
        _       (tap> [:connect-map (select-keys client [:server-type :system :storage-dir]) db-name])
        c       (dc/client (select-keys client [:server-type :system :storage-dir]))
        _       (dc/create-database c db-name)
        conn    (dc/connect c db-name)]
    (assoc client :conn conn)))

(defn stop-client [{:keys [client]}]
  (dl/release-db (select-keys client [:storage-dir :system :db-name])))

(defn start [config]
  (case (shim/current-mode)
    :peer   (start-peer config)
    :client (start-client config)))

(defn stop [config]
  (case (shim/current-mode)
    :peer   (stop-peer config)
    :client (stop-client config)))

(comment
  (def uri "datomic:sql://app?jdbc:sqlite:data.dev/datomic/data/datomic-sqlite.db")
  (d/create-database uri)
  (def conn (d/connect uri))
  ;; rcf
  )
