(ns app.email.domain
  (:require
   [app.schemas :as s]))

(def sent-statuses #{:email-status/not-sent
                     :email-status/sent
                     :email-status/send-error})

(def Attachment
  [:map {:name :app.entity/attachment}
   [:filename ::s/non-blank-string]
   [:content-type ::s/non-blank-string]
   [:content bytes?]])

(def QueuedEmailMessage
  [:map {:name :app.entity/queued-email}
   [:email/attachments {:optional true} [:sequential Attachment]]
   [:email/email-id :uuid]
   [:email/tos [:vector ::s/email-address]]
   [:email/subject ::s/non-blank-string]
   [:email/body-html ::s/non-blank-string]
   [:email/body-plain ::s/non-blank-string]
   [:email/batch? :boolean]
   [:email/sender [:enum :mailgun :band-smtp]]
   [:email/recipient-variables {:optional true} :map]
   ;; [:email/sent-status (s/enum-from sent-statuses)]
   [:email/created-at {:optional true} ::s/inst]])
