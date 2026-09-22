(ns app.insurance.policy.notifications.actions-test
  (:require
   [app.insurance.policy.notifications.actions :as actions]
   [app.insurance.queries :as queries]
   [app.insurance.test-support :as insurance-test]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn tr
  ([path]
   (name (last path)))
  ([path vars]
   (str (name (last path)) ": " (:category-names vars))))

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-payment-notifications")
        {:keys [coverage-id] :as ids}
        (insurance-test/seed-page-shell-fixture! conn member-id)
        ledger-id                (random-uuid)]
    @(d/transact conn [[:db/add
                        [:instrument.coverage/coverage-id coverage-id]
                        :instrument.coverage/private?
                        true]
                       {:ledger/ledger-id ledger-id
                        :ledger/owner     [:member/member-id member-id]
                        :ledger/balance   0}])
    (merge ids
           {:conn      conn
            :ledger-id ledger-id
            :member-id member-id})))

(deftest payment-notification-data-and-action-test
  (let [{:keys [conn member-id policy-id]} (fixture)
        state                              {:current-member-id member-id
                                            :db                (d/db conn)
                                            :tr                tr}
        data                               (queries/notification-data (:db state) policy-id member-id)]
    (testing "the read model groups payable private instruments by owner"
      (is (= {:member-id       member-id
              :member-name     "Ada"
              :private-count   1
              :cost-available? true
              :sender-name     "Ada"
              :authorized?     true
              :time-range      "2026 - 2026"}
             {:member-id       (get-in data [:members-data 0 :member :member/member-id])
              :member-name     (get-in data [:members-data 0 :member :member/name])
              :private-count   (get-in data [:members-data 0 :count-private])
              :cost-available? (get-in data [:members-data 0 :private-costs-available?])
              :sender-name     (:sender-name data)
              :authorized?     (:authorized? data)
              :time-range      (:time-range data)})))

    (testing "ledger data and email intent share one transaction"
      (let [[[_ tx opts]]         (actions/send-notifications-action
                                   state {:insurancePayments {:policyId  (str policy-id)
                                                              :memberIds [(str member-id)]}})
            [[kind args options]] (:jobs opts)]
        (is (= "send-email" kind))
        (is (= :app.email.mailers/insurance-debt (:mailer args)))
        (is (= {:policy-id policy-id :sender-id member-id :member-id member-id} (:arguments args)))
        (is (= "start-within-2m" (:queue options)))
        (is (seq tx))
        (is (= [:db/add "datomic.tx" :audit/user [:member/member-id member-id]] (last tx)))
        (is (= [support/clear-loading
                [:app.datastar/assoc-state [:insurance-payments :result] {:status :queued :count-queued 1}]]
               (:on-success opts))))))

  (testing "a non-insurance-team member cannot send payment notifications"
    (let [{:keys [conn member-id outsider-id policy-id]} (fixture)
          effects                                        (actions/send-notifications-action
                                                          {:current-member-id outsider-id
                                                           :db                (d/db conn)
                                                           :tr                tr}
                                                          {:insurancePayments
                                                           {:policyId  (str policy-id)
                                                            :memberIds [(str member-id)]}})]
      (is (= support/clear-loading (first effects)))
      (is (= :error (get-in effects [1 2 :status])))))

  (testing "a forged selection with an unavailable private cost is rejected"
    (let [{:keys [conn member-id policy-id]} (fixture)
          [factor-eid]
          (d/q '[:find [?factor ...]
                 :in $ ?policy-id
                 :where
                 [?policy :insurance.policy/policy-id ?policy-id]
                 [?policy :insurance.policy/category-factors ?factor]]
               (d/db conn)
               policy-id)]
      @(d/transact conn [[:db/retract
                          [:insurance.policy/policy-id policy-id]
                          :insurance.policy/category-factors
                          factor-eid]])
      (let [effects (actions/send-notifications-action
                     {:current-member-id member-id
                      :db                (d/db conn)
                      :tr                tr}
                     {:insurancePayments
                      {:policyId  (str policy-id)
                       :memberIds [(str member-id)]}})]
        (is (= support/clear-loading (first effects)))
        (is (= :app.datastar/assoc-state (first (second effects))))
        (is (= :error (get-in effects [1 2 :status])))))))
