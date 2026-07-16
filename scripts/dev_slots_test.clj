(ns dev-slots-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests testing]]
   [dev-slots :as slots]))

(def valid-registry
  [{:slot :agent-1
    :http-port 6171
    :nrepl-port 7011
    :redis-port 6381
    :datomic-port 4434
    :datomic-console-port 8181
    :smtp4dev-http-port 5102
    :smtp4dev-smtp-port 2601
    :smtp4dev-imap-port 1531}
   {:slot :agent-2
    :http-port 6172
    :nrepl-port 7012
    :redis-port 6382
    :datomic-port 4534
    :datomic-console-port 8281
    :smtp4dev-http-port 5202
    :smtp4dev-smtp-port 2602
    :smtp4dev-imap-port 1532}
   {:slot :agent-3
    :http-port 6173
    :nrepl-port 7013
    :redis-port 6383
    :datomic-port 4634
    :datomic-console-port 8381
    :smtp4dev-http-port 5302
    :smtp4dev-smtp-port 2603
    :smtp4dev-imap-port 1533}])

(def agent-1 (first valid-registry))

(deftest registry-validation-test
  (testing "accepts the planned slot registry"
    (is (= valid-registry (slots/validate-registry valid-registry))))

  (testing "loads the checked-in registry file"
    (is (= valid-registry
           (-> "dev/agent-slots.edn"
               slots/read-registry
               slots/validate-registry))))

  (testing "rejects duplicate slot names before a slot can be claimed ambiguously"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Duplicate slot"
         (slots/validate-registry
          [(first valid-registry)
           (assoc (second valid-registry) :slot :agent-1)]))))

  (testing "rejects duplicate ports across all host-exposed slot ports"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Duplicate port"
         (slots/validate-registry
          [(first valid-registry)
           (assoc (second valid-registry) :redis-port 6381)]))))

  (testing "rejects ports used by the default dev stack"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"default dev stack"
         (slots/validate-registry
          [(assoc (first valid-registry) :redis-port 6379)]))))

  (testing "rejects missing required port keys"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"missing required"
         (slots/validate-registry
          [(dissoc (first valid-registry) :smtp4dev-imap-port)]))))

  (testing "rejects slot names that can escape the slots directory"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"safe lowercase name"
         (slots/validate-registry
          [(assoc (first valid-registry)
                  :slot
                  (keyword "x/../victim"))])))))

(deftest state-path-rendering-test
  (fs/with-temp-dir [main-root {}]
    (let [main-root (str (fs/absolutize main-root))]
      (testing "derives all shared state roots under the main-root .dev-state directory"
        (is (= {:main-root main-root
                :state-root (str (fs/path main-root ".dev-state"))
                :slots-root (str (fs/path main-root ".dev-state" "slots"))
                :templates-root (str (fs/path main-root ".dev-state" "datomic-templates"))
                :artifact-cache-root (str (fs/path main-root ".dev-state" "artifact-cache"))}
               (slots/state-paths main-root))))

      (testing "derives slot paths under the shared main-root state"
        (is (= {:slot :agent-1
                :slot-root (str (fs/path main-root ".dev-state" "slots" "agent-1"))
                :claim-file (str (fs/path main-root ".dev-state" "slots" "agent-1" "claim.edn"))
                :lock-file (str (fs/path main-root ".dev-state" "slots" "agent-1" "slot.lock"))
                :env-file (str (fs/path main-root ".dev-state" "slots" "agent-1" "env.sh"))
                :compose-env-file (str (fs/path main-root ".dev-state" "slots" "agent-1" "compose.env"))
                :secrets-file (str (fs/path main-root ".dev-state" "slots" "agent-1" "secrets.edn"))
                :datomic-data-dir (str (fs/path main-root ".dev-state" "slots" "agent-1" "datomic" "data"))
                :datomic-config-dir (str (fs/path main-root ".dev-state" "slots" "agent-1" "datomic" "config"))
                :smtp4dev-dir (str (fs/path main-root ".dev-state" "slots" "agent-1" "smtp4dev"))
                :logs-dir (str (fs/path main-root ".dev-state" "slots" "agent-1" "logs"))
                :firefox-profile-dir (str (fs/path main-root ".dev-state" "slots" "agent-1" "firefox"))
                :shared-filestore-dir (str (fs/path main-root "data.dev" "filestore"))}
               (slots/slot-paths main-root :agent-1))))

      (testing "refuses unsafe slot path segments"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"safe lowercase name"
             (slots/slot-paths main-root (keyword "x/../../victim"))))))))

(deftest env-sh-rendering-test
  (fs/with-temp-dir [main-root {}]
    (let [main-root (str (fs/absolutize main-root))
          text (slots/render-env-sh main-root agent-1)]
      (testing "renders host process environment for the selected slot"
        (is (str/includes? text (str "export PROBEMATIC_MAIN_ROOT=\"" main-root "\"")))
        (is (str/includes? text "export PROBEMATIC_SLOT=agent-1"))
        (is (str/includes? text (str "export PROBEMATIC_SLOT_ROOT=\"" main-root "/.dev-state/slots/agent-1\"")))
        (is (str/includes? text "export COMPOSE_PROJECT_NAME=probematic-agent-1"))
        (is (str/includes? text "export HTTP_PORT=6171"))
        (is (str/includes? text "export NREPL_PORT=7011"))
        (is (str/includes? text (str "export APP_SECRETS_FILE=\"" main-root "/.dev-state/slots/agent-1/secrets.edn\"")))
        (is (str/includes? text (str "export DATOMIC_URI=\"datomic:sql://app?jdbc:sqlite:" main-root "/.dev-state/slots/agent-1/datomic/data/datomic-sqlite.db\"")))
        (is (str/includes? text (str "export APP_FILESTORE_DIR=\"" main-root "/data.dev/filestore\"")))))))

(deftest compose-env-rendering-test
  (fs/with-temp-dir [main-root {}]
    (let [main-root (str (fs/absolutize main-root))
          text (slots/render-compose-env main-root agent-1)]
      (testing "renders Docker Compose interpolation values for the selected slot"
        (is (str/includes? text "COMPOSE_PROJECT_NAME=probematic-agent-1"))
        (is (str/includes? text "PROBEMATIC_DEV_REDIS_PORT_MAPPING=127.0.0.1:6381:6379"))
        (is (str/includes? text "PROBEMATIC_DEV_SMTP4DEV_WEB_PORT_MAPPING=127.0.0.1:5102:80"))
        (is (str/includes? text "PROBEMATIC_DEV_SMTP4DEV_SMTP_PORT_MAPPING=127.0.0.1:2601:25"))
        (is (str/includes? text "PROBEMATIC_DEV_SMTP4DEV_IMAP_PORT_MAPPING=127.0.0.1:1531:143"))
        (is (str/includes? text (str "PROBEMATIC_DEV_SMTP4DEV_DATA_DIR=" main-root "/.dev-state/slots/agent-1/smtp4dev")))
        (is (str/includes? text "PROBEMATIC_DEV_DATOMIC_CONSOLE_PORT_MAPPING=127.0.0.1:8181:8080"))
        (is (str/includes? text "PROBEMATIC_DEV_DATOMIC_PORT=4434"))
        (is (str/includes? text "PROBEMATIC_DEV_DATOMIC_PORT_MAPPING=127.0.0.1:4434:4434"))
        (is (str/includes? text (str "PROBEMATIC_DEV_DATOMIC_DATA_DIR=" main-root "/.dev-state/slots/agent-1/datomic/data")))
        (is (str/includes? text (str "PROBEMATIC_DEV_DATOMIC_CONFIG_DIR=" main-root "/.dev-state/slots/agent-1/datomic/config")))))))

