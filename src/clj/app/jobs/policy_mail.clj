(ns app.jobs.policy-mail
  "Sends a committed policy snapshot before confirming the changes it contained."
  (:require [app.insurance.domain :as domain]
            [app.datomic :as datomic]
            [app.insurance.exporters :as exporters]
            [app.jobs.feedback :as feedback]
            [app.queries :as q]
            [app.urls :as urls]
            [app.write-runner :as writer]
            [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
            [datomic.api :as d]
            [s-exp.drip :as drip]))

(>defn confirmation-tx
  "Confirms only coverages unchanged since the mailed snapshot.

  New or edited coverages stay pending for the next notification. A removed
  policy or a manually changed policy status is not overwritten."
  [sent current]
  [:map [:maybe :map] => [:sequential :any]]
  (if (and (:insurance.policy/policy-id current)
           (= (:insurance.policy/status sent) (:insurance.policy/status current)))
    (let [current-by-id (into {} (map (juxt :instrument.coverage/coverage-id identity))
                              (:insurance.policy/covered-instruments current))
          unchanged     (filter #(= % (get current-by-id (:instrument.coverage/coverage-id %)))
                                (:insurance.policy/covered-instruments sent))]
      (domain/txs-confirm-and-activate-policy
       (assoc sent :insurance.policy/covered-instruments unchanged)))
    []))

(defn- send! [system policy {:keys [recipient subject body attachment-filename-new attachment-filename-changes]}]
  (let [smtp (get-in system [:env :smtp-sno])]
    (exporters/send-email! policy smtp (:from smtp) recipient subject body
                           attachment-filename-new attachment-filename-changes)))

(>defn deliver!
  "Delivers the source snapshot, then commits confirmation and a retry receipt.

  A retry after that commit does not resend. A crash after SMTP acceptance but
  before the commit can still duplicate delivery; the two systems are not atomic."
  [system {:keys [effect-id source-t origin mail]}]
  [:map [:map [:effect-id uuid?] [:source-t pos-int?] [:mail :map]] => :any]
  (let [conn    (get-in system [:datomic :conn])
        receipt [:app.external-effect/id effect-id]
        db      (d/db conn)]
    (when-not (d/entid db receipt)
      (when (< (d/basis-t db) source-t)
        (throw (ex-info "Policy mail source snapshot is not yet available" {:source-t source-t})))
      (let [policy-id (:policy-id mail)
            sent      (q/retrieve-policy (d/as-of db source-t) policy-id)]
        (when-not (:insurance.policy/policy-id sent)
          (throw (ex-info "Policy mail source snapshot has no policy" {:source-t source-t})))
        (send! system sent mail)
        (writer/call!
         (get-in system [:frame-loop :write-runner])
         (fn []
           (let [current-db (d/db conn)]
             (when-not (d/entid current-db receipt)
               (datomic/transact
                conn
                {:tx-data
                 (into [{:app.external-effect/id effect-id}]
                       (confirmation-tx
                        sent
                        (when (d/entid current-db [:insurance.policy/policy-id policy-id])
                          (q/retrieve-policy current-db policy-id))))
                 :audit
                 (cond-> {:audit/action ::confirm-delivered
                          :audit/origin :app.origin/job}
                   (and (:member-id origin)
                        (d/entid current-db [:member/member-id (:member-id origin)]))
                   (assoc :audit/user [:member/member-id (:member-id origin)]))})))))))))

(defn handle! [system client {:keys [id args attempt]}]
  (try
    (deliver! system args)
    (feedback/redirect! system (:origin args) (urls/link-policy (get-in args [:mail :policy-id])))
    (drip/complete-job client id)
    (catch Exception e
      (when (= 1 attempt) (feedback/failure! system (:origin args)))
      (throw e))))
