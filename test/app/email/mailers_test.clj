(ns app.email.mailers-test
  (:require
   [app.email :as email]
   [app.email.email-worker :as worker]
   [app.email.mailers :as mailers]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.nexus :as nexus]
   [app.email.email-worker-test :as provider-fixtures]
   [app.email.job-queue-test :as queue-fixtures]
   [app.email.lettermint :as lettermint]
   [app.i18n :as i18n]
   [app.job-queue :as job-queue]
   [app.jobs.gig-events :as gig-events]
   [app.jobs.worker :as jobs-worker]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(def gig-id #uuid "0198215f-95e8-7e0d-8418-743646aa1551")
(def ada-id #uuid "01982160-b5ef-7152-8c87-0f4aed1622ee")
(def grace-id #uuid "01982160-f0e2-7e58-970c-80fb61a62a11")

(defn- seed! []
  (let [{:keys [conn]} (tc/new-system "deferred-email")]
    @(d/transact conn [{:gig/gig-id   gig-id
                        :gig/title    "Before the edit"
                        :gig/date     #inst "2026-08-20"
                        :gig/gig-type :gig.type/gig
                        :gig/status   :gig.status/confirmed
                        :gig/location "Original hall"}
                       {:member/member-id ada-id :member/name  "Ada"
                        :member/active?   true   :member/email "ada@example.test"}
                       {:member/member-id grace-id :member/name  "Grace"
                        :member/active?   true     :member/email "grace@example.test"}])
    conn))

(defn- runtime [queue conn]
  {:job-queue  queue
   :datomic    {:conn conn}
   :i18n-langs (i18n/read-langs)
   :env        {:app-base-url "https://example.test"
                :ig/system    {:app.ig/profile :prod}}
   :lettermint {:from                    "sender@example.test"
                :project-api-token       provider-fixtures/test-token
                :testing-addresses-only? false
                :timeout-ms              2000}})

(defn- request [sys db]
  {:system sys :db db :datomic-conn (get-in sys [:datomic :conn]) :current-locale :de})

(defn- event-report [report]
  (assoc report
         :gig-before (q/retrieve-gig (:db-before report) gig-id)
         :gig (q/retrieve-gig (:db-after report) gig-id)))

(defn- notify-edit! [req report]
  ;; Close the real timer before forum/calendar work runs. Email enqueueing is
  ;; synchronous; no email, queue, database, or rendering function is replaced.
  (with-open [^java.lang.AutoCloseable _scheduled
              (gig-events/trigger-gig-details-edited req true false (event-report report))]
    (first (drip/list-jobs (get-in req [:system :job-queue :client]) {:state :available}))))

(defn- edit! [conn]
  @(d/transact conn [[:db/add [:gig/gig-id gig-id] :gig/title "Committed concert"]
                     [:db/add [:gig/gig-id gig-id] :gig/location "Committed hall"]]))

(defn- change-live-data! [conn]
  @(d/transact conn [[:db/add [:gig/gig-id gig-id] :gig/title "Later concert"]
                     [:db/add [:gig/gig-id gig-id] :gig/location "Later hall"]
                     [:db/add [:member/member-id ada-id] :member/name "Later Ada"]
                     [:db/add [:member/member-id ada-id] :member/email "later@example.test"]
                     [:db/add [:member/member-id grace-id] :member/active? false]
                     {:member/member-id (random-uuid)      :member/name    "New member"
                      :member/email     "new@example.test" :member/active? true}]))

(defn- assert-current-recipient-delivery
  [invocation {:keys [messages options]}]
  (is (= #{["later@example.test"] ["new@example.test"]}
         (set (map :to messages))))
  (is (= ["Gig-Bearbeitung: Committed concert" "Gig-Bearbeitung: Committed concert"]
         (mapv :subject messages)))
  (is (= {:idempotency-key (str (:email-id invocation))} options))
  (doseq [{:keys [html text]} messages]
    (is (every? #(str/includes? % "Committed hall") [html text]))
    (is (not-any? #(str/includes? % "Later") [html text])))
  (is (= 1 (count (distinct (map #(select-keys % [:html :text]) messages))))))

(deftest committed-gig-update-uses-current-recipients-and-freezes-retry
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn       (seed!)
            sys        (runtime queue conn)
            req        (request sys (d/db conn))
            report     (edit! conn)
            _          (change-live-data! conn)
            job        (notify-edit! req report)
            invocation (:args (drip/get-job client (:id job)))
            deliveries (atom [])]
        (is (= {:version   2
                :mailer    :app.email.mailers/gig-updated
                :arguments {:gig-id       gig-id
                            :edited-attrs [:gig/location :gig/title]}
                :source-t  (d/basis-t (:db-after report))
                :email-id  (:email-id invocation)
                :locale    :de}
               invocation))
        (is (uuid? (:email-id invocation)))
        (is (= {:kind "send-email" :queue worker/email-queue-name :max-attempts 25 :state :available}
               (select-keys job [:kind :queue :max-attempts :state])))
        (with-redefs [lettermint/send-emails!
                      (fn [_ messages options]
                        (swap! deliveries conj {:messages messages :options options})
                        (if (= 1 (count @deliveries))
                          {:error :provider :retry? true}
                          {:result :email-sent}))]
          (let [running (jobs-worker/start! sys)]
            (try
              (is (= {:state :retryable :attempt 1}
                     (select-keys (queue-fixtures/await-state client (:id job) :retryable)
                                  [:state :attempt])))
              (assert-current-recipient-delivery invocation (first @deliveries))
              (is (contains? (:metadata (drip/get-job client (:id job))) :email/prepared))
              @(d/transact conn [[:db/add [:gig/gig-id gig-id] :gig/location "Newest hall"]
                                 [:db/add [:member/member-id ada-id] :member/email "newest@example.test"]])
              (drip/retry-job client (:id job))
              (is (= {:state :completed :attempt 2}
                     (select-keys (queue-fixtures/await-state client (:id job) :completed)
                                  [:state :attempt])))
              (is (= 2 (count @deliveries)))
              (is (apply = @deliveries))
              (is (= invocation (:args (drip/get-job client (:id job)))))
              (finally (jobs-worker/stop! running)))))))))

(deftest prepared-and-deferred-jobs-survive-queue-restart
  (let [dir    (.toFile (java.nio.file.Files/createTempDirectory
                         "deferred-email-restart" (make-array java.nio.file.attribute.FileAttribute 0)))
        config {:filename (str (io/file dir "jobs.sqlite"))}
        conn   (seed!)]
    (try
      (let [jobs       (let [queue (job-queue/start! config)]
                         (try
                           (let [sys      (runtime queue conn)
                                 job      (notify-edit! (request sys (d/db conn)) (edit! conn))
                                 prepared (worker/queue-mail! queue provider-fixtures/single-queued-email)]
                             [job prepared])
                           (finally (job-queue/stop! queue))))
            queue      (job-queue/start! config)
            deliveries (atom [])
            deliver!   (fn [_ messages options]
                         (swap! deliveries conj {:messages messages :options options})
                         {:result :email-sent})]
        (try
          (change-live-data! conn)
          (with-redefs [lettermint/send-emails! deliver!
                        lettermint/send-email!  (fn [config message options] (deliver! config [message] options))]
            (let [running (jobs-worker/start! (runtime queue conn))]
              (try
                (doseq [job jobs]
                  (let [stored (queue-fixtures/await-state (:client queue) (:id job) :completed)]
                    (is (= {:state :completed :attempt 1 :args (:args job)}
                           (select-keys stored [:state :attempt :args])))))
                (let [[deferred prepared] jobs
                      by-id               (into {} (map (juxt #(get-in % [:options :idempotency-key]) identity)) @deliveries)]
                  (is (= 2 (count @deliveries)))
                  (assert-current-recipient-delivery (:args deferred)
                                                     (get by-id (str (get-in deferred [:args :email-id]))))
                  (is (= [{:to   ["ada@example.test"] :subject "Hello Ada"           :html "<p>Hello Ada.</p>"
                           :text "Hello Ada."         :from    "sender@example.test"}]
                         (:messages (get by-id (str provider-fixtures/email-id)))))
                  (is (contains? (:args prepared) :prepared-email)))
                (finally (jobs-worker/stop! running)))))
          (finally (job-queue/stop! queue))))
      (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file))))))

(deftest invalid-invocations-are-terminal-without-delivery
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn    (seed!)
            sys     (runtime queue conn)
            job     (notify-edit! (request sys (d/db conn)) (edit! conn))
            valid   (:args job)
            _       (drip/cancel-job client (:id job))
            calls   (atom [])
            invalid [(assoc valid :mailer "clojure.core/eval")
                     (assoc valid :version 99)
                     (assoc-in valid [:arguments :gig-id] "invalid")
                     (assoc-in valid [:arguments :edited-attrs] [])
                     (assoc-in valid [:arguments :member-ids] [ada-id ada-id])
                     (assoc valid :email-id "invalid")
                     (dissoc valid :source-t)
                     {:payload "retired-format"}
                     (assoc-in valid [:arguments :gig-id] (random-uuid))]]
        (with-redefs [lettermint/send-emails! (fn [& args] (swap! calls conj args) {:result :email-sent})]
          (let [jobs    (mapv #(drip/insert-job client "send-email" % :queue worker/email-queue-name) invalid)
                running (jobs-worker/start! sys)]
            (try
              (doseq [job jobs]
                (is (= {:state :discarded :attempt 1}
                       (select-keys (queue-fixtures/await-state client (:id job) :discarded) [:state :attempt]))))
              (is (empty? @calls))
              (finally (jobs-worker/stop! running)))))))))

(deftest uncommitted-and-filtered-sources-are-not-enqueued
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn         (seed!)
            sys          (runtime queue conn)
            req          (request sys (d/db conn))
            report       (edit! conn)
            db           (:db-after report)
            hypothetical (d/with db [[:db/add [:gig/gig-id gig-id] :gig/title "Uncommitted"]])
            _            (change-live-data! conn)
            arguments    {:gig-id gig-id :member-ids [ada-id grace-id] :edited-attrs [:gig/title]}
            producer     {:job-queue queue :datomic-conn conn :current-locale :de}]
        (doseq [source [hypothetical
                        (assoc report :db-after (d/as-of db (d/basis-t db)))
                        (assoc report :db-after (d/since db 1000))
                        (assoc report :db-after (d/filter db (fn [_ _] true)))
                        (assoc report :db-after (d/history db))]]
          (is (thrown? Exception (worker/queue-mailer! producer source :app.email.mailers/gig-updated arguments))))
        (doseq [invalid [(assoc arguments :gig-id "invalid")
                         (assoc arguments :edited-attrs [])
                         (assoc arguments :member-ids (mapv (fn [_] (random-uuid)) (range 501)))]]
          (is (thrown? Exception (worker/queue-mailer! producer report :app.email.mailers/gig-updated invalid))))
        (is (thrown? Exception (worker/queue-mailer! producer report "unknown" arguments)))
        (is (nil? (email/send-gig-updated! req report gig-id [])))
        (is (empty? (drip/list-jobs client {})))))))

(deftest unobserved-snapshots-retry-and-can-recover
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn       (seed!)
            sys        (runtime queue conn)
            initial    (notify-edit! (request sys (d/db conn)) (edit! conn))
            _          (drip/cancel-job client (:id initial))
            target-t   (d/next-t (d/db conn))
            invocation (assoc (:args initial) :source-t target-t)
            job        (drip/insert-job client "send-email" invocation :queue worker/email-queue-name :max-attempts 2)
            deliveries (atom [])]
        (with-redefs [lettermint/send-emails! (fn [_ messages options]
                                                (swap! deliveries conj {:messages messages :options options})
                                                {:result :email-sent})]
          (let [running (jobs-worker/start! sys)]
            (try
              (is (= {:state :retryable :attempt 1 :args invocation}
                     (select-keys (queue-fixtures/await-state client (:id job) :retryable) [:state :attempt :args])))
              (is (empty? @deliveries))
              (let [report @(d/transact conn [[:db/add [:gig/gig-id gig-id] :gig/location "Visible after sync"]])]
                (is (= target-t (d/basis-t (:db-after report)))))
              (drip/retry-job client (:id job))
              (is (= {:state :completed :attempt 2 :args invocation}
                     (select-keys (queue-fixtures/await-state client (:id job) :completed) [:state :attempt :args])))
              (is (= {:delivery-count 1    :options {:idempotency-key (str (:email-id invocation))}
                      :visible?       true}
                     {:delivery-count (count @deliveries)
                      :options        (:options (first @deliveries))
                      :visible?       (every? #(str/includes? (:text %) "Visible after sync")
                                              (:messages (first @deliveries)))}))
              (let [unavailable (drip/insert-job client "send-email" (assoc invocation :source-t (+ target-t 10000))
                                                 :queue worker/email-queue-name :max-attempts 2)]
                (is (= :retryable (:state (queue-fixtures/await-state client (:id unavailable) :retryable))))
                (drip/retry-job client (:id unavailable))
                (is (= {:state :discarded :attempt 2}
                       (select-keys (queue-fixtures/await-state client (:id unavailable) :discarded) [:state :attempt])))
                (is (= 1 (count @deliveries))))
              (finally (jobs-worker/stop! running)))))))))

(deftest removal-only-edits-are-deferred-and-demo-mode-still-completes
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn       (seed!)
            sys        (assoc-in (runtime queue conn) [:lettermint :demo-mode?] true)
            req        (request sys (d/db conn))
            report     @(d/transact conn [[:db/retract [:gig/gig-id gig-id] :gig/location "Original hall"]])
            job        (notify-edit! req report)
            deliveries (atom [])]
        (is (= [:gig/location] (get-in job [:args :arguments :edited-attrs])))
        (with-redefs [lettermint/send-emails! (fn [& args] (swap! deliveries conj args))]
          (let [running (jobs-worker/start! sys)]
            (try
              (is (= {:state :completed :attempt 1}
                     (select-keys (queue-fixtures/await-state client (:id job) :completed) [:state :attempt])))
              (is (empty? @deliveries))
              (finally (jobs-worker/stop! running)))))))))