(defn compose-config
  [& args]
  (:out (apply shell {:out :string :err :string} "docker" "compose" args)))

(deftest docker-compose-dev-parameterization-test
  (testing "the default compose config keeps the current project-root dev ports and volumes"
    (let [repo-root (str (fs/normalize (fs/absolutize ".")))
          out (compose-config "-f" "docker-compose.dev.yml" "config")]
      (is (str/includes? out "published: \"6379\""))
      (is (str/includes? out "published: \"5002\""))
      (is (str/includes? out "published: \"2500\""))
      (is (str/includes? out "published: \"1430\""))
      (is (str/includes? out "published: \"8081\""))
      (is (str/includes? out "published: \"4334\""))
      (is (str/includes? out (str "source: " repo-root "/data.dev/datomic/data")))
      (is (str/includes? out (str "source: " repo-root "/data.dev/datomic/config")))
      (is (str/includes? out (str "source: " repo-root "/data-smtp4dev")))))

  (testing "a slot compose env changes only the slot-sensitive host ports, Datomic advertised port, and service data directories"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            compose-env-file (str (fs/path main-root "compose.env"))
            _ (spit compose-env-file (slots/render-compose-env main-root agent-1))
            out (compose-config "-p" "probematic-agent-1"
                                "--env-file" compose-env-file
                                "-f" "docker-compose.dev.yml"
                                "config")]
        (is (str/includes? out "published: \"6381\""))
        (is (str/includes? out "published: \"5102\""))
        (is (str/includes? out "published: \"2601\""))
        (is (str/includes? out "published: \"1531\""))
        (is (str/includes? out "published: \"8181\""))
        (is (str/includes? out "published: \"4434\""))
        (is (str/includes? out "target: 4434"))
        (is (str/includes? out "DATOMIC_PORT: \"4434\""))
        (is (str/includes? out (str "source: " main-root "/.dev-state/slots/agent-1/datomic/data")))
        (is (str/includes? out (str "source: " main-root "/.dev-state/slots/agent-1/datomic/config")))
        (is (str/includes? out (str "source: " main-root "/.dev-state/slots/agent-1/smtp4dev")))))))

(deftest help-flags-test
  (doseq [flag ["-h" "--help"]]
    (testing (str "prints command summary for " flag)
      (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                          "bb" "scripts/dev_slots.clj" flag)]
        (is (= 0 exit))
        (is (= "" err))
        (is (str/includes? out "dev-slot - Manage parallel agent dev slots"))
        (is (str/includes? out "Usage:"))
        (is (str/includes? out "Commands:"))
        (is (str/includes? out "list"))
        (is (str/includes? out "List configured dev slots"))
        (is (str/includes? out "env SLOT"))
        (is (str/includes? out "Print the path to a slot env.sh file"))
        (is (str/includes? out "init SLOT WORKTREE"))
        (is (str/includes? out "Initialize generated slot state"))
        (is (str/includes? out "artifacts"))
        (is (str/includes? out "Manage ignored artifact cache and worktree links"))
        (is (str/includes? out "Use 'bb dev-slot <command> --help'"))))))

(deftest subcommand-help-test
  (testing "env help describes what env does and its command-specific flags"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "env" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot env - Print the path to a slot env.sh file"))
      (is (str/includes? out "Usage:"))
      (is (str/includes? out "bb dev-slot env SLOT [options]"))
      (is (str/includes? out "Options:"))
      (is (str/includes? out "--print-source-command"))
      (is (str/includes? out "Examples:"))
      (is (str/includes? out "bb dev-slot env agent-1 --print-source-command"))))

  (testing "claim help describes ownership options"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "claim" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot claim - Claim a slot for a worktree"))
      (is (str/includes? out "bb dev-slot claim SLOT WORKTREE"))
      (is (str/includes? out "--branch"))))

  (testing "release help describes slot reset"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "release" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot release - Stop, reset, and release a dev slot"))
      (is (str/includes? out "bb dev-slot release SLOT"))
      (is (str/includes? out "preserves each slot's Firefox profile")))))

(deftest artifact-help-test
  (testing "artifacts help describes artifact subcommands"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "artifacts" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot artifacts - Manage ignored artifact cache and worktree links"))
      (is (str/includes? out "import"))
      (is (str/includes? out "Import ignored artifacts"))
      (is (str/includes? out "link SLOT|WORKTREE"))))

  (testing "artifact import help describes source options"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "artifacts" "import" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot artifacts import - Import ignored artifacts into the main-root cache"))
      (is (str/includes? out "--source-root"))
      (is (str/includes? out "--webawesome-version")))))

(deftest template-and-hydrate-help-test
  (testing "template help describes template subcommands"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "template" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot template - Manage reusable Datomic templates"))
      (is (str/includes? out "from-active"))
      (is (str/includes? out "alias ALIAS TEMPLATE"))))

  (testing "hydrate help describes slot hydration"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "hydrate" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot hydrate - Replace a slot Datomic store from a template"))
      (is (str/includes? out "bb dev-slot hydrate SLOT --template TEMPLATE"))
      (is (str/includes? out "--template")))))

(deftest command-error-test
  (testing "missing env slot prints a concise error and usage without a stack trace"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "env")]
      (is (= 2 exit))
      (is (= "" out))
      (is (str/includes? err "Error: env requires SLOT"))
      (is (str/includes? err "dev-slot env - Print the path to a slot env.sh file"))
      (is (str/includes? err "bb dev-slot env SLOT [options]"))
      (is (not (str/includes? err "----- Error")))
      (is (not (str/includes? err "Stack trace")))))

  (testing "unknown command prints a concise error and usage without a stack trace"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "wat")]
      (is (= 2 exit))
      (is (= "" out))
      (is (str/includes? err "Error: Unknown dev-slot command"))
      (is (str/includes? err "Usage:"))
      (is (not (str/includes? err "----- Error")))
      (is (not (str/includes? err "Stack trace"))))))

(deftest bb-task-error-test
  (testing "bb task propagates the script error without adding task stack noise"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "dev-slot" "env")]
      (is (= 2 exit))
      (is (= "" out))
      (is (str/includes? err "Error: env requires SLOT"))
      (is (str/includes? err "Usage:"))
      (is (not (str/includes? err "Error while executing task")))
      (is (not (str/includes? err "----- Error")))
      (is (not (str/includes? err "Stack trace"))))))

(def base-secrets
  {:app-base-url "https://example.com"
   :redis {:conn-spec {:host "localhost"
                       :port 6379
                       :password "base-password"
                       :ssl? true}}
   :mailgun {:demo-mode? true}
   :untouched {:nested true}})

(def expected-agent-1-secrets
  {:app-base-url "http://agent-1.probematic.localhost:6171"
   :redis {:conn-spec {:host "127.0.0.1"
                       :port 6381
                       :password "devpassword123"}}
   :mailgun {:demo-mode? true}
   :untouched {:nested true}})

