(ns app.members.invite.admission
  "Admits public invitation setup without running external account operations."
  (:require
   [app.members.invite.cells :as cells]
   [app.members.invite.domain :as domain]
   [app.members.queries :as members]
   [app.write-runner :as writer]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [datomic.api :as d]))

(>defn request-setup!
  "Validates the bearer on the writer and commits only its durable claim.

  Repeated requests do not enqueue another attempt. In-flight legacy attempts
  are left unchanged for status/recovery policy to classify. The result contains
  no bearer, authentication grant, or account profile. Writer rejection propagates."
  [{:keys [datomic-conn write-runner clock] :as resources} invite-code]
  [[:map [:datomic-conn :any] [:write-runner writer/Control] [:clock ifn?]]
   [:maybe :string]
   => [:map [:status [:enum :accepted :creating :retry :unavailable]]
       [:member-id {:optional true} uuid?]]]
  (writer/call!
   write-runner
   (fn []
     (let [db           (d/db datomic-conn)
           requested-at (clock)]
       (if-let [receipt (members/accepted-invitation-by-code db invite-code)]
         {:status :accepted :member-id (get-in receipt [:member :member/member-id])}
         (if-let [{:keys [member-id invite-status]} (members/acceptance-invitation db requested-at invite-code)]
           (if (= :member.invite.status/pending invite-status)
             (let [result (cells/claim-invitation!
                           resources
                           {:member/member-id           member-id
                            :member-invite/state        (domain/invitation-state db member-id)
                            :member-invite/requested-at requested-at})]
               {:status    (if (= :claimed (:member-invite/claim-status result)) :creating :retry)
                :member-id member-id})
             {:status :creating :member-id member-id})
           {:status :unavailable}))))))
