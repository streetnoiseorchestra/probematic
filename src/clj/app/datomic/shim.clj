(ns app.datomic.shim
  "Shim for migrating from datomic.client.api to datomic.api"
  (:require
   [datomic.client.api :as dc]
   [datomic.api :as d]))

(def ^:dynamic *DATOMIC-MODE* :client)

(defmacro with-datomic-mode [mode & body]
  `(binding [*DATOMIC-MODE* ~mode]
     ~@body))

(defn client [arg-map]
  (case *DATOMIC-MODE*
    :peer   (throw (ex-info "Peer does not support d/client" {}))
    :client (dc/client arg-map)))

(defn connect
  ([uri]
   (case *DATOMIC-MODE*
     :peer   (d/connect uri)
     :client (throw (ex-info "Client uses d/connect 2-arity" {}))))
  ([client arg-map]
   (case *DATOMIC-MODE*
     :peer   (throw (ex-info "Peer uses d/connect 1-arity" {}))
     :client (dc/connect client arg-map))))

(defn create-database
  ([uri]
   (case *DATOMIC-MODE*
     :peer   (d/create-database uri)
     :client (throw (ex-info "Client uses d/create-database 2-arity" {}))))
  ([client arg-map]
   (case *DATOMIC-MODE*
     :peer   (throw (ex-info "Peer uses d/create-database 1-arity" {}))
     :client (dc/create-database client arg-map))))

(defn db [conn]
  (case *DATOMIC-MODE*
    :peer   (d/db conn)
    :client (dc/db conn)))

(defn transact! [conn tx-map]
  (case *DATOMIC-MODE*
    :peer   @(d/transact conn (:tx-data tx-map))
    :client (dc/transact conn tx-map)))

(def transact transact!)

(defn history [db]
  (case *DATOMIC-MODE*
    :peer   (d/history db)
    :client (dc/history db)))

(defn as-of [db time-point]
  (case *DATOMIC-MODE*
    :peer   (d/as-of db time-point)
    :client (dc/as-of db time-point)))

(defn with [conn tx-map]
  (case *DATOMIC-MODE*
    :peer   (d/with (d/db conn) (:tx-data tx-map))
    :client (dc/with (dc/with-db conn) tx-map)))

(defn entid [db id]
  (case *DATOMIC-MODE*
    :peer   (d/entid db id)
    :client (:db/id (dc/pull db [:db/id] id))))

(defn q [& args]
  (case *DATOMIC-MODE*
    :peer   (apply d/q args)
    :client (apply dc/q args)))

(defn pull
  ([db arg-map]
   (case *DATOMIC-MODE*
     :peer   (throw (ex-info "Peer uses d/pull 3-arity" {}))
     :client (dc/pull db arg-map)))
  ([db pattern id]
   (case *DATOMIC-MODE*
     :peer   (d/pull db pattern id)
     :client (dc/pull db pattern id))))

(defn attr [db id attr-name]
  (case *DATOMIC-MODE*
    :peer   (attr-name (d/entity db id))
    :client (attr-name (dc/pull db [attr-name] id))))

(defn attr-type [db attr-name]
  (attr db attr-name :db/valueType))

(defn attr-is-unique? [db attr-name]
  (some? (attr db attr-name :db/unique)))

(defn attr-is-ref? [db attr-name]
  (= (attr-type db attr-name) :db.type/ref))
