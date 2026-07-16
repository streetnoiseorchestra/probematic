#!/usr/bin/env bb
(ns datomic-prod-sync
  "Syncs a production Datomic Local dump into the local Datomic Pro dev store.

  Run from the project root where `bb.edn` lives.
  Pass the path where the production Datomic Local data was synced.
  The path may be the dump root, the nested `datomic` storage directory, or a sync root containing `prod-local-*` dumps.

  First make a consistent production dump.
  On `sno`, stop the production service from `/root/services/snorga.streetnoise.at`, then restart it after the copy finishes:

  ```bash
  ssh sno 'cd /root/services/snorga.streetnoise.at && docker compose stop'
  rsync -avr sno:/srv/snorga.streetnoise.at/app/datomic data.dev/prod-sync/prod-local-$(date +'%Y-%m-%d-%H%M%S')
  ssh sno 'cd /root/services/snorga.streetnoise.at && docker compose up -d'
  ```

  Full export, staging restore, validation, and promotion:

  ```bash
  scripts/datomic_prod_sync.clj data.dev/prod-sync/prod-local-2026-04-29-094854
  ```

  Staging restore and validation only:

  ```bash
  scripts/datomic_prod_sync.clj data.dev/prod-sync/prod-local-2026-04-29-094854 --no-promote
  ```

  Before running, stop app and REPL processes that may hold Datomic peer connections.
  If the source dump has stale Datomic Local locks, remove `lock` or `.lock` files from the copied dump before running.
  The staging transactor uses host port `4434`, so that port must be free.

  The script first exports production txs from Datomic Local, then restores into a fresh staging Datomic Pro SQLite store under `data.dev/prod-sync/staging-pro-<run-id>`.
  It validates staging by comparing export-derived final counts and the max `:db/txInstant` against the staging database.
  If promotion is enabled, it stops the active dev Datomic compose services, stops the staging transactor, backs up the whole active `data.dev/datomic` directory to `archive/datomic.pro.pre-promote.<run-id>`, copies only staging `data/` into `data.dev/datomic/data`, restarts compose, and validates active dev.

  Generated exports, reports, helper scripts, and id mappings stay under `data.dev/prod-sync` for audit and debugging.
  The stopped staging container is left in Docker unless removed manually."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [tick.core :as t]))

(def default-options
  {:sync-root       "data.dev/prod-sync"
   :active-data-dir "data.dev/datomic/data"
   :active-db-uri   "datomic:sql://app?jdbc:sqlite:data.dev/datomic/data/datomic-sqlite.db"
   :backup-dir      "archive"
   :backup-start    "2023-01-01T00:00:00.000Z"
   :db-name         "probematic"
   :system          "app"
   :image           "ghcr.io/outskirtslabs/datomic-pro:1.0.7622"
   :staging-port    "4434"
   :compose-file    "./docker-compose.dev.yml"
   :promote?        true})

(def validation-attrs
  [:member/member-id
   :gig/gig-id
   :song/song-id
   :attendance/gig+member
   :played/play-id
   :ledger/ledger-id
   :insurance.survey/survey-id])

(def option-keys
  {"--sync-root"    :sync-root
   "--backup-start" :backup-start
   "--image"        :image
   "--staging-port" :staging-port})

(defn usage []
  (str "Usage: scripts/datomic_prod_sync.clj PROD_LOCAL_PATH [options]\n\n"
       "PROD_LOCAL_PATH is the path where the production Datomic Local dump was synced.\n"
       "The script accepts the dump root, the nested Datomic storage directory, or a sync root containing prod-local-* dumps.\n\n"
       "Options:\n"
       "  --sync-root PATH        Default: data.dev/prod-sync\n"
       "  --backup-start INSTANT  Default: 2023-01-01T00:00:00.000Z\n"
       "  --image IMAGE           Default: ghcr.io/outskirtslabs/datomic-pro:1.0.7622\n"
       "  --staging-port PORT     Default: 4434\n"
       "  --no-promote            Restore and validate staging, but do not replace active dev\n"
       "  --help                  Show this help\n"))

(defn timestamp []
  (t/format "yyyyMMdd-HHmmss" (t/date-time)))

(defn parse-args [args]
  (loop [opts default-options
         positional []
         [arg & more] args]
    (cond
      (nil? arg)
      (cond
        (:help? opts) opts
        (= 1 (count positional)) (assoc opts :prod-local-path (first positional))
        :else (throw (ex-info "Expected exactly one PROD_LOCAL_PATH argument" {:positional positional})))

      (= arg "--help")
      (recur (assoc opts :help? true) positional more)

      (= arg "--no-promote")
      (recur (assoc opts :promote? false) positional more)

      (contains? option-keys arg)
      (let [v (first more)]
        (when-not v
          (throw (ex-info "Missing option value" {:option arg})))
        (recur (assoc opts (option-keys arg) v) positional (rest more)))

      (str/starts-with? arg "--")
      (throw (ex-info "Unknown option" {:option arg}))

      :else
      (recur opts (conj positional arg) more))))

(defn storage-dir-candidates [prod-local-path]
  (let [p (fs/path prod-local-path)]
    (concat [p (fs/path p "datomic")]
            (sort (fs/glob p "prod-local-*/datomic")))))

(defn datomic-local-storage-dir? [db-name candidate]
  (fs/directory? (fs/path candidate "app" db-name)))

(defn choose-storage-dir [{:keys [prod-local-path db-name]}]
  (let [candidates (storage-dir-candidates prod-local-path)]
    (or (last (filter #(datomic-local-storage-dir? db-name %) candidates))
        (throw (ex-info "Could not find Datomic Local storage directory"
                        {:prod-local-path prod-local-path
                         :checked         (mapv str candidates)
                         :expected        (str "app/" db-name)})))))

(defn ensure-project-root! []
  (when-not (fs/exists? "bb.edn")
    (throw (ex-info "Run this script from the project root where bb.edn lives" {}))))

(defn build-plan [opts]
  (ensure-project-root!)
  (let [run-id          (or (:run-id opts) (timestamp))
        sync-root       (fs/path (:sync-root opts))
        staging-root    (fs/path sync-root (str "staging-pro-" run-id))
        staging-data    (fs/path staging-root "data")
        staging-config  (fs/path staging-root "config")
        scripts-dir     (fs/path staging-root "scripts")
        reports-dir     (fs/path staging-root "reports")
        export-file     (fs/path sync-root (str "prod-" run-id "-txns.edn"))
        mapping-file    (fs/path sync-root (str "prod-" run-id "-old-to-new.edn"))
        active-backup   (fs/path (:backup-dir opts) (str "datomic.pro.pre-promote." run-id))
        container       (str "probematic-datomic-staging-" run-id)
        storage-dir     (choose-storage-dir opts)
        staging-db-path (fs/path staging-data "datomic-sqlite.db")
        staging-db-uri  (str "datomic:sql://app?jdbc:sqlite:" staging-db-path)]
    (merge opts
           {:run-id             run-id
            :project-root       (str (fs/absolutize "."))
            :storage-dir        (str (fs/absolutize storage-dir))
            :sync-root          (str sync-root)
            :staging-root       (str staging-root)
            :staging-data       (str staging-data)
            :staging-config     (str staging-config)
            :scripts-dir        (str scripts-dir)
            :reports-dir        (str reports-dir)
            :export-file        (str export-file)
            :mapping-file       (str mapping-file)
            :active-backup      (str active-backup)
            :staging-container  container
            :staging-db-path    (str staging-db-path)
            :staging-db-uri     staging-db-uri
            :export-script      (str (fs/path scripts-dir "export.clj"))
            :restore-script     (str (fs/path scripts-dir "restore.clj"))
            :validate-script    (str (fs/path scripts-dir "validate.clj"))
            :run-config-file    (str (fs/path staging-root "run-config.edn"))
            :restore-log        (str (fs/path reports-dir "restore.log"))
            :restore-summary    (str (fs/path reports-dir "restore-summary.edn"))
            :restore-failure    (str (fs/path reports-dir "restore-failure.edn"))
            :staging-validation (str (fs/path reports-dir "staging-validation.edn"))
            :active-validation  (str (fs/path reports-dir "active-validation.edn"))})))

(defn say [& xs]
  (apply println xs)
  (flush))

(defn run-cmd! [plan & cmd]
  (say "run:" (str/join " " cmd))
  (apply p/shell {:dir (:project-root plan)} cmd))

(defn run-continue [plan & cmd]
  (apply p/shell {:dir      (:project-root plan)
                  :out      :string
                  :err      :string
                  :continue true}
         cmd))

(defn wait-until! [label timeout-ms pred]
  (let [deadline (t/>> (t/instant)
                       (t/new-duration timeout-ms :millis))]
    (loop []
      (cond
        (pred) true
        (t/> (t/instant) deadline)
        (throw (ex-info "Timed out while waiting" {:label label :timeout-ms timeout-ms}))
        :else
        (do
          (Thread/sleep 5000)
          (recur))))))

(defn write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (with-out-str (prn value))))

(defn delete-tree-if-exists! [path]
  (when (fs/exists? path)
    (fs/delete-tree path {:force true})))

(defn host-path [plan path]
  (let [p (fs/path path)]
    (if (.isAbsolute p)
      (str p)
      (str (fs/path (:project-root plan) p)))))

(defn script-source [forms]
  (with-out-str
    (binding [*print-namespace-maps* false]
      (doseq [form forms]
        (prn form)
        (newline)))))

(def export-forms
  '[(require '[app.datomic.shim :as d]
             '[app.datomic.client-backup :as backup]
             '[clojure.edn :as edn]
             '[clojure.java.io :as io])

    (def cfg (edn/read-string (slurp (first *command-line-args*))))

    (require '[tick.core :as t])

    (def backup-start (t/inst (t/instant (:backup-start cfg))))

    (try
      (println :export-start (t/inst))
      (println :storage-dir (:storage-dir cfg))
      (println :export-file (:export-file cfg))
      (d/with-datomic-mode :client
        (let [client (d/client {:server-type :datomic-local
                                :system      (:system cfg)
                                :storage-dir (:storage-dir cfg)})
              conn   (d/connect client {:db-name (:db-name cfg)})]
          (with-open [out (io/writer (:export-file cfg))]
            ((var backup/output-all-tx) backup-start conn out))))
      (println :export-finished (t/inst))
      (shutdown-agents)
      (System/exit 0)
      (catch Throwable t
        (.printStackTrace t)
        (shutdown-agents)
        (System/exit 1)))])

(def restore-forms
  '[(require '[app.datomic.client-backup :as backup]
             '[app.datomic.shim :as shim]
             '[clojure.edn :as edn]
             '[clojure.java.io :as io]
             '[datomic.api :as d-peer])

    (def cfg (edn/read-string (slurp (first *command-line-args*))))

    (require '[tick.core :as t])

    (defn write-edn! [path value]
      (spit path (with-out-str (prn value))))

    (try
      (println :restore-start (t/inst))
      (println :uri (:staging-db-uri cfg))
      (println :export-file (:export-file cfg))
      (let [created?   (d-peer/create-database (:staging-db-uri cfg))
            conn       (d-peer/connect (:staging-db-uri cfg))
            started-at (t/inst)
            result     (shim/with-datomic-mode :peer
                         (with-open [rdr (io/reader (:export-file cfg))]
                           (backup/restore-tx-file conn (java.io.PushbackReader. rdr))))
            finished-at (t/inst)
            summary    {:created?         created?
                        :started-at       started-at
                        :finished-at      finished-at
                        :backup-timestamp (:backup-timestamp result)
                        :step             (:step result)
                        :remaining-txs    (count (:txs result))
                        :old->new-count   (count (:old->new result))
                        :target-basis-t   (d-peer/basis-t (d-peer/db conn))}]
        (println :restore-finished finished-at)
        (println :summary summary)
        (write-edn! (:mapping-file cfg) (:old->new result))
        (write-edn! (:restore-summary cfg) summary)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (let [failure {:failed-at (t/inst)
                       :class     (str (class t))
                       :message   (ex-message t)
                       :data      (ex-data t)}]
          (println :restore-failed failure)
          (.printStackTrace t)
          (write-edn! (:restore-failure cfg) failure)
          (shutdown-agents)
          (System/exit 1))))])

(def validate-forms
  '[(require '[app.datomic.shim :as d]
             '[clojure.edn :as edn]
             '[clojure.java.io :as io]
             '[clojure.set :as set]
             '[datomic.api :as d-peer])

    (def cfg (edn/read-string (slurp (first *command-line-args*))))

    (def target-key (keyword (second *command-line-args*)))

    (def validation-file (get cfg (keyword (str (name target-key) "-validation"))))

    (def target-uri (case target-key
                      :staging (:staging-db-uri cfg)
                      :active  (:active-db-uri cfg)))

    (def attrs (:validation-attrs cfg))

    (def attr-set (set attrs))

    (def old->new (edn/read-string (slurp (:mapping-file cfg))))

    (def missing-mappings* (atom #{}))

    (defn write-edn! [path value]
      (spit path (with-out-str (prn value))))

    (defn mapped-id [id]
      (if-let [mapped (get old->new (str id))]
        mapped
        (do
          (swap! missing-mappings* conj id)
          id)))

    (defn rewrite-tuple-value [tuple-ref-attrs attr value]
      (if-let [ref-indexes (tuple-ref-attrs attr)]
        (mapv (fn [idx tuple-value]
                (if (contains? ref-indexes idx)
                  (mapped-id tuple-value)
                  tuple-value))
              (range)
              value)
        value))

    (defn compact-current-values [current-values]
      (into {}
            (map (fn [[attr e->values]]
                   [attr (into {}
                               (keep (fn [[e values]]
                                       (when (seq values)
                                         [e values])))
                               e->values)]))
            current-values))

    (defn count-current-values [current-values]
      (into {}
            (for [[attr e->values] current-values]
              [attr (count (keep (fn [[_ values]]
                                   (when (seq values) true))
                                 e->values))])))

    (defn update-current-values [current-values e attr v added?]
      (update-in current-values [attr e]
                 (fnil (if added? conj disj) #{})
                 v))

    (defn export-info [path]
      (reset! missing-mappings* #{})
      (with-open [rdr (java.io.PushbackReader. (io/reader path))]
        (let [header          (read rdr false :sync/eof)
              tuple-ref-attrs (or (:tuple-ref-attrs header) {})]
          (loop [tx-count       0
                 last-tx-instant nil
                 current-values (zipmap attrs (repeat {}))
                 tuple-values   (zipmap (keys tuple-ref-attrs) (repeat {}))]
            (let [tx (read rdr false :sync/eof)]
              (if (= :sync/eof tx)
                {:header-ok?             (and (set? (:ref-attrs header))
                                              (map? (:tuple-attrs header))
                                              (map? tuple-ref-attrs)
                                              (inst? (:backup-timestamp header)))
                 :backup-timestamp      (:backup-timestamp header)
                 :tuple-ref-attrs       tuple-ref-attrs
                 :tx-count              tx-count
                 :last-tx-instant       last-tx-instant
                 :counts                (count-current-values current-values)
                 :tuple-values          (compact-current-values tuple-values)
                 :missing-mapping-count (count @missing-mappings*)
                 :missing-mapping-sample (vec (take 20 @missing-mappings*))}
                (let [[current-values tuple-values]
                      (reduce (fn [[current-values tuple-values] [e attr v added?]]
                                [(if (attr-set attr)
                                   (update-current-values current-values e attr v added?)
                                   current-values)
                                 (if (tuple-ref-attrs attr)
                                   (update-current-values tuple-values
                                                          (mapped-id e)
                                                          attr
                                                          (rewrite-tuple-value tuple-ref-attrs attr v)
                                                          added?)
                                   tuple-values)])
                              [current-values tuple-values]
                              (:data tx))]
                  (recur (inc tx-count)
                         (get-in tx [:tx :db/txInstant])
                         current-values
                         tuple-values))))))))

    (defn entity-count [db attr]
      (d/q '[:find (count ?e) .
             :in $ ?attr
             :where [?e ?attr]]
           db attr))

    (defn attr-counts [db]
      (into {} (for [attr attrs] [attr (entity-count db attr)])))

    (defn max-tx-instant [db]
      (d/q '[:find (max ?inst) .
             :where [_ :db/txInstant ?inst]]
           db))

    (defn schema-present? [db attr]
      (boolean (d/pull db [:db/ident] attr)))

    (defn target-tuple-values [db tuple-ref-attrs]
      (into {}
            (for [attr (keys tuple-ref-attrs)]
              [attr (reduce (fn [acc [e v]]
                              (update acc e (fnil conj #{}) v))
                            {}
                            (d/q '[:find ?e ?v
                                   :in $ ?attr
                                   :where [?e ?attr ?v]]
                                 db attr))])))

    (defn tuple-mismatch-samples [expected actual]
      (vec
       (take 20
             (for [attr (sort-by str (set/union (set (keys expected))
                                                (set (keys actual))))
                   e    (sort (set/union (set (keys (get expected attr)))
                                         (set (keys (get actual attr)))))
                   :let [expected-values (get-in expected [attr e] #{})
                         actual-values   (get-in actual [attr e] #{})]
                   :when (not= expected-values actual-values)]
               {:attr          attr
                :entity        e
                :expected-only (vec (take 10 (set/difference expected-values actual-values)))
                :actual-only   (vec (take 10 (set/difference actual-values expected-values)))}))))

    (defn audit-user-validation [db]
      (let [rows   (d/q '[:find ?tx ?user
                          :where [?tx :audit/user ?user]]
                        db)
            broken (vec (keep (fn [[tx user]]
                                (let [member (d/pull db [:member/member-id :member/name] user)]
                                  (when-not (:member/member-id member)
                                    {:tx         tx
                                     :audit-user user
                                     :pull       member})))
                              rows))]
        {:count         (count rows)
         :broken-count  (count broken)
         :broken-sample (vec (take 20 broken))
         :ok?           (zero? (count broken))}))

    (try
      (let [export (export-info (:export-file cfg))
            target (d/with-datomic-mode :peer
                     (let [conn (d/connect target-uri)
                           db   (d/db conn)]
                       {:counts         (attr-counts db)
                        :tuple-values   (target-tuple-values db (:tuple-ref-attrs export))
                        :audit-users    (audit-user-validation db)
                        :max-tx-instant (max-tx-instant db)
                        :basis-t        (d-peer/basis-t db)
                        :schema-present (into {}
                                              (for [attr attrs]
                                                [attr (schema-present? db attr)]))}))
            tuple-mismatch-sample (tuple-mismatch-samples (:tuple-values export)
                                                          (:tuple-values target))
            result {:target-key                 target-key
                    :target-uri                 target-uri
                    :export                     export
                    :target                     target
                    :counts-match?              (= (:counts export) (:counts target))
                    :tx-instant-matches-export? (= (:last-tx-instant export)
                                                   (:max-tx-instant target))
                    :tuple-ref-values-match?    (empty? tuple-mismatch-sample)
                    :tuple-mismatch-sample      tuple-mismatch-sample
                    :audit-users-ok?            (get-in target [:audit-users :ok?])
                    :missing-mappings-ok?       (zero? (:missing-mapping-count export))}
            ok?    (and (:counts-match? result)
                        (:tx-instant-matches-export? result)
                        (:tuple-ref-values-match? result)
                        (:audit-users-ok? result)
                        (:missing-mappings-ok? result))]
        (prn result)
        (write-edn! validation-file result)
        (shutdown-agents)
        (System/exit (if ok? 0 1)))
      (catch Throwable t
        (.printStackTrace t)
        (shutdown-agents)
        (System/exit 2)))])

(defn write-helper-scripts! [plan]
  (fs/create-dirs (:scripts-dir plan))
  (fs/create-dirs (:reports-dir plan))
  (write-edn! (:run-config-file plan)
              (select-keys (assoc plan :validation-attrs validation-attrs)
                           [:backup-start
                            :storage-dir
                            :system
                            :db-name
                            :export-file
                            :mapping-file
                            :staging-db-uri
                            :active-db-uri
                            :restore-summary
                            :restore-failure
                            :staging-validation
                            :active-validation
                            :validation-attrs]))
  (spit (:export-script plan) (script-source export-forms))
  (spit (:restore-script plan) (script-source restore-forms))
  (spit (:validate-script plan) (script-source validate-forms)))

(defn verify-export-shape! [plan]
  (let [line-count (with-open [rdr (io/reader (:export-file plan))]
                     (count (line-seq rdr)))
        header     (with-open [rdr (java.io.PushbackReader.
                                    (io/reader (:export-file plan)))]
                     (read rdr false :sync/eof))]
    (when-not (and (map? header)
                   (set? (:ref-attrs header))
                   (map? (:tuple-attrs header))
                   (map? (:tuple-ref-attrs header))
                   (inst? (:backup-timestamp header)))
      (throw (ex-info "Export header did not have expected shape" {:header header})))
    (say "export line count:" line-count)
    (say "tuple ref attrs:" (:tuple-ref-attrs header))
    {:line-count line-count
     :header     header}))

(defn prepare-staging-sqlite! [plan]
  (delete-tree-if-exists! (:staging-data plan))
  (delete-tree-if-exists! (:staging-config plan))
  (fs/create-dirs (:staging-data plan))
  (fs/create-dirs (:staging-config plan))
  (run-cmd! plan "bash" "scripts/init-datomic-sqlite.sh" (:staging-db-path plan)))

(defn ensure-staging-port-free! [plan]
  (let [ports (:out (run-continue plan "docker" "ps" "--format" "{{.Names}} {{.Ports}}"))]
    (when (str/includes? ports (str "127.0.0.1:" (:staging-port plan) "->"))
      (throw (ex-info "Staging port is already in use" {:port      (:staging-port plan)
                                                        :docker-ps ports})))))

(defn start-staging-transactor! [plan]
  (ensure-staging-port-free! plan)
  (run-continue plan "docker" "rm" "-f" (:staging-container plan))
  (run-cmd! plan
            "docker" "run" "-d"
            "--name" (:staging-container plan)
            "--hostname" (:staging-container plan)
            "-p" (str "127.0.0.1:" (:staging-port plan) ":4434")
            "-v" (str (host-path plan (:staging-data plan)) ":/data:z")
            "-v" (str (host-path plan (:staging-config plan)) ":/config:z")
            "-e" "DATOMIC_PROTOCOL=sql"
            "-e" "DATOMIC_SQL_DRIVER_CLASS=org.sqlite.JDBC"
            "-e" "DATOMIC_SQL_URL=jdbc:sqlite:/data/datomic-sqlite.db"
            "-e" "DATOMIC_JAVA_OPTS=-Xmx4g -Xms4g -Dlogback.configurationFile=/config/logback.xml"
            "-e" "DATOMIC_HEALTHCHECK_HOST=127.0.0.1"
            "-e" "DATOMIC_HEALTHCHECK_PORT=9999"
            "-e" "DATOMIC_HEARTBEAT_INTERVAL_MSEC=60000"
            "-e" "DATOMIC_PORT=4434"
            "-e" (str "DATOMIC_HOST=" (:staging-container plan))
            "-e" "DATOMIC_ALT_HOST=127.0.0.1"
            "-e" "DATOMIC_STORAGE_ADMIN_PASSWORD=admin"
            "-e" "DATOMIC_STORAGE_DATOMIC_PASSWORD=dev"
            (:image plan))
  (say "waiting for staging transactor heartbeat...")
  (wait-until! "staging transactor heartbeat" 180000
               (fn []
                 (let [logs (:out (run-continue plan "docker" "logs" "--tail" "300" (:staging-container plan)))]
                   (and (str/includes? logs "Started EPOLL Acceptor")
                        (str/includes? logs ":transactor/heartbeat")))))
  (say "staging transactor ready"))

(defn export-prod-local! [plan]
  (run-cmd! plan "clojure" "-M:dev" (:export-script plan) (:run-config-file plan))
  (verify-export-shape! plan))

(defn restore-staging! [plan]
  (let [result (p/shell {:dir      (:project-root plan)
                         :out      :string
                         :err      :out
                         :continue true}
                        "clojure" "-M:dev" (:restore-script plan) (:run-config-file plan))]
    (spit (:restore-log plan) (:out result))
    (print (:out result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Staging restore failed" {:exit (:exit result)
                                                :log  (:restore-log plan)})))))

(defn validate-target! [plan target]
  (run-cmd! plan "clojure" "-M:dev" (:validate-script plan) (:run-config-file plan) (name target)))

(defn wait-active-health! [plan]
  (wait-until! "active Datomic transactor healthcheck" 180000
               (fn []
                 (let [result (run-continue plan
                                            "docker" "inspect" "probematic-datomic-transactor-1"
                                            "--format" "{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}")]
                   (= "healthy" (str/trim (:out result)))))))

(defn promote-staging! [plan]
  (run-cmd! plan "docker" "compose" "-f" (:compose-file plan) "stop" "datomic-console" "datomic-transactor")
  (run-cmd! plan "docker" "stop" (:staging-container plan))
  (fs/create-dirs (:backup-dir plan))
  (say "copy active store to" (:active-backup plan))
  (fs/copy-tree "data.dev/datomic" (:active-backup plan))
  (say "replace active data dir" (:active-data-dir plan))
  (delete-tree-if-exists! (:active-data-dir plan))
  (fs/copy-tree (:staging-data plan) (:active-data-dir plan))
  (run-cmd! plan "sync")
  (run-cmd! plan "docker" "compose" "-f" (:compose-file plan) "up" "-d" "--force-recreate" "datomic-transactor" "datomic-console")
  (wait-active-health! plan)
  (validate-target! plan :active))

(defn run-workflow! [plan]
  (say "run id:" (:run-id plan))
  (say "storage dir:" (:storage-dir plan))
  (say "staging root:" (:staging-root plan))
  (write-helper-scripts! plan)
  (export-prod-local! plan)
  (prepare-staging-sqlite! plan)
  (start-staging-transactor! plan)
  (restore-staging! plan)
  (validate-target! plan :staging)
  (if (:promote? plan)
    (promote-staging! plan)
    (say "skipping active promotion because --no-promote was provided"))
  (say "done:" (:run-id plan))
  (select-keys plan [:run-id
                     :export-file
                     :staging-root
                     :restore-summary
                     :staging-validation
                     :active-validation
                     :mapping-file
                     :active-backup]))

(defn -main [& args]
  (try
    (let [opts (parse-args args)]
      (if (:help? opts)
        (print (usage))
        (let [result (run-workflow! (build-plan opts))]
          (println "summary:")
          (prn result))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "ERROR:" (ex-message t))
        (when (seq (ex-data t))
          (prn (ex-data t))))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