(deftest slot-secrets-test
  (testing "merges only slot-specific app URL and Redis connection overrides into the base secrets"
    (is (= expected-agent-1-secrets
           (slots/merge-slot-secrets base-secrets agent-1))))

  (testing "writes a complete slot secrets file with owner-only permissions"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            secrets-file (:secrets-file (slots/slot-paths main-root :agent-1))
            result (slots/write-slot-secrets! main-root agent-1 base-secrets)]
        (is (= secrets-file result))
        (is (= expected-agent-1-secrets
               (edn/read-string (slurp secrets-file))))
        (is (= "rw-------"
               (fs/posix->str (fs/posix-file-permissions secrets-file))))))))

(deftest init-command-base-secrets-reading-test
  (testing "init reads base secrets that contain Aero-style tagged literals"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            registry-file (fs/path main-root "dev" "agent-slots.edn")
            secrets-file (fs/path main-root "secrets.edn")
            generated-secrets-file (:secrets-file (slots/slot-paths main-root :agent-1))]
        (fs/create-dirs (fs/parent registry-file))
        (fs/create-dirs worktree)
        (fs/create-dirs (fs/path main-root "dev" "datomic"))
        (spit (str (fs/path main-root "dev" "datomic" "logback.xml")) "<configuration/>\n")
        (spit (str registry-file) (pr-str valid-registry))
        (spit (str secrets-file) "{:app-base-url \"https://example.com\" :redis {:conn-spec {:host \"localhost\" :port 6379 :password \"p\"}} :external #ref [:foo]}\n")
        (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                            "bb" "scripts/dev_slots.clj"
                                            "--main-root" main-root
                                            "init" "agent-1" worktree)]
          (is (= 0 exit) err)
          (is (str/includes? out "initialized agent-1"))
          (is (str/includes? (slurp generated-secrets-file) "#ref [:foo]")))))))

(deftest slot-claim-test
  (testing "claims a slot for a worktree and leaves the same claim unchanged when repeated"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            claim-file (:claim-file (slots/slot-paths main-root :agent-1))
            result (slots/claim-slot! {:main-root main-root
                                       :slot agent-1
                                       :worktree worktree
                                       :branch "feature/agent-1"})
            saved-claim (edn/read-string (slurp claim-file))]
        (is (= saved-claim result))
        (is (= :agent-1 (:slot saved-claim)))
        (is (= worktree (:worktree saved-claim)))
        (is (= "feature/agent-1" (:branch saved-claim)))
        (is (= "probematic-agent-1" (:compose-project-name saved-claim)))
        (is (string? (:claimed-by saved-claim)))
        (is (string? (:hostname saved-claim)))
        (is (string? (:claimed-at saved-claim)))
        (is (= saved-claim
               (slots/claim-slot! {:main-root main-root
                                   :slot agent-1
                                   :worktree worktree
                                   :branch "feature/agent-1"})))
        (is (= saved-claim
               (edn/read-string (slurp claim-file)))))))

  (testing "refuses to claim a slot owned by another worktree unless forced"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree-a (str (fs/path main-root "worktree-a"))
            worktree-b (str (fs/path main-root "worktree-b"))
            claim-a (slots/claim-slot! {:main-root main-root
                                        :slot agent-1
                                        :worktree worktree-a
                                        :branch "feature/a"})
            claim-file (:claim-file (slots/slot-paths main-root :agent-1))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"already claimed"
             (slots/claim-slot! {:main-root main-root
                                 :slot agent-1
                                 :worktree worktree-b
                                 :branch "feature/b"})))
        (is (= claim-a
               (edn/read-string (slurp claim-file))))
        (let [claim-b (slots/claim-slot! {:main-root main-root
                                          :slot agent-1
                                          :worktree worktree-b
                                          :branch "feature/b"
                                          :force? true})]
          (is (= worktree-b (:worktree claim-b)))
          (is (= "feature/b" (:branch claim-b)))
          (is (= claim-b
                 (edn/read-string (slurp claim-file))))))))

  (testing "release removes an existing claim and is idempotent"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            claim-file (:claim-file (slots/slot-paths main-root :agent-1))]
        (is (= {:slot :agent-1
                :claim-file claim-file
                :released? false}
               (slots/release-slot! {:main-root main-root
                                     :slot agent-1
                                     :stop-services? false})))
        (slots/claim-slot! {:main-root main-root
                            :slot agent-1
                            :worktree worktree
                            :branch "feature/agent-1"})
        (is (fs/exists? claim-file))
        (is (= {:slot :agent-1
                :claim-file claim-file
                :released? true}
               (select-keys (slots/release-slot! {:main-root main-root
                                                  :slot agent-1
                                                  :stop-services? false})
                            [:slot :claim-file :released?])))
        (is (not (fs/exists? claim-file)))
        (is (= {:slot :agent-1
                :claim-file claim-file
                :released? false}
               (slots/release-slot! {:main-root main-root
                                     :slot agent-1
                                     :stop-services? false})))))))

(def logback-template "<configuration/>\n")

(defn write-logback-template! [main-root]
  (let [template (fs/path main-root "dev" "datomic" "logback.xml")]
    (fs/create-dirs (fs/parent template))
    (spit (str template) logback-template)))

(def task-env-probe-config
  '{:tasks
    {task-env-probe
     (-> (clojure {:extra-env {"PROBE_EXTRA" "kept"}
                   :out :string}
                  "-Srepro"
                  "-Sdeps"
                  "{}"
                  "-M"
                  "-e"
                  (str "(prn (select-keys (System/getenv) "
                       "[\"PROBEMATIC_SLOT\" \"HTTP_PORT\" "
                       "\"APP_SECRETS_FILE\" \"PROBE_EXTRA\"]))"))
         :out
         print)}})

(defn task-env-probe [worktree inherited-env]
  (let [repo-root (str (fs/normalize (fs/absolutize ".")))
        {:keys [exit out err]}
        (shell {:continue true
                :dir worktree
                :err :string
                :extra-env inherited-env
                :out :string}
               "bb"
               "--config" (str (fs/path repo-root "bb.edn"))
               "--deps-root" repo-root
               "-Sdeps" (pr-str task-env-probe-config)
               "task-env-probe")]
    {:exit exit
     :out (when-not (str/blank? out)
            (edn/read-string out))
     :err err}))

(deftest task-env-installation-test
  (testing "bb tasks automatically use the initialized worktree's slot environment"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            paths (slots/slot-paths main-root :agent-1)]
        (write-logback-template! main-root)
        (fs/create-dirs worktree)
        (slots/init-slot! {:main-root main-root
                           :worktree worktree
                           :slot agent-1
                           :base-secrets base-secrets})
        (is (= {:exit 0
                :out {"PROBEMATIC_SLOT" "agent-1"
                      "HTTP_PORT" "6171"
                      "APP_SECRETS_FILE" (:secrets-file paths)
                      "PROBE_EXTRA" "kept"}
                :err ""}
               (task-env-probe worktree
                               {"PROBEMATIC_SLOT" "wrong-slot"
                                "HTTP_PORT" "9999"
                                "APP_SECRETS_FILE" "/wrong/secrets.edn"}))))))

  (testing "bb tasks outside an initialized slot keep their inherited environment"
    (fs/with-temp-dir [worktree {}]
      (is (= {:exit 0
              :out {"PROBEMATIC_SLOT" "inherited-slot"
                    "HTTP_PORT" "9999"
                    "APP_SECRETS_FILE" "/inherited/secrets.edn"
                    "PROBE_EXTRA" "kept"}
              :err ""}
             (task-env-probe (str worktree)
                             {"PROBEMATIC_SLOT" "inherited-slot"
                              "HTTP_PORT" "9999"
                              "APP_SECRETS_FILE" "/inherited/secrets.edn"})))))

  (testing "bb tasks fail clearly when the current slot environment is missing"
    (fs/with-temp-dir [worktree {}]
      (let [current-slot-link (fs/path worktree "data.dev" "current-slot")
            missing-slot-root (fs/path worktree "missing-slot")]
        (fs/create-dirs (fs/parent current-slot-link))
        (fs/create-sym-link current-slot-link missing-slot-root)
        (let [{:keys [exit out err]}
              (task-env-probe (str worktree)
                              {"PROBEMATIC_SLOT" "inherited-slot"})]
          (is (= {:exit 1
                  :out nil
                  :missing-env-error?
                  true}
                 {:exit exit
                  :out out
                  :missing-env-error?
                  (str/includes? err
                                 "Current dev slot environment is missing")})))))))

