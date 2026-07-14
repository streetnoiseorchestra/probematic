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
        ledger-id (random-uuid)]
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
        state {:current-member-id member-id
               :db                (d/db conn)
               :tr                tr}
        data  (queries/notification-data (:db state) policy-id member-id)]
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

    (testing "the action returns one ordered ledger-and-email effect"
      (let [[effect] (actions/send-notifications-action
                      state
                      {:insurancePayments
                       {:policyId  (str policy-id)
                        :memberIds [(str member-id)]}})
            [_ payload] effect]
        (is (= :app.insurance/send-payment-notifications (first effect)))
        (is (= {:member-ids   [member-id]
                :sender-name  "Ada"
                :time-range   "2026 - 2026"
                :success      {:status :sent :count-sent 1}
                :result-path  [:insurance-payments :result]}
               {:member-ids  (mapv #(get-in % [:member :member/member-id])
                                   (:members-data payload))
                :sender-name (:sender-name payload)
                :time-range  (:time-range payload)
                :success     (:success payload)
                :result-path (:result-path payload)}))
        (is (seq (:tx-data payload)))
        (is (= [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]
               (last (:tx-data payload)))))))

  (testing "a non-insurance-team member cannot send payment notifications"
    (let [{:keys [conn member-id outsider-id policy-id]} (fixture)
          effects (actions/send-notifications-action
                   {:current-member-id outsider-id
                    :db                (d/db conn)
                    :tr                tr}
                   {:insurancePayments
                    {:policyId  (str policy-id)
                     :memberIds [(str member-id)]}})]
      (is (= support/clear-loading (first effects)))
      (is (= :error (get-in effects [1 2 :status])))
      (is (not-any? #(= :app.insurance/send-payment-notifications (first %))
                    effects))))

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
        (is (= :error (get-in effects [1 2 :status])))
        (is (not-any? #(= :app.insurance/send-payment-notifications (first %)) effects))))))
