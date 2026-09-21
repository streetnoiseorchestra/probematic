(ns app.jobs.worker
  "Runs every durable application job through one shared Dollop worker."
  (:require
   [app.email.email-worker :as email]
   [app.insurance.policy.changes.actions :as policy-actions]
   [app.jobs.identity :as identity]
   [app.jobs.integrations :as integrations]
   [app.jobs.invitations :as invitations]
   [app.jobs.play-stats :as play-stats]
   [app.jobs.policy-mail :as policy-mail]
   [com.brunobonacci.mulog :as μ]
   [s-exp.drip :as drip]
   [s-exp.drip.client :as client]
   [tick.core :as t]))

(def ^:private queues
  ["invitation-setup"
   "identity-sync"
   "policy-mail"
   "email-send-queue"
   "play-stats"
   "integrations"])

(defn- admission-client [delegate]
  (let [monitor     (Object.)
        next-queue  (atom 0)
        queue-index (zipmap queues (range))
        advance!    #(swap! next-queue (fn [idx] (mod (inc idx) (count queues))))]
    (reify
      clojure.lang.ILookup
      (valAt [_ key]
        (get delegate key))
      (valAt [_ key not-found]
        (get delegate key not-found))

      client/Notifications
      (notify-job-available! [_ queue]
        (client/notify-job-available! delegate queue))
      (start-listener! [_ on-notify]
        (client/start-listener! delegate on-notify))
      (stop-listener! [_ listener]
        (client/stop-listener! delegate listener))

      client/Jobs
      (insert-job! [_ tx kind args opts]
        (client/insert-job! delegate tx kind args opts))
      (fetch-jobs! [_ tx queue worker-id opts]
        (locking monitor
          (if (not= (get queue-index queue) @next-queue)
            []
            (do
              (advance!)
              (if (seq (client/list-jobs! delegate
                                          tx
                                          {:queue queue :state :running :limit 1}))
                []
                (client/fetch-jobs! delegate
                                    tx
                                    queue
                                    worker-id
                                    (assoc opts :limit 1)))))))
      (record-output! [_ tx job-id output]
        (client/record-output! delegate tx job-id output))
      (complete-job! [_ tx job-id]
        (client/complete-job! delegate tx job-id))
      (fail-job! [_ tx job-id error-map retry-policy]
        (client/fail-job! delegate tx job-id error-map retry-policy))
      (cancel-job! [_ tx job-id]
        (client/cancel-job! delegate tx job-id))
      (retry-job! [_ tx job-id]
        (client/retry-job! delegate tx job-id))
      (discard-job! [_ tx job-id]
        (client/discard-job! delegate tx job-id))
      (snooze-job! [_ tx job-id duration]
        (client/snooze-job! delegate tx job-id duration))
      (promote-scheduled-jobs! [_ tx]
        (client/promote-scheduled-jobs! delegate tx))
      (rescue-stuck-jobs! [_ tx stuck-after retry-policy selected-queues]
        (client/rescue-stuck-jobs! delegate tx stuck-after retry-policy selected-queues))
      (delete-jobs! [_ tx opts]
        (client/delete-jobs! delegate tx opts))
      (update-job! [_ tx job-id opts]
        (client/update-job! delegate tx job-id opts))
      (delete-job! [_ tx job-id]
        (client/delete-job! delegate tx job-id))
      (get-job! [_ tx job-id]
        (client/get-job! delegate tx job-id))
      (list-jobs! [_ tx opts]
        (client/list-jobs! delegate tx opts))
      (expire-ttl-jobs! [_ tx]
        (client/expire-ttl-jobs! delegate tx))

      client/Queues
      (upsert-queue! [_ tx queue-name metadata]
        (client/upsert-queue! delegate tx queue-name metadata))
      (pause-queue! [_ tx queue-name]
        (client/pause-queue! delegate tx queue-name))
      (resume-queue! [_ tx queue-name]
        (client/resume-queue! delegate tx queue-name))
      (queue-paused? [_ tx queue-name]
        (locking monitor
          (let [paused? (client/queue-paused? delegate tx queue-name)]
            (when (and paused?
                       (= (get queue-index queue-name) @next-queue))
              (advance!))
            paused?)))
      (list-queues! [_ tx]
        (client/list-queues! delegate tx)))))

(defn- registry [system]
  (let [invitation-resources {:datomic-conn (get-in system [:datomic :conn])
                              :write-runner (get-in system [:frame-loop :write-runner])
                              :clock        t/inst
                              :keycloak     (:keycloak system)}]
    {"accept-invitation"           (partial invitations/handle! invitation-resources)
     "sync-member-identity"        (partial identity/handle! system)
     "sync-gig"                    (partial integrations/handle! system :gig)
     "sync-song"                   (partial integrations/handle! system :song)
     "sync-all-songs"              (partial integrations/handle! system :all-songs)
     "refresh-play-stats"          (partial play-stats/handle! system)
     policy-actions/email-job-kind (partial policy-mail/handle! system)
     "send-email"                  (partial email/job-handler system)}))

(defn start!
  "Starts the shared five-job worker for all application queues.

  Admission rotates across the six queues and allows one active job per queue.
  This preserves each former worker's serialization while sharing five permits."
  [{:keys [job-queue] :as system}]
  (μ/log ::starting)
  (drip/start-worker!
   {:client         (admission-client (:client job-queue))
    :registry       (registry system)
    :queues         queues
    :concurrency    5
    :poll-interval  100
    :retry-policies {:default                      drip/default-retry-policy
                     "accept-invitation"           (drip/constant-retry-policy 5000)
                     "sync-member-identity"        (drip/constant-retry-policy 5000)
                     "refresh-play-stats"          (drip/constant-retry-policy 5000)
                     policy-actions/email-job-kind (drip/constant-retry-policy 5000)
                     "send-email"                  (drip/constant-retry-policy 5000)}}))

(defn stop!
  "Drains and stops `worker`, or does nothing when it is absent."
  [worker]
  (when (and worker
             (not (drip/stop-worker! worker :drain true)))
    (throw (ex-info "Shared job worker did not stop" {}))))
