(ns app.email.mailgun
  (:require
   [jsonista.core :as j]
   [org.httpkit.client :as client]))

(def retryable-errors #{429 500})

(defn can-send-mail-to-in-test-mode? [{:keys [mailgun]} to]
  (contains? (:test-mode-disabled-for mailgun) to))

(defn send-email-req
  "Creates the email payload
  documentation: https://documentation.mailgun.com/en/latest/api-sending.html#sending"
  [{:keys [mailgun] :as sys} to subject plain html]
  (let [{:keys [api-key mailgun-domain send-domain from test-mode?]} mailgun]
    {:method :post
     :url (str "https://" mailgun-domain "/v3/" send-domain "/messages")
     :basic-auth ["api" api-key]
     :form-params {:to to
                   :from from
                   :subject subject
                   :text plain
                   :html html
                   "o:testmode" (if test-mode?  (not (can-send-mail-to-in-test-mode? sys to)) false)
                   "o:tracking" false}}))

(defn partition-tos [{:keys [mailgun] :as sys} tos]
  (if (:test-mode? mailgun)
    (let [r (group-by (partial can-send-mail-to-in-test-mode? sys) tos)]
      {:send-in-normalmode (get r true)
       :send-in-testmode (get r false)})

    {:send-in-normalmode tos
     :send-in-testmode nil}))
(comment

  (partition-tos {:mailgun {:test-mode? false}} ["alice@example.com" "foo@foobar.com"])
;; => {:send-in-normalmode ["alice@example.com" "foo@foobar.com"],
;;     :send-in-testmode nil}

  (partition-tos {:mailgun {:test-mode? true
                            :test-mode-disabled-for #{}}}
                 ["alice@example.com" "foo@foobar.com" "bob@foobar.com"])
;; => {:send-in-normalmode ["alice@example.com" "foo@foobar.com" "bob@foobar.com"],
;;     :send-in-testmode nil}
  )

(defn send-batch-req
  "Creates the email payload for sending batched emails
  documentation: https://documentation.mailgun.com/en/latest/user_manual.html#batch-sending-1"
  [{:keys [mailgun]}  test-mode? tos subject plain html recipient-variables]
  (assert (<=  (count tos) 1000) (format  "The maximum number of recipients allowed for Batch Sending is 1,000.. was %d" (count tos)))
  (let [{:keys [api-key mailgun-domain send-domain from]} mailgun]
    {:method :post
     :url (str "https://" mailgun-domain "/v3/" send-domain "/messages")
     :basic-auth ["api" api-key]
     :form-params {:to tos
                   :from from
                   :subject subject
                   :text plain
                   :html html
                   "recipient-variables" (j/write-value-as-string recipient-variables)
                   "o:testmode" test-mode?
                   "o:tracking" false}}))

(defn- make-request!
  "Handles the HTTP POST and response for email sending."
  [{:keys [mailgun] :as _sys} req]
  (if-not (:demo-mode? mailgun)
    (let [resp @(client/request req)]
      (if (= 200 (:status resp))
        {:result :email-sent
         :mode (if (:test-mode? mailgun) :test-mode :prod-mode)
         :mailgun-id (get  (j/read-value (:body resp)) "id")}
        {:error (:status resp)
         :retry? (retryable-errors (:status resp))
         :message (get (j/read-value (:body resp)) "message")}))
    (do
      (tap> {:email-send-request req})
      {:result :email-sent :mode :demo-mode})))

(defn send-email!
  "Sends the email"
  [sys to subject plain html]
  (let [req (send-email-req sys to subject plain html)]
    (make-request! sys req)))

(defn send-batch!
  "Sends batch emails"
  [sys tos subject plain html recipient-variables]
  (let [{:keys [send-in-normalmode send-in-testmode]} (partition-tos sys tos)]
    (when send-in-normalmode
      (tap> {:send-normal! send-in-normalmode})
      (make-request! sys (send-batch-req sys false send-in-normalmode subject plain html recipient-variables)))
    (when send-in-testmode
      (tap> {:send-test! send-in-testmode
             :mailgun-req (send-batch-req sys true send-in-testmode subject plain html recipient-variables)}))))

(comment

  (do
    (require '[integrant.repl.state :as state])
    (require '[hiccup2.core :refer [html]])
    (def mailgun (-> state/system :app.ig/mailgun))
    (def recipient1 "foo@example.com")
    (def recipient2 "foo+test@example.com")) ;; rcf

  (send-email-req {:mailgun mailgun} recipient1 "Hello from probematic" "Hello world"
                  (str
                   (html [:div [:h1 "Hello worle!"]
                          [:p "Lorem ipsum.."]
                          [:p [:a {:href "https://streetnoise.at"} "streetnoise.at"]]
                          [:p "Things and stuff!"]])))

  (send-email! {:mailgun mailgun} recipient1 "Hello from probematic" "Hello world"
               (str
                (html [:div [:h1 "Hello worle!"]
                       [:p "Lorem ipsum.."]
                       [:p [:a {:href "https://streetnoise.at"} "streetnoise.at"]]
                       [:p "Things and stuff!"]])))

  (send-batch! {:mailgun mailgun} [recipient1  recipient2] "Hello from probematic" "Hello world, %recipient.foobar%"
               (str
                (html [:div [:h1 "Hello world!"]
                       [:p " and it is '%recipient.foobar%'"]
                       [:p [:a {:href "https://streetnoise.at/%recipient.foobar%"} "streetnoise.at"]]
                       [:p "Things and stuff!"]]))
               {recipient2 {:foobar "FOOBARRECIPIENT2"} recipient1 {:foobar "FOOBARRECIPIENT1"}})

;;
  )
