(ns app.jobs.policy-mail
  "Sends a committed policy snapshot before confirming the changes it contained."
  (:require [app.datomic :as datomic]
            [app.email.email-worker :as email-worker]
            [app.insurance.domain :as domain]
            [app.insurance.exporters :as exporters]
            [app.jobs.feedback :as feedback]
            [app.queries :as q]
            [app.urls :as urls]
            [app.write-runner :as writer]
            [clojure.string :as str]
            [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
            [datomic.api :as d]
            [s-exp.drip :as drip])
  (:import
   [java.util Base64]))

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

(defn- recipient-address [recipient]
  (if-let [[_ address] (re-matches #".*<\s*([^<>]+?)\s*>\s*" recipient)]
    (str/trim address)
    (str/trim recipient)))

(defn- encode-attachment [{:keys [content] :as attachment}]
  (assoc attachment
         :content
         (.encodeToString (Base64/getEncoder) ^bytes content)))

(defn- source-policy [db source-t mail]
  (when (< (d/basis-t db) source-t)
    (throw (ex-info "Policy mail source snapshot is not yet available"
                    {:source-t source-t})))
  (let [policy (q/retrieve-policy (d/as-of db source-t) (:policy-id mail))]
    (when-not (:insurance.policy/policy-id policy)
      (throw (ex-info "Policy mail source snapshot has no policy"
                      {:source-t source-t})))
    policy))

(defn- prepare-email
  [system policy effect-id
   {:keys [recipient subject body attachment-filename-new
           attachment-filename-changes]}]
  (let [runtime     (:lettermint system)
        attachments (exporters/generate-attachments!
                     policy
                     attachment-filename-new
                     attachment-filename-changes)
        message     (cond-> {:to          [(recipient-address recipient)]
                             :reply-to    [(get-in system [:env :insurance :email-reply-to])]
                             :subject     subject
                             :text        body
                             :attachments (mapv encode-attachment attachments)}
                      (some? (:from runtime))
                      (assoc :from (:from runtime))

                      (some? (:route runtime))
                      (assoc :route (:route runtime)))]
    {:email/sender          :lettermint
     :email/email-id        effect-id
     :email/batch?          false
     :email/freeze-runtime? true
     :email/messages        [message]}))

(>defn deliver!
  "Delivers the source snapshot, then commits confirmation and a retry receipt.

  Retries reuse the external-effect ID as Lettermint's idempotency key. The
  provider's retention window bounds its duplicate protection."
  [system {:keys [effect-id source-t origin mail prepared-email]}]
  [:map [:map [:effect-id uuid?] [:source-t pos-int?] [:mail :map]] => :any]
  (let [conn    (get-in system [:datomic :conn])
        receipt [:app.external-effect/id effect-id]
        db      (d/db conn)]
    (when-not (d/entid db receipt)
      (let [policy-id (:policy-id mail)
            sent      (source-policy db source-t mail)
            email     (or prepared-email
                          (prepare-email system sent effect-id mail))
            result    (email-worker/lettermint-handler system email)]
        (when (:error result)
          (throw (ex-info "Lettermint rejected policy mail delivery"
                          {:type      ::delivery-error
                           :effect-id effect-id
                           :result    result
                           :retry?    (boolean (:retry? result))})))
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
(defn- prepare-job-email
  [system client {:keys [id args metadata]}]
  (let [db      (d/db (get-in system [:datomic :conn]))
        receipt [:app.external-effect/id (:effect-id args)]]
    (when-not (d/entid db receipt)
      (or (:email/prepared metadata)
          (let [email (prepare-email
                       system
                       (source-policy db (:source-t args) (:mail args))
                       (:effect-id args)
                       (:mail args))]
            (drip/update-job client id
                             {:metadata (assoc metadata :email/prepared email)})
            email)))))

(defn handle! [system client {:keys [id args attempt] :as job}]
  (try
    (let [prepared-email (prepare-job-email system client job)]
      (deliver! system (cond-> args
                         prepared-email
                         (assoc :prepared-email prepared-email))))
    (feedback/redirect! system (:origin args)
                        (urls/link-policy (get-in args [:mail :policy-id])))
    (drip/complete-job client id)
    (catch Exception e
      (when (= 1 attempt)
        (feedback/failure! system (:origin args)))
      (if (and (= ::delivery-error (:type (ex-data e)))
               (false? (:retry? (ex-data e))))
        (drip/discard-job client id)
        (throw e)))))
