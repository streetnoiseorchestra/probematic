(ns app.email.job-queue-test
  (:require [app.email :as email]
            [app.email.email-worker :as worker]
            [app.email.email-worker-test :as fixtures]
            [app.email.lettermint :as lettermint]
            [app.job-queue :as job-queue]
            [app.jobs.worker :as jobs-worker]
            [app.system :as system]
            [app.write-runner :as writer]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [s-exp.drip :as drip]))

(defn with-queue [f]
  (let [dir   (.toFile (java.nio.file.Files/createTempDirectory
                        "email-jobs" (make-array java.nio.file.attribute.FileAttribute 0)))
        queue (job-queue/start! {:filename     (str (io/file dir "jobs.sqlite"))
                                 :write-runner (writer/create)})]
    (try
      (f queue)
      (finally
        (job-queue/stop! queue)
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file))))))

(defn await-state [client id state]
  (loop [remaining 200]
    (let [job (drip/get-job client id)]
      (if (or (= state (:state job)) (zero? remaining))
        job
        (do (Thread/sleep 25) (recur (dec remaining)))))))

(def legacy-smtp-email
  {:email/attachments [{:content      (byte-array [0 1 -1 127])
                        :content-type "application/pdf"
                        :filename     "report.pdf"}]
   :email/batch?      false
   :email/body-html   "<p>Report</p>"
   :email/body-plain  "Report"
   :email/email-id    #uuid "01982164-4e20-7857-80d0-abfa9b90bef1"
   :email/sender      :band-smtp
   :email/subject     "Report"
   :email/tos         ["ada@example.test"]})

(deftest email-payloads-survive-sqlite-round-trips
  (with-queue
    (fn [{:keys [client] :as queue}]
      (doseq [message [fixtures/single-queued-email
                       fixtures/batch-queued-email
                       legacy-smtp-email]]
        (let [job     (email/queue-email! {:job-queue queue} message)
              stored  (drip/get-job client (:id job))
              decoded (get-in stored [:args :prepared-email])]
          (is (= {:kind         "send-email" :queue worker/email-queue-name
                  :max-attempts 25           :state :available}
                 (select-keys stored [:kind :queue :max-attempts :state])))
          (is (= (dissoc message :email/attachments)
                 (dissoc decoded :email/attachments)))
          (when (:email/attachments message)
            (is (= [0 1 -1 127]
                   (vec (get-in decoded [:email/attachments 0 :content]))))))))))

(deftest invalid-email-is-not-enqueued
  (with-queue
    (fn [{:keys [client] :as queue}]
      (is (thrown? Exception (worker/queue-mail! queue {})))
      (is (= [] (drip/list-jobs client {}))))))

(deftest real-worker-completes-demo-email
  (with-queue
    (fn [{:keys [client] :as queue}]
      (let [job     (email/queue-email! {:job-queue queue} fixtures/single-queued-email)
            running (jobs-worker/start! {:job-queue queue :lettermint {:demo-mode? true}})]
        (try
          (is (= {:state :completed :attempt 1}
                 (select-keys (await-state client (:id job) :completed) [:state :attempt])))
          (finally (jobs-worker/stop! running)))))))

(deftest real-worker-retries-and-discards-provider-failures
  (doseq [[response expected-state] [[{:error :provider :retry? true} :retryable]
                                     [{:error :provider :retry? false} :discarded]]]
    (testing (name expected-state)
      (with-queue
        (fn [{:keys [client] :as queue}]
          (with-redefs [lettermint/send-email! (fn [& _] response)]
            (let [job     (worker/queue-mail! queue fixtures/single-queued-email)
                  running (jobs-worker/start!
                           {:job-queue  queue
                            :lettermint {:from                    "sender@example.test"
                                         :project-api-token       fixtures/test-token
                                         :testing-addresses-only? false
                                         :timeout-ms              2000}})]
              (try
                (let [failed (await-state client (:id job) expected-state)]
                  (is (= {:state expected-state :attempt 1}
                         (select-keys failed [:state :attempt])))
                  (when (= :retryable expected-state)
                    (drip/update-job client (:id job) {:max-attempts 2})
                    (drip/retry-job client (:id job))
                    (is (= {:state :discarded :attempt 2}
                           (select-keys (await-state client (:id job) :discarded)
                                        [:state :attempt])))))
                (finally (jobs-worker/stop! running))))))))))

