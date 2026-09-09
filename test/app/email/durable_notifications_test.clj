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

(deftest survey-reminder-retains-the-selected-recipient-snapshot
  (queue-fixtures/with-queue
    (fn [{:keys [client]}]
      (let [{:keys [conn member-id policy-id coverage-id state]} (survey-fixtures/fixture)
            {:keys [survey-id]}                                  (insurance-fixtures/seed-member-survey!
                                                                  conn {:member-id member-id :policy-id policy-id :coverage-ids [coverage-id]})
            member-ref                                           [:member/member-id member-id]
            original-email                                       (:member/email (d/entity (d/db conn) member-ref))
            effects                                              (surveys/send-reminders-action
                                                                  (assoc state :db (d/db conn) :durable-jobs? true)
                                                                  (assoc (survey-fixtures/survey-signals policy-id {}) :targetid (str survey-id)))]
        (is (= [:db/transact] (mapv first effects)))
        (is (= {:status :queued :count-queued 1}
               (get-in effects [0 2 :on-success 1 2])))
        (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
        @(d/transact conn (nexus/batch-transactions (mapv rest effects)))
        @(d/transact conn [[:db/add member-ref :member/email "later@example.test"]])
        (log-dispatch/dispatch-pending! conn client 128)
        (let [jobs       (drip/list-jobs client {})
              invocation (:args (first jobs))
              message    (mailers/prepare! (mail-system conn) invocation)]
          (is (= 1 (count jobs)))
          (is (= ::mailers/survey-reminder (:mailer invocation)))
          (is (= [[original-email]] (mapv :to (:email/messages message))))
          (is (= (:email-id invocation) (:email/email-id message))))))))
