(ns app.email.email-worker
  (:require
   [app.email.domain :refer [QueuedEmailMessage]]
   [app.email.lettermint :as lettermint]
   [app.email.mailers :as mailers]
   [app.schemas :as s]
   [com.brunobonacci.mulog :as μ]
   [s-exp.drip :as drip])
  (:import
   [java.util Base64]))

(def email-queue-name "start-within-2m")

(defn track-email-error!  [email attempt result throwable]
  (μ/log ::email-error
         :message (or (:message result) (ex-message throwable) "Error occured in email-worker")
         :extra {:email   email
                 :attempt attempt
                 :result  result}
         :ex throwable))

(defn- lettermint-client-config [config]
  (select-keys config
               [:project-api-token
                :testing-addresses-only?
                :timeout-ms]))

(defn- with-runtime-fields [config message]
  (cond-> message
    (some? (:from config))
    (assoc :from (:from config))

    (some? (:route config))
    (assoc :route (:route config))))

(defn- lettermint-message [config freeze-runtime? message]
  (if freeze-runtime?
    message
    (with-runtime-fields config message)))

(defn- freeze-lettermint-envelope [config email]
  (-> email
      (assoc :email/freeze-runtime? true)
      (update :email/messages
              #(mapv (partial with-runtime-fields config) %))))

(defn- ensure-valid-lettermint-request! [client-config messages]
  (when-not (s/valid? lettermint/ClientConfig client-config)
    (s/throw-error "Invalid Lettermint client configuration."
                   nil
                   lettermint/ClientConfig
                   (dissoc client-config :project-api-token)))
  (when-not (s/valid? lettermint/Batch messages)
    (s/throw-error "Invalid Lettermint messages."
                   nil
                   lettermint/Batch
                   messages))
  (when (and (:testing-addresses-only? client-config)
             (not (s/valid? lettermint/TestingBatch messages)))
    (s/throw-error "Invalid Lettermint testing-address messages."
                   nil
                   lettermint/TestingBatch
                   messages)))

(defn- dry-run-summary [message]
  {:batch?        (:email/batch? message)
   :email-id      (:email/email-id message)
   :message-count (count (:email/messages message))
   :recipients    (mapv :to (:email/messages message))})

(defn lettermint-handler [{:keys [lettermint]} message]
  (if (:demo-mode? lettermint)
    (do
      (tap> {:lettermint/dry-run (dry-run-summary message)})
      {:mode   :demo-mode
       :result :email-sent})
    (let [client-config   (lettermint-client-config lettermint)
          messages        (mapv #(lettermint-message
                                  lettermint
                                  (:email/freeze-runtime? message)
                                  %)
                                (:email/messages message))
          request-options {:idempotency-key
                           (str (:email/email-id message))}]
      (ensure-valid-lettermint-request! client-config messages)
      (if (:email/batch? message)
        (lettermint/send-emails! client-config
                                 messages
                                 request-options)
        (lettermint/send-email! client-config
                                (first messages)
                                request-options)))))

(defn- encode-attachment [{:keys [content] :as attachment}]
  (assoc attachment
         :content
         (.encodeToString (Base64/getEncoder)
                          (if (bytes? content)
                            content
                            (byte-array content)))))

(defn- legacy-envelope->lettermint [config message]
  (assert (not (:email/batch? message)))
  (let [attachments (:email/attachments message)]
    (freeze-lettermint-envelope
     config
     {:email/sender   :lettermint
      :email/email-id (:email/email-id message)
      :email/batch?   false
      :email/messages
      [(cond-> {:to      (:email/tos message)
                :subject (:email/subject message)
                :html    (:email/body-html message)
                :text    (:email/body-plain message)}
         (seq attachments)
         (assoc :attachments
                (mapv encode-attachment attachments)))]})))

(defn- legacy-envelope-handler [sys message]
  (lettermint-handler
   sys
   (legacy-envelope->lettermint (:lettermint sys) message)))

(defn- freeze-provider-envelope [{:keys [lettermint]} message]
  (if (:email/freeze-runtime? message)
    message
    (case (:email/sender message)
      :lettermint (freeze-lettermint-envelope lettermint message)
      :band-smtp (legacy-envelope->lettermint lettermint message))))

(defn handler
  [sys message attempt]
  (tap> {:email-worker/received message :email-worker/attempt attempt})
  (try
    (if (s/valid? QueuedEmailMessage message)
      (let [sender (case (:email/sender message)
                     :lettermint lettermint-handler
                     :band-smtp legacy-envelope-handler)
            result (sender sys message)]
        (tap> {:email-send result})
        (if (:error result)
          (do
            (track-email-error! message attempt result nil)
            (if (:retry? result)
              {:status :retry :backoff-ms 5000}
              {:status :error}))
          {:status :success}))
      (do
        (track-email-error! message attempt (s/explain-human QueuedEmailMessage message) nil)
        {:status :error}))
    (catch Exception e
      (tap> e)
      (track-email-error! message attempt nil e)
      {:status :error})))

(defn- map-attachment-content [f email]
  ;; Nested byte arrays are not EDN. Only attachment bytes need conversion.
  (cond-> email
    (seq (:email/attachments email))
    (update :email/attachments #(mapv (fn [attachment] (update attachment :content f)) %))))

(defn- prepare-job-email
  [sys client {:keys [id args metadata]}]
  (let [stored  (or (:email/prepared metadata)
                    (:prepared-email args))
        message (if stored
                  (map-attachment-content byte-array stored)
                  (mailers/prepare! sys args))]
    (if (= ::mailers/skip message)
      message
      (let [prepared (freeze-provider-envelope sys message)]
        (when (not= prepared (:email/prepared metadata))
          (drip/update-job client id
                           {:metadata (assoc metadata
                                             :email/prepared
                                             prepared)}))
        prepared))))

(defn job-handler
  [sys client {:keys [id attempt] :as job}]
  (let [prepared (try
                   (let [message (prepare-job-email sys client job)]
                     (if (= ::mailers/skip message) {:status :skipped} {:message message}))
                   (catch Exception e
                     (let [permanent? (:email/permanent? (ex-data e))]
                       (μ/log ::email-preparation-failed
                              :job-id id :attempt attempt
                              :permanent? (boolean permanent?)
                              :reason (or (:email/reason (ex-data e)) :preparation-failed))
                       (if permanent?
                         {:status :error}
                         (throw (ex-info "Retryable email preparation failure" {:job-id id}))))))
        result   (if (:status prepared)
                   prepared
                   (handler sys (:message prepared) attempt))]
    (case (:status result)
      :success (drip/complete-job client id)
      :skipped (drip/complete-job client id)
      :error (drip/discard-job client id)
      :retry (throw (ex-info "Retryable email delivery failure" {:job-id id})))))

(defn queue-mail! [{:keys [client]} email]
  (when-not (s/valid? QueuedEmailMessage email)
    (s/throw-error "Invalid queued email message."
                   nil
                   QueuedEmailMessage
                   email))
  (drip/insert-job client "send-email"
                   {:prepared-email (map-attachment-content vec email)}
                   :queue email-queue-name
                   :max-attempts 25))

(defn queue-mailer!
  "Queues a named mailer from a successful transaction report without rendering.

  `sys` supplies `:job-queue`, `:datomic-conn`, and `:current-locale`.
  `arguments` must satisfy the registered mailer's EDN contract."
  [{:keys [job-queue datomic-conn current-locale]} tx-result mailer arguments]
  (let [invocation {:version   2
                    :mailer    mailer
                    :arguments arguments
                    :source-t  (mailers/source-t datomic-conn tx-result)
                    :email-id  (random-uuid)
                    :locale    (or current-locale :en)}]
    (mailers/validate! invocation)
    (drip/insert-job (:client job-queue) "send-email" invocation
                     :queue email-queue-name
                     :max-attempts 25)))
