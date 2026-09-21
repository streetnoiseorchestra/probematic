(ns app.email.durable-notifications-test
  (:require
   [app.email.job-queue-test :as queue-fixtures]
   [app.email.mailers :as mailers]
   [app.i18n :as i18n]
   [app.insurance.policy.notifications.actions :as payments]
   [app.insurance.policy.notifications.actions-test :as payment-fixtures]
   [app.insurance.policy.surveys.actions :as surveys]
   [app.insurance.policy.surveys.actions-test :as survey-fixtures]
   [app.insurance.test-support :as insurance-fixtures]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.nexus :as nexus]
   [app.poll.detail.actions :as polls]
   [app.poll.test-support :as poll-fixtures]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(defn- mail-system [conn]
  {:datomic    {:conn conn}
   :env        {:app-base-url "https://example.test"}
   :i18n-langs (i18n/read-langs)})

(deftest payment-charge-and-notification-share-the-source-transaction
  (queue-fixtures/with-queue
    (fn [{:keys [client]}]
      (let [{:keys [conn member-id policy-id ledger-id]} (payment-fixtures/fixture)
            member-ref                                   [:member/member-id member-id]
            original-email                               (:member/email (d/entity (d/db conn) member-ref))
            effects                                      (payments/send-notifications-action
                                                          {:db            (d/db conn) :current-member-id member-id
                                                           :durable-jobs? true        :tr                payment-fixtures/tr}
                                                          {:insurancePayments {:policyId  (str policy-id)
                                                                               :memberIds [(str member-id)]}})]
        (is (= [:db/transact] (mapv first effects)))
        (is (= {:status :queued :count-queued 1}
               (get-in effects [0 2 :on-success 1 2])))
        (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
        (let [report   @(d/transact conn (nexus/batch-transactions (mapv rest effects)))
              source-t (d/basis-t (:db-after report))]
          (is (= 10 (:ledger/balance (d/entity (:db-after report) [:ledger/ledger-id ledger-id]))))
          (is (string? (:audit/jobs (d/entity (:db-after report) (d/t->tx source-t)))))
          @(d/transact conn [[:db/add member-ref :member/email "later@example.test"]])
          (log-dispatch/dispatch-pending! conn client 128)
          (let [jobs       (drip/list-jobs client {})
                invocation (:args (first jobs))
                message    (mailers/prepare! (mail-system conn) invocation)]
            (is (= 1 (count jobs)))
            (is (= ::mailers/insurance-debt (:mailer invocation)))
            (is (= source-t (:source-t invocation)))
            (is (= [original-email] (:email/tos message)))
            (is (= (:email-id invocation) (:email/email-id message)))))))))

(deftest survey-reminder-uses-current-recipient-details
  (queue-fixtures/with-queue
    (fn [{:keys [client]}]
      (let [{:keys [conn member-id policy-id coverage-id state]} (survey-fixtures/fixture)
            {:keys [survey-id]}                                  (insurance-fixtures/seed-member-survey!
                                                                  conn {:member-id member-id :policy-id policy-id :coverage-ids [coverage-id]})
            member-ref                                           [:member/member-id member-id]
            effects                                              (surveys/send-reminders-action
                                                                  (assoc state :db (d/db conn) :durable-jobs? true)
                                                                  (assoc (survey-fixtures/survey-signals policy-id {})
                                                                         :targetid (str survey-id)))]
        (is (= [:db/transact] (mapv first effects)))
        (is (= {:status :queued :count-queued 1}
               (get-in effects [0 2 :on-success 1 2])))
        (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
        @(d/transact conn (nexus/batch-transactions (mapv rest effects)))
        @(d/transact conn [[:db/add member-ref :member/email "later@example.test"]
                           [:db/add member-ref :member/active? false]])
        (log-dispatch/dispatch-pending! conn client 128)
        (let [jobs       (drip/list-jobs client {})
              invocation (:args (first jobs))
              message    (mailers/prepare! (mail-system conn) invocation)]
          (is (= 1 (count jobs)))
          (is (= ::mailers/survey-reminder (:mailer invocation)))
          (is (= [["later@example.test"]] (mapv :to (:email/messages message))))
          (is (= (:email-id invocation) (:email/email-id message))))))))

(deftest survey-reminder-skips-responses-completed-before-execution
  (queue-fixtures/with-queue
    (fn [{:keys [client]}]
      (let [{:keys [conn member-id policy-id coverage-id state]} (survey-fixtures/fixture)
            {:keys [survey-id response-id]}                      (insurance-fixtures/seed-member-survey!
                                                                  conn {:member-id member-id :policy-id policy-id :coverage-ids [coverage-id]})
            effects                                              (surveys/send-reminders-action
                                                                  (assoc state :db (d/db conn) :durable-jobs? true)
                                                                  (assoc (survey-fixtures/survey-signals policy-id {})
                                                                         :targetid (str survey-id)))]
        (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
        @(d/transact conn (nexus/batch-transactions (mapv rest effects)))
        @(d/transact conn [[:db/add
                            [:insurance.survey.response/response-id response-id]
                            :insurance.survey.response/completed-at
                            #inst "2026-09-21T12:00:00Z"]])
        (log-dispatch/dispatch-pending! conn client 128)
        (let [invocation (:args (first (drip/list-jobs client {})))]
          (is (= ::mailers/skip (mailers/prepare! (mail-system conn) invocation))))))))

(deftest opening-a-poll-selects-active-members-when-the-job-runs
  (queue-fixtures/with-queue
    (fn [{:keys [client]}]
      (let [{:keys [conn member-id]} (tc/new-system "durable-poll-mail")
            member-ref               [:member/member-id member-id]
            new-member-id            (random-uuid)
            _                        @(d/transact conn [{:member/member-id member-id
                                                         :member/name      "Ada"
                                                         :member/active?   true
                                                         :member/email     "ada@example.test"}])
            {:keys [poll-id]}        (poll-fixtures/seed-poll! conn member-id
                                                               {:poll/poll-status :poll.status/draft})
            effects                  (polls/open-poll-action
                                      (assoc (poll-fixtures/action-state conn member-id) :durable-jobs? true)
                                      (poll-fixtures/poll-detail-signals poll-id))]
        (is (= [:db/transact] (mapv first effects)))
        (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
        @(d/transact conn (nexus/batch-transactions (mapv rest effects)))
        (is (= :poll.status/open
               (:poll/poll-status (d/entity (d/db conn) (poll-fixtures/poll-ref poll-id)))))
        @(d/transact conn [[:db/add member-ref :member/active? false]
                           {:member/member-id new-member-id
                            :member/name      "Grace"
                            :member/active?   true
                            :member/email     "grace@example.test"}])
        (log-dispatch/dispatch-pending! conn client 128)
        (let [jobs       (drip/list-jobs client {})
              invocation (:args (first jobs))
              message    (mailers/prepare! (mail-system conn) invocation)]
          (is (= 1 (count jobs)))
          (is (= ::mailers/poll-opened (:mailer invocation)))
          (is (= [["grace@example.test"]] (mapv :to (:email/messages message))))
          (is (= (:email-id invocation) (:email/email-id message))))))))
