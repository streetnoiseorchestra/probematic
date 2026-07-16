(ns app.email-test
  (:require
   [app.email :as email]
   [app.email.domain :as email.domain]
   [app.schemas :as s]
   [app.secret-box :as secret-box]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(def test-secret "email-rendering-test-secret")

(def test-env
  {:app-base-url "https://example.test"
   :app-secret-key test-secret})

(defn- tr [message & arguments]
  (str (first message)
       (when (seq arguments)
         (str " " (pr-str arguments)))))

(def test-system
  {:env test-env
   :tr tr})

(def gig-id
  #uuid "0198215f-95e8-7e0d-8418-743646aa1551")

(def gig
  {:gig/date (t/date "2026-08-20")
   :gig/gig-id gig-id
   :gig/gig-type :gig.type/gig
   :gig/location "Concert hall"
   :gig/status :gig.status/confirmed
   :gig/title "Summer concert"})

(def members
  [{:member/email "ada@example.test"
    :member/member-id
    #uuid "01982160-b5ef-7152-8c87-0f4aed1622ee"
    :member/name "Ada"}
   {:member/email "grace@example.test"
    :member/member-id
    #uuid "01982160-f0e2-7e58-970c-80fb61a62a11"
    :member/name "Grace"}])

(defn- answer-tokens [body]
  (map second
       (re-seq #"https://example\.test/answer-link\?answer=([^\"\s<]+)"
               (or body ""))))

(defn- answer-payloads [body]
  (mapv #(secret-box/decrypt % test-secret)
        (answer-tokens body)))

(defn- expected-answer-payloads [member-id]
  #{{:attendance/plan :plan/definitely
     :gig/gig-id gig-id
     :member/member-id member-id}
    {:attendance/plan :plan/definitely-not
     :gig/gig-id gig-id
     :member/member-id member-id}
    {:gig/gig-id gig-id
     :member/member-id member-id
     :reminder true}})

(defn- message-for [queued-email address]
  (some #(when (= [address] (:to %)) %)
        (:email/messages queued-email)))

(defn- assert-personalized-answer-links [queued-email]
  (is (= (mapv (comp vector :member/email) members)
         (mapv :to (:email/messages queued-email))))
  (doseq [{:member/keys [email member-id]} members]
    (let [message (message-for queued-email email)
          expected (expected-answer-payloads member-id)]
      (is (= expected (set (answer-payloads (:html message)))))
      (is (= expected (set (answer-payloads (:text message))))))))

(deftest gig-created-email-materializes-member-specific-answer-links
  (let [queued-email (email/build-gig-created-email test-system gig members)]
    (is (= {:batch? true
            :message-count 2
            :sender :lettermint}
           {:batch? (:email/batch? queued-email)
            :message-count (count (:email/messages queued-email))
            :sender (:email/sender queued-email)}))
    (assert-personalized-answer-links queued-email)
    (is (not (str/includes? (pr-str queued-email) "%recipient.")))
    (is (not (contains? queued-email :email/recipient-variables)))))

(deftest gig-reminder-email-materializes-member-specific-answer-links
  (let [queued-email (email/build-gig-reminder-email test-system gig members)]
    (is (= {:batch? true
            :message-count 2
            :sender :lettermint}
           {:batch? (:email/batch? queued-email)
            :message-count (count (:email/messages queued-email))
            :sender (:email/sender queued-email)}))
    (assert-personalized-answer-links queued-email)
    (is (not (str/includes? (pr-str queued-email) "%recipient.")))
    (is (not (contains? queued-email :email/recipient-variables)))))

(defn- body-pairs [queued-email]
  (mapv #(select-keys % [:html :text])
        (:email/messages queued-email)))

(defn- assert-shared-body-batch [queued-email]
  (is (= {:batch? true
          :recipients (mapv (comp vector :member/email) members)
          :sender :lettermint}
         {:batch? (:email/batch? queued-email)
          :recipients (mapv :to (:email/messages queued-email))
          :sender (:email/sender queued-email)}))
  (is (= 1 (count (distinct (body-pairs queued-email)))))
  (is (every? (fn [{:keys [html subject text]}]
                (and (not (str/blank? html))
                     (not (str/blank? subject))
                     (not (str/blank? text))))
              (:email/messages queued-email))))

(deftest shared-batch-bodies-become-complete-per-recipient-messages
  (testing "gig-updated email"
    (assert-shared-body-batch
     (email/build-gig-updated-email test-system
                                    gig
                                    members
                                    [:gig/location])))

  (testing "poll-opened email"
    (assert-shared-body-batch
     (email/build-new-poll-opened
      test-system
      {:poll/closes-at (t/instant "2099-08-31T20:00:00Z")
       :poll/description "Choose a rehearsal day."
       :poll/options [{:poll.option/value "Monday"}
                      {:poll.option/value "Tuesday"}]
       :poll/poll-id
       #uuid "01982162-587f-78fe-8ab8-dff97d7d32f4"
       :poll/title "Rehearsal day"}
      members)))

  (testing "insurance survey notification"
    (assert-shared-body-batch
     (email/build-survey-notifications
      {:system {:env test-env}
       :tr tr}
      "Linus"
      {:insurance.policy/policy-id
       #uuid "01982163-3da9-7500-953b-d4642732fc3f"}
      members
      {:closes-at (t/instant "2099-09-30T20:00:00Z")
       :member-most-instrument-count 0
       :member-most-instruments nil}))))

(deftest single-email-builder-creates-one-complete-lettermint-message
  (let [queued-email (email/build-new-user-invite
                      test-system
                      (first members)
                      "invite-code")]
    (is (= {:batch? false
            :message-count 1
            :sender :lettermint}
           {:batch? (:email/batch? queued-email)
            :message-count (count (:email/messages queued-email))
            :sender (:email/sender queued-email)}))
    (is (= ["ada@example.test"]
           (get-in queued-email [:email/messages 0 :to])))
    (is (every? #(not (str/blank? (get-in queued-email
                                          [:email/messages 0 %])))
                [:html :subject :text]))
    (is (not-any? #(contains? queued-email %)
                  [:email/body-html
                   :email/body-plain
                   :email/recipient-variables
                   :email/subject
                   :email/tos]))))

