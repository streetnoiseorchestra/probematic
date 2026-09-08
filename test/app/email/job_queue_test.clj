(ns app.email.job-queue-test
  (:require [app.email :as email]
            [app.email.email-worker :as worker]
            [app.email.email-worker-test :as fixtures]
            [app.email.lettermint :as lettermint]
            [app.job-queue :as job-queue]
            [app.system :as system]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [s-exp.drip :as drip]
            [taoensso.nippy :as nippy])
  (:import [java.util Base64]))

(defn with-queue [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
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
                       (email/build-smtp-email
                        "ada@example.test" "Hello" "<p>Hello</p>" "Hello"
                        [{:filename "report.pdf" :content-type "application/pdf"
                          :content (byte-array [0 1 -1 127])}])]]
        (let [job (email/queue-email! {:job-queue queue} message)
              stored (drip/get-job client (:id job))
              decoded (nippy/thaw (.decode (Base64/getDecoder)
                                           ^String (get-in stored [:args :payload])))]
          (is (= {:kind "send-email" :queue worker/email-queue-name
                  :max-attempts 25 :state :available}
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
      (let [job (email/queue-email! {:job-queue queue} fixtures/single-queued-email)
            running (worker/start! {:job-queue queue :lettermint {:demo-mode? true}})]
        (try
          (is (= {:state :completed :attempt 1}
                 (select-keys (await-state client (:id job) :completed) [:state :attempt])))
          (finally (worker/stop! running)))))))

(deftest real-worker-retries-and-discards-provider-failures
  (doseq [[response expected-state] [[{:error :provider :retry? true} :retryable]
                                     [{:error :provider :retry? false} :discarded]]]
    (testing (name expected-state)
      (with-queue
        (fn [{:keys [client] :as queue}]
          (with-redefs [lettermint/send-email! (fn [& _] response)]
            (let [job (worker/queue-mail! queue fixtures/single-queued-email)
                  running (worker/start!
                           {:job-queue queue
                            :lettermint {:from "sender@example.test"
                                         :project-api-token fixtures/test-token
                                         :testing-addresses-only? false
                                         :timeout-ms 2000}})]
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
                (finally (worker/stop! running))))))))))

(deftest email-producers-and-worker-share-the-queue-component
  (let [config (:ig/system (system/config {:profile :test}))]
    (doseq [component [:app.ig/handler :app.ig.jobs/definitions :app.ig/email-worker]]
      (is (= (ig/ref :app.ig/job-queue) (get-in config [component :job-queue]))))
    (is (not (contains? (:app.ig/email-worker config) :redis)))))
