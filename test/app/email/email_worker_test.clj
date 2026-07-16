(ns app.email.email-worker-test
  (:require
   [app.email.email-worker :as worker]
   [app.ig]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [org.httpkit.server :as http-server]))

(def test-token "lettermint-worker-test-token")

(def email-id
  #uuid "01982185-a5f1-7634-b3eb-438f17d0662a")

(def single-queued-email
  {:email/batch? false
   :email/created-at #inst "2026-07-16T10:00:00.000-00:00"
   :email/email-id email-id
   :email/messages [{:html "<p>Hello Ada.</p>"
                     :subject "Hello Ada"
                     :text "Hello Ada."
                     :to ["ada@example.test"]}]
   :email/sender :lettermint})

(def batch-queued-email
  {:email/batch? true
   :email/created-at #inst "2026-07-16T10:00:00.000-00:00"
   :email/email-id email-id
   :email/messages [{:html "<p>Hello Ada.</p>"
                     :subject "Hello"
                     :text "Hello Ada."
                     :to ["ada@example.test"]}
                    {:html "<p>Hello Grace.</p>"
                     :subject "Hello"
                     :text "Hello Grace."
                     :to ["grace@example.test"]}]
   :email/sender :lettermint})

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

(defn- worker-system
  ([]
   (worker-system {}))
  ([overrides]
   {:lettermint
    (merge {:demo-mode? false
            :from "Probematic <sender@example.test>"
            :project-api-token test-token
            :route "transactional"
            :testing-addresses-only? false
            :timeout-ms 2000}
           overrides)}))

(defn- init-lettermint [config]
  (ig/init-key :app.ig/lettermint
               {:env {:lettermint config}}))

(deftest lettermint-component-validates-runtime-configuration-at-startup
  (let [valid-config (:lettermint (worker-system))
        demo-config (-> valid-config
                        (assoc :demo-mode? true)
                        (dissoc :from :project-api-token))
        invalid-configs [(dissoc valid-config :project-api-token)
                         (assoc valid-config :from "")
                         (assoc valid-config :route "")
                         (assoc valid-config :timeout-ms 0)
                         (assoc valid-config
                                :testing-addresses-only?
                                nil)
                         (assoc valid-config :demo-mode? "false")]
        safely-rejected?
        (fn [config]
          (let [exception (try
                            (init-lettermint config)
                            nil
                            (catch Throwable error
                              error))
                printed-data (binding [*print-meta* true]
                               (pr-str (ex-data exception)))]
            (and (some? exception)
                 (not (str/includes? (or (ex-message exception) "")
                                     test-token))
                 (not (str/includes? printed-data test-token)))))]
    (is (= {:demo-mode demo-config
            :invalid-configs-safely-rejected? true
            :valid valid-config}
           {:demo-mode (init-lettermint demo-config)
            :invalid-configs-safely-rejected?
            (every? safely-rejected? invalid-configs)
            :valid (init-lettermint valid-config)}))))

(deftest worker-sends-a-single-message-with-runtime-fields
  (with-http-server
    (json-response 202
                   {"message_id" "single-message-id"
                    "status" "queued"})
    (fn [{:keys [requests]}]
      (let [result (worker/handler
                    (worker-system)
                    single-queued-email
                    1)]
        (is (= {:request
                {:body
                 {"from" "Probematic <sender@example.test>"
                  "html" "<p>Hello Ada.</p>"
                  "route" "transactional"
                  "subject" "Hello Ada"
                  "text" "Hello Ada."
                  "to" ["ada@example.test"]}
                 :headers
                 {"content-type" "application/json"
                  "idempotency-key" (str email-id)
                  "x-lettermint-token" test-token}
                 :request-method :post
                 :uri "/v1/send"}
                :worker-result {:status :success}}
               {:request (first @requests)
                :worker-result result}))))))

(deftest worker-sends-a-complete-message-batch
  (with-http-server
    (json-response 202
                   [{"message_id" "batch-message-1"
                     "status" "queued"}
                    {"message_id" "batch-message-2"
                     "status" "queued"}])
    (fn [{:keys [requests]}]
      (let [result (worker/handler
                    (worker-system {:route nil})
                    batch-queued-email
                    1)]
        (is (= {:body
                [{"from" "Probematic <sender@example.test>"
                  "html" "<p>Hello Ada.</p>"
                  "subject" "Hello"
                  "text" "Hello Ada."
                  "to" ["ada@example.test"]}
                 {"from" "Probematic <sender@example.test>"
                  "html" "<p>Hello Grace.</p>"
                  "subject" "Hello"
                  "text" "Hello Grace."
                  "to" ["grace@example.test"]}]
                :result {:status :success}
                :uri "/v1/send/batch"}
               {:body (get-in @requests [0 :body])
                :result result
                :uri (get-in @requests [0 :uri])}))))))

(deftest worker-preserves-retry-and-permanent-error-decisions
  (testing "a retryable provider error"
    (with-http-server
      (json-response 500 {"message" "Temporary provider failure."})
      (fn [_]
        (is (= {:backoff-ms 5000
                :status :retry}
               (worker/handler (worker-system)
                               single-queued-email
                               2))))))

  (testing "a permanent provider validation error"
    (with-http-server
      (json-response 422
                     {"errors" {"to" ["The to field is invalid."]}
                      "message" "The given data was invalid."})
      (fn [{:keys [requests]}]
        (let [result (worker/handler (worker-system)
                                     single-queued-email
                                     1)]
          (is (= {:request-count 1
                  :result {:status :error}}
                 {:request-count (count @requests)
                  :result result})))))))

(deftest worker-rejects-malformed-queue-data-before-network-io
  (with-http-server
    (json-response 202
                   {"message_id" "must-not-be-used"
                    "status" "queued"})
    (fn [{:keys [requests]}]
      (is (= {:requests []
              :result {:status :error}}
             {:requests @requests
              :result (worker/handler
                       (worker-system)
                       (dissoc single-queued-email :email/messages)
                       1)})))))

(deftest invalid-runtime-fields-do-not-expose-the-project-token
  (with-http-server
    (json-response 202
                   {"message_id" "must-not-be-used"
                    "status" "queued"})
    (fn [{:keys [requests]}]
      (let [sys (worker-system {:from ""})
            exception (try
                        (worker/lettermint-handler
                         sys
                         single-queued-email)
                        nil
                        (catch Throwable error
                          error))
            printed-data (binding [*print-meta* true]
                           (pr-str (ex-data exception)))]
        (is (= {:exception? true
                :network-requests []
                :token-exposed? false
                :worker-result {:status :error}}
               {:exception? (some? exception)
                :network-requests @requests
                :token-exposed?
                (or (str/includes? (or (ex-message exception) "")
                                   test-token)
                    (str/includes? printed-data test-token))
                :worker-result (worker/handler sys
                                               single-queued-email
                                               1)}))))))

(defn- public-fn [symbol]
  (some-> (ns-resolve 'app.email.email-worker symbol)
          deref))

(deftest demo-mode-is-a-sanitized-network-free-success
  (with-http-server
    (json-response 202
                   {"message_id" "must-not-be-used"
                    "status" "queued"})
    (fn [{:keys [requests]}]
      (let [lettermint-handler (public-fn 'lettermint-handler)
            sys (worker-system {:demo-mode? true})
            send-result (when lettermint-handler
                          (lettermint-handler sys single-queued-email))
            worker-result (worker/handler sys single-queued-email 1)]
        (is (= {:requests []
                :send-result {:mode :demo-mode
                              :result :email-sent}
                :worker-result {:status :success}}
               {:requests @requests
                :send-result send-result
                :worker-result worker-result}))
        (is (not (str/includes? (pr-str send-result) test-token)))))))
