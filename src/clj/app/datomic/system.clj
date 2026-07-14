(ns app.datomic.system
  (:require

   [app.datomic.shim :as shim]
   [datomic.local :as dl]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as μ]
   [com.fulcrologic.guardrails.malli.core :refer [>defn-]]
   [datomic.client.api :as dc]
   [datomic.api :as d]
   [app.datomic.migrations :as migrations]))

(def MigrateableComponent
  [:map
   [:migrations migrations/MigrationInputSchema]])

(def MigrateableComponentsMap
  [:map-of :keyword MigrateableComponent])

(>defn- gather-migrations
        "Gathers migrations from the provided components"
        [migration-components]
        [MigrateableComponentsMap => migrations/MigrationInputSchema]
        (mapcat :migrations (vals migration-components)))

(>defn- ensure-and-connect [db-uri]
        [migrations/DatomicDbUriSchema => migrations/DatomicConnectionSchema]
        (when (d/create-database db-uri)
          (μ/log ::db-created :msg "Datomic database created"))
        (d/connect db-uri))

(defn transact-schema [conn]
  @(d/transact conn (-> (io/resource "schema-meta.edn") slurp edn/read-string))
  (let [schema-data (edn/read-string
                     {:readers *data-readers*}
                     (slurp (io/resource "schema.edn")))
        db           (d/db conn)
        unique-alters
        (keep (fn [{:db/keys [ident index unique]}]
                (when (and ident index unique (d/entid db ident))
                  (let [current (d/pull db [:db/index :db/unique] ident)]
                    (when-not (:db/unique current)
                      {:current current :ident ident}))))
              schema-data)
        needs-avet  (keep #(when-not (get-in % [:current :db/index])
                             (:ident %))
                          unique-alters)
        indexed-db  (if (seq needs-avet)
                      (:db-after
                       @(d/transact
                         conn
                         (mapcat (fn [ident]
                                   [[:db/add ident :db/index true]
                                    [:db/add :db.part/db
                                     :db.alter/attribute ident]])
                                 needs-avet)))
                      db)]
    (when (seq unique-alters)
      @(d/sync-schema conn (d/basis-t indexed-db)))
    (d/transact conn schema-data)))

(defn start-peer [{:keys [peer]}]
  (assert (:db-uri peer))
  (let [conn           (ensure-and-connect (:db-uri peer))
        #_#_migrations (gather-migrations (:migration-components peer))]
    @(transact-schema conn)
    #_(when migrations
        (μ/log ::db-migrations :msg "Datomic installing schema migrations")
        (migrations/install-schema conn migrations))
    (μ/log ::db-connected :msg "Datomic database started successfully")

    (assoc peer :conn conn)))

(defn stop-peer [config]
  (d/release (:conn config)))

(defn start-client [{:keys [client]}]
  (let [db-name (select-keys client [:db-name])
        _       (tap> [:connect-map (select-keys client [:server-type :system :storage-dir]) db-name])
        c       (dc/client (select-keys client [:server-type :system :storage-dir]))
        _       (dc/create-database c db-name)
        conn    (dc/connect c db-name)]
    #_(datomic.migrations/migrate! (:env client) conn migrations/migration-fns)
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
