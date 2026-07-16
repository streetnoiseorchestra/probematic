(ns app.email.domain
  (:require
   [app.schemas :as s]))

(def Attachment
  [:map {:name :app.entity/attachment}
   [:filename ::s/non-blank-string]
   [:content-type ::s/non-blank-string]
   [:content bytes?]])

(defn- no-project-api-token? [value]
  (not (contains? value :project-api-token)))

(def QueuedLettermintMessage
  [:and {:name :app.entity/queued-lettermint-message}
   [:map
    [:to [:vector {:min 1
                   :max 1}
          ::s/email-address]]
    [:subject ::s/non-blank-string]
    [:html ::s/non-blank-string]
    [:text ::s/non-blank-string]]
   [:fn {:error/message
         "a queued Lettermint message must not contain an API token"}
    no-project-api-token?]])

(def QueuedLettermintEmail
  [:and {:name :app.entity/queued-lettermint-email}
   [:map
    [:email/email-id :uuid]
    [:email/messages [:vector {:min 1
                               :max 500}
                      QueuedLettermintMessage]]
    [:email/batch? :boolean]
    [:email/sender [:= :lettermint]]
    [:email/created-at {:optional true} ::s/inst]]
   [:fn {:error/message
         "a queued Lettermint email must not contain an API token"}
    no-project-api-token?]
   [:fn {:error/message
         "a non-batch queued email must contain exactly one message"}
    (fn [{:email/keys [batch? messages]}]
      (or batch?
          (= 1 (count messages))))]])

(def QueuedBandSmtpEmail
  [:map {:name :app.entity/queued-band-smtp-email}
   [:email/attachments {:optional true} [:sequential Attachment]]
   [:email/email-id :uuid]
   [:email/tos [:vector ::s/email-address]]
   [:email/subject ::s/non-blank-string]
   [:email/body-html ::s/non-blank-string]
   [:email/body-plain ::s/non-blank-string]
   [:email/batch? [:= false]]
   [:email/sender [:= :band-smtp]]
   [:email/created-at {:optional true} ::s/inst]])

(def QueuedEmailMessage
  [:multi {:dispatch :email/sender
           :name :app.entity/queued-email}
   [:lettermint QueuedLettermintEmail]
   [:band-smtp QueuedBandSmtpEmail]])