(deftest init-slot-test
  (testing "initializes generated slot files, directories, and worktree symlinks idempotently"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            _ (write-logback-template! main-root)
            _ (fs/create-dirs worktree)
            paths (slots/slot-paths main-root :agent-1)
            result (slots/init-slot! {:main-root main-root
                                      :worktree worktree
                                      :slot agent-1
                                      :base-secrets base-secrets})
            current-slot-link (fs/path worktree "data.dev" "current-slot")
            filestore-link (fs/path worktree "data.dev" "filestore")
            firefox-profile-dir (str (fs/path (:slot-root paths) "firefox"))
            firefox-profile-link (fs/path worktree "data.dev" "firefox")]
        (is (= paths result))
        (is (fs/directory? (:datomic-data-dir paths)))
        (is (fs/directory? (:datomic-config-dir paths)))
        (is (fs/directory? (:smtp4dev-dir paths)))
        (is (fs/directory? (:logs-dir paths)))
        (is (fs/directory? firefox-profile-dir))
        (is (fs/directory? (:shared-filestore-dir paths)))
        (is (= (slots/render-env-sh main-root agent-1)
               (slurp (:env-file paths))))
        (is (= (slots/render-compose-env main-root agent-1)
               (slurp (:compose-env-file paths))))
        (is (= expected-agent-1-secrets
               (edn/read-string (slurp (:secrets-file paths)))))
        (is (= logback-template
               (slurp (str (fs/path (:datomic-config-dir paths) "logback.xml")))))
        (is (fs/sym-link? current-slot-link))
        (is (= (:slot-root paths)
               (str (fs/read-link current-slot-link))))
        (is (fs/sym-link? filestore-link))
        (is (= (:shared-filestore-dir paths)
               (str (fs/read-link filestore-link))))
        (is (and (fs/sym-link? firefox-profile-link)
                 (= firefox-profile-dir
                    (str (fs/read-link firefox-profile-link)))))
        (is (= paths
               (slots/init-slot! {:main-root main-root
                                  :worktree worktree
                                  :slot agent-1
                                  :base-secrets base-secrets})))
        (let [claim (edn/read-string (slurp (:claim-file paths)))]
          (is (= :agent-1 (:slot claim)))
          (is (= worktree (:worktree claim)))))))

  (testing "refuses to replace a real worktree filestore directory"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            filestore-dir (fs/path worktree "data.dev" "filestore")
            claim-file (fs/path main-root ".dev-state" "slots" "agent-1" "claim.edn")
            worktree-profile (fs/path worktree "data.dev" "firefox")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")]
        (write-logback-template! main-root)
        (fs/create-dirs filestore-dir)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Refusing to replace existing path"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (not (fs/exists? claim-file)))
        (is (and (not (fs/exists? worktree-profile {:nofollow-links true}))
                 (not (fs/exists? slot-profile))))))))

(deftest firefox-profile-init-test
  (testing "migrates an existing worktree Firefox profile into empty slot state"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            profile-marker (fs/path worktree-profile "session-marker")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")]
        (write-logback-template! main-root)
        (fs/create-dirs worktree-profile)
        (spit (str profile-marker) "authenticated")
        (slots/init-slot! {:main-root main-root
                           :worktree worktree
                           :slot agent-1
                           :base-secrets base-secrets})
        (is (and (fs/sym-link? worktree-profile)
                 (= (str slot-profile)
                    (str (fs/read-link worktree-profile)))))
        (is (and (fs/regular-file? (fs/path slot-profile "session-marker"))
                 (= "authenticated"
                    (slurp (str (fs/path slot-profile "session-marker")))))))))

  (testing "refuses to merge worktree and slot Firefox profiles"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")]
        (write-logback-template! main-root)
        (fs/create-dirs worktree-profile)
        (fs/create-dirs slot-profile)
        (spit (str (fs/path worktree-profile "worktree-marker")) "worktree")
        (spit (str (fs/path slot-profile "slot-marker")) "slot")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"both the worktree and slot Firefox profiles exist"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (and (fs/regular-file? (fs/path worktree-profile "worktree-marker"))
                 (fs/regular-file? (fs/path slot-profile "slot-marker")))))))

  (testing "refuses a symlinked slot Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            external-profile (fs/path main-root "external-firefox")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")]
        (write-logback-template! main-root)
        (fs/create-dirs worktree)
        (fs/create-dirs external-profile)
        (fs/create-dirs (fs/parent slot-profile))
        (fs/create-sym-link slot-profile external-profile)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"slot Firefox profile must be a real directory"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets}))))))

  (testing "refuses a non-directory worktree Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")]
        (write-logback-template! main-root)
        (fs/create-dirs (fs/parent worktree-profile))
        (spit (str worktree-profile) "not a profile")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"worktree Firefox profile must be a directory"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))))))

(deftest firefox-profile-migration-safety-test
  (testing "refuses to migrate a locked worktree Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")
            claim-file (fs/path main-root ".dev-state" "slots" "agent-1" "claim.edn")]
        (write-logback-template! main-root)
        (fs/create-dirs worktree-profile)
        (spit (str (fs/path worktree-profile ".parentlock")) "locked")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Firefox profile appears to be in use"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (and (fs/directory? worktree-profile)
                 (not (fs/sym-link? worktree-profile))
                 (not (fs/exists? slot-profile))))
        (is (not (fs/exists? claim-file))))))

  (testing "refuses to attach a worktree to a locked slot profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            paths (slots/slot-paths main-root :agent-1)
            lock-file (fs/path (:firefox-profile-dir paths) ".parentlock")]
        (write-logback-template! main-root)
        (fs/create-dirs (:firefox-profile-dir paths))
        (spit (str lock-file) "locked")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Firefox profile appears to be in use"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (fs/regular-file? lock-file))
        (is (not (fs/exists? worktree-profile {:nofollow-links true})))
        (is (not (fs/exists? (:claim-file paths))))))))

(deftest firefox-profile-slot-path-safety-test
  (testing "refuses migration through a symlinked slot root"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            session-marker (fs/path worktree-profile "session-marker")
            external-slot-root (fs/path main-root "external-slot")
            slots-root (fs/path main-root ".dev-state" "slots")
            slot-root (fs/path slots-root "agent-1")]
        (write-logback-template! main-root)
        (fs/create-dirs worktree-profile)
        (spit (str session-marker) "authenticated")
        (fs/create-dirs external-slot-root)
        (fs/create-dirs slots-root)
        (fs/create-sym-link slot-root external-slot-root)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"slot state path must not be a symlink"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (= "authenticated" (slurp (str session-marker))))
        (is (not (fs/exists? (fs/path external-slot-root "firefox"))))
        (is (not (fs/exists? (fs/path external-slot-root "claim.edn"))))))))

