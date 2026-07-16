(ns app.email.email-worker
  (:require
   [app.config :as config]
   [app.email.domain :refer [QueuedEmailMessage]]
   [app.email.lettermint :as lettermint]
   [app.schemas :as s]
   [com.brunobonacci.mulog :as μ]
   [taoensso.carmine :as car]
   [taoensso.carmine.message-queue :as car-mq]
   [tarayo.core :as tarayo]))

(def email-queue-name "email-send-queue")

(defn track-email-error!  [email attempt result throwable]
  (μ/log ::email-error
         :message (or (:message result) (ex-message throwable) "Error occured in email-worker")
         :extra {:email email
                 :attempt attempt
                 :result result}
         :ex throwable))

(defn- lettermint-client-config [config]
  (select-keys config
               [:project-api-token
                :testing-addresses-only?
                :timeout-ms]))

(defn- lettermint-message [config message]
  (cond-> (assoc message :from (:from config))
    (some? (:route config))
    (assoc :route (:route config))))

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
  {:batch? (:email/batch? message)
   :email-id (:email/email-id message)
   :message-count (count (:email/messages message))
   :recipients (mapv :to (:email/messages message))})

(defn lettermint-handler [{:keys [lettermint]} message]
  (if (:demo-mode? lettermint)
    (do
      (tap> {:lettermint/dry-run (dry-run-summary message)})
      {:mode :demo-mode
       :result :email-sent})
    (let [client-config (lettermint-client-config lettermint)
          messages (mapv #(lettermint-message lettermint %)
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

(defn format-attachments [attachments]
  (map (fn [{:keys [content content-type filename]}]
         {:content      content
          :content-type content-type
          :filename     filename})
       attachments))

(defn band-smtp-handler [sys message]
  (assert (not (:email/batch? message)))
  (let [{:keys [from dev-mode-override-recipient] :as smtp} (config/band-smtp (:env sys))]
    (assert smtp)
    (assert from)
    (tarayo/send! (tarayo/connect smtp)
                  {:from   from
                   :to (or dev-mode-override-recipient nil) ;; (or dev-mode-override-recipient (first (:email/tos message)))
                   :subject (:email/subject message)
                   :body (into [] (concat [{:content-type "text/html" :content (:email/body-html message)}
                                           {:content-type "text/plain" :content (:email/body-plain message)}]
                                          (format-attachments (:email/attachments message))))})))

(defn handler
  "Has strict return contract with carmine. See http://ptaoussanis.github.io/carmine/taoensso.carmine.message-queue.html#var-worker"
  [sys message attempt]
  (tap> {:email-worker/received message :email-worker/attempt attempt})
  (try
    (if (s/valid? QueuedEmailMessage message)
      (let [sender (case (:email/sender message)
                     :lettermint lettermint-handler
                     :band-smtp band-smtp-handler)
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
    (catch Throwable e
      (tap> e)
      (track-email-error! message attempt nil e)
      {:status :error})))

(defn start! [{:keys [redis] :as sys}]
  (μ/log ::email-worker-starting)
  (car-mq/worker redis email-queue-name
                 {:handler (fn [{:keys [message attempt]}] (handler sys message attempt))
                  :eoq-backoff-ms 50
                  :throttle-ms 50}))
(defn stop! [worker]
  (car-mq/stop worker))

(defn queue-mail! [redis-opts email]
  (when-not (s/valid? QueuedEmailMessage email)
    (s/throw-error "Invalid queued email message."
                   nil
                   QueuedEmailMessage
                   email))
  (car/wcar redis-opts
            (car-mq/enqueue email-queue-name email)))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require '[hiccup2.core :refer [html]])

    (def recipient1 "foo@example.com")
    (def recipient2 "foo+test@example.com") ;; rcf
    (def redis-opts (-> state/system :app.ig/redis))) ;; rcf

  (queue-mail! redis-opts
               {:email/batch? true
                :email/email-id (random-uuid)
                :email/messages
                [{:to [recipient1]
                  :subject "Hello from Probematic"
                  :text "Hello world."
                  :html (str
                         (html [:div
                                [:h1 "Hello world!"]
                                [:p "Things and stuff!"]]))}
                 {:to [recipient2]
                  :subject "Hello from Probematic"
                  :text "Hello world."
                  :html (str
                         (html [:div
                                [:h1 "Hello world!"]
                                [:p "Things and stuff!"]]))}]
                :email/sender :lettermint})

  ;;
  )
