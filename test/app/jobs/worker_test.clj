(ns app.jobs.worker-test
  (:require
   [app.email.job-queue-test :as queue-fixtures]
   [app.jobs.identity :as identity]
   [app.jobs.play-stats :as play-stats]
   [app.jobs.worker :as worker]
   [clojure.test :refer [deftest is]]
   [s-exp.drip :as drip]))

(def job-kinds
  #{"accept-invitation"
    "refresh-play-stats"
    "send-email"
    "send-policy-changes"
    "sync-all-songs"
    "sync-gig"
    "sync-member-identity"
    "sync-song"})

(def queue-names
  ["start-within-15s"
   "start-within-2m"
   "start-within-15m"])

(deftest starts-one-serial-worker-per-start-within-queue
  (queue-fixtures/with-queue
    (fn [queue]
      (let [workers (worker/start! {:job-queue queue})]
        (try
          (is (= [{:concurrency 1
                   :kinds       job-kinds
                   :queues      ["start-within-15s"]}
                  {:concurrency 1
                   :kinds       job-kinds
                   :queues      ["start-within-2m"]}
                  {:concurrency 1
                   :kinds       job-kinds
                   :queues      ["start-within-15m"]}]
                 (mapv (fn [running]
                         {:concurrency (:concurrency running)
                          :kinds       (set (keys (:registry running)))
                          :queues      (:queues running)})
                       workers)))
          (doseq [kind ["accept-invitation"
                        "refresh-play-stats"
                        "send-email"
                        "send-policy-changes"
                        "sync-member-identity"]]
            (is (= 5000 ((get (:retry-policies (first workers)) kind) 1))))
          (is (identical? drip/default-retry-policy
                          (get (:retry-policies (first workers)) :default)))
          (finally
            (worker/stop! workers)))))))

(deftest workers-serialize-each-queue-and-make-cross-queue-progress
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [first-identity  (drip/insert-job client "sync-member-identity" {}
                                             :queue "start-within-15s")
            second-identity (drip/insert-job client "sync-member-identity" {}
                                             :queue "start-within-15s")
            play-job        (drip/insert-job client "refresh-play-stats" {}
                                             :queue "start-within-15m")
            first-entered   (promise)
            second-entered  (promise)
            play-entered    (promise)
            release         (promise)]
        (with-redefs [identity/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (if (= id (:id first-identity))
                          (do
                            (deliver first-entered true)
                            @release)
                          (deliver second-entered true))
                        (drip/complete-job worker-client id))
                      play-stats/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (deliver play-entered true)
                        (drip/complete-job worker-client id))]
          (let [workers (worker/start! {:job-queue queue})]
            (try
              (is (= true (deref first-entered 1000 :timeout)))
              (is (= true (deref play-entered 1000 :timeout)))
              (is (= :timeout (deref second-entered 50 :timeout)))
              (is (= :available (:state (drip/get-job client (:id second-identity)))))
              (is (= :completed
                     (:state (queue-fixtures/await-state client (:id play-job) :completed))))
              (deliver release true)
              (is (= :completed
                     (:state (queue-fixtures/await-state client (:id first-identity) :completed))))
              (is (= true (deref second-entered 1000 :timeout)))
              (is (= :completed
                     (:state (queue-fixtures/await-state client (:id second-identity) :completed))))
              (finally
                (deliver release true)
                (worker/stop! workers)))))))))

(deftest paused-start-within-queue-does-not-block-another-worker
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [identity-entered (promise)]
        (with-redefs [identity/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (deliver identity-entered true)
                        (drip/complete-job worker-client id))
                      play-stats/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (drip/complete-job worker-client id))]
          (let [workers (worker/start! {:job-queue queue})]
            (try
              (drip/upsert-queue client "start-within-15s" {})
              (drip/pause-queue client "start-within-15s")
              (let [identity-job (drip/insert-job client "sync-member-identity" {}
                                                  :queue "start-within-15s")
                    play-job     (drip/insert-job client "refresh-play-stats" {}
                                                  :queue "start-within-15m")]
                (is (= :completed
                       (:state (queue-fixtures/await-state client (:id play-job) :completed))))
                (is (= :available (:state (drip/get-job client (:id identity-job)))))
                (is (= :timeout (deref identity-entered 50 :timeout))))
              (finally
                (worker/stop! workers)))))))))

(deftest worker-group-drains-an-admitted-job
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [job     (drip/insert-job client "sync-member-identity" {}
                                     :queue "start-within-15s")
            entered (promise)
            release (promise)]
        (with-redefs [identity/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (deliver entered true)
                        @release
                        (drip/complete-job worker-client id))]
          (let [workers (worker/start! {:job-queue queue})]
            (try
              (is (= true (deref entered 1000 :timeout)))
              (let [stopping (future
                               (worker/stop! workers)
                               :stopped)]
                (is (= :timeout (deref stopping 50 :timeout)))
                (deliver release true)
                (is (= :stopped (deref stopping 1000 :timeout)))
                (is (= :completed (:state (drip/get-job client (:id job))))))
              (finally
                (deliver release true)
                (worker/stop! workers)))))))))

(deftest partial-start-cleanup-reports-every-incomplete-worker
  (let [first-worker  ::first-worker
        second-worker ::second-worker
        start-error   (ex-info "start failed" {})
        stop-error    (ex-info "stop failed" {})
        start-count   (atom 0)
        stop-calls    (atom [])]
    (with-redefs [drip/start-worker!
                  (fn [_opts]
                    (condp = (swap! start-count inc)
                      1 first-worker
                      2 second-worker
                      (throw start-error)))
                  drip/stop-worker!
                  (fn [candidate & _opts]
                    (swap! stop-calls conj candidate)
                    (case candidate
                      ::second-worker (throw stop-error)
                      ::first-worker false))]
      (let [thrown   (try
                       (worker/start! {:job-queue {:client ::client}})
                       nil
                       (catch Exception error
                         error))
            failures (:incomplete-worker-shutdowns (ex-data thrown))]
        (is (= 3 @start-count))
        (is (= [second-worker first-worker] @stop-calls))
        (is (= "Job workers failed to start and cleanup did not complete"
               (ex-message thrown)))
        (is (identical? start-error (ex-cause thrown)))
        (is (= [{:worker second-worker
                 :error  stop-error}
                {:worker first-worker}]
               failures))))))

(deftest absent-worker-group-needs-no-shutdown
  (is (nil? (worker/stop! nil))))