(deftest firefox-profile-symlink-conflict-test
  (testing "preserves a worktree Firefox symlink to another profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            external-profile (fs/path main-root "external-firefox")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")
            claim-file (fs/path main-root ".dev-state" "slots" "agent-1" "claim.edn")]
        (write-logback-template! main-root)
        (fs/create-dirs (fs/parent worktree-profile))
        (fs/create-dirs external-profile)
        (spit (str (fs/path external-profile "session-marker")) "external")
        (fs/create-sym-link worktree-profile external-profile)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Firefox profile symlink points outside the slot"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (and (fs/sym-link? worktree-profile)
                 (= (str external-profile)
                    (str (fs/read-link worktree-profile)))
                 (= "external"
                    (slurp (str (fs/path external-profile "session-marker"))))))
        (is (and (not (fs/exists? slot-profile))
                 (not (fs/exists? claim-file)))))))

  (testing "preserves a dangling worktree Firefox symlink"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            missing-profile (fs/path main-root "missing-firefox")
            slot-profile (fs/path main-root ".dev-state" "slots" "agent-1" "firefox")]
        (write-logback-template! main-root)
        (fs/create-dirs (fs/parent worktree-profile))
        (fs/create-sym-link worktree-profile missing-profile)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Firefox profile symlink points outside the slot"
             (slots/init-slot! {:main-root main-root
                                :worktree worktree
                                :slot agent-1
                                :base-secrets base-secrets})))
        (is (and (fs/sym-link? worktree-profile)
                 (= (str missing-profile)
                    (str (fs/read-link worktree-profile)))
                 (not (fs/exists? slot-profile))))))))

(deftest firefox-profile-failed-force-init-test
  (testing "a refused forced migration preserves the previous claim"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree-a (str (fs/path main-root "worktree-a"))
            worktree-b (str (fs/path main-root "worktree-b"))
            worktree-b-profile (fs/path worktree-b "data.dev" "firefox")
            claim-file (fs/path main-root ".dev-state" "slots" "agent-1" "claim.edn")]
        (write-logback-template! main-root)
        (fs/create-dirs worktree-b-profile)
        (spit (str (fs/path worktree-b-profile ".parentlock")) "locked")
        (let [original-claim (slots/claim-slot! {:main-root main-root
                                                 :slot agent-1
                                                 :worktree worktree-a
                                                 :branch "feature/a"})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Firefox profile appears to be in use"
               (slots/init-slot! {:main-root main-root
                                  :worktree worktree-b
                                  :slot agent-1
                                  :base-secrets base-secrets
                                  :branch "feature/b"
                                  :force? true})))
          (is (= original-claim
                 (edn/read-string (slurp (str claim-file)))))))))

  (testing "a late failure leaves profile state with the previous claimant"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree-a (str (fs/path main-root "worktree-a"))
            worktree-a-profile (fs/path worktree-a "data.dev" "firefox")
            worktree-b (str (fs/path main-root "worktree-b"))
            worktree-b-profile (fs/path worktree-b "data.dev" "firefox")
            session-marker (fs/path worktree-a-profile "session-marker")
            paths (slots/slot-paths main-root :agent-1)]
        (write-logback-template! main-root)
        (fs/create-dirs worktree-a-profile)
        (fs/create-dirs worktree-b)
        (spit (str session-marker) "authenticated")
        (spit (str (fs/path main-root "data.dev")) "not a directory")
        (let [original-claim (slots/claim-slot! {:main-root main-root
                                                 :slot agent-1
                                                 :worktree worktree-a
                                                 :branch "feature/a"})]
          (is (thrown?
               Exception
               (slots/init-slot! {:main-root main-root
                                  :worktree worktree-b
                                  :slot agent-1
                                  :base-secrets base-secrets
                                  :branch "feature/b"
                                  :force? true})))
          (is (= original-claim
                 (edn/read-string (slurp (:claim-file paths)))))
          (is (= "authenticated" (slurp (str session-marker))))
          (is (not (fs/exists? worktree-b-profile {:nofollow-links true})))
          (is (not (fs/exists? (:firefox-profile-dir paths)))))))))

(testing "refuses to initialize a worktree over another worktree's claim"
  (fs/with-temp-dir [main-root {}]
    (let [main-root (str (fs/absolutize main-root))
          worktree-a (str (fs/path main-root "worktree-a"))
          worktree-b (str (fs/path main-root "worktree-b"))]
      (write-logback-template! main-root)
      (slots/claim-slot! {:main-root main-root
                          :slot agent-1
                          :worktree worktree-a
                          :branch "feature/a"})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already claimed"
           (slots/init-slot! {:main-root main-root
                              :worktree worktree-b
                              :slot agent-1
                              :base-secrets base-secrets}))))))

(deftest firefox-profile-release-test
  (testing "release unlinks the worktree and preserves the slot Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            _ (write-logback-template! main-root)
            _ (fs/create-dirs worktree)
            paths (slots/init-slot! {:main-root main-root
                                     :worktree worktree
                                     :slot agent-1
                                     :base-secrets base-secrets})
            firefox-profile-dir (fs/path (:slot-root paths) "firefox")
            session-marker (fs/path firefox-profile-dir "session-marker")
            firefox-profile-link (fs/path worktree "data.dev" "firefox")]
        (fs/create-dirs firefox-profile-dir)
        (spit (str session-marker) "authenticated")
        (slots/release-slot! {:main-root main-root
                              :slot agent-1
                              :stop-services? false})
        (is (and (fs/directory? firefox-profile-dir)
                 (fs/regular-file? session-marker)
                 (= "authenticated" (slurp (str session-marker)))))
        (is (not (fs/exists? firefox-profile-link {:nofollow-links true})))
        (is (not (fs/exists? (:logs-dir paths))))
        (is (not (fs/exists? (:env-file paths)))))))

  (testing "release migrates a pre-upgrade worktree Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            paths (slots/slot-paths main-root :agent-1)
            slot-profile (fs/path (:firefox-profile-dir paths))
            session-marker (fs/path worktree-profile "session-marker")]
        (fs/create-dirs worktree-profile)
        (spit (str session-marker) "authenticated")
        (slots/claim-slot! {:main-root main-root
                            :slot agent-1
                            :worktree worktree
                            :branch "feature/agent-1"})
        (slots/release-slot! {:main-root main-root
                              :slot agent-1
                              :stop-services? false})
        (is (not (fs/exists? worktree-profile {:nofollow-links true})))
        (is (= "authenticated"
               (slurp (str (fs/path slot-profile "session-marker")))))
        (is (not (fs/exists? (:claim-file paths)))))))

  (testing "release refuses to migrate a locked pre-upgrade Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            worktree-profile (fs/path worktree "data.dev" "firefox")
            paths (slots/slot-paths main-root :agent-1)
            lock-file (fs/path worktree-profile ".parentlock")]
        (fs/create-dirs worktree-profile)
        (spit (str lock-file) "locked")
        (slots/claim-slot! {:main-root main-root
                            :slot agent-1
                            :worktree worktree
                            :branch "feature/agent-1"})
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Firefox profile appears to be in use"
             (slots/release-slot! {:main-root main-root
                                   :slot agent-1
                                   :stop-services? false})))
        (is (fs/regular-file? lock-file))
        (is (fs/regular-file? (:claim-file paths)))
        (is (not (fs/exists? (:firefox-profile-dir paths)))))))

  (testing "release refuses a lock in an already managed Firefox profile"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            _ (write-logback-template! main-root)
            _ (fs/create-dirs worktree)
            paths (slots/init-slot! {:main-root main-root
                                     :worktree worktree
                                     :slot agent-1
                                     :base-secrets base-secrets})
            firefox-profile-link (fs/path worktree "data.dev" "firefox")
            lock-file (fs/path (:firefox-profile-dir paths) ".parentlock")]
        (spit (str lock-file) "locked")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Firefox profile appears to be in use"
             (slots/release-slot! {:main-root main-root
                                   :slot agent-1
                                   :stop-services? false})))
        (is (fs/regular-file? lock-file))
        (is (fs/regular-file? (:claim-file paths)))
        (is (and (fs/sym-link? firefox-profile-link)
                 (= (str (:firefox-profile-dir paths))
                    (str (fs/read-link firefox-profile-link)))))))))

