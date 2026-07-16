(ns app.email.lettermint-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as j]
   [org.httpkit.server :as http-server]))

(def test-token "lm_project_test")

(def minimal-message
  {:from "Probematic <sender@example.com>"
   :subject "Hello"
   :text "Hello from Probematic."
   :to ["member@example.com"]})

(def maximal-message
  {:attachments
   [{:content "UERG"
     :content-type "application/pdf"
     :filename "guide.pdf"}
    {:content "UE5H"
     :content-id "logo-image"
     :content-type "image/png"
     :filename "logo.png"}]
   :bcc ["blind@example.com"]
   :cc ["copy@example.com"]
   :from "Probematic <sender@example.com>"
   :headers {"X-Campaign-ID" "campaign-1"
             "X-Custom-Header" "custom-value"}
   :html "<p>Hello <img src=\"cid:logo-image\"></p>"
   :metadata {"campaign_id" "welcome-2026"
              "user_id" "member-1"}
   :reply-to ["reply@example.com"]
   :route "transactional"
   :settings {:track-clicks true
              :track-opens false}
   :subject "Welcome"
   :tag "welcome-email"
   :text "Hello from Probematic."
   :to ["member@example.com"]})

(def maximal-wire-message
  {"attachments"
   [{"content" "UERG"
     "content_type" "application/pdf"
     "filename" "guide.pdf"}
    {"content" "UE5H"
     "content_id" "logo-image"
     "content_type" "image/png"
     "filename" "logo.png"}]
   "bcc" ["blind@example.com"]
   "cc" ["copy@example.com"]
   "from" "Probematic <sender@example.com>"
   "headers" {"X-Campaign-ID" "campaign-1"
              "X-Custom-Header" "custom-value"}
   "html" "<p>Hello <img src=\"cid:logo-image\"></p>"
   "metadata" {"campaign_id" "welcome-2026"
               "user_id" "member-1"}
   "reply_to" ["reply@example.com"]
   "route" "transactional"
   "settings" {"track_clicks" true
               "track_opens" false}
   "subject" "Welcome"
   "tag" "welcome-email"
   "text" "Hello from Probematic."
   "to" ["member@example.com"]})

(defn- public-fn [symbol]
  (try
    (some-> (requiring-resolve symbol) deref)
    (catch java.io.FileNotFoundException _exception
      nil)))

(defn- request-summary
  [{:keys [body headers request-method uri]}]
  {:body (j/read-value (slurp body))
   :headers (select-keys headers
                         ["content-type"
                          "idempotency-key"
                          "x-lettermint-token"])
   :request-method request-method
   :uri uri})

(defn- json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (j/write-value-as-string body)})

(defn- raw-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body body})

