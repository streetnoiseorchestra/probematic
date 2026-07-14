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

(def ^:private remove-member-avatar-function
  (d/function
   {:lang "clojure"
    :params '[db member expected-avatar]
    :requires '[[datomic.api :as d]]
    :code
    '(let [member-eid (d/entid db member)
           current-avatar
           (ffirst
            (d/q '[:find ?avatar
                   :in $ ?member
                   :where [?member :member/avatar ?avatar]]
                 db
                 member-eid))]
       (if (= current-avatar expected-avatar)
         (cond-> []
           current-avatar
           (conj [:db/retract member-eid :member/avatar current-avatar]
                 [:db/retractEntity current-avatar]))
         (throw
          (ex-info "The member avatar changed before it could be removed"
                   {:member member
                    :expected-avatar expected-avatar
                    :current-avatar current-avatar}))))}))

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

(defn schema-index-upgrades
  "Returns index transactions required before altering unique attributes.

  Datomic must finish indexing an existing attribute before uniqueness can be
  added or changed. New attributes and attributes already using the desired
  uniqueness require no preparatory transaction."
  [db schema]
  (->> schema
       (keep (fn [{:db/keys [ident unique]}]
               (when (and ident
                          unique
                          (d/entid db ident)
                          (not= unique
                                (get-in (d/pull db
                                                '[{:db/unique [:db/ident]}]
                                                ident)
                                        [:db/unique :db/ident])))
                 {:db/id ident
                  :db/index true})))
       vec))

(defn transact-schema
  "Installs schema metadata and the application schema.

  Existing unique-attribute alterations are prepared and indexed first so the
  same schema works for both fresh and long-lived databases. Returns the final
  transaction future."
  [conn]
  @(d/transact conn (-> (io/resource "schema-meta.edn") slurp edn/read-string))
  (let [schema (edn/read-string
                {:readers *data-readers*}
                (slurp (io/resource "schema.edn")))
        index-upgrades (schema-index-upgrades (d/db conn) schema)]
    (when (seq index-upgrades)
      (μ/log ::schema-index-upgrade
             :attributes (mapv :db/id index-upgrades))
      (let [tx-report @(d/transact conn index-upgrades)]
        @(d/sync-schema conn (d/basis-t (:db-after tx-report)))))
    (d/transact
     conn
     (conj schema
           {:db/ident :app.account/remove-member-avatar
            :db/fn remove-member-avatar-function}))))

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
