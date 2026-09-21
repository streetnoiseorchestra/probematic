(ns app.members.invite.jobs
  "Builds durable account-setup intent without depending on the workflow runner."
  (:require
   [app.jobs.log-dispatch :as log-dispatch]
   [app.members.invite.domain :as domain]))

(defn acceptance-job
  "Returns a setup job correlated with the generation committed by the claim."
  [member-id claim-generation]
  ["accept-invitation" {:member-id member-id :claim-generation claim-generation}
   {:queue "invitation-setup" :max-attempts 25}])

(defn claim-tx
  "Returns [[app.members.invite.domain/claim-tx]] with atomic setup intent.

  Uses the same `params` contract as the domain plan. Job arguments contain the
  member and claim generation, not the invitation bearer. A rejected plan is nil."
  [db {:keys [member-id] :as params}]
  (when-let [plan (domain/claim-tx db params)]
    (update plan :tx-data into
            (log-dispatch/intent-tx
             [(acceptance-job member-id (get-in plan [:state :generation]))]))))