(defn- with-base-url [base-url f]
  (with-bindings {(requiring-resolve
                   'app.email.lettermint/*base-url-override*)
                  base-url}
    (f)))

(defn- with-http-server [response f]
  (let [requests (atom [])
        server (http-server/run-server
                (fn [request]
                  (let [request (request-summary request)]
                    (swap! requests conj request)
                    (if (fn? response)
                      (response request)
                      response)))
                {:ip "127.0.0.1"
                 :port 0})]
    (try
      (let [base-url (str "http://127.0.0.1:"
                          (:local-port (meta server))
                          "/v1")]
        (with-base-url base-url
          #(f {:requests requests})))
      (finally
        (server)))))

(defn- client-config
  ([]
   (client-config {}))
  ([overrides]
   (merge {:project-api-token test-token
           :testing-addresses-only? false
           :timeout-ms 2000}
          overrides)))

(defn- exception-info? [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo _exception
      true)))

(deftest public-sending-api-exists
  (is (= {:send-email! true
          :send-emails! true}
         {:send-email! (fn? (public-fn 'app.email.lettermint/send-email!))
          :send-emails! (fn? (public-fn
                              'app.email.lettermint/send-emails!))})))

(deftest send-email-serializes-every-documented-field
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (with-http-server
      (json-response 202
                     {"message_id" "message-1"
                      "status" "queued"})
      (fn [{:keys [requests]}]
        (let [result (send-email!
                      (client-config)
                      maximal-message
                      {:idempotency-key "email-1"})]
          (is (= {:request
                  {:body maximal-wire-message
                   :headers
                   {"content-type" "application/json"
                    "idempotency-key" "email-1"
                    "x-lettermint-token" test-token}
                   :request-method :post
                   :uri "/v1/send"}
                  :result {:message-id "message-1"
                           :result :email-sent
                           :status :queued}}
                 {:request (first @requests)
                  :result result})))))))

(deftest send-email-supports-nullable-fields-and-drops-unknown-fields
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (with-http-server
      (json-response 202
                     {"message_id" "message-nullable"
                      "status" "soft_bounced"})
      (fn [{:keys [requests]}]
        (let [message (assoc minimal-message
                             :attachments
                             [{:content "UERG"
                               :content-id nil
                               :content-type nil
                               :filename "guide.pdf"}]
                             :html nil
                             :internal/secret "must-not-leak"
                             :settings nil
                             :tag nil
                             :text nil)
              result (send-email! (client-config) message)]
          (is (= {:request
                  {:body
                   {"attachments"
                    [{"content" "UERG"
                      "content_id" nil
                      "content_type" nil
                      "filename" "guide.pdf"}]
                    "from" "Probematic <sender@example.com>"
                    "html" nil
                    "settings" nil
                    "subject" "Hello"
                    "tag" nil
                    "text" nil
                    "to" ["member@example.com"]}
                   :headers
                   {"content-type" "application/json"
                    "x-lettermint-token" test-token}
                   :request-method :post
                   :uri "/v1/send"}
                  :result {:message-id "message-nullable"
                           :result :email-sent
                           :status :soft-bounced}}
                 {:request (first @requests)
                  :result result})))))))

(deftest send-emails-serializes-every-field-for-batch-items
  (when-let [send-emails! (public-fn 'app.email.lettermint/send-emails!)]
    (let [nullable-message (assoc minimal-message
                                  :headers {"X-Batch" "second"}
                                  :html nil
                                  :metadata {"batch_id" "batch-1"}
                                  :settings nil
                                  :tag nil
                                  :text nil)]
      (with-http-server
        (json-response 202
                       [{"message_id" "batch-1"
                         "status" "queued"}
                        {"message_id" "batch-2"
                         "status" "delivered"}])
        (fn [{:keys [requests]}]
          (let [result (send-emails!
                        (client-config)
                        [maximal-message nullable-message]
                        {:idempotency-key "batch-email-1"})]
            (is (= {:request
                    {:body
                     [maximal-wire-message
                      {"from" "Probematic <sender@example.com>"
                       "headers" {"X-Batch" "second"}
                       "html" nil
                       "metadata" {"batch_id" "batch-1"}
                       "settings" nil
                       "subject" "Hello"
                       "tag" nil
                       "text" nil
                       "to" ["member@example.com"]}]
                     :headers
                     {"content-type" "application/json"
                      "idempotency-key" "batch-email-1"
                      "x-lettermint-token" test-token}
                     :request-method :post
                     :uri "/v1/send/batch"}
                    :result
                    {:messages [{:message-id "batch-1"
                                 :status :queued}
                                {:message-id "batch-2"
                                 :status :delivered}]
                     :result :emails-sent}}
                   {:request (first @requests)
                    :result result}))))))))

(deftest send-emails-preserves-all-documented-statuses-in-order
  (when-let [send-emails! (public-fn 'app.email.lettermint/send-emails!)]
    (let [wire-statuses ["pending"
                         "queued"
                         "suppressed"
                         "processed"
                         "delivered"
                         "opened"
                         "clicked"
                         "soft_bounced"
                         "hard_bounced"
                         "spam_complaint"
                         "failed"
                         "blocked"
                         "policy_rejected"
                         "unsubscribed"]
          statuses [:pending
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
                    :unsubscribed]
          response (mapv (fn [index status]
                           {"message_id" (str "message-" index)
                            "status" status})
                         (range)
                         wire-statuses)
          expected (mapv (fn [index status]
                           {:message-id (str "message-" index)
                            :status status})
                         (range)
                         statuses)]
      (with-http-server
        (json-response 202 response)
        (fn [_]
          (is (= {:messages expected
                  :result :emails-sent}
                 (send-emails! (client-config)
                               (vec (repeat (count statuses)
                                            minimal-message))))))))))

(deftest send-email-validates-every-message-boundary-before-network-io
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (let [attachment {:content "UERG"
                      :content-type "application/pdf"
                      :filename "guide.pdf"}
          invalid-messages
          [["blank sender" (assoc minimal-message :from "")]
           ["empty recipients" (assoc minimal-message :to [])]
           ["invalid to address" (assoc minimal-message
                                        :to ["not-an-address"])]
           ["invalid cc address" (assoc minimal-message
                                        :cc ["not-an-address"])]
           ["invalid bcc address" (assoc minimal-message
                                         :bcc ["not-an-address"])]
           ["invalid reply-to address" (assoc minimal-message
                                              :reply-to
                                              ["not-an-address"])]
           ["more than fifty delivery recipients"
            (assoc minimal-message
                   :cc (vec (repeat 25 "copy@example.com"))
                   :bcc (vec (repeat 25 "blind@example.com")))]
           ["blank route" (assoc minimal-message :route "")]
           ["blank subject" (assoc minimal-message :subject "")]
           ["subject longer than 998 characters"
            (assoc minimal-message :subject (apply str (repeat 999 "s")))]
           ["invalid tag characters" (assoc minimal-message
                                            :tag "invalid/tag")]
           ["tag longer than 255 characters"
            (assoc minimal-message :tag (apply str (repeat 256 "t")))]
           ["HTML shorter than three characters"
            (assoc minimal-message :html "hi")]
           ["text shorter than three characters"
            (assoc minimal-message :text "hi")]
           ["non-string header value"
            (assoc minimal-message :headers {"X-Count" 1})]
           ["non-string metadata key"
            (assoc minimal-message :metadata {:member "member-1"})]
           ["non-boolean tracking setting"
            (assoc minimal-message :settings {:track-opens "yes"})]
           ["blank attachment filename"
            (assoc minimal-message :attachments
                   [(assoc attachment :filename "")])]
           ["invalid base64 attachment content"
            (assoc minimal-message :attachments
                   [(assoc attachment :content "not base64!")])]
           ["attachment content type longer than 255 characters"
            (assoc minimal-message :attachments
                   [(assoc attachment
                           :content-type (apply str (repeat 256 "t")))])]
           ["invalid attachment content ID"
            (assoc minimal-message :attachments
                   [(assoc attachment :content-id "invalid/content-id")])]
           ["attachment content ID longer than 255 characters"
            (assoc minimal-message :attachments
                   [(assoc attachment
                           :content-id (apply str (repeat 256 "a")))])]]]
      (with-http-server
        (json-response 202
                       {"message_id" "must-not-send"
                        "status" "queued"})
        (fn [{:keys [requests]}]
          (doseq [[description message] invalid-messages]
            (testing description
              (is (true? (exception-info?
                          #(send-email! (client-config)
                                        message))))))
          (is (empty? @requests)
              "Invalid messages must not reach the HTTP server"))))))

(deftest send-email-validates-client-and-idempotency-options-before-network-io
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (with-http-server
      (json-response 202
                     {"message_id" "must-not-send"
                      "status" "queued"})
      (fn [{:keys [requests]}]
        (let [invalid-configs
              [["blank project token"
                (client-config {:project-api-token ""})]
               ["zero timeout"
                (client-config {:timeout-ms 0})]
               ["non-boolean testing-address flag"
                (client-config
                 {:testing-addresses-only? :yes})]]
              invalid-options
              [["empty idempotency key" {:idempotency-key ""}]
               ["idempotency key longer than 255 characters"
                {:idempotency-key (apply str (repeat 256 "i"))}]]]
          (doseq [[description config] invalid-configs]
            (testing description
              (is (true? (exception-info?
                          #(send-email! config minimal-message))))))
          (doseq [[description options] invalid-options]
            (testing description
              (is (true? (exception-info?
                          #(send-email! (client-config)
                                        minimal-message
                                        options))))))
          (is (empty? @requests)
              "Invalid configuration and options must not reach the server"))))))

(deftest invalid-input-errors-do-not-expose-the-project-token
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (let [send-emails! (public-fn 'app.email.lettermint/send-emails!)
          token "project-token-that-must-stay-secret"
          config (client-config {:project-api-token token})
          invalid-message (assoc minimal-message :from "")
          calls [#(send-email! (assoc config :timeout-ms 0)
                               minimal-message)
                 #(send-email! config invalid-message)
                 #(send-email! config
                               minimal-message
                               {:idempotency-key ""})
                 #(send-emails! config [invalid-message])
                 #(send-emails! config [])]
          exceptions
          (mapv (fn [call]
                  (try
                    (call)
                    nil
                    (catch Throwable error
                      error)))
                calls)
          printed-errors
          (mapv (fn [error]
                  (binding [*print-meta* true]
                    (str (ex-message error)
                         " "
                         (pr-str (ex-data error)))))
                exceptions)]
      (is (every? some? exceptions))
      (is (not-any? #(str/includes? % token)
                    printed-errors)))))

(deftest send-email-allows-documented-maximum-boundaries
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (let [subject (apply str (repeat 998 "s"))
          tag (apply str (repeat 255 "t"))
          content-type (apply str (repeat 255 "c"))
          content-id (apply str (repeat 255 "a"))
          idempotency-key (apply str (repeat 255 "i"))
          message (assoc minimal-message
                         :attachments
                         [{:content "UERG"
                           :content-id content-id
                           :content-type content-type
                           :filename "guide.pdf"}]
                         :cc (vec (repeat 49 "copy@example.com"))
                         :subject subject
                         :tag tag)]
      (with-http-server
        (json-response 202
                       {"message_id" "maximums"
                        "status" "queued"})
        (fn [{:keys [requests]}]
          (let [result (send-email! (client-config)
                                    message
                                    {:idempotency-key idempotency-key})
                request (first @requests)]
            (is (= {:content-id-length 255
                    :content-type-length 255
                    :idempotency-key-length 255
                    :recipient-count 50
                    :request-count 1
                    :result {:message-id "maximums"
                             :result :email-sent
                             :status :queued}
                    :subject-length 998
                    :tag-length 255}
                   {:content-id-length
                    (count (get-in request
                                   [:body "attachments" 0 "content_id"]))
                    :content-type-length
                    (count (get-in request
                                   [:body "attachments" 0 "content_type"]))
                    :idempotency-key-length
                    (count (get-in request
                                   [:headers "idempotency-key"]))
                    :recipient-count
                    (+ (count (get-in request [:body "to"]))
                       (count (get-in request [:body "cc"])))
                    :request-count (count @requests)
                    :result result
                    :subject-length (count (get-in request
                                                   [:body "subject"]))
                    :tag-length (count (get-in request [:body "tag"]))}))))))))

(deftest send-emails-enforces-the-500-message-boundary
  (when-let [send-emails! (public-fn 'app.email.lettermint/send-emails!)]
    (with-http-server
      (fn [request]
        (json-response
         202
         (mapv (fn [index]
                 {"message_id" (str "batch-" index)
                  "status" "queued"})
               (range (count (:body request))))))
      (fn [{:keys [requests]}]
        (let [config (client-config)
              messages (vec (repeat 500 minimal-message))
              expected-messages
              (mapv (fn [index]
                      {:message-id (str "batch-" index)
                       :status :queued})
                    (range 500))
              result (send-emails! config messages)]
          (is (= {:request-count 1
                  :request-message-count 500
                  :result {:messages expected-messages
                           :result :emails-sent}}
                 {:request-count (count @requests)
                  :request-message-count (count (:body (first @requests)))
                  :result result}))
          (is (true? (exception-info? #(send-emails!
                                        config
                                        (conj messages
                                              minimal-message)))))
          (is (true? (exception-info? #(send-emails! config []))))
          (is (= 1 (count @requests))
              "Invalid batch sizes must not reach the HTTP server"))))))

(deftest testing-address-mode-blocks-real-delivery-recipients
  (let [send-email! (public-fn 'app.email.lettermint/send-email!)
        send-emails! (public-fn 'app.email.lettermint/send-emails!)]
    (when (and send-email! send-emails!)
      (with-http-server
        (json-response 202
                       {"message_id" "testing-address"
                        "status" "queued"})
        (fn [{:keys [requests]}]
          (let [config (client-config
                        {:testing-addresses-only? true})
                single-blocked?
                (exception-info? #(send-email! config minimal-message))
                batch-blocked?
                (exception-info?
                 #(send-emails!
                   config
                   [(assoc minimal-message
                           :cc ["copy@example.com"]
                           :to ["ok@testing.lettermint.co"])]))
                result (send-email!
                        config
                        (assoc minimal-message
                               :to ["ok@testing.lettermint.co"]))]
            (is (= {:accepted-recipient
                    ["ok@testing.lettermint.co"]
                    :batch-blocked? true
                    :request-count 1
                    :result {:message-id "testing-address"
                             :result :email-sent
                             :status :queued}
                    :single-blocked? true}
                   {:accepted-recipient
                    (get-in (first @requests) [:body "to"])
                    :batch-blocked? batch-blocked?
                    :request-count (count @requests)
                    :result result
                    :single-blocked? single-blocked?}))))))))

(deftest send-email-classifies-provider-errors
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (let [cases
          [["validation errors are permanent"
            422
            {"errors" {"to" ["The to field is required."]}
             "message" "The given data was invalid."}
            {:details {:to ["The to field is required."]}
             :error 422
             :message "The given data was invalid."
             :retry? false}]
           ["concurrent idempotency conflicts are retryable"
            409
            {"code" "concurrent_idempotent_requests"
             "message" "Another request is currently being processed."}
            {:code :concurrent-idempotent-requests
             :error 409
             :message "Another request is currently being processed."
             :retry? true}]
           ["payload idempotency conflicts are permanent"
            409
            {"code" "invalid_idempotent_request"
             "message" "The key was used with a different payload."}
            {:code :invalid-idempotent-request
             :error 409
             :message "The key was used with a different payload."
             :retry? false}]
           ["request timeouts are retryable"
            408
            {"message" "Request timed out."}
            {:error 408
             :message "Request timed out."
             :retry? true}]
           ["rate limits are retryable"
            429
            {"message" "Too many requests."}
            {:error 429
             :message "Too many requests."
             :retry? true}]
           ["server failures are retryable"
            500
            {"message" "Server error."}
            {:error 500
             :message "Server error."
             :retry? true}]
           ["other client failures are permanent"
            403
            {"message" "Forbidden."}
            {:error 403
             :message "Forbidden."
             :retry? false}]]]
      (doseq [[description status body expected] cases]
        (testing description
          (with-http-server
            (json-response status body)
            (fn [_]
              (is (= expected
                     (send-email! (client-config)
                                  minimal-message))))))))))

(deftest malformed-success-responses-are-retryable
  (let [send-email! (public-fn 'app.email.lettermint/send-email!)
        send-emails! (public-fn 'app.email.lettermint/send-emails!)]
    (when (and send-email! send-emails!)
      (let [expected {:error :invalid-success-response
                      :message "Lettermint returned an invalid success response."
                      :retry? true
                      :status 202}
            cases
            [["invalid JSON"
              send-email!
              (raw-response 202 "not-json")
              minimal-message]
             ["missing single-send status"
              send-email!
              (json-response 202 {"message_id" "message-1"})
              minimal-message]
             ["unknown status"
              send-email!
              (json-response 202
                             {"message_id" "message-1"
                              "status" "unknown"})
              minimal-message]
             ["non-array batch response"
              send-emails!
              (json-response 202
                             {"message_id" "message-1"
                              "status" "queued"})
              [minimal-message]]
             ["batch response count does not match the request"
              send-emails!
              (json-response 202
                             [{"message_id" "message-1"
                               "status" "queued"}])
              [minimal-message minimal-message]]]]
        (doseq [[description send! response payload] cases]
          (testing description
            (with-http-server
              response
              (fn [_]
                (is (= expected
                       (send! (client-config)
                              payload)))))))))))

(deftest transport-errors-are-retryable-and-sanitized
  (when-let [send-email! (public-fn 'app.email.lettermint/send-email!)]
    (with-base-url "http://127.0.0.1:1/v1"
      (fn []
        (let [result (send-email!
                      (client-config {:timeout-ms 100})
                      minimal-message)]
          (is (= {:error :transport-error
                  :message "Lettermint request failed."
                  :retry? true}
                 result)))))))
