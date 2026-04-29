(ns app.datomic.decant
  (:require [app.datomic.client-backup :as backup]
            [app.datomic.shim :as shim]
            #_[datomic.client.api :as d-client]))

#_(def org-conn
    (let [db-name {:db-name "probematic"} client (d-client/client
                                                  {:server-type :datomic-local
                                                   :system      "app"
                                                   :storage-dir "/home/ramblurr/src/sno/probematic/datomic.data"})]
      (d-client/create-database client db-name)
      (d-client/connect client db-name)))

#_(def test-restore-conn
    (let [db-name {:db-name "probematic"} client (d-client/client
                                                  {:server-type :datomic-local
                                                   :system      "app"
                                                   :storage-dir "/home/ramblurr/src/sno/probematic/datomic.data.restored"})]
      (d-client/create-database client db-name)
      (d-client/connect client db-name)))

(comment

  (do
    (require '[portal.api :as p])
    (require '[com.brunobonacci.mulog :as mu])
    (def p (p/open {:theme :portal.colors/gruvbox}))
    (add-tap #'p/submit)

    (require '[datomic.api :as d-peer])
    (def dst-conn (let [db-uri "datomic:sql://app?jdbc:sqlite:data.dev/datomic/data/datomic-sqlite.db"]
                    (d-peer/create-database db-uri)
                    (d-peer/connect db-uri)))) ;; rcf
  (shim/with-datomic-mode :peer
    (shim/current-mode))
  ;;
  )
(comment
  ;; Backup.
  ;; Tuple ref rewriting and transaction metadata ref rewriting are handled by
  ;; app.datomic.client-backup during restore.
  (backup/backup-to org-conn "txns.edn" #inst "2023-01-01T00:00:00.000-00:00")
  ;; Restore
  (restore-from dst-conn "txns.edn")

  (reset! backup/*abort?* true)
  ;;
  )
(defn restore-from [conn f]
  (future (shim/with-datomic-mode :peer
            (backup/restore-from conn f))))
