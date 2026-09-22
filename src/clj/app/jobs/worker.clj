(ns app.jobs.worker
  "Runs durable application jobs in schedule-to-start classes."
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
   [tick.core :as t]))

(def ^:private queues
  ["start-within-15s"
   "start-within-2m"
   "start-within-15m"])

(def ^:private retry-policies
  {:default                      drip/default-retry-policy
   "accept-invitation"           (drip/constant-retry-policy 5000)
   "sync-member-identity"        (drip/constant-retry-policy 5000)
   "refresh-play-stats"          (drip/constant-retry-policy 5000)
   policy-actions/email-job-kind (drip/constant-retry-policy 5000)
   "send-email"                  (drip/constant-retry-policy 5000)})

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
  "Starts one serial Dollop worker for each schedule-to-start queue.

  Returns the workers in schedule-to-start order."
  [{:keys [job-queue] :as system}]
  (μ/log ::starting)
  (let [opts    {:client         (:client job-queue)
                 :registry       (registry system)
                 :concurrency    1
                 :poll-interval  100
                 :retry-policies retry-policies}
        started (volatile! [])]
    (try
      (doseq [queue queues]
        (vswap! started conj (drip/start-worker! (assoc opts :queues [queue]))))
      @started
      (catch Throwable t
        (doseq [worker (rseq @started)]
          (drip/stop-worker! worker :drain true))
        (throw t)))))

(defn stop!
  "Drains and stops `workers`, or does nothing when it is absent."
  [workers]
  (when workers
    (when-not (every? true? (mapv #(drip/stop-worker! % :drain true) workers))
      (throw (ex-info "Job workers did not stop" {})))))