(defn fake-webawesome-source! [source-root]
  (let [layout-file (fs/path source-root "src" "clj" "app" "layout2.clj")
        wa-dir (fs/path source-root "resources" "public" "vendor" "webawesome@3.8.0")
        skill-dir (fs/path source-root ".agents" "skills" "webawesome")
        java-classes-dir (fs/path source-root "src" "java-classes" "com" "outskirtslabs" "nextcloudcal4j")
        phosphor-dir (fs/path source-root "resources" "public" "img" "phosphor" "phosphor-regular")
        inspector-file (fs/path source-root "resources" "public" "js" "datastar-inspector@1.1.4.js")]
    (fs/create-dirs (fs/parent layout-file))
    (spit (str layout-file) "{:imports {\"wa/\" \"/vendor/webawesome@3.8.0/\"}}")
    (fs/create-dirs wa-dir)
    (spit (str (fs/path wa-dir "webawesome.js")) "// webawesome")
    (fs/create-dirs skill-dir)
    (spit (str (fs/path skill-dir "SKILL.md")) "# WebAwesome skill")
    (fs/create-dirs java-classes-dir)
    (spit (str (fs/path java-classes-dir "Event.class")) "bytecode")
    (fs/create-dirs phosphor-dir)
    (spit (str (fs/path phosphor-dir "info.svg")) "<svg/>")
    (fs/create-dirs (fs/parent inspector-file))
    (spit (str inspector-file) "// datastar inspector")
    {:wa-dir (str wa-dir)
     :skill-dir (str skill-dir)
     :java-classes-dir (str (fs/path source-root "src" "java-classes"))
     :phosphor-dir (str (fs/path source-root "resources" "public" "img" "phosphor"))
     :inspector-file (str inspector-file)}))

(deftest artifact-bootstrap-test
  (testing "detects the active WebAwesome version from layout2"
    (fs/with-temp-dir [source-root {}]
      (fake-webawesome-source! source-root)
      (is (= "3.8.0"
             (slots/detect-webawesome-version source-root)))))

  (testing "import fails fast when the active WebAwesome vendor directory is missing"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (let [layout-file (fs/path source-root "src" "clj" "app" "layout2.clj")]
          (fs/create-dirs (fs/parent layout-file))
          (spit (str layout-file) "\"/vendor/webawesome@3.8.0/\"")
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"WebAwesome vendor directory is missing"
               (slots/import-artifacts! {:main-root (str main-root)
                                         :source-root (str source-root)})))))))

  (testing "import fails fast when the Datastar inspector artifact is missing"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (let [{:keys [inspector-file]} (fake-webawesome-source! source-root)]
          (fs/delete inspector-file)
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Datastar inspector file is missing"
               (slots/import-artifacts! {:main-root (str main-root)
                                         :source-root (str source-root)})))))))

  (testing "imports WebAwesome vendor files and project-local WebAwesome skill into the main-root artifact cache idempotently"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (fake-webawesome-source! source-root)
        (let [result (slots/import-artifacts! {:main-root (str main-root)
                                               :source-root (str source-root)})
              cache-root (:artifact-cache-root (slots/state-paths (str main-root)))
              wa-cache (fs/path cache-root "resources" "public" "vendor" "webawesome@3.8.0")
              skill-cache (fs/path cache-root ".agents" "skills" "webawesome")
              java-classes-cache (fs/path cache-root "src" "java-classes")
              phosphor-cache (fs/path cache-root "resources" "public" "img" "phosphor")
              inspector-cache (fs/path cache-root "resources" "public" "js" "datastar-inspector@1.1.4.js")]
          (is (= {:webawesome-version "3.8.0"
                  :webawesome-cache-dir (str wa-cache)
                  :webawesome-skill-cache-dir (str skill-cache)
                  :java-classes-cache-dir (str java-classes-cache)
                  :phosphor-icons-cache-dir (str phosphor-cache)
                  :datastar-inspector-cache-file (str inspector-cache)}
                 result))
          (is (= "// webawesome"
                 (slurp (str (fs/path wa-cache "webawesome.js")))))
          (is (= "# WebAwesome skill"
                 (slurp (str (fs/path skill-cache "SKILL.md")))))
          (is (= "bytecode"
                 (slurp (str (fs/path java-classes-cache "com" "outskirtslabs" "nextcloudcal4j" "Event.class")))))
          (is (= "<svg/>"
                 (slurp (str (fs/path phosphor-cache "phosphor-regular" "info.svg")))))
          (is (= {:file? true :content "// datastar inspector"}
                 {:file? (fs/regular-file? inspector-cache)
                  :content (when (fs/regular-file? inspector-cache)
                             (slurp (str inspector-cache)))}))
          (is (= result
                 (slots/import-artifacts! {:main-root (str main-root)
                                           :source-root (str source-root)})))))))

  (testing "links cached artifacts into a worktree and repairs broken links idempotently"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (let [worktree (fs/path main-root "worktree")
              _ (fs/create-dirs worktree)
              _ (fake-webawesome-source! source-root)
              _ (slots/import-artifacts! {:main-root (str main-root)
                                          :source-root (str source-root)})
              result (slots/link-artifacts! {:main-root (str main-root)
                                             :worktree (str worktree)})
              wa-link (fs/path worktree "resources" "public" "vendor" "webawesome@3.8.0")
              skill-link (fs/path worktree ".agents" "skills" "webawesome")
              java-classes-link (fs/path worktree "src" "java-classes")
              phosphor-link (fs/path worktree "resources" "public" "img" "phosphor")
              inspector-link (fs/path worktree "resources" "public" "js" "datastar-inspector@1.1.4.js")]
          (is (= {:webawesome-version "3.8.0"
                  :webawesome-link (str wa-link)
                  :webawesome-skill-link (str skill-link)
                  :java-classes-link (str java-classes-link)
                  :phosphor-icons-link (str phosphor-link)
                  :datastar-inspector-link (str inspector-link)}
                 result))
          (is (fs/sym-link? wa-link))
          (is (fs/sym-link? skill-link))
          (is (fs/sym-link? phosphor-link))
          (is (fs/sym-link? java-classes-link))
          (is (fs/sym-link? inspector-link))
          (fs/delete-if-exists skill-link)
          (fs/create-sym-link skill-link (fs/path main-root "missing-skill-target"))
          (is (= result
                 (slots/link-artifacts! {:main-root (str main-root)
                                         :worktree (str worktree)})))
          (is (fs/sym-link? skill-link))
          (is (= (str (fs/path (:artifact-cache-root (slots/state-paths (str main-root))) ".agents" "skills" "webawesome"))
                 (str (fs/read-link skill-link))))))))

  (testing "linking fails fast when the cached Datastar inspector is missing"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (let [worktree (fs/path main-root "worktree")
              _ (fs/create-dirs worktree)
              _ (fake-webawesome-source! source-root)
              imported (slots/import-artifacts! {:main-root (str main-root)
                                                 :source-root (str source-root)})]
          (fs/delete (:datastar-inspector-cache-file imported))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Cached Datastar inspector file is missing"
               (slots/link-artifacts! {:main-root (str main-root)
                                       :worktree (str worktree)})))))))

  (testing "links cached artifacts into a claimed slot worktree"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (let [main-root (str (fs/absolutize main-root))
              worktree (str (fs/path main-root "worktree"))
              _ (fs/create-dirs worktree)
              _ (fake-webawesome-source! source-root)
              _ (slots/import-artifacts! {:main-root main-root
                                          :source-root (str source-root)})
              _ (slots/claim-slot! {:main-root main-root
                                    :slot agent-1
                                    :worktree worktree
                                    :branch "feature/agent-1"})
              result (slots/link-artifacts-for-slot! {:main-root main-root
                                                      :slot agent-1})
              wa-link (fs/path worktree "resources" "public" "vendor" "webawesome@3.8.0")]
          (is (= (str wa-link)
                 (:webawesome-link result)))
          (is (fs/sym-link? wa-link))))))

  (testing "linking refuses to replace a real WebAwesome directory"
    (fs/with-temp-dir [main-root {}]
      (fs/with-temp-dir [source-root {}]
        (let [worktree (fs/path main-root "worktree")
              wa-dir (fs/path worktree "resources" "public" "vendor" "webawesome@3.8.0")]
          (fake-webawesome-source! source-root)
          (slots/import-artifacts! {:main-root (str main-root)
                                    :source-root (str source-root)})
          (fs/create-dirs wa-dir)
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Refusing to replace existing path"
               (slots/link-artifacts! {:main-root (str main-root)
                                       :worktree (str worktree)}))))))))