(def valid-lettermint-email
  {:email/batch? false
   :email/created-at #inst "2026-07-16T10:00:00.000-00:00"
   :email/email-id
   #uuid "01982164-08d4-7443-9789-3bd1d9ded0b9"
   :email/messages [{:html "<p>Hello.</p>"
                     :subject "Hello"
                     :text "Hello."
                     :to ["ada@example.test"]}]
   :email/sender :lettermint})

(def valid-band-smtp-email
  {:email/attachments
   [{:content (.getBytes "attachment")
     :content-type "text/plain"
     :filename "attachment.txt"}]
   :email/batch? false
   :email/body-html "<p>Hello.</p>"
   :email/body-plain "Hello."
   :email/created-at #inst "2026-07-16T10:00:00.000-00:00"
   :email/email-id
   #uuid "01982164-4e20-7857-80d0-abfa9b90bef1"
   :email/sender :band-smtp
   :email/subject "Hello"
   :email/tos ["ada@example.test"]})

(deftest queued-email-schema-discriminates-provider-message-shapes
  (is (= {:band-smtp true
          :lettermint true
          :lettermint-message-with-project-token false
          :lettermint-with-project-token false
          :lettermint-with-smtp-shape false
          :single-lettermint-with-multiple-messages false
          :smtp-with-lettermint-shape false}
         {:band-smtp (s/valid? email.domain/QueuedEmailMessage
                               valid-band-smtp-email)
          :lettermint (s/valid? email.domain/QueuedEmailMessage
                                valid-lettermint-email)
          :lettermint-message-with-project-token
          (s/valid? email.domain/QueuedEmailMessage
                    (assoc-in valid-lettermint-email
                              [:email/messages 0 :project-api-token]
                              "must-not-be-queued"))
          :lettermint-with-project-token
          (s/valid? email.domain/QueuedEmailMessage
                    (assoc valid-lettermint-email
                           :project-api-token "must-not-be-queued"))
          :lettermint-with-smtp-shape
          (s/valid? email.domain/QueuedEmailMessage
                    (assoc valid-band-smtp-email
                           :email/sender :lettermint))
          :single-lettermint-with-multiple-messages
          (s/valid? email.domain/QueuedEmailMessage
                    (update valid-lettermint-email
                            :email/messages
                            conj
                            (first (:email/messages valid-lettermint-email))))
          :smtp-with-lettermint-shape
          (s/valid? email.domain/QueuedEmailMessage
                    (assoc valid-lettermint-email
                           :email/sender :band-smtp))})))