(deftest email-producers-and-worker-share-the-queue-component
  (let [config  (:ig/system (system/config {:profile :test}))
        workers (:app.ig/job-worker config)]
    (doseq [component [:app.ig/handler :app.ig.jobs/definitions :app.ig/job-worker]]
      (is (= (ig/ref :app.ig/job-queue) (get-in config [component :job-queue]))))
    (is (= {:calendar   (ig/ref :app.ig/calendar)
            :datomic    (ig/ref :app.ig/datomic-db)
            :frame-loop (ig/ref :app.ig/frame-loop)
            :i18n-langs (ig/ref :app.ig/i18n-langs)
            :keycloak   (ig/ref :app.ig/keycloak)
            :lettermint (ig/ref :app.ig/lettermint)}
           (select-keys workers
                        [:calendar :datomic :frame-loop :i18n-langs :keycloak :lettermint])))
    (is (not (contains? config :app.ig/redis)))
    (doseq [component [:app.ig/handler :app.ig.jobs/definitions :app.ig/job-worker]]
      (is (not (contains? (get config component) :redis))))))

(deftest legacy-smtp-envelope-is-delivered-through-lettermint
  (with-queue
    (fn [{:keys [client] :as queue}]
      (let [job        (worker/queue-mail! queue legacy-smtp-email)
            deliveries (atom [])]
        (with-redefs [lettermint/send-email!
                      (fn [config message options]
                        (swap! deliveries conj [config message options])
                        {:result     :email-sent
                         :message-id "legacy-message"
                         :status     :queued})]
          (let [running (jobs-worker/start!
                         {:job-queue  queue
                          :lettermint {:from                    "sender@example.test"
                                       :project-api-token       fixtures/test-token
                                       :testing-addresses-only? false
                                       :timeout-ms              2000}})]
            (try
              (is (= {:state :completed :attempt 1}
                     (select-keys (await-state client (:id job) :completed)
                                  [:state :attempt])))
              (is (= 1 (count @deliveries)))
              (let [[_ message options] (first @deliveries)]
                (is (= {:from        "sender@example.test"
                        :to          ["ada@example.test"]
                        :subject     "Report"
                        :html        "<p>Report</p>"
                        :text        "Report"
                        :attachments [{:filename     "report.pdf"
                                       :content-type "application/pdf"
                                       :content      "AAH/fw=="}]}
                       message))
                (is (= {:idempotency-key
                        "01982164-4e20-7857-80d0-abfa9b90bef1"}
                       options)))
              (finally (jobs-worker/stop! running)))))))))

(deftest provider-payload-is-frozen-across-worker-restarts
  (doseq [[description message]
          [["Lettermint envelope" fixtures/single-queued-email]
           ["legacy SMTP envelope" legacy-smtp-email]]]
    (testing description
      (with-queue
        (fn [{:keys [client] :as queue}]
          (let [calls     (atom [])
                attempt   (atom 0)
                job       (worker/queue-mail! queue message)
                run-once! (fn [from route]
                            (jobs-worker/start!
                             {:job-queue  queue
                              :lettermint {:from                    from
                                           :route                   route
                                           :project-api-token       fixtures/test-token
                                           :testing-addresses-only? false
                                           :timeout-ms              2000}}))]
            (with-redefs [lettermint/send-email!
                          (fn [_ provider-message options]
                            (swap! calls conj [provider-message options])
                            (if (= 1 (swap! attempt inc))
                              {:error :transport-error :retry? true}
                              {:result     :email-sent
                               :message-id "accepted-message"
                               :status     :queued}))]
              (let [running (run-once! "first@example.test" "first-route")]
                (try
                  (is (= :retryable
                         (:state (await-state client (:id job) :retryable))))
                  (finally (jobs-worker/stop! running))))
              (let [prepared (get-in (drip/get-job client (:id job))
                                     [:metadata :email/prepared])]
                (is (true? (:email/freeze-runtime? prepared)))
                (is (= :lettermint (:email/sender prepared)))
                (is (= {:from  "first@example.test"
                        :route "first-route"}
                       (select-keys (first (:email/messages prepared))
                                    [:from :route]))))
              (drip/retry-job client (:id job))
              (let [running (run-once! "changed@example.test" "changed-route")]
                (try
                  (is (= :completed
                         (:state (await-state client (:id job) :completed))))
                  (finally (jobs-worker/stop! running))))
              (is (= 2 (count @calls)))
              (is (= (first @calls) (second @calls))))))))))
