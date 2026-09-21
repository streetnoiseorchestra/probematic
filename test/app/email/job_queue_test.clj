(ns app.email.job-queue-test
  (:require [app.email :as email]
            [app.email.email-worker :as worker]
            [app.email.email-worker-test :as fixtures]
            [app.email.lettermint :as lettermint]
            [app.email.messages :as messages]
            [app.job-queue :as job-queue]
            [app.jobs.worker :as jobs-worker]
            [app.system :as system]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [s-exp.drip :as drip]
            [tarayo.core :as tarayo]))

(defn with-queue [f]
  (let [dir   (.toFile (java.nio.file.Files/createTempDirectory
                        "email-jobs" (make-array java.nio.file.attribute.FileAttribute 0)))
        queue (job-queue/start! {:filename (str (io/file dir "jobs.sqlite"))})]
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

(deftest email-payloads-survive-sqlite-round-trips
  (with-queue
    (fn [{:keys [client] :as queue}]
      (doseq [message [fixtures/single-queued-email
                       fixtures/batch-queued-email
                       (messages/build-smtp-email
                        "ada@example.test" "Hello" "<p>Hello</p>" "Hello"
                        [{:filename "report.pdf"              :content-type "application/pdf"
                          :content  (byte-array [0 1 -1 127])}])]]
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

(deftest prepared-attachment-bytes-reach-smtp-through-edn-storage
  (with-queue
    (fn [{:keys [client] :as queue}]
      (let [message    (messages/build-smtp-email
                        "ada@example.test" "Report" "<p>Report</p>" "Report"
                        [{:filename "report.pdf"              :content-type "application/pdf"
                          :content  (byte-array [0 1 -1 127])}])
            job        (worker/queue-mail! queue message)
            deliveries (atom [])]
        (with-redefs [tarayo/connect (fn [_] ::smtp-connection)
                      tarayo/send!   (fn [_ email] (swap! deliveries conj email) {})]
          (let [running (jobs-worker/start!
                         {:job-queue queue
                          :env       {:smtp-sno {:from                        "sender@example.test"
                                                 :dev-mode-override-recipient "ada@example.test"}}})]
            (try
              (is (= {:state :completed :attempt 1}
                     (select-keys (await-state client (:id job) :completed) [:state :attempt])))
              (is (= 1 (count @deliveries)))
              (let [attachment (last (:body (first @deliveries)))]
                (is (= {:filename "report.pdf" :content-type "application/pdf" :content [0 1 -1 127]}
                       (update attachment :content vec)))
                (is (bytes? (:content attachment))))
              (finally (jobs-worker/stop! running)))))))))
