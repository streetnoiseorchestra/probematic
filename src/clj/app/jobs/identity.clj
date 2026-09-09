(ns app.jobs.identity
  "Durable synchronization of committed member data to Keycloak."
  (:require [app.jobs.feedback :as feedback]
            [app.keycloak :as keycloak]
            [app.queries :as q]
            [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
            [datomic.api :as d]
            [s-exp.drip :as drip]))

(>defn job
  "Returns a job specification for a member's current identity link.

  `changes` contains `:metadata?` and/or `:enabled?` flags. Enabled-state requests
  must also be recorded on the member in the same business transaction."
  [state member-id keycloak-id changes]
  [:map uuid? :string :map => [:vector :any]]
  ["sync-member-identity"
   {:member-id member-id :keycloak-id keycloak-id :changes changes :origin (:job-origin state)}
   {:queue "identity-sync" :max-attempts 25}])

(>defn sync-member!
  "Applies the latest committed member values, not an old job's field values.

  A removed member or replaced identity link makes the job a no-op. Retries of
  an older enabled-state request use the latest requested state, so they cannot
  restore an older value. Remote calls run on the job worker, not the writer."
  [system {:keys [member-id keycloak-id changes]}]
  [:map [:map [:member-id uuid?] [:keycloak-id :string] [:changes :map]] => :any]
  (let [db     (d/db (get-in system [:datomic :conn]))
        member (q/retrieve-member db member-id)]
    (when (and member (= keycloak-id (:member/keycloak-id member)))
      (when (:metadata? changes)
        (keycloak/update-user-meta! (:keycloak system) member))
      (when (:enabled? changes)
        (let [enabled? (:member/keycloak-enabled-request (d/entity db [:member/member-id member-id]))]
          (when (nil? enabled?)
            (throw (ex-info "Identity job has no committed enabled-state request" {:member-id member-id})))
          ((if enabled? keycloak/unlock-account! keycloak/lock-account!) (:keycloak system) member))))))

(defn handle! [system client {:keys [id args attempt]}]
  (try
    (sync-member! system args)
    (drip/complete-job client id)
    (catch Exception e
      (when (= 1 attempt) (feedback/failure! system (:origin args)))
      (throw e))))

(defn start! [system]
  (drip/start-worker!
   {:client         (get-in system [:job-queue :client])
    :registry       {"sync-member-identity" (partial handle! system)}
    :queues         ["identity-sync"]
    :concurrency    1
    :retry-policies {"sync-member-identity" (drip/constant-retry-policy 5000)}}))

(defn stop! [worker]
  (when-not (drip/stop-worker! worker :drain true)
    (throw (ex-info "Identity worker did not stop" {}))))
