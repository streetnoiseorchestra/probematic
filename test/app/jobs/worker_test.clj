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
  ["invitation-setup"
   "identity-sync"
   "policy-mail"
   "email-send-queue"
   "play-stats"
   "integrations"])

(deftest shared-worker-registers-every-existing-kind-and-queue
  (queue-fixtures/with-queue
    (fn [queue]
      (let [running (worker/start! {:job-queue queue})]
        (try
          (is (= {:concurrency 5
                  :kinds       job-kinds
                  :queues      queue-names}
                 {:concurrency (:concurrency running)
                  :kinds       (set (keys (:registry running)))
                  :queues      (:queues running)}))
          (doseq [kind ["accept-invitation"
                        "refresh-play-stats"
                        "send-email"
                        "send-policy-changes"
                        "sync-member-identity"]]
            (is (= 5000 ((get (:retry-policies running) kind) 1))))
          (is (identical? drip/default-retry-policy
                          (get (:retry-policies running) :default)))
          (finally
            (worker/stop! running)))))))

(deftest shared-worker-preserves-lane-order-and-cross-lane-progress
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [first-identity  (drip/insert-job client "sync-member-identity" {}
                                             :queue "identity-sync")
            second-identity (drip/insert-job client "sync-member-identity" {}
                                             :queue "identity-sync")
            play-job        (drip/insert-job client "refresh-play-stats" {}
                                             :queue "play-stats")
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
          (let [running (worker/start! {:job-queue queue})]
            (try
              (is (= true (deref first-entered 1000 :timeout)))
              (is (= true (deref play-entered 1000 :timeout)))
              (is (thrown? clojure.lang.ExceptionInfo
                           (drip/with-tx [tx (:client running)]
                             (drip/complete-job! (:client running)
                                                 tx
                                                 (:id first-identity))
                             (throw (ex-info "Roll back completion" {})))))
              (is (= :running (:state (drip/get-job client (:id first-identity)))))
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
                (worker/stop! running)))))))))

(deftest paused-queue-does-not-block-rotating-admission
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (drip/pause-queue client "invitation-setup")
      (let [job (drip/insert-job client "sync-member-identity" {}
                                 :queue "identity-sync")]
        (with-redefs [identity/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (drip/complete-job worker-client id))]
          (let [running (worker/start! {:job-queue queue})]
            (try
              (is (= :completed
                     (:state (queue-fixtures/await-state client (:id job) :completed))))
              (finally
                (worker/stop! running)))))))))

(deftest shared-worker-drains-an-admitted-job
  (queue-fixtures/with-queue
    (fn [{:keys [client] :as queue}]
      (let [job     (drip/insert-job client "sync-member-identity" {}
                                     :queue "identity-sync")
            entered (promise)
            release (promise)]
        (with-redefs [identity/handle!
                      (fn [_system worker-client {:keys [id]}]
                        (deliver entered true)
                        @release
                        (drip/complete-job worker-client id))]
          (let [running (worker/start! {:job-queue queue})]
            (try
              (is (= true (deref entered 1000 :timeout)))
              (let [stopping (future
                               (worker/stop! running)
                               :stopped)]
                (is (= :timeout (deref stopping 50 :timeout)))
                (deliver release true)
                (is (= :stopped (deref stopping 1000 :timeout)))
                (is (= :completed (:state (drip/get-job client (:id job))))))
              (finally
                (deliver release true)
                (worker/stop! running)))))))))

(deftest absent-worker-needs-no-shutdown
  (is (nil? (worker/stop! nil))))
