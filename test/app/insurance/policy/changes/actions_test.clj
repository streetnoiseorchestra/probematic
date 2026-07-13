(ns app.insurance.policy.changes.actions-test
  (:require
   [app.insurance.policy.changes.actions :as actions]
   [app.insurance.test-support :as insurance-test]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn tr [[key] & _]
  (name key))

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-policy-changes-action")
        policy-id                (random-uuid)]
    (insurance-test/seed-policy! conn policy-id)
    {:member-id member-id
     :policy-id policy-id
     :state     {:current-member-id member-id
                 :db                (d/db conn)
                 :tr                tr}}))

(defn signals [policy-id]
  {:insurance-policy-changes
   {:policy-id                   (str policy-id)
    :recipient                   "Insurer <insurance@example.test>"
    :subject                     "Policy update"
    :body                        "Please find the updates attached."
    :attachment-filename-new     "new.xls"
    :attachment-filename-changes "changes.xls"}})

(deftest confirm-changes-action-test
  (testing "confirmation activates the policy and returns to its dashboard"
    (let [{:keys [member-id policy-id state]} (fixture)
          [[_ tx-data opts] redirect]         (actions/confirm-changes-action
                                               state
                                               (signals policy-id))]
      (is (= {} opts))
      (is (= :insurance.policy.status/active
             (:insurance.policy/status (first tx-data))))
      (is (= [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]
             (last tx-data)))
      (is (= [:app.datastar/redirect (urls/link-policy policy-id)] redirect)))))

(deftest send-and-confirm-changes-action-test
  (testing "email delivery is one ordered effect with confirmation as its continuation"
    (let [{:keys [policy-id state]} (fixture)
          [effect]                  (actions/send-and-confirm-changes-action
                                     state
                                     (signals policy-id))
          [_ payload]               effect]
      (is (= :app.insurance/send-policy-changes (first effect)))
      (is (= {:policy-id                   policy-id
              :recipient                   "Insurer <insurance@example.test>"
              :subject                     "Policy update"
              :body                        "Please find the updates attached."
              :attachment-filename-new     "new.xls"
              :attachment-filename-changes "changes.xls"
              :on-success                  [[::actions/confirm-sent
                                             {:insurance-policy-changes
                                              {:policy-id (str policy-id)}}]]
              :redirect                    (urls/link-policy policy-id)}
             payload)))))
