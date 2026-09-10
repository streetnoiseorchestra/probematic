(ns app.jobs.invitations
  "Resumes only the acceptance attempt identified by a durable invitation job."
  (:require
   [app.members.invite.domain :as domain]
   [app.members.invite.workflows :as workflows]
   [app.write-runner :as writer]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [datomic.api :as d]))

(defn- accepted-attempt? [state claim-generation]
  (domain/accepted-invitation?
   {:member-invite/state               state
    :member-invite/resolved-generation (+ claim-generation 3)}))

(>defn resume!
  "Resumes the job's claim without adopting another attempt or creating from scratch.

  Reads and local transitions use the writer. Keycloak calls run on the caller.
  Returns a safe outcome keyword; exceptions remain retryable by the job runner.
  A verified committed receipt wins over an ambiguous final workflow response."
  [{:keys [datomic-conn write-runner clock] :as resources}
   {:keys [member-id claim-generation]}]
  [[:map [:datomic-conn :any] [:write-runner writer/Control] [:clock ifn?] [:keycloak :map]]
   [:map [:member-id uuid?] [:claim-generation pos-int?]]
   => [:enum :accepted :pending :retry :operator-required :superseded]]
  (let [read-state #(writer/call! write-runner (fn [] (domain/invitation-state (d/db datomic-conn) member-id)))
        state      (read-state)]
    (cond
      (accepted-attempt? state claim-generation) :accepted
      (not (domain/resumable-acceptance-attempt? state claim-generation)) :superseded
      :else
      (try
        (let [result  (workflows/accept-or-recover!
                       resources
                       {:member/member-id                  member-id
                        :member-invite/resolved-generation (:generation state)
                        :member-invite/requested-at        (clock)
                        :keycloak/group-name               "Mitglieder"})
              current (read-state)]
          (cond
            (accepted-attempt? current claim-generation) :accepted
            (= :pending (:member-invite/result result)) :pending
            (not (domain/resumable-acceptance-attempt? current claim-generation)) :superseded
            (= :operator-required (:member-invite/result result)) :operator-required
            :else :retry))
        (catch Exception e
          (if (accepted-attempt? (read-state) claim-generation)
            :accepted
            (throw e)))))))