(deftest notification-opt-out-does-not-enqueue
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn (seed!)
            sys  (runtime queue conn)]
        (with-open [^java.lang.AutoCloseable _scheduled
                    (gig-events/trigger-gig-details-edited
                     (request sys (d/db conn)) false false (event-report (edit! conn)))]
          (is (empty? (drip/list-jobs client {}))))))))

(deftest version-one-retries-preserve-the-stored-recipient-snapshot
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn       (seed!)
            sys        (runtime queue conn)
            initial    (worker/queue-mailer!
                        {:job-queue queue :datomic-conn conn :current-locale :de}
                        (edit! conn) :app.email.mailers/gig-updated
                        {:gig-id gig-id                                          :member-ids [ada-id] :edited-attrs [:gig/title]
                         :extra  {:a/uuid (random-uuid) :a/set #{:a/one :a/two}}})
            _          (drip/cancel-job client (:id initial))
            invocation (assoc (:args initial) :version 1 :extra :app/extra)
            _changed   (change-live-data! conn)
            job        (drip/insert-job client "send-email" invocation :queue worker/email-queue-name)
            deliveries (atom [])]
        (with-redefs [lettermint/send-emails! (fn [_ messages options]
                                                (swap! deliveries conj {:messages messages :options options})
                                                {:result :email-sent})]
          (let [running (jobs-worker/start! sys)]
            (try
              (is (= {:state :completed :attempt 1 :args invocation}
                     (select-keys (queue-fixtures/await-state client (:id job) :completed) [:state :attempt :args])))
              (is (= [{:recipients #{["ada@example.test"]}
                       :options    {:idempotency-key (str (:email-id invocation))}}]
                     (mapv (fn [{:keys [messages options]}]
                             {:recipients (set (map :to messages)) :options options})
                           @deliveries)))
              (finally (jobs-worker/stop! running)))))))))

