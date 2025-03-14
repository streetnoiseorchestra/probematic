(ns app.datomic.system
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as μ]
   [integrant.core :as ig]
   [com.fulcrologic.guardrails.malli.core :refer [>defn-]]
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
  (d/transact conn (-> (io/resource "schema.edn") slurp edn/read-string)))

(defmethod ig/init-key ::datomic-pro
  [_ config]
  (let [conn       (ensure-and-connect (:db-uri config))
        migrations (gather-migrations (:migration-components config))]
    (transact-schema conn)
    (when migrations
      (μ/log ::db-migrations :msg "Datomic installing schema migrations")
      (migrations/install-schema conn migrations))
    (μ/log ::db-connected :msg "Datomic database started successfully")
    conn))

(defmethod ig/halt-key! ::datomic-pro
  [_ server]
  (μ/log ::db-stop))

(comment
  (def uri "datomic:sql://app?jdbc:sqlite:data/datomic-sqlite.db")
  (d/create-database uri)
  (def conn (d/connect uri))
  ;; rcf
  )