(defn fake-active-datomic-data! [main-root]
  (let [data-dir (fs/path main-root "data.dev" "datomic" "data")]
    (fs/create-dirs data-dir)
    (spit (str (fs/path data-dir "datomic-sqlite.db")) "db")
    (spit (str (fs/path data-dir "datomic-sqlite.db-wal")) "wal")
    (str data-dir)))

(deftest datomic-template-test
  (testing "creates a reusable template from active dev Datomic data"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            active-data-dir (fake-active-datomic-data! main-root)
            result (slots/template-from-active! {:main-root main-root
                                                 :name "dev-current"})
            template-dir (fs/path main-root ".dev-state" "datomic-templates" "dev-current")
            template-data-dir (fs/path template-dir "data")]
        (is (= {:template "dev-current"
                :template-dir (str template-dir)
                :data-dir (str template-data-dir)}
               result))
        (is (= "db"
               (slurp (str (fs/path template-data-dir "datomic-sqlite.db")))))
        (is (= "wal"
               (slurp (str (fs/path template-data-dir "datomic-sqlite.db-wal")))))
        (let [metadata (edn/read-string (slurp (str (fs/path template-dir "metadata.edn"))))]
          (is (= {:template "dev-current"
                  :source-data-dir active-data-dir
                  :kind :from-active}
                 (select-keys metadata [:template :source-data-dir :kind])))
          (is (string? (:created-at metadata)))
          (is (string? (:source-git-hash metadata)))
          (is (string? (:script-version metadata)))))))

  (testing "template aliases are idempotent symlinks"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            _ (fake-active-datomic-data! main-root)
            _ (slots/template-from-active! {:main-root main-root
                                            :name "dev-current"})
            result (slots/alias-template! {:main-root main-root
                                           :alias "dev-latest"
                                           :template "dev-current"})
            alias-link (fs/path main-root ".dev-state" "datomic-templates" "dev-latest")
            target (fs/path main-root ".dev-state" "datomic-templates" "dev-current")]
        (is (= {:alias "dev-latest"
                :alias-path (str alias-link)
                :template "dev-current"
                :template-dir (str target)}
               result))
        (is (fs/sym-link? alias-link))
        (is (= (str target)
               (str (fs/read-link alias-link))))
        (is (= result
               (slots/alias-template! {:main-root main-root
                                       :alias "dev-latest"
                                       :template "dev-current"}))))))

  (testing "hydrates a slot from a template and removes stale Datomic files"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            _ (fake-active-datomic-data! main-root)
            _ (slots/template-from-active! {:main-root main-root
                                            :name "dev-current"})
            _ (slots/alias-template! {:main-root main-root
                                      :alias "dev-latest"
                                      :template "dev-current"})
            slot-data-dir (:datomic-data-dir (slots/slot-paths main-root :agent-1))
            stale-file (fs/path slot-data-dir "stale.txt")
            _ (fs/create-dirs slot-data-dir)
            _ (spit (str stale-file) "stale")
            result (slots/hydrate-slot! {:main-root main-root
                                         :slot agent-1
                                         :template "dev-latest"})]
        (is (= {:slot :agent-1
                :template "dev-latest"
                :datomic-data-dir slot-data-dir}
               result))
        (is (= "db"
               (slurp (str (fs/path slot-data-dir "datomic-sqlite.db")))))
        (is (not (fs/exists? stale-file)))))))

(testing "restores a reusable template from an existing Datomic data directory"
  (fs/with-temp-dir [main-root {}]
    (fs/with-temp-dir [source-data-dir {}]
      (let [main-root (str (fs/absolutize main-root))
            source-data-dir (str (fs/absolutize source-data-dir))
            _ (spit (str (fs/path source-data-dir "datomic-sqlite.db")) "restored-db")
            result (slots/restore-template! {:main-root main-root
                                             :name "prod-2026-07-03"
                                             :from source-data-dir})
            template-dir (fs/path main-root ".dev-state" "datomic-templates" "prod-2026-07-03")
            template-data-dir (fs/path template-dir "data")
            metadata (edn/read-string (slurp (str (fs/path template-dir "metadata.edn"))))]
        (is (= {:template "prod-2026-07-03"
                :template-dir (str template-dir)
                :data-dir (str template-data-dir)}
               result))
        (is (= "restored-db"
               (slurp (str (fs/path template-data-dir "datomic-sqlite.db")))))
        (is (= {:template "prod-2026-07-03"
                :source-data-dir source-data-dir
                :kind :restore}
               (select-keys metadata [:template :source-data-dir :kind])))
        (is (string? (:created-at metadata)))))))

(testing "hydrate safety stops slot services and refuses to copy when app ports stay open"
  (fs/with-temp-dir [main-root {}]
    (let [main-root (str (fs/absolutize main-root))
          _ (fake-active-datomic-data! main-root)
          _ (slots/template-from-active! {:main-root main-root
                                          :name "dev-current"})
          slot-data-dir (:datomic-data-dir (slots/slot-paths main-root :agent-1))
          stale-file (fs/path slot-data-dir "stale.txt")
          calls (atom [])
          runner (fn [command]
                   (swap! calls conj command)
                   {:exit 0 :out "" :err ""})]
      (fs/create-dirs slot-data-dir)
      (spit (str stale-file) "stale")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Slot ports are still in use"
           (slots/hydrate-slot! {:main-root main-root
                                 :slot agent-1
                                 :template "dev-current"
                                 :stop-services? true
                                 :runner runner
                                 :port-open? (fn [port]
                                               (= port (:http-port agent-1)))})))
      (is (fs/exists? stale-file))
      (is (= [(slots/compose-command main-root agent-1 ["down" "--remove-orphans"])
              (slots/docker-ps-command agent-1 "-q")
              (slots/docker-ps-command agent-1 "-aq")
              (slots/docker-ps-command agent-1 "-aq")]
             @calls)))))