(deftest log-dispatched-gig-mail-keeps-its-snapshot-and-skips-no-op-edits
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [conn       (seed!)
            sys        (runtime queue conn)
            intent     (mailers/job {:current-locale :de} ::mailers/gig-committed-update
                                    {:gig-id gig-id :member-ids [ada-id grace-id]})
            deliveries (atom [])]
        (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
        (nexus/db-transact-fx {} {:system sys :request {}}
                              [[[[:db/add [:gig/gig-id gig-id] :gig/title "Committed concert"]
                                 [:db/add [:gig/gig-id gig-id] :gig/location "Committed hall"]]
                                {:jobs [intent]}]])
        (let [source-t (d/basis-t (d/db conn))]
          (nexus/db-transact-fx {} {:system sys :request {}} [[[] {:jobs [intent]}]])
          (change-live-data! conn)
          (log-dispatch/dispatch-pending! conn client 128)
          (let [jobs       (drip/list-jobs client {})
                invocation (:args (first (filter #(= source-t (get-in % [:args :source-t])) jobs)))]
            (is (= 2 (count jobs)))
            (with-redefs [lettermint/send-emails!
                          (fn [_ messages options]
                            (swap! deliveries conj {:messages messages :options options})
                            {:result :email-sent})]
              (let [running (jobs-worker/start! sys)]
                (try
                  (doseq [job jobs]
                    (is (= :completed (:state (queue-fixtures/await-state client (:id job) :completed)))))
                  (is (= 1 (count @deliveries)))
                  (assert-current-recipient-delivery invocation (first @deliveries))
                  (finally (jobs-worker/stop! running)))))))))))
