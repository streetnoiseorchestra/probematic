(ns app.members.invite.queries
  "Reads durable invitation setup status without exposing job arguments."
  (:require
   [app.jobs.log-dispatch :as log-dispatch]
   [app.members.invite.domain :as domain]
   [app.members.queries :as members]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(defn- latest-claim [db member-id]
  (when-let [tx (d/q '[:find (max ?tx) . :in $ ?id
                       :where [?e :member/member-id ?id]
                       [?e :member/invite-status :member.invite.status/accepting ?tx true]]
                     (d/history db) member-id)]
    (let [source-t (d/tx->t tx)]
      {:source-t   source-t
       :generation (:generation (domain/invitation-state (d/as-of db source-t) member-id))})))

(defn- matching-job [client tx member-id claim-generation]
  (loop [after nil]
    (let [page (drip/list-jobs! client tx
                                (cond-> {:kind "accept-invitation" :queue "start-within-15s" :limit 100}
                                  after (assoc :after after)))]
      (or (some #(when (= {:member-id member-id :claim-generation claim-generation}
                          (select-keys (:args %) [:member-id :claim-generation])) %)
                page)
          (when (seq page)
            (recur (:id (last page))))))))

(>defn setup-status
  "Returns a safe status for an already-authorized invitation capability.

  `tx` must be borrowed from the frame's job-store reader. A committed receipt
  wins over job state. Consumed but missing work requires operator help, even
  after retention or for a pre-cutover claim; it is never silently restarted."
  [db client tx member-id]
  [:any :any :any uuid? => [:enum :accepted :pending :creating :operator-required :unavailable]]
  (let [state (domain/invitation-state db member-id)]
    (case (:status state)
      :member.invite.status/accepted
      (if (domain/accepted-invitation? {:member-invite/state               state
                                        :member-invite/resolved-generation (:generation state)})
        :accepted
        :operator-required)
      :member.invite.status/pending :pending
      (:member.invite.status/accepting :member.invite.status/creating
                                       :member.invite.status/activating :member.invite.status/compensating)
      (let [{:keys [source-t generation]} (latest-claim db member-id)]
        (if (and source-t (domain/resumable-acceptance-attempt? state generation))
          (if (log-dispatch/processed-through? db tx source-t)
            (if (contains? #{:available :scheduled :executing :retryable}
                           (:state (matching-job client tx member-id generation)))
              :creating
              :operator-required)
            :creating)
          :operator-required))
      :unavailable)))

(>defn status-for-code
  "Authorizes the bearer against this frame and returns status plus minimal member data.

  Recheck on every render. Revoked, replaced, or expired pending capabilities
  return only unavailable. A completed receipt remains valid for the login link.
  No job arguments, bearer, session, or account-access grant is returned."
  [db client tx now invite-code]
  [:any :any :any :any [:maybe :string] => :map]
  (if-let [invitation (or (members/accepted-invitation-by-code db invite-code)
                          (members/acceptance-invitation db now invite-code))]
    (let [member (select-keys (:member invitation) [:member/member-id :member/email])]
      {:status (setup-status db client tx (:member/member-id member))
       :member member})
    {:status :unavailable}))
