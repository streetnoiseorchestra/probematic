(ns app.email.messages
  "Builds email messages without enqueuing or delivering them."
  (:require [app.email.templates :as tmpl]
            [app.ui2 :as ui2]
            [app.urls :as url]
            [app.util :as util]
            [com.yetanalytics.squuid :as sq]
            [tick.core :as t]))

(defn- lettermint-message [to subject body-html body-plain]
  {:to [to] :subject subject :html body-html :text body-plain})

(defn- build-lettermint-email [batch? messages]
  {:email/sender   :lettermint          :email/batch?   batch?
   :email/email-id (sq/generate-squuid) :email/messages messages :email/created-at (t/inst)})

(defn build-email [to subject body-html body-plain]
  (assert subject)
  (build-lettermint-email false [(lettermint-message to subject body-html body-plain)]))

(defn build-smtp-email
  ([to subject body-html body-plain]
   (build-smtp-email to subject body-html body-plain nil))
  ([to subject body-html body-plain attachments]
   (assert subject)
   (util/remove-nils
    {:email/sender     :band-smtp           :email/batch?    false     :email/attachments attachments
     :email/email-id   (sq/generate-squuid) :email/tos       [to]      :email/subject     subject
     :email/body-plain body-plain           :email/body-html body-html :email/created-at  (t/inst)})))

(defn build-batch-emails [tos subject body-html body-plain]
  (assert subject)
  (build-lettermint-email true (mapv #(lettermint-message % subject body-html body-plain) tos)))

(defn build-gig-created-email [{:keys [tr] :as sys} gig members]
  (let [subject (tr [:email-subject/gig-created] {:gig-title (:gig/title gig)})]
    (build-lettermint-email
     true
     (mapv (fn [member]
             (lettermint-message (:member/email member) subject
                                 (tmpl/gig-created-email-html sys gig member false)
                                 (tmpl/gig-created-email-plain sys gig member false)))
           members))))

(defn build-gig-reminder-email [{:keys [tr] :as sys} gig members]
  (assert tr)
  (let [subject (tr [:email-subject/gig-reminder] {:gig-title (:gig/title gig)})]
    (build-lettermint-email
     true
     (mapv (fn [member]
             (lettermint-message (:member/email member) subject
                                 (tmpl/gig-created-email-html sys gig member true)
                                 (tmpl/gig-created-email-plain sys gig member true)))
           members))))

(defn build-new-poll-opened [{:keys [tr env] :as sys} poll members]
  (let [url (url/absolute-link-poll env (:poll/poll-id poll))]
    (build-batch-emails
     (mapv :member/email members)
     (tr [:email-subject/poll-created] {:poll-title (:poll/title poll)})
     (tmpl/generic-email-html sys (tmpl/poll-created-email-html-body tr poll) (tr [:polls/vote-now]) url)
     (tmpl/generic-email-plain sys (tmpl/poll-created-email-plain-body tr poll) (tr [:polls/vote-now]) url))))

(defn build-new-user-invite [{:keys [tr] :as sys} {:member/keys [email]} invite-code]
  (build-email email (tr [:email-subject/new-invite])
               (tmpl/new-user-invite-html sys invite-code)
               (tmpl/new-user-invite-plain sys invite-code)))

(defn build-generic-email [sys to-email subject body-text cta-text cta-url]
  (build-email to-email subject
               (tmpl/generic-email-html sys body-text cta-text cta-url)
               (tmpl/generic-email-plain sys body-text cta-text cta-url)))

(defn build-rehearsal-leader-email [{:keys [tr env] :as context} gig leader]
  (build-generic-email context (:member/email leader)
                       (tr [:email/subject-log-plays])
                       (tr [:email/body-log-plays]
                           {:gig-date (ui2/format-date-range {:current-locale :de} :compact-with-weekday
                                                             (:gig/date gig) (:gig/end-date gig))})
                       (tr [:email/cta-log-plays])
                       (url/absolute-link-gig-log-plays env (:gig/gig-id gig))))

(defn build-insurance-debt-notification-emails [{:keys [tr] :as sys} sender-name time-range member-data]
  (assert tr)
  (map (fn [{:keys [member private-cost-total private-coverages]}]
         (assert private-cost-total)
         (assert time-range)
         (assert (:member/email member))
         (let [args    (tmpl/build-insurance-debt-args sys member private-coverages sender-name time-range private-cost-total)
               subject (tr [:insurance/payment-email-subject]
                           {:member-name (:member/name member) :time-range time-range})]
           (build-smtp-email (:member/email member) subject
                             (tmpl/insurance-debt-html sys args)
                             (tmpl/insurance-debt-plain sys args))))
       member-data))

(defn build-survey-notifications [{:keys [tr env] :as sys} sender-name policy members email-data]
  (let [url (url/absolute-link-insurance-survey-start env (:insurance.policy/policy-id policy))]
    (build-batch-emails
     (mapv :member/email members)
     (tr [:insurance/survey-email-subject])
     (tmpl/generic-email-html sys (tmpl/insurance-survey-created-email-html-body tr email-data) (tr [:insurance/survey-email-start]) url
                              {:sign-off [:p (tr [:email/sign-off-personal]) [:br] sender-name
                                          [:br] (tr [:insurance/email-team-name])]})
     (tmpl/generic-email-plain sys (tmpl/insurance-survey-created-email-plain-body tr email-data) (tr [:insurance/survey-email-start]) url
                               {:sign-off (str (tr [:email/sign-off-personal]) "\n" sender-name
                                               "\n" (tr [:insurance/email-team-name]))}))))