(deftest lifecycle-command-test
  (testing "builds the slot Docker Compose command from the main root and slot"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))]
        (is (= ["docker" "compose"
                "-p" "probematic-agent-1"
                "--env-file" (str (fs/path main-root ".dev-state" "slots" "agent-1" "compose.env"))
                "-f" (str (fs/path main-root "docker-compose.dev.yml"))
                "ps"]
               (slots/compose-command main-root agent-1 ["ps"]))))))

  (testing "ps runs docker compose ps through the provided runner"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            calls (atom [])
            runner (fn [command]
                     (swap! calls conj command)
                     {:exit 0 :out "NAME\n" :err ""})
            result (slots/ps-slot! {:main-root main-root
                                    :slot agent-1
                                    :runner runner})]
        (is (= {:exit 0 :out "NAME\n" :err ""} result))
        (is (= [(slots/compose-command main-root agent-1 ["ps"])]
               @calls)))))

  (testing "up refuses occupied ports before invoking compose when no slot containers exist"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            calls (atom [])
            runner (fn [command]
                     (swap! calls conj command)
                     {:exit 0 :out "" :err ""})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Slot ports are already in use"
             (slots/up-slot! {:main-root main-root
                              :slot agent-1
                              :runner runner
                              :port-open? (constantly true)})))
        (is (= [(slots/docker-ps-command agent-1 "-q")]
               @calls)))))

  (testing "down attempts compose down, leftover stop, leftover removal, and final inspection even after a failure"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            calls (atom [])
            all-containers-calls (atom 0)
            runner (fn [command]
                     (swap! calls conj command)
                     (cond
                       (= command (slots/compose-command main-root agent-1 ["down" "--remove-orphans"]))
                       {:exit 1 :out "" :err "compose failed"}

                       (= command (slots/docker-ps-command agent-1 "-q"))
                       {:exit 0 :out "running-1\n" :err ""}

                       (= command (slots/docker-ps-command agent-1 "-aq"))
                       (if (= 1 (swap! all-containers-calls inc))
                         {:exit 0 :out "stopped-1\n" :err ""}
                         {:exit 0 :out "" :err ""})

                       (= command ["docker" "stop" "running-1"])
                       {:exit 0 :out "" :err ""}

                       (= command ["docker" "rm" "stopped-1"])
                       {:exit 0 :out "" :err ""}

                       :else
                       {:exit 99 :out "" :err (str "unexpected " command)}))
            result (slots/down-slot! {:main-root main-root
                                      :slot agent-1
                                      :runner runner})]
        (is (false? (:ok? result)))
        (is (= [] (:remaining result)))
        (is (= [(slots/compose-command main-root agent-1 ["down" "--remove-orphans"])
                (slots/docker-ps-command agent-1 "-q")
                ["docker" "stop" "running-1"]
                (slots/docker-ps-command agent-1 "-aq")
                ["docker" "rm" "stopped-1"]
                (slots/docker-ps-command agent-1 "-aq")]
               @calls))))))

(deftest slot-lock-test
  (testing "slot locks serialize mutating work"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))]
        (is (= :done
               (slots/with-slot-lock! {:main-root main-root
                                       :slot agent-1}
                 (fn [] :done))))
        (slots/with-slot-lock! {:main-root main-root
                                :slot agent-1}
          (fn []
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"already locked"
                 (slots/with-slot-lock! {:main-root main-root
                                         :slot agent-1}
                   (fn [] :nested))))))))))

(deftest doctor-test
  (testing "doctor reports an initialized slot as healthy without mutating services"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            worktree (str (fs/path main-root "worktree"))
            _ (write-logback-template! main-root)
            _ (fs/create-dirs worktree)
            paths (slots/init-slot! {:main-root main-root
                                     :worktree worktree
                                     :slot agent-1
                                     :base-secrets base-secrets})
            _ (spit (str (fs/path (:datomic-data-dir paths) "datomic-sqlite.db")) "db")
            calls (atom [])
            runner (fn [command]
                     (swap! calls conj command)
                     {:exit 0 :out "NAME STATE\n" :err ""})
            result (slots/doctor-slot! {:main-root main-root
                                        :slot agent-1
                                        :runner runner
                                        :port-open? (constantly false)})
            checks-by-name (into {} (map (juxt :check identity) (:checks result)))]
        (is (:ok? result))
        (is (every? :ok? (:checks result)))
        (doseq [check [:slot-root :env-file :compose-env-file :secrets-file
                       :datomic-db :datomic-logback :current-slot-link
                       :filestore-link :firefox-profile-dir
                       :firefox-profile-link :http-port-free :nrepl-port-free
                       :compose-ps]]
          (is (contains? checks-by-name check)))
        (is (= [(slots/compose-command main-root agent-1 ["ps"])]
               @calls))
        (is (= {:app             "http://agent-1.probematic.localhost:6171"
                :smtp4dev        "http://agent-1.probematic.localhost:5102"
                :datomic-console "http://agent-1.probematic.localhost:8181"}
               (:urls result))))))

  (testing "doctor completes all checks and returns failures"
    (fs/with-temp-dir [main-root {}]
      (let [main-root (str (fs/absolutize main-root))
            result (slots/doctor-slot! {:main-root main-root
                                        :slot agent-1
                                        :runner (fn [_] {:exit 1 :out "" :err "missing"})
                                        :port-open? (constantly true)})
            checks-by-name (into {} (map (juxt :check identity) (:checks result)))]
        (is (false? (:ok? result)))
        (is (false? (get-in checks-by-name [:slot-root :ok?])))
        (is (false? (get-in checks-by-name [:http-port-free :ok?])))
        (is (false? (get-in checks-by-name [:compose-ps :ok?])))
        (is (< 5 (count (:checks result))))))))

(deftest restore-and-doctor-help-test
  (testing "template restore help describes source option"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "template" "restore" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot template restore - Create a template from an existing Datomic data directory"))
      (is (str/includes? out "--from"))))

  (testing "doctor help describes support checks"
    (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                        "bb" "scripts/dev_slots.clj" "doctor" "--help")]
      (is (= 0 exit))
      (is (= "" err))
      (is (str/includes? out "dev-slot doctor - Check slot configuration and service status"))
      (is (str/includes? out "bb dev-slot doctor SLOT")))))

(deftest lifecycle-help-test
  (doseq [[command summary] [["up" "dev-slot up - Start slot infrastructure services"]
                             ["down" "dev-slot down - Stop and remove slot infrastructure services"]
                             ["ps" "dev-slot ps - Show slot infrastructure service status"]]]
    (testing (str command " help describes the lifecycle command")
      (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                          "bb" "scripts/dev_slots.clj" command "--help")]
        (is (= 0 exit))
        (is (= "" err))
        (is (str/includes? out summary))))))

(defn -main []
  (let [{:keys [fail error]} (run-tests 'dev-slots-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
