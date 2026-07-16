(ns app.email.lettermint
  "Direct client for Lettermint's Sending API.

  Requests and successful responses are validated at runtime. The client
  supports single sends and batches of up to 500 complete messages."
  (:require
   [app.schemas :as s]
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn-]]
   [jsonista.core :as j]
   [org.httpkit.client :as http-client])
  (:import
   [java.util Base64]))

(def default-base-url
  "The default base URL for Lettermint's Sending API."
  "https://api.lettermint.co/v1")

(def ^:dynamic ^:private *base-url-override*
  nil)

(defn- base64-string? [value]
  (and (string? value)
       (try
         (.decode (Base64/getDecoder) ^String value)
         true
         (catch IllegalArgumentException _exception
           false))))

(defn- recipient-count [{:keys [bcc cc to]}]
  (+ (count to) (count cc) (count bcc)))

(defn- testing-address? [address]
  (str/ends-with? (str/lower-case address)
                  "@testing.lettermint.co"))

(defn- testing-recipients? [{:keys [bcc cc to]}]
  (every? testing-address? (concat to cc bcc)))

(defn- non-blank-string? [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- runtime-credentials-present? [{:keys [demo-mode?
                                             from
                                             project-api-token]}]
  (or demo-mode?
      (and (non-blank-string? from)
           (non-blank-string? project-api-token))))

(def RuntimeConfig
  [:and {:name ::runtime-config}
   [:map
    [:project-api-token {:optional true} :string]
    [:from {:optional true} :string]
    [:route {:optional true} ::s/non-blank-string]
    [:timeout-ms pos-int?]
    [:demo-mode? :boolean]
    [:testing-addresses-only? :boolean]]
   [:fn {:error/message
         "non-demo mode requires a project API token and from address"}
    runtime-credentials-present?]])

(def ClientConfig
  [:map {:name ::client-config}
   [:project-api-token ::s/non-blank-string]
   [:testing-addresses-only? :boolean]
   [:timeout-ms pos-int?]])

(def ^:private RedactedClientConfig
  [:map {:name ::redacted-client-config}
   [:testing-addresses-only? :boolean]
   [:timeout-ms pos-int?]])

(def RequestOptions
  [:map {:name ::request-options}
   [:idempotency-key
    {:optional true}
    [:string {:min 1
              :max 255}]]])

(def Tag
  [:and {:name ::tag}
   [:string {:min 1
             :max 255}]
   [:re #"^[a-zA-Z0-9_-]+(?:\s[a-zA-Z0-9_-]+)*$"]])

(def Settings
  [:map {:name ::settings}
   [:track-clicks {:optional true} :boolean]
   [:track-opens {:optional true} :boolean]])

(def Base64Content
  [:and {:name ::base64-content}
   ::s/non-blank-string
   [:fn {:error/message "should be valid base64"}
    base64-string?]])

(def ContentId
  [:and {:name ::content-id}
   [:string {:max 255}]
   [:re #"^[a-zA-Z0-9._@-]+$"]])

(def Attachment
  [:map {:name ::attachment}
   [:filename ::s/non-blank-string]
   [:content Base64Content]
   [:content-type
    {:optional true}
    [:maybe [:string {:max 255}]]]
   [:content-id
    {:optional true}
    [:maybe ContentId]]])

(def Message
  [:and {:name ::message}
   [:map
    [:route {:optional true} ::s/non-blank-string]
    [:from ::s/non-blank-string]
    [:subject
     [:and
      ::s/non-blank-string
      [:string {:max 998}]]]
    [:tag {:optional true} [:maybe Tag]]
    [:html {:optional true} [:maybe [:string {:min 3}]]]
    [:text {:optional true} [:maybe [:string {:min 3}]]]
    [:to [:vector {:min 1} ::s/email-address]]
    [:cc {:optional true} [:vector ::s/email-address]]
    [:bcc {:optional true} [:vector ::s/email-address]]
    [:reply-to {:optional true} [:vector ::s/email-address]]
    [:headers {:optional true} [:map-of :string :string]]
    [:metadata {:optional true} [:map-of :string :string]]
    [:settings {:optional true} [:maybe Settings]]
    [:attachments {:optional true} [:vector Attachment]]]
   [:fn {:error/message
         "to, cc, and bcc must contain at most 50 recipients combined"}
    #(<= (recipient-count %) 50)]])

(def TestingMessage
  [:and {:name ::testing-message}
   Message
   [:fn {:error/message
         "delivery recipients must use @testing.lettermint.co"}
    testing-recipients?]])

(def Batch
  [:vector {:name ::batch
            :min 1
            :max 500}
   Message])

(def TestingBatch
  [:vector {:name ::testing-batch
            :min 1
            :max 500}
   TestingMessage])

(def MessageStatus
  [:enum {:name ::message-status}
   :pending
   :queued
   :suppressed
   :processed
   :delivered
   :opened
   :clicked
   :soft-bounced
   :hard-bounced
   :spam-complaint
   :failed
   :blocked
   :policy-rejected
   :unsubscribed])

(def SuccessResponse
  [:map {:name ::success-response}
   [:message-id ::s/non-blank-string]
   [:status MessageStatus]])

(def BatchSuccessResponse
  [:vector {:name ::batch-success-response
            :min 1}
   SuccessResponse])

(def ValidationErrors
  [:map-of :keyword [:vector :string]])

(def ValidationResponse
  [:map {:name ::validation-response}
   [:message :string]
   [:errors ValidationErrors]])

(def ErrorResult
  [:map {:name ::error-result}
   [:error [:or
            :int
            [:enum :invalid-success-response
             :transport-error]]]
   [:message ::s/non-blank-string]
   [:retry? :boolean]
   [:status {:optional true} :int]
   [:code {:optional true} :keyword]
   [:details {:optional true} ValidationErrors]])

(def SendSuccess
  [:map {:name ::send-success}
   [:result [:= :email-sent]]
   [:message-id ::s/non-blank-string]
   [:status MessageStatus]])

(def BatchSendSuccess
  [:map {:name ::batch-send-success}
   [:result [:= :emails-sent]]
   [:messages BatchSuccessResponse]])

(def SendResult
  [:or {:name ::send-result}
   SendSuccess
   ErrorResult])

(def BatchSendResult
  [:or {:name ::batch-send-result}
   BatchSendSuccess
   ErrorResult])

(def ^:private RedactedClientConfigGuard
  (s/schema RedactedClientConfig))

(def ^:private RequestOptionsGuard
  (s/schema RequestOptions))

(def ^:private MessageGuard
  (s/schema Message))

(def ^:private BatchGuard
  (s/schema Batch))

(def ^:private SendResultGuard
  (s/schema SendResult))

(def ^:private BatchSendResultGuard
  (s/schema BatchSendResult))

(def ^:private wire-mapper
  (j/object-mapper
   {:encode-key-fn
    (fn [key]
      (if (keyword? key)
        (csk/->snake_case_string key)
        key))
    :decode-key-fn csk/->kebab-case-keyword}))

(def ^:private message-fields
  [:route
   :from
   :subject
   :tag
   :html
   :text
   :to
   :cc
   :bcc
   :reply-to
   :headers
   :metadata
   :settings
   :attachments])

(def ^:private attachment-fields
  [:filename
   :content
   :content-type
   :content-id])

(def ^:private settings-fields
  [:track-opens
   :track-clicks])

(defn- ensure-valid! [description schema value]
  (when-not (s/valid? schema value)
    (s/throw-error (str "Invalid Lettermint " description ".")
                   nil
                   schema
                   value))
  value)

(defn- ensure-valid-config! [config]
  (when-not (s/valid? ClientConfig config)
    (s/throw-error "Invalid Lettermint client configuration."
                   nil
                   ClientConfig
                   (if (map? config)
                     (dissoc config :project-api-token)
                     :redacted-invalid-config)))
  config)

(defn- projected-attachment [attachment]
  (select-keys attachment attachment-fields))

(defn- projected-settings [settings]
  (when settings
    (select-keys settings settings-fields)))

(defn- projected-message [message]
  (cond-> (select-keys message message-fields)
    (contains? message :attachments)
    (update :attachments #(mapv projected-attachment %))

    (contains? message :settings)
    (update :settings projected-settings)))

(defn- normalized-status [status]
  (when (string? status)
    (csk/->kebab-case-keyword status)))

(defn- normalized-success-message [message]
  (when (map? message)
    (update message :status normalized-status)))

(defn- parsed-json [body]
  (j/read-value body wire-mapper))

(defn- parsed-success [batch? body]
  (try
    (let [response (parsed-json body)]
      (if batch?
        (when (vector? response)
          (mapv normalized-success-message response))
        (normalized-success-message response)))
    (catch Throwable _exception
      nil)))

(defn- invalid-success-result []
  {:error :invalid-success-response
   :message "Lettermint returned an invalid success response."
   :retry? true
   :status 202})

(defn- success-result [batch? response]
  (if batch?
    {:messages response
     :result :emails-sent}
    (assoc response :result :email-sent)))

(defn- parsed-error [body]
  (try
    (let [response (parsed-json body)]
      (when (map? response)
        response))
    (catch Throwable _exception
      nil)))

(defn- normalized-error-code [response]
  (let [code (or (:code response)
                 (:error-code response)
                 (when (string? (:error response))
                   (:error response)))]
    (cond
      (keyword? code) code
      (string? code) (csk/->kebab-case-keyword code)
      :else nil)))

(defn- concurrent-idempotency-error?
  [status code message]
  (and (= 409 status)
       (or (= :concurrent-idempotent-requests code)
           (and (string? message)
                (str/includes? (str/lower-case message)
                               "currently being processed")))))

(defn- retryable-status? [status code message]
  (or (concurrent-idempotency-error? status code message)
      (contains? #{408 429} status)
      (and (int? status)
           (<= 500 status 599))))

(defn- provider-error-result [status body]
  (let [response (parsed-error body)
        code (normalized-error-code response)
        message (if (and (string? (:message response))
                         (not (str/blank? (:message response))))
                  (:message response)
                  (str "Lettermint request failed with HTTP "
                       status
                       "."))
        details (:errors response)]
    (cond-> {:error status
             :message message
             :retry? (retryable-status? status code message)}
      code
      (assoc :code code)

      (s/valid? ValidationErrors details)
      (assoc :details details))))

(defn- response-result [batch? expected-message-count response]
  (if (:error response)
    {:error :transport-error
     :message "Lettermint request failed."
     :retry? true}
    (let [{:keys [body status]} response]
      (if (= 202 status)
        (let [success (parsed-success batch? body)
              schema (if batch?
                       BatchSuccessResponse
                       SuccessResponse)]
          (if (and (s/valid? schema success)
                   (or (not batch?)
                       (= expected-message-count
                          (count success))))
            (success-result batch? success)
            (invalid-success-result)))
        (provider-error-result status body)))))

(defn- request-headers [project-api-token request-options]
  (cond-> {"Content-Type" "application/json"
           "x-lettermint-token" project-api-token}
    (:idempotency-key request-options)
    (assoc "Idempotency-Key"
           (:idempotency-key request-options))))

(defn- perform-request!
  [config endpoint payload request-options batch?]
  (try
    (response-result
     batch?
     (if batch?
       (count payload)
       1)
     @(http-client/request
       {:as :text
        :body (j/write-value-as-string payload wire-mapper)
        :headers (request-headers (:project-api-token config)
                                  request-options)
        :method :post
        :timeout (:timeout-ms config)
        :url (str (str/replace (or *base-url-override*
                                   default-base-url)
                               #"/+$"
                               "")
                  endpoint)}))
    (catch Throwable _exception
      {:error :transport-error
       :message "Lettermint request failed."
       :retry? true})))

;; Guardrails includes function arguments in validation-error metadata. Pass
;; the credential through an opaque function instead of the guarded values.
(>defn- guarded-single-request!
        [redacted-config message request-options project-api-token]
        [RedactedClientConfigGuard
         MessageGuard
         RequestOptionsGuard
         fn?
         =>
         SendResultGuard]
        (perform-request!
         (assoc redacted-config
                :project-api-token
                (project-api-token))
         "/send"
         (projected-message message)
         request-options
         false))

(>defn- guarded-batch-request!
        [redacted-config messages request-options project-api-token]
        [RedactedClientConfigGuard
         BatchGuard
         RequestOptionsGuard
         fn?
         =>
         BatchSendResultGuard]
        (perform-request!
         (assoc redacted-config
                :project-api-token
                (project-api-token))
         "/send/batch"
         (mapv projected-message messages)
         request-options
         true))

(defn- send-one! [config message request-options]
  (ensure-valid-config! config)
  (ensure-valid! "message" Message message)
  (when (:testing-addresses-only? config)
    (ensure-valid! "testing-address message"
                   TestingMessage
                   message))
  (ensure-valid! "request options" RequestOptions request-options)
  (guarded-single-request!
   (dissoc config :project-api-token)
   message
   request-options
   #(:project-api-token config)))

(defn- send-many! [config messages request-options]
  (ensure-valid-config! config)
  (ensure-valid! "batch" Batch messages)
  (when (:testing-addresses-only? config)
    (ensure-valid! "testing-address batch"
                   TestingBatch
                   messages))
  (ensure-valid! "request options" RequestOptions request-options)
  (guarded-batch-request!
   (dissoc config :project-api-token)
   messages
   request-options
   #(:project-api-token config)))

(defn send-email!
  "Sends one complete email through Lettermint.

  `message` must satisfy [[Message]]. The function blocks until HTTP Kit
  returns and classifies failures for the application email worker.

  Client configuration:

  | key                        | description
  |----------------------------|------------
  | `:project-api-token`       | Lettermint Project API token
  | `:timeout-ms`              | Positive request timeout in milliseconds
  | `:testing-addresses-only?` | Reject non-testing delivery recipients

  Request options:

  | key                | description
  |--------------------|------------
  | `:idempotency-key` | Optional 1-255 character idempotency key"
  ([config message]
   (send-one! config message {}))
  ([config message request-options]
   (send-one! config message request-options)))

(defn send-emails!
  "Sends one to 500 complete emails through Lettermint's batch endpoint.

  Every item in `messages` must satisfy [[Message]]. The response preserves
  Lettermint's request order.

  Client configuration:

  | key                        | description
  |----------------------------|------------
  | `:project-api-token`       | Lettermint Project API token
  | `:timeout-ms`              | Positive request timeout in milliseconds
  | `:testing-addresses-only?` | Reject non-testing delivery recipients

  Request options:

  | key                | description
  |--------------------|------------
  | `:idempotency-key` | Optional 1-255 character idempotency key"
  ([config messages]
   (send-many! config messages {}))
  ([config messages request-options]
   (send-many! config messages request-options)))
