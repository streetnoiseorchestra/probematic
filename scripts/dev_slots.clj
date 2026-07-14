(ns dev-slots
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def script-version "dev-slots-1")
(def ^:private datastar-inspector-filename "datastar-inspector@1.1.4.js")
(def slot-port-keys
  [:http-port
   :nrepl-port
   :redis-port
   :datomic-port
   :datomic-console-port
   :smtp4dev-http-port
   :smtp4dev-smtp-port
   :smtp4dev-imap-port])

(def required-slot-keys
  (into [:slot] slot-port-keys))

(def default-dev-ports
  #{6161 6162 6379 5002 2500 1430 8081 4334})

(defn- duplicate-values [xs]
  (->> xs
       frequencies
       (keep (fn [[x n]]
               (when (< 1 n) x)))
       vec))

(defn- slot-key [slot-or-name]
  (let [slot-name (if (map? slot-or-name)
                    (:slot slot-or-name)
                    slot-or-name)]
    (cond
      (keyword? slot-name) slot-name
      (string? slot-name) (keyword slot-name)
      :else (throw (ex-info "Slot name must be a keyword or string"
                            {:slot slot-name})))))

(defn- slot-id [slot-or-name]
  (name (slot-key slot-or-name)))

(defn- slot-hostname [slot]
  (str (slot-id slot) ".probematic.localhost"))

(defn- slot-http-url [slot port-key]
  (str "http://" (slot-hostname slot) ":" (get slot port-key)))

(defn- absolutize-str [path]
  (str (fs/normalize (fs/absolutize (fs/expand-home path)))))

(defn- path-str [& parts]
  (str (apply fs/path parts)))

(defn read-registry
  [path]
  (-> path slurp edn/read-string))

(defn validate-registry
  [registry]
  (when-not (sequential? registry)
    (throw (ex-info "Slot registry must be a sequential collection"
                    {:registry registry})))
  (doseq [slot registry
          :let [missing (seq (remove #(contains? slot %) required-slot-keys))]
          :when missing]
    (throw (ex-info "Slot has missing required keys"
                    {:slot (:slot slot)
                     :missing (vec missing)})))
  (when-let [dupes (seq (duplicate-values (map :slot registry)))]
    (throw (ex-info "Duplicate slot names in slot registry"
                    {:duplicate-slots (vec dupes)})))
  (let [port-entries (for [slot registry
                           port-key slot-port-keys]
                       {:slot (:slot slot)
                        :port-key port-key
                        :port (get slot port-key)})
        duplicate-ports (->> port-entries
                             (group-by :port)
                             (keep (fn [[port entries]]
                                     (when (< 1 (count entries))
                                       {:port port
                                        :entries (mapv #(select-keys % [:slot :port-key]) entries)})))
                             vec)
        default-conflicts (->> port-entries
                               (filter #(contains? default-dev-ports (:port %)))
                               vec)]
    (when (seq duplicate-ports)
      (throw (ex-info "Duplicate port values in slot registry"
                      {:duplicate-ports duplicate-ports})))
    (when (seq default-conflicts)
      (throw (ex-info "Slot port conflicts with default dev stack"
                      {:conflicts default-conflicts
                       :default-dev-ports default-dev-ports}))))
  registry)

(defn state-paths
  [main-root]
  (let [main-root (absolutize-str main-root)
        state-root (path-str main-root ".dev-state")]
    {:main-root main-root
     :state-root state-root
     :slots-root (path-str state-root "slots")
     :templates-root (path-str state-root "datomic-templates")
     :artifact-cache-root (path-str state-root "artifact-cache")}))

(defn slot-paths
  [main-root slot-name]
  (let [{:keys [main-root slots-root]} (state-paths main-root)
        slot (slot-key slot-name)
        slot-id (name slot)
        slot-root (path-str slots-root slot-id)]
    {:slot slot
     :slot-root slot-root
     :claim-file (path-str slot-root "claim.edn")
     :lock-file (path-str slot-root "slot.lock")
     :env-file (path-str slot-root "env.sh")
     :compose-env-file (path-str slot-root "compose.env")
     :secrets-file (path-str slot-root "secrets.edn")
     :datomic-data-dir (path-str slot-root "datomic" "data")
     :datomic-config-dir (path-str slot-root "datomic" "config")
     :smtp4dev-dir (path-str slot-root "smtp4dev")
     :logs-dir (path-str slot-root "logs")
     :shared-filestore-dir (path-str main-root "data.dev" "filestore")}))

(defn- shell-double-quote [s]
  (str "\""
       (str/escape (str s)
                   {\\ "\\\\"
                    \" "\\\""
                    \$ "\\$"
                    \` "\\`"})
       "\""))

(defn render-env-sh
  [main-root slot]
  (let [slot-id (slot-id slot)
        project-name (str "probematic-" slot-id)
        paths (slot-paths main-root (:slot slot))
        main-root (-> main-root state-paths :main-root)
        {:keys [slot-root secrets-file datomic-data-dir shared-filestore-dir]} paths]
    (str/join
     "\n"
     [(str "export PROBEMATIC_MAIN_ROOT=" (shell-double-quote main-root))
      (str "export PROBEMATIC_SLOT=" slot-id)
      (str "export PROBEMATIC_SLOT_ROOT=" (shell-double-quote slot-root))
      (str "export COMPOSE_PROJECT_NAME=" project-name)
      (str "export HTTP_PORT=" (:http-port slot))
      (str "export NREPL_PORT=" (:nrepl-port slot))
      (str "export APP_SECRETS_FILE=" (shell-double-quote secrets-file))
      (str "export DATOMIC_URI="
           (shell-double-quote
            (str "datomic:sql://app?jdbc:sqlite:" datomic-data-dir "/datomic-sqlite.db")))
      (str "export APP_FILESTORE_DIR=" (shell-double-quote shared-filestore-dir))
      ""])))

(defn render-compose-env
  [main-root slot]
  (let [slot-id (slot-id slot)
        project-name (str "probematic-" slot-id)
        {:keys [datomic-data-dir datomic-config-dir smtp4dev-dir]}
        (slot-paths main-root (:slot slot))]
    (str/join
     "\n"
     [(str "COMPOSE_PROJECT_NAME=" project-name)
      (str "PROBEMATIC_DEV_REDIS_PORT_MAPPING=127.0.0.1:" (:redis-port slot) ":6379")
      (str "PROBEMATIC_DEV_SMTP4DEV_WEB_PORT_MAPPING=127.0.0.1:" (:smtp4dev-http-port slot) ":80")
      (str "PROBEMATIC_DEV_SMTP4DEV_SMTP_PORT_MAPPING=127.0.0.1:" (:smtp4dev-smtp-port slot) ":25")
      (str "PROBEMATIC_DEV_SMTP4DEV_IMAP_PORT_MAPPING=127.0.0.1:" (:smtp4dev-imap-port slot) ":143")
      (str "PROBEMATIC_DEV_SMTP4DEV_DATA_DIR=" smtp4dev-dir)
      (str "PROBEMATIC_DEV_DATOMIC_CONSOLE_PORT_MAPPING=127.0.0.1:" (:datomic-console-port slot) ":8080")
      (str "PROBEMATIC_DEV_DATOMIC_PORT=" (:datomic-port slot))
      (str "PROBEMATIC_DEV_DATOMIC_PORT_MAPPING=127.0.0.1:" (:datomic-port slot) ":" (:datomic-port slot))
      (str "PROBEMATIC_DEV_DATOMIC_DATA_DIR=" datomic-data-dir)
      (str "PROBEMATIC_DEV_DATOMIC_CONFIG_DIR=" datomic-config-dir)
      ""])))

(def redis-password "devpassword123")

(defn merge-slot-secrets
  [base-secrets slot]
  (-> base-secrets
      (assoc :app-base-url (slot-http-url slot :http-port))
      (assoc-in [:redis :conn-spec]
                {:host "127.0.0.1"
                 :port (:redis-port slot)
                 :password redis-password})))

(defn write-slot-secrets!
  [main-root slot base-secrets]
  (let [{:keys [secrets-file]} (slot-paths main-root (:slot slot))
        secrets (merge-slot-secrets base-secrets slot)]
    (fs/create-dirs (fs/parent secrets-file))
    (spit secrets-file (str (pr-str secrets) "\n"))
    (fs/set-posix-file-permissions secrets-file "rw-------")
    secrets-file))

(defn- write-text-file!
  [path text]
  (fs/create-dirs (fs/parent path))
  (spit path text)
  path)

(defn- project-name [slot]
  (str "probematic-" (slot-id slot)))

(defn- current-user []
  (or (System/getenv "USER")
      (System/getProperty "user.name")
      "unknown"))

(defn- current-hostname []
  (or (System/getenv "HOSTNAME")
      "unknown"))

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn- read-claim-file [claim-file]
  (when (fs/regular-file? claim-file)
    (-> claim-file slurp edn/read-string)))

(defn- claim-record
  [slot worktree branch]
  (let [slot (slot-key slot)
        worktree (absolutize-str worktree)]
    {:slot slot
     :worktree worktree
     :branch (or branch "unknown")
     :compose-project-name (project-name slot)
     :claimed-by (current-user)
     :hostname (current-hostname)
     :claimed-at (now-iso)}))

(defn claim-slot!
  [{:keys [main-root slot worktree branch force?]}]
  (when-not worktree
    (throw (ex-info "claim requires WORKTREE" {:command "claim"})))
  (let [main-root (-> main-root state-paths :main-root)
        slot (slot-key slot)
        worktree (absolutize-str worktree)
        {:keys [claim-file]} (slot-paths main-root slot)
        existing-claim (read-claim-file claim-file)]
    (cond
      (and existing-claim
           (= worktree (:worktree existing-claim)))
      existing-claim

      (and existing-claim
           (not force?))
      (throw (ex-info "Slot is already claimed"
                      {:slot slot
                       :claim-file claim-file
                       :current-claim existing-claim}))

      :else
      (let [claim (claim-record slot worktree branch)]
        (write-text-file! claim-file (str (pr-str claim) "\n"))
        claim))))

(defn release-slot!
  [{:keys [main-root slot]}]
  (let [main-root (-> main-root state-paths :main-root)
        slot (slot-key slot)
        {:keys [claim-file]} (slot-paths main-root slot)
        released-claim (read-claim-file claim-file)
        released? (boolean released-claim)]
    (when released?
      (fs/delete-if-exists claim-file))
    (cond-> {:slot slot
             :claim-file claim-file
             :released? released?}
      released? (assoc :released-claim released-claim))))

(defn with-slot-lock!
  [{:keys [main-root slot]} f]
  (let [{:keys [lock-file]} (slot-paths main-root (slot-key slot))
        lock-path (fs/path lock-file)]
    (fs/create-dirs (fs/parent lock-path))
    (try
      (fs/create-dir lock-path)
      (catch Exception _
        (throw (ex-info "Slot is already locked"
                        {:slot (slot-key slot)
                         :lock-file lock-file}))))
    (try
      (f)
      (finally
        (fs/delete-tree lock-path)))))

(defn- ensure-symlink!
  [link target]
  (let [link (fs/path link)
        target (fs/path target)]
    (fs/create-dirs (fs/parent link))
    (cond
      (and (fs/sym-link? link)
           (= (str target) (str (fs/read-link link))))
      (str link)

      (fs/sym-link? link)
      (do
        (fs/delete-if-exists link)
        (fs/create-sym-link link target)
        (str link))

      (fs/exists? link)
      (throw (ex-info "Refusing to replace existing path with symlink"
                      {:link (str link)
                       :target (str target)}))

      :else
      (do
        (fs/create-sym-link link target)
        (str link)))))

(defn init-slot!
  [{:keys [main-root worktree slot base-secrets branch force?]}]
  (let [main-root (-> main-root state-paths :main-root)
        worktree (absolutize-str worktree)
        slot-name (:slot slot)
        paths (slot-paths main-root slot-name)
        logback-template (path-str main-root "dev" "datomic" "logback.xml")
        logback-target (path-str (:datomic-config-dir paths) "logback.xml")
        current-slot-link (path-str worktree "data.dev" "current-slot")
        filestore-link (path-str worktree "data.dev" "filestore")]
    (when-not (fs/regular-file? logback-template)
      (throw (ex-info "Datomic logback template is missing"
                      {:template logback-template})))
    (claim-slot! {:main-root main-root
                  :slot slot-name
                  :worktree worktree
                  :branch branch
                  :force? force?})
    (doseq [dir [(:datomic-data-dir paths)
                 (:datomic-config-dir paths)
                 (:smtp4dev-dir paths)
                 (:logs-dir paths)
                 (:shared-filestore-dir paths)]]
      (fs/create-dirs dir))
    (fs/copy logback-template logback-target {:replace-existing true})
    (write-text-file! (:env-file paths) (render-env-sh main-root slot))
    (write-text-file! (:compose-env-file paths) (render-compose-env main-root slot))
    (write-slot-secrets! main-root slot base-secrets)
    (ensure-symlink! current-slot-link (:slot-root paths))
    (ensure-symlink! filestore-link (:shared-filestore-dir paths))
    paths))

(defn detect-webawesome-version
  [source-root]
  (let [layout-file (path-str source-root "src" "clj" "app" "layout2.clj")
        content (slurp layout-file)]
    (or (some-> (re-find #"webawesome@([^/\"'\s)]+)" content) second)
        (throw (ex-info "Could not detect WebAwesome version"
                        {:source-root (absolutize-str source-root)
                         :layout-file layout-file})))))

(defn- artifact-paths
  [main-root webawesome-version]
  (let [{:keys [artifact-cache-root]} (state-paths main-root)
        webawesome-dir-name (str "webawesome@" webawesome-version)]
    {:webawesome-version webawesome-version
     :webawesome-cache-dir (path-str artifact-cache-root "resources" "public" "vendor" webawesome-dir-name)
     :webawesome-skill-cache-dir (path-str artifact-cache-root ".agents" "skills" "webawesome")
     :java-classes-cache-dir (path-str artifact-cache-root "src" "java-classes")
     :phosphor-icons-cache-dir (path-str artifact-cache-root "resources" "public" "img" "phosphor")
     :datastar-inspector-cache-file (path-str artifact-cache-root "resources" "public" "js" datastar-inspector-filename)}))

(defn- copy-tree-replacing!
  [source target]
  (let [source (fs/path source)
        target (fs/path target)]
    (when (fs/exists? target)
      (if (fs/sym-link? target)
        (fs/delete-if-exists target)
        (fs/delete-tree target)))
    (fs/create-dirs (fs/parent target))
    (fs/copy-tree source target)
    (str target)))

(defn- copy-file-replacing!
  [source target]
  (fs/create-dirs (fs/parent target))
  (fs/copy source target {:replace-existing true})
  (str target))

(defn import-artifacts!
  [{:keys [main-root source-root webawesome-version]}]
  (let [main-root (-> main-root state-paths :main-root)
        source-root (absolutize-str (or source-root main-root))
        webawesome-version (or webawesome-version
                               (detect-webawesome-version source-root))
        paths (artifact-paths main-root webawesome-version)
        source-webawesome-dir (path-str source-root "resources" "public" "vendor" (str "webawesome@" webawesome-version))
        source-skill-dir (path-str source-root ".agents" "skills" "webawesome")
        source-java-classes-dir (path-str source-root "src" "java-classes")
        source-phosphor-icons-dir (path-str source-root "resources" "public" "img" "phosphor")
        source-datastar-inspector-file (path-str source-root "resources" "public" "js" datastar-inspector-filename)]
    (when-not (fs/directory? source-webawesome-dir)
      (throw (ex-info "WebAwesome vendor directory is missing"
                      {:source source-webawesome-dir
                       :webawesome-version webawesome-version})))
    (when-not (fs/regular-file? source-datastar-inspector-file)
      (throw (ex-info "Datastar inspector file is missing"
                      {:source source-datastar-inspector-file})))
    (copy-tree-replacing! source-webawesome-dir (:webawesome-cache-dir paths))
    (when (fs/directory? source-skill-dir)
      (copy-tree-replacing! source-skill-dir (:webawesome-skill-cache-dir paths)))
    (when (fs/directory? source-java-classes-dir)
      (copy-tree-replacing! source-java-classes-dir (:java-classes-cache-dir paths)))
    (when (fs/directory? source-phosphor-icons-dir)
      (copy-tree-replacing! source-phosphor-icons-dir (:phosphor-icons-cache-dir paths)))
    (copy-file-replacing! source-datastar-inspector-file (:datastar-inspector-cache-file paths))
    (cond-> (select-keys paths [:webawesome-version :webawesome-cache-dir :datastar-inspector-cache-file])
      (fs/directory? (:webawesome-skill-cache-dir paths))
      (assoc :webawesome-skill-cache-dir (:webawesome-skill-cache-dir paths))

      (fs/directory? (:java-classes-cache-dir paths))
      (assoc :java-classes-cache-dir (:java-classes-cache-dir paths))

      (fs/directory? (:phosphor-icons-cache-dir paths))
      (assoc :phosphor-icons-cache-dir (:phosphor-icons-cache-dir paths)))))

(defn- cached-webawesome-version
  [main-root]
  (let [{:keys [artifact-cache-root]} (state-paths main-root)
        vendor-dir (fs/path artifact-cache-root "resources" "public" "vendor")
        versions (when (fs/directory? vendor-dir)
                   (->> (fs/list-dir vendor-dir)
                        (map #(str (fs/file-name %)))
                        (keep #(second (re-find #"^webawesome@(.+)$" %)))
                        sort
                        vec))]
    (case (count versions)
      0 (throw (ex-info "No cached WebAwesome artifact found"
                        {:vendor-dir (str vendor-dir)}))
      1 (first versions)
      (throw (ex-info "Multiple cached WebAwesome versions found; pass --webawesome-version"
                      {:versions versions
                       :vendor-dir (str vendor-dir)})))))

(defn- artifact-version-for-link
  [{:keys [main-root webawesome-version]}]
  (or webawesome-version
      (let [layout-file (path-str main-root "src" "clj" "app" "layout2.clj")]
        (when (fs/regular-file? layout-file)
          (detect-webawesome-version main-root)))
      (cached-webawesome-version main-root)))

(defn link-artifacts!
  [{:keys [main-root worktree webawesome-version]}]
  (let [main-root (-> main-root state-paths :main-root)
        worktree (absolutize-str worktree)
        webawesome-version (artifact-version-for-link {:main-root main-root
                                                       :webawesome-version webawesome-version})
        paths (artifact-paths main-root webawesome-version)
        webawesome-link (path-str worktree "resources" "public" "vendor" (str "webawesome@" webawesome-version))
        skill-link (path-str worktree ".agents" "skills" "webawesome")
        java-classes-link (path-str worktree "src" "java-classes")
        phosphor-icons-link (path-str worktree "resources" "public" "img" "phosphor")
        datastar-inspector-link (path-str worktree "resources" "public" "js" datastar-inspector-filename)]
    (when-not (fs/directory? (:webawesome-cache-dir paths))
      (throw (ex-info "Cached WebAwesome vendor directory is missing"
                      {:cache-dir (:webawesome-cache-dir paths)})))
    (when-not (fs/regular-file? (:datastar-inspector-cache-file paths))
      (throw (ex-info "Cached Datastar inspector file is missing"
                      {:cache-file (:datastar-inspector-cache-file paths)})))
    (ensure-symlink! webawesome-link (:webawesome-cache-dir paths))
    (cond-> {:webawesome-version webawesome-version
             :webawesome-link webawesome-link
             :datastar-inspector-link
             (ensure-symlink! datastar-inspector-link (:datastar-inspector-cache-file paths))}
      (fs/directory? (:webawesome-skill-cache-dir paths))
      (assoc :webawesome-skill-link
             (ensure-symlink! skill-link (:webawesome-skill-cache-dir paths)))
      (fs/directory? (:java-classes-cache-dir paths))
      (assoc :java-classes-link
             (ensure-symlink! java-classes-link (:java-classes-cache-dir paths)))
      (fs/directory? (:phosphor-icons-cache-dir paths))
      (assoc :phosphor-icons-link
             (ensure-symlink! phosphor-icons-link (:phosphor-icons-cache-dir paths))))))

(defn link-artifacts-for-slot!
  [{:keys [main-root slot webawesome-version]}]
  (let [main-root (-> main-root state-paths :main-root)
        slot (slot-key slot)
        {:keys [claim-file]} (slot-paths main-root slot)
        claim (read-claim-file claim-file)
        worktree (:worktree claim)]
    (when-not worktree
      (throw (ex-info "Slot has no claimed worktree for artifact linking"
                      {:slot slot
                       :claim-file claim-file})))
    (link-artifacts! {:main-root main-root
                      :worktree worktree
                      :webawesome-version webawesome-version})))

(defn template-paths
  [main-root template-name]
  (let [{:keys [templates-root]} (state-paths main-root)
        template (str template-name)
        template-dir (path-str templates-root template)]
    {:template template
     :template-dir template-dir
     :data-dir (path-str template-dir "data")
     :metadata-file (path-str template-dir "metadata.edn")}))

(defn- source-git-hash [main-root]
  (try
    (let [{:keys [exit out]} (shell {:out :string
                                     :err :string
                                     :continue true
                                     :dir main-root}
                                    "git" "rev-parse" "HEAD")]
      (if (zero? exit)
        (str/trim out)
        "unknown"))
    (catch Exception _
      "unknown")))

(defn- template-metadata
  [{:keys [main-root template source-data-dir kind from]}]
  (cond-> {:template template
           :source-data-dir source-data-dir
           :kind kind
           :created-at (now-iso)
           :source-git-hash (source-git-hash main-root)
           :script-version script-version}
    from (assoc :from from)))

(defn- write-template!
  [{:keys [main-root name source-data-dir kind from]}]
  (let [main-root (-> main-root state-paths :main-root)
        paths (template-paths main-root name)
        metadata (template-metadata {:main-root main-root
                                     :template (:template paths)
                                     :source-data-dir source-data-dir
                                     :kind kind
                                     :from from})]
    (copy-tree-replacing! source-data-dir (:data-dir paths))
    (write-text-file! (:metadata-file paths) (str (pr-str metadata) "\n"))
    (select-keys paths [:template :template-dir :data-dir])))

(defn template-from-active!
  [{:keys [main-root name source-data-dir]}]
  (when-not name
    (throw (ex-info "template from-active requires NAME" {:command "template from-active"})))
  (let [main-root (-> main-root state-paths :main-root)
        source-data-dir (or source-data-dir
                            (path-str main-root "data.dev" "datomic" "data"))]
    (when-not (fs/directory? source-data-dir)
      (throw (ex-info "Active Datomic data directory is missing"
                      {:source-data-dir source-data-dir})))
    (write-template! {:main-root main-root
                      :name name
                      :source-data-dir source-data-dir
                      :kind :from-active})))

(defn- restore-source-data-dir [from]
  (let [from (absolutize-str from)
        nested-data-dir (path-str from "data")]
    (cond
      (fs/regular-file? (path-str from "datomic-sqlite.db"))
      from

      (fs/regular-file? (path-str nested-data-dir "datomic-sqlite.db"))
      nested-data-dir

      :else
      (throw (ex-info "Datomic data directory is missing datomic-sqlite.db"
                      {:from from
                       :checked [from nested-data-dir]})))))

(defn restore-template!
  [{:keys [main-root name from]}]
  (when-not name
    (throw (ex-info "template restore requires NAME" {:command "template restore"})))
  (when-not from
    (throw (ex-info "template restore requires --from PATH" {:command "template restore"})))
  (let [source-data-dir (restore-source-data-dir from)]
    (write-template! {:main-root main-root
                      :name name
                      :source-data-dir source-data-dir
                      :kind :restore
                      :from (absolutize-str from)})))

(defn alias-template!
  [{:keys [main-root alias template]}]
  (let [main-root (-> main-root state-paths :main-root)
        alias-paths (template-paths main-root alias)
        target-paths (template-paths main-root template)]
    (when-not (fs/directory? (:template-dir target-paths))
      (throw (ex-info "Template directory is missing"
                      {:template template
                       :template-dir (:template-dir target-paths)})))
    (ensure-symlink! (:template-dir alias-paths) (:template-dir target-paths))
    {:alias (:template alias-paths)
     :alias-path (:template-dir alias-paths)
     :template (:template target-paths)
     :template-dir (:template-dir target-paths)}))

(declare down-slot! host-port-open?)

(defn hydrate-slot!
  [{:keys [main-root slot template stop-services? runner port-open? force?]}]
  (let [main-root (-> main-root state-paths :main-root)
        slot-map (if (map? slot) slot {:slot (slot-key slot)})
        slot (slot-key slot)
        slot-paths (slot-paths main-root slot)
        template-paths (template-paths main-root template)
        template-data-dir (:data-dir template-paths)
        port-open? (or port-open? host-port-open?)]
    (when-not (fs/directory? template-data-dir)
      (throw (ex-info "Template Datomic data directory is missing"
                      {:template template
                       :data-dir template-data-dir})))
    (when stop-services?
      (let [down-result (down-slot! {:main-root main-root
                                     :slot slot
                                     :runner runner})]
        (when-not (:ok? down-result)
          (throw (ex-info "Could not stop slot services before hydration"
                          {:slot slot
                           :down-result down-result}))))
      (let [open-ports (->> [:http-port :nrepl-port]
                            (keep (fn [port-key]
                                    (let [port (get slot-map port-key)]
                                      (when (port-open? port)
                                        {:port-key port-key
                                         :port port}))))
                            vec)]
        (when (and (seq open-ports) (not force?))
          (throw (ex-info "Slot ports are still in use"
                          {:slot slot
                           :open-ports open-ports})))))
    (copy-tree-replacing! template-data-dir (:datomic-data-dir slot-paths))
    {:slot slot
     :template (:template template-paths)
     :datomic-data-dir (:datomic-data-dir slot-paths)}))

(defn registry-path [main-root]
  (path-str (-> main-root state-paths :main-root) "dev" "agent-slots.edn"))

(defn load-registry [main-root]
  (-> main-root registry-path read-registry validate-registry))

(defn find-slot
  [registry slot-name]
  (let [slot (slot-key slot-name)]
    (or (first (filter #(= slot (:slot %)) registry))
        (throw (ex-info "Unknown dev slot"
                        {:slot slot
                         :available-slots (mapv :slot registry)})))))

(defn compose-command
  [main-root slot args]
  (let [main-root (-> main-root state-paths :main-root)
        paths (slot-paths main-root (slot-key slot))]
    (into ["docker" "compose"
           "-p" (project-name slot)
           "--env-file" (:compose-env-file paths)
           "-f" (path-str main-root "docker-compose.dev.yml")]
          args)))

(defn docker-ps-command
  [slot flag]
  ["docker" "ps" flag "--filter" (str "label=com.docker.compose.project=" (project-name slot))])

(defn- run-command! [command]
  (apply shell {:out :string :err :string :continue true} command))

(defn- command-ok? [result]
  (zero? (or (:exit result) 0)))

(defn- container-ids [out]
  (->> (str/split-lines (or out ""))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- host-port-open? [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 200)
      true)
    (catch Exception _
      false)))

(defn- open-slot-ports [slot port-open?]
  (->> slot-port-keys
       (keep (fn [port-key]
               (let [port (get slot port-key)]
                 (when (port-open? port)
                   {:port-key port-key
                    :port port}))))
       vec))

(defn ps-slot!
  [{:keys [main-root slot runner]}]
  (let [runner (or runner run-command!)]
    (runner (compose-command main-root slot ["ps"]))))

(defn up-slot!
  [{:keys [main-root slot runner port-open?]}]
  (let [runner (or runner run-command!)
        port-open? (or port-open? host-port-open?)
        existing-containers (container-ids (:out (runner (docker-ps-command slot "-q"))))]
    (when (empty? existing-containers)
      (let [open-ports (open-slot-ports slot port-open?)]
        (when (seq open-ports)
          (throw (ex-info "Slot ports are already in use"
                          {:slot (slot-key slot)
                           :open-ports open-ports})))))
    (runner (compose-command main-root slot ["up" "-d"]))))

(defn down-slot!
  [{:keys [main-root slot runner]}]
  (let [runner (or runner run-command!)
        steps (atom [])
        failures (atom [])
        run-step! (fn [step-name command]
                    (let [result (runner command)
                          step {:step step-name
                                :command command
                                :result result}]
                      (swap! steps conj step)
                      (when-not (command-ok? result)
                        (swap! failures conj step))
                      result))
        compose-down-command (compose-command main-root slot ["down" "--remove-orphans"])
        _ (run-step! :compose-down compose-down-command)
        running-containers (container-ids (:out (run-step! :running-containers
                                                           (docker-ps-command slot "-q"))))
        _ (when (seq running-containers)
            (run-step! :stop-containers (into ["docker" "stop"] running-containers)))
        containers-before-rm (container-ids (:out (run-step! :remaining-containers
                                                             (docker-ps-command slot "-aq"))))
        _ (when (seq containers-before-rm)
            (run-step! :remove-containers (into ["docker" "rm"] containers-before-rm)))
        remaining-containers (container-ids (:out (run-step! :final-containers
                                                             (docker-ps-command slot "-aq"))))
        ok? (and (empty? @failures)
                 (empty? remaining-containers))]
    {:ok? ok?
     :steps @steps
     :failures @failures
     :remaining remaining-containers}))

(defn- check-result
  ([check ok? message]
   (check-result check ok? message nil))
  ([check ok? message data]
   (cond-> {:check check
            :ok? (boolean ok?)
            :message message}
     data (assoc :data data))))

(defn- symlink-target? [link target]
  (and (fs/sym-link? link)
       (= (str (fs/path target))
          (str (fs/read-link link)))))

(defn doctor-slot!
  [{:keys [main-root slot runner port-open?]}]
  (let [main-root (-> main-root state-paths :main-root)
        slot-map (if (map? slot) slot {:slot (slot-key slot)})
        slot (slot-key slot)
        slot-paths (slot-paths main-root slot)
        port-open? (or port-open? host-port-open?)
        claim (read-claim-file (:claim-file slot-paths))
        worktree (:worktree claim)
        current-slot-link (when worktree (fs/path worktree "data.dev" "current-slot"))
        filestore-link (when worktree (fs/path worktree "data.dev" "filestore"))
        compose-result (ps-slot! {:main-root main-root
                                  :slot slot-map
                                  :runner runner})
        checks [(check-result :slot-root
                              (fs/directory? (:slot-root slot-paths))
                              "slot root exists"
                              (:slot-root slot-paths))
                (check-result :env-file
                              (fs/regular-file? (:env-file slot-paths))
                              "env.sh exists"
                              (:env-file slot-paths))
                (check-result :compose-env-file
                              (fs/regular-file? (:compose-env-file slot-paths))
                              "compose.env exists"
                              (:compose-env-file slot-paths))
                (check-result :secrets-file
                              (fs/regular-file? (:secrets-file slot-paths))
                              "secrets.edn exists"
                              (:secrets-file slot-paths))
                (check-result :datomic-db
                              (fs/regular-file? (path-str (:datomic-data-dir slot-paths) "datomic-sqlite.db"))
                              "Datomic SQLite database exists"
                              (:datomic-data-dir slot-paths))
                (check-result :datomic-logback
                              (fs/regular-file? (path-str (:datomic-config-dir slot-paths) "logback.xml"))
                              "Datomic logback config exists"
                              (:datomic-config-dir slot-paths))
                (check-result :current-slot-link
                              (and current-slot-link
                                   (symlink-target? current-slot-link (:slot-root slot-paths)))
                              "worktree current-slot link points at slot root"
                              (some-> current-slot-link str))
                (check-result :filestore-link
                              (and filestore-link
                                   (symlink-target? filestore-link (:shared-filestore-dir slot-paths)))
                              "worktree filestore link points at shared filestore"
                              (some-> filestore-link str))
                (check-result :http-port-free
                              (not (port-open? (:http-port slot-map)))
                              "HTTP port is free before app startup"
                              (:http-port slot-map))
                (check-result :nrepl-port-free
                              (not (port-open? (:nrepl-port slot-map)))
                              "nREPL port is free before app startup"
                              (:nrepl-port slot-map))
                (check-result :compose-ps
                              (command-ok? compose-result)
                              "docker compose ps succeeds"
                              (select-keys compose-result [:exit :out :err]))]
        ok? (every? :ok? checks)]
    {:ok? ok?
     :checks checks
     :urls {:app (slot-http-url slot-map :http-port)
            :smtp4dev (slot-http-url slot-map :smtp4dev-http-port)
            :datomic-console (slot-http-url slot-map :datomic-console-port)}
     :ports (select-keys slot-map slot-port-keys)
     :shared-filestore-dir (:shared-filestore-dir slot-paths)}))

(def option-specs
  {:main-root {:desc "Main project root that owns .dev-state"
               :ref "PATH"}
   :base-secrets {:desc "Base secrets.edn file for init"
                  :ref "PATH"}
   :print-source-command {:coerce :boolean
                          :desc "Print a source command instead of the env file path"}
   :force {:coerce :boolean
           :desc "Repair or replace safe generated state when supported"}
   :branch {:desc "Branch name for future claim/worktree commands"
            :ref "BRANCH"}
   :source-root {:desc "Checkout to import ignored artifacts from"
                 :ref "PATH"}
   :webawesome-version {:desc "WebAwesome version to import or link"
                        :ref "VERSION"}
   :name {:desc "Template name"
          :ref "NAME"}
   :template {:desc "Datomic template name or alias"
              :ref "TEMPLATE"}
   :from {:desc "Existing Datomic data directory for template restore"
          :ref "PATH"}
   :help {:coerce :boolean
          :alias :h
          :desc "Show help"}})

(def cli-spec
  {:spec option-specs})

(defn- opts-spec [& ks]
  {:spec (select-keys option-specs ks)})

(def global-help-spec
  (opts-spec :main-root :help))

(def list-help-spec
  (opts-spec :main-root :help))

(def env-help-spec
  (opts-spec :main-root :print-source-command :help))

(def init-help-spec
  (opts-spec :main-root :base-secrets :force :branch :help))

(def claim-help-spec
  (opts-spec :main-root :branch :force :help))

(def release-help-spec
  (opts-spec :main-root :help))

(def up-help-spec
  (opts-spec :main-root :help))

(def down-help-spec
  (opts-spec :main-root :help))

(def ps-help-spec
  (opts-spec :main-root :help))

(def artifacts-help-spec
  (opts-spec :main-root :help))

(def artifacts-import-help-spec
  (opts-spec :main-root :source-root :webawesome-version :help))

(def artifacts-link-help-spec
  (opts-spec :main-root :webawesome-version :help))

(def template-help-spec
  (opts-spec :main-root :help))

(def template-from-active-help-spec
  (opts-spec :main-root :name :help))

(def template-restore-help-spec
  (opts-spec :main-root :name :from :help))

(def template-alias-help-spec
  (opts-spec :main-root :help))

(def hydrate-help-spec
  (opts-spec :main-root :template :help))

(def doctor-help-spec
  (opts-spec :main-root :help))

(defn- parse-cli [args]
  (cli/parse-args args cli-spec))

(defn- effective-main-root [opts]
  (or (:main-root opts)
      (System/getenv "PROBEMATIC_MAIN_ROOT")
      "."))

(defn- read-base-secrets [main-root opts]
  (let [path (or (:base-secrets opts)
                 (path-str main-root "secrets.edn"))]
    (edn/read-string {:default tagged-literal} (slurp path))))

(defn usage []
  (str
   (str/join
    "\n"
    ["dev-slot - Manage parallel agent dev slots"
     ""
     "Usage:"
     "  bb dev-slot [flags] <command> [arguments]"
     ""
     "Commands:"
     "  list                 List configured dev slots"
     "  env SLOT             Print the path to a slot env.sh file"
     "  init SLOT WORKTREE   Initialize generated slot state for a worktree"
     "  claim SLOT WORKTREE  Claim a slot for a worktree"
     "  up SLOT              Start slot infrastructure services"
     "  down SLOT            Stop and remove slot infrastructure services"
     "  ps SLOT              Show slot infrastructure service status"
     "  doctor SLOT          Check slot configuration and service status"
     "  artifacts            Manage ignored artifact cache and worktree links"
     "  template             Manage reusable Datomic templates"
     "  hydrate SLOT         Replace a slot Datomic store from a template"
     "  release SLOT         Release a slot claim"
     ""
     "Global Flags:"])
   "\n"
   (cli/format-opts global-help-spec)
   "\n"
   "Examples:\n"
   "  bb dev-slot list\n"
   "  bb dev-slot env agent-1 --print-source-command\n"
   "  bb dev-slot init agent-1 ~/src/sno/probematic-agent-1\n"
   "  bb dev-slot artifacts import --source-root /home/ramblurr/src/sno/probematic\n"
   "\n"
   "Use 'bb dev-slot <command> --help' for command-specific options.\n"))

(defn command-usage [command]
  (case command
    "list"
    (str
     "dev-slot list - List configured dev slots\n\n"
     "Usage:\n"
     "  bb dev-slot list [options]\n\n"
     "Options:\n"
     (cli/format-opts list-help-spec)
     "\nExamples:\n"
     "  bb dev-slot list\n")

    "env"
    (str
     "dev-slot env - Print the path to a slot env.sh file\n\n"
     "Usage:\n"
     "  bb dev-slot env SLOT [options]\n\n"
     "Options:\n"
     (cli/format-opts env-help-spec)
     "\nExamples:\n"
     "  bb dev-slot env agent-1\n"
     "  bb dev-slot env agent-1 --print-source-command\n")

    "init"
    (str
     "dev-slot init - Initialize generated slot state for a worktree\n\n"
     "Usage:\n"
     "  bb dev-slot init SLOT WORKTREE [options]\n\n"
     "Options:\n"
     (cli/format-opts init-help-spec)
     "\nExamples:\n"
     "  bb dev-slot init agent-1 ~/src/sno/probematic-agent-1\n"
     "  bb dev-slot init agent-1 ~/src/sno/probematic-agent-1 --base-secrets ./secrets.edn\n")

    "claim"
    (str
     "dev-slot claim - Claim a slot for a worktree\n\n"
     "Usage:\n"
     "  bb dev-slot claim SLOT WORKTREE [options]\n\n"
     "Options:\n"
     (cli/format-opts claim-help-spec)
     "\nExamples:\n"
     "  bb dev-slot claim agent-1 ~/src/sno/probematic-agent-1 --branch feature/agent-1\n")

    "release"
    (str
     "dev-slot release - Release a slot claim\n\n"
     "Usage:\n"
     "  bb dev-slot release SLOT [options]\n\n"
     "Options:\n"
     (cli/format-opts release-help-spec)
     "\nExamples:\n"
     "  bb dev-slot release agent-1\n")

    "up"
    (str
     "dev-slot up - Start slot infrastructure services\n\n"
     "Usage:\n"
     "  bb dev-slot up SLOT [options]\n\n"
     "Options:\n"
     (cli/format-opts up-help-spec)
     "\nExamples:\n"
     "  bb dev-slot up agent-1\n")

    "down"
    (str
     "dev-slot down - Stop and remove slot infrastructure services\n\n"
     "Usage:\n"
     "  bb dev-slot down SLOT [options]\n\n"
     "Options:\n"
     (cli/format-opts down-help-spec)
     "\nExamples:\n"
     "  bb dev-slot down agent-1\n")

    "ps"
    (str
     "dev-slot ps - Show slot infrastructure service status\n\n"
     "Usage:\n"
     "  bb dev-slot ps SLOT [options]\n\n"
     "Options:\n"
     (cli/format-opts ps-help-spec)
     "\nExamples:\n"
     "  bb dev-slot ps agent-1\n")

    "doctor"
    (str
     "dev-slot doctor - Check slot configuration and service status\n\n"
     "Usage:\n"
     "  bb dev-slot doctor SLOT [options]\n\n"
     "Options:\n"
     (cli/format-opts doctor-help-spec)
     "\nExamples:\n"
     "  bb dev-slot doctor agent-1\n")

    "artifacts"
    (str
     "dev-slot artifacts - Manage ignored artifact cache and worktree links\n\n"
     "Usage:\n"
     "  bb dev-slot artifacts <subcommand> [arguments] [options]\n\n"
     "Subcommands:\n"
     "  import          Import ignored artifacts into the main-root cache\n"
     "  link SLOT|WORKTREE Link cached artifacts into a claimed slot or worktree\n\n"
     "Options:\n"
     (cli/format-opts artifacts-help-spec)
     "\nExamples:\n"
     "  bb dev-slot artifacts import --source-root /home/ramblurr/src/sno/probematic\n"
     "  bb dev-slot artifacts link ~/src/sno/probematic-agent-1\n\n"
     "Related Commands:\n"
     "  bb dev-slot artifacts import --help\n"
     "  bb dev-slot artifacts link --help\n")

    "artifacts import"
    (str
     "dev-slot artifacts import - Import ignored artifacts into the main-root cache\n\n"
     "Usage:\n"
     "  bb dev-slot artifacts import [options]\n\n"
     "Options:\n"
     (cli/format-opts artifacts-import-help-spec)
     "\nExamples:\n"
     "  bb dev-slot artifacts import --source-root /home/ramblurr/src/sno/probematic\n"
     "  bb dev-slot artifacts import --source-root . --webawesome-version 3.8.0\n")

    "artifacts link"
    (str
     "dev-slot artifacts link - Link cached artifacts into a claimed slot or worktree\n\n"
     "Usage:\n"
     "  bb dev-slot artifacts link SLOT|WORKTREE [options]\n\n"
     "Options:\n"
     (cli/format-opts artifacts-link-help-spec)
     "\nExamples:\n"
     "  bb dev-slot artifacts link ~/src/sno/probematic-agent-1\n"
     "  bb dev-slot artifacts link agent-1\n"
     "  bb dev-slot artifacts link ~/src/sno/probematic-agent-1 --webawesome-version 3.8.0\n")

    "template"
    (str
     "dev-slot template - Manage reusable Datomic templates\n\n"
     "Usage:\n"
     "  bb dev-slot template <subcommand> [arguments] [options]\n\n"
     "Subcommands:\n"
     "  list                    List Datomic templates\n"
     "  from-active --name NAME Create a template from active dev Datomic data\n"
     "  restore --name NAME --from PATH Create a template from an existing Datomic data directory\n"
     "  alias ALIAS TEMPLATE    Point an alias at a template\n\n"
     "Options:\n"
     (cli/format-opts template-help-spec)
     "\nExamples:\n"
     "  bb dev-slot template from-active --name dev-current\n"
     "  bb dev-slot template alias dev-latest dev-current\n")

    "template from-active"
    (str
     "dev-slot template from-active - Create a template from active dev Datomic data\n\n"
     "Usage:\n"
     "  bb dev-slot template from-active --name NAME [options]\n\n"
     "Options:\n"
     (cli/format-opts template-from-active-help-spec)
     "\nExamples:\n"
     "  bb dev-slot template from-active --name dev-current\n")

    "template restore"
    (str
     "dev-slot template restore - Create a template from an existing Datomic data directory\n\n"
     "Usage:\n"
     "  bb dev-slot template restore --name NAME --from PATH [options]\n\n"
     "Options:\n"
     (cli/format-opts template-restore-help-spec)
     "\nExamples:\n"
     "  bb dev-slot template restore --name prod-2026-07-03 --from data.dev/prod-sync/prod-local\n")

    "template alias"
    (str
     "dev-slot template alias - Point an alias at a template\n\n"
     "Usage:\n"
     "  bb dev-slot template alias ALIAS TEMPLATE [options]\n\n"
     "Options:\n"
     (cli/format-opts template-alias-help-spec)
     "\nExamples:\n"
     "  bb dev-slot template alias dev-latest dev-current\n")

    "hydrate"
    (str
     "dev-slot hydrate - Replace a slot Datomic store from a template\n\n"
     "Usage:\n"
     "  bb dev-slot hydrate SLOT --template TEMPLATE [options]\n\n"
     "Options:\n"
     (cli/format-opts hydrate-help-spec)
     "\nExamples:\n"
     "  bb dev-slot hydrate agent-1 --template dev-latest\n")

    (usage)))

(defn- active-worktree-branch [{:keys [worktree branch]}]
  (let [{:keys [exit out]} (run-command!
                            ["git" "-C" worktree "branch" "--show-current"])]
    (cond
      (not (zero? exit)) branch
      (str/blank? out) "detached"
      :else (str/trim out))))

(defn- slot-list-row [main-root slot]
  (let [claim-file (:claim-file (slot-paths main-root (:slot slot)))]
    (if-let [claim (read-claim-file claim-file)]
      (let [worktree (str (fs/relativize main-root (:worktree claim)))]
        [(name (:slot slot))
         "claimed"
         (if (str/blank? worktree) "." worktree)
         (active-worktree-branch claim)])
      [(name (:slot slot)) "unclaimed" "-" "-"])))

(defn- print-slot-list [rows]
  (let [rows (into [["SLOT" "STATUS" "WORKTREE" "BRANCH"]] rows)
        widths (apply mapv
                      (fn [& cells]
                        (apply max (map count cells)))
                      rows)]
    (doseq [row rows]
      (println
       (str/trimr
        (str/join "  "
                  (map (fn [cell width]
                         (format (str "%-" width "s") cell))
                       row
                       widths)))))))

(defn- list-command [opts]
  (let [main-root (-> opts effective-main-root state-paths :main-root)
        registry (load-registry main-root)]
    (print-slot-list (map #(slot-list-row main-root %) registry))))

(defn- env-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "env requires SLOT" {:command "env"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        env-file (:env-file (slot-paths main-root (:slot slot)))]
    (println (if (:print-source-command opts)
               (str "source " (shell-double-quote env-file))
               env-file))))

(defn- init-command [opts [slot-name worktree]]
  (when-not (and slot-name worktree)
    (throw (ex-info "init requires SLOT and WORKTREE" {:command "init"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        base-secrets (read-base-secrets main-root opts)
        paths (with-slot-lock! {:main-root main-root :slot slot}
                (fn []
                  (init-slot! {:main-root main-root
                               :worktree worktree
                               :slot slot
                               :base-secrets base-secrets
                               :branch (:branch opts)
                               :force? (:force opts)})))]
    (println "initialized" (name (:slot slot)) "at" (:slot-root paths))))

(defn- claim-command [opts [slot-name worktree]]
  (when-not (and slot-name worktree)
    (throw (ex-info "claim requires SLOT and WORKTREE" {:command "claim"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        claim (with-slot-lock! {:main-root main-root :slot slot}
                (fn []
                  (claim-slot! {:main-root main-root
                                :slot slot
                                :worktree worktree
                                :branch (:branch opts)
                                :force? (:force opts)})))]
    (println "claimed" (name (:slot claim)) "for" (:worktree claim))))

(defn- release-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "release requires SLOT" {:command "release"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        result (with-slot-lock! {:main-root main-root :slot slot}
                 (fn []
                   (release-slot! {:main-root main-root
                                   :slot slot})))]
    (println (if (:released? result) "released" "already released")
             (name (:slot result)))))

(defn- up-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "up requires SLOT" {:command "up"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        result (with-slot-lock! {:main-root main-root :slot slot}
                 (fn []
                   (up-slot! {:main-root main-root
                              :slot slot})))]
    (when-not (command-ok? result)
      (throw (ex-info "Slot up failed" {:command "up"
                                        :result result})))
    (println "started" (name (:slot slot)))))

(defn- down-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "down requires SLOT" {:command "down"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        result (with-slot-lock! {:main-root main-root :slot slot}
                 (fn []
                   (down-slot! {:main-root main-root
                                :slot slot})))]
    (println "down" (name (:slot slot))
             (if (:ok? result) "complete" "incomplete"))
    (when-not (:ok? result)
      (throw (ex-info "Slot down did not fully complete"
                      {:command "down"
                       :result result})))))

(defn- ps-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "ps requires SLOT" {:command "ps"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        result (ps-slot! {:main-root main-root
                          :slot slot})]
    (print (:out result))
    (when-not (command-ok? result)
      (throw (ex-info "Slot ps failed" {:command "ps"
                                        :result result})))))

(defn- doctor-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "doctor requires SLOT" {:command "doctor"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        result (doctor-slot! {:main-root main-root
                              :slot slot})]
    (doseq [{:keys [check ok? message data]} (:checks result)]
      (println (if ok? "ok" "fail") (name check) "-" message (or data "")))
    (println "app" (get-in result [:urls :app]))
    (println "smtp4dev" (get-in result [:urls :smtp4dev]))
    (println "datomic-console" (get-in result [:urls :datomic-console]))
    (println "shared-filestore" (:shared-filestore-dir result))
    (when-not (:ok? result)
      (throw (ex-info "Doctor checks failed"
                      {:command "doctor"
                       :result result})))))

(defn- artifacts-import-command [opts]
  (let [main-root (effective-main-root opts)
        result (import-artifacts! {:main-root main-root
                                   :source-root (:source-root opts)
                                   :webawesome-version (:webawesome-version opts)})]
    (println "imported webawesome@" (:webawesome-version result) "to" (:webawesome-cache-dir result))))

(defn- artifacts-link-command [opts [target]]
  (when-not target
    (throw (ex-info "artifacts link requires SLOT or WORKTREE" {:command "artifacts link"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        maybe-slot (some #(when (= (slot-key target) (:slot %)) %) registry)
        result (if maybe-slot
                 (link-artifacts-for-slot! {:main-root main-root
                                            :slot maybe-slot
                                            :webawesome-version (:webawesome-version opts)})
                 (link-artifacts! {:main-root main-root
                                   :worktree target
                                   :webawesome-version (:webawesome-version opts)}))]
    (println "linked webawesome@" (:webawesome-version result) "into" target)))

(defn- artifacts-command [opts [subcommand & args]]
  (case subcommand
    "import" (artifacts-import-command opts)
    "link" (artifacts-link-command opts args)
    (throw (ex-info "artifacts requires SUBCOMMAND" {:command "artifacts"}))))

(defn- template-list-command [opts]
  (let [{:keys [templates-root]} (state-paths (effective-main-root opts))]
    (when (fs/directory? templates-root)
      (doseq [template-name (->> (fs/list-dir templates-root)
                                 (map #(str (fs/file-name %)))
                                 sort)]
        (println template-name)))))

(defn- template-from-active-command [opts]
  (when-not (:name opts)
    (throw (ex-info "template from-active requires --name NAME" {:command "template from-active"})))
  (let [result (template-from-active! {:main-root (effective-main-root opts)
                                       :name (:name opts)})]
    (println "created template" (:template result) "at" (:template-dir result))))

(defn- template-restore-command [opts]
  (when-not (:name opts)
    (throw (ex-info "template restore requires --name NAME" {:command "template restore"})))
  (when-not (:from opts)
    (throw (ex-info "template restore requires --from PATH" {:command "template restore"})))
  (let [result (restore-template! {:main-root (effective-main-root opts)
                                   :name (:name opts)
                                   :from (:from opts)})]
    (println "restored template" (:template result) "at" (:template-dir result))))

(defn- template-alias-command [opts [alias template]]
  (when-not (and alias template)
    (throw (ex-info "template alias requires ALIAS and TEMPLATE" {:command "template alias"})))
  (let [result (alias-template! {:main-root (effective-main-root opts)
                                 :alias alias
                                 :template template})]
    (println "aliased" (:alias result) "to" (:template result))))

(defn- template-command [opts [subcommand & args]]
  (case subcommand
    "list" (template-list-command opts)
    "from-active" (template-from-active-command opts)
    "alias" (template-alias-command opts args)
    "restore" (template-restore-command opts)
    (throw (ex-info "template requires SUBCOMMAND" {:command "template"}))))

(defn- hydrate-command [opts [slot-name]]
  (when-not slot-name
    (throw (ex-info "hydrate requires SLOT" {:command "hydrate"})))
  (when-not (:template opts)
    (throw (ex-info "hydrate requires --template TEMPLATE" {:command "hydrate"})))
  (let [main-root (effective-main-root opts)
        registry (load-registry main-root)
        slot (find-slot registry slot-name)
        result (with-slot-lock! {:main-root main-root :slot slot}
                 (fn []
                   (hydrate-slot! {:main-root main-root
                                   :slot slot
                                   :template (:template opts)
                                   :stop-services? true
                                   :force? (:force opts)})))]
    (println "hydrated" (name (:slot result)) "from" (:template result))))

(defn- command-help-key [command command-args]
  (cond
    (= "artifacts" command)
    (if-let [subcommand (first command-args)]
      (str "artifacts " subcommand)
      "artifacts")

    (= "template" command)
    (if-let [subcommand (first command-args)]
      (str "template " subcommand)
      "template")

    :else command))

(defn run-cli! [& args]
  (let [{:keys [opts args]} (parse-cli args)
        [command & command-args] args]
    (cond
      (:help opts)
      (println (if command
                 (command-usage (command-help-key command command-args))
                 (usage)))

      (= command "list")
      (list-command opts)

      (= command "env")
      (env-command opts command-args)

      (= command "init")
      (init-command opts command-args)

      (= command "claim")
      (claim-command opts command-args)

      (= command "release")
      (release-command opts command-args)

      (= command "up")
      (up-command opts command-args)

      (= command "down")
      (down-command opts command-args)

      (= command "ps")
      (ps-command opts command-args)

      (= command "doctor")
      (doctor-command opts command-args)

      (= command "artifacts")
      (artifacts-command opts command-args)

      (= command "template")
      (template-command opts command-args)

      (= command "hydrate")
      (hydrate-command opts command-args)

      (nil? command)
      (println (usage))

      :else
      (throw (ex-info "Unknown dev-slot command" {:command command})))))

(defn- print-error! [e]
  (binding [*out* *err*]
    (println "Error:" (ex-message e))
    (println)
    (println (or (command-usage (-> e ex-data :command))
                 (usage)))))

(defn -main [& args]
  (try
    (apply run-cli! args)
    (catch Exception e
      (print-error! e)
      (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
