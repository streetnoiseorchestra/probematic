(ns app.datomic.decant
  (:require [app.datomic.client-backup :as backup]
            ;; [clojure.java.io :as io]
            [datomic.api :as d-peer]
            [datomic.client.api :as d-client]))

(def org-conn
  (let [db-name {:db-name "probematic"} client (d-client/client
                                                {:server-type :datomic-local
                                                 :system      "app"
                                                 :storage-dir "/home/ramblurr/src/sno/probematic/datomic.data"})]
    (d-client/create-database client db-name)
    (d-client/connect client db-name)))

(def test-restore-conn
  (let [db-name {:db-name "probematic"} client (d-client/client
                                                {:server-type :datomic-local
                                                 :system      "app"
                                                 :storage-dir "/home/ramblurr/src/sno/probematic/datomic.data.restored"})]
    (d-client/create-database client db-name)
    (d-client/connect client db-name)))

(def dst-conn (let [db-uri "datomic:sql://app?jdbc:sqlite:data.dev/datomic/data/datomic-sqlite.db"]
                (d-peer/create-database db-uri)
                (d-peer/connect db-uri)))

(comment
  (def client-txs (all-transactions org-conn))
  (count client-txs)
  (first client-txs)
  (d-client/datoms)
  (let [t0 (-> (d-client/tx-range org-conn nil)
               seq
               (or (throw (ex-info
                           "org-conn has an empty Log"
                           {})))
               first
               :t)]
    (d-client/tx)
    t0)

  ;; Backup
  (backup/backup-to org-conn "txns.edn")
  ;; Restore
  (backup/restore-tx-file test-restore-conn (java.io.PushbackReader. (io/reader "txns.edn")))

  (def old->new (let [rdr (java.io.PushbackReader. (io/reader "txns.edn"))]
                  (restore-tx-file dst-conn rdr)))

  :done

  (d-peer/q '[:find (pull ?e [*])
              :in $
              :where [?e :gig/title _]]
            (d-peer/db dst-conn))
  1

  (d-peer/q
   '[:find ?e
     :where [?e _ "Probentag"]]
   (d-peer/db dst-conn))
  (let [rdr                                                      (java.io.PushbackReader. (io/reader "txns.edn"))
        {:keys [backup-timestamp ref-attrs tuple-attrs] :as wtf} (read rdr)
        _                                                        (assert ref-attrs)
        txs                                                      (backup/read-seq rdr)
        cardinality-many-attrs                                   (atom (into #{}
                                                                             (map first)
                                                                             (d-peer/q '[:find ?ident
                                                                                         :where
                                                                                         [?a :db/cardinality :db.cardinality/many]
                                                                                         [?a :db/ident ?ident]]
                                                                                       (d-peer/db dst-conn))))
        tx                                                       (second txs)
        tx-data                                                  (into [(merge (:tx tx)
                                                                               {:db/id "datomic.tx"})]
                                                                       (backup/prepare-restore-tx (:data tx)
                                                                                                  old->new
                                                                                                  ref-attrs
                                                                                                  @cardinality-many-attrs))
        #_#_                                                     {tempids :tempids} (backup/with-retry
                                                                                      #(d-peer/transact conn tx-data))]
    (tap> [:tx-data-is tx-data])
    #_(backup/add-cardinality-many-attrs! cardinality-many-attrs tx-data)
    #_(progress!)
    ;; Update old->new mapping with entity ids created in this tx
    #_(recur (merge old->new tempids)
             (rest txs)))

  ;;
  )

(defn restore-tx-file
  "Restore a backup by running the transactions in from the reader
  to the database pointed to by `conn`. It is assumed that
  the given database is empty.

  Returns the old->new id mapping."
  [conn rdr]
  ;; Read first form which is the mapping containing info about the backup
  (let [{:keys [backup-timestamp ref-attrs tuple-attrs]} (read rdr)
        ;; Initial set of card many attributes, tx processing will add any new ones here
        cardinality-many-attrs                           (atom (into #{}
                                                                     (map first)
                                                                     (d-peer/q '[:find ?ident
                                                                                 :where
                                                                                 [?a :db/cardinality :db.cardinality/many]
                                                                                 [?a :db/ident ?ident]]
                                                                               (d-peer/db conn))))
        progress!                                        (backup/progress-fn "transactions restored")]
    (assert (set? ref-attrs) "Expected set of :ref-attrs in 1st backup form")
    (assert (map? tuple-attrs) "Expected map of :tuple-attrs in 1st backup form")
    (assert (inst? backup-timestamp) "Expected :backup-timestamp in 1st backup form")
    (loop [old->new {}

           ;; Read rest of the forms (tx data) without retaining head
           txs (backup/read-seq rdr)]
      (if-let [tx (first txs)]
        (let [tx-data (into [(merge (:tx tx)
                                    {:db/id "datomic.tx"})]
                            (backup/prepare-restore-tx (:data tx)
                                                       old->new
                                                       ref-attrs
                                                       @cardinality-many-attrs))
              {tempids :tempids}
              (backup/with-retry
                #(d-peer/transact conn tx-data))]
          (backup/add-cardinality-many-attrs! cardinality-many-attrs tx-data)
          (progress!)
          ;; Update old->new mapping with entity ids created in this tx
          (recur (merge old->new tempids)
                 (rest txs)))
        old->new))))
