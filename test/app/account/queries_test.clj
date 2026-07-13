(ns app.account.queries-test
  (:require
   [app.account.test-support :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn seed-current-member! [conn member-id]
  @(d/transact conn [{:db/id                  [:member/member-id member-id]
                      :member/name            "Ada Lovelace"
                      :member/nick            "ada"
                      :member/email           "ADA@EXAMPLE.TEST"
                      :member/username        "Ada_L"
                      :member/phone           "+436601234567"
                      :member/active?         true
                      :member/avatar-template "/user_avatar/ada/{size}/1.png"}]))

(deftest current-member-read-model-comes-from-datomic
  (let [current-member (support/public-fn 'app.account.queries/current-member)]
    (is (fn? current-member) "app.account.queries/current-member should exist")
    (when current-member
      (let [{:keys [conn member-id]} (tc/new-system "account-current-member")]
        (seed-current-member! conn member-id)
        (is (= {:member/member-id      member-id
                :member/name           "Ada Lovelace"
                :member/nick           "ada"
                :member/email          "ADA@EXAMPLE.TEST"
                :member/username       "Ada_L"
                :member/phone          "+436601234567"
                :member/active?        true
                :member/avatar-template "/user_avatar/ada/{size}/1.png"}
               (select-keys (current-member (d/db conn) member-id)
                            [:member/member-id
                             :member/name
                             :member/nick
                             :member/email
                             :member/username
                             :member/phone
                             :member/active?
                             :member/avatar-template])))))))

(deftest profile-page-state-combines-persisted-and-prototype-values
  (let [profile-state (support/public-fn 'app.account.queries/profile-page-state)]
    (is (fn? profile-state) "app.account.queries/profile-page-state should exist")
    (when profile-state
      (let [{:keys [conn member-id]} (tc/new-system "account-profile-state")]
        (seed-current-member! conn member-id)
        (testing "a fresh tab starts from Datomic and empty prototype defaults"
          (is (= {:name            "Ada Lovelace"
                  :nick            "ada"
                  :email           "ADA@EXAMPLE.TEST"
                  :username        "Ada_L"
                  :phone           "+436601234567"
                  :current-status  ""
                  :date-of-birth   ""
                  :avatar          nil
                  :avatar-removed? false
                  :_error          {}
                  :_saved?         false}
                 (profile-state (d/db conn) member-id {}))))
        (testing "tab-scoped form state is retained without changing its owner"
          (is (= {:member-id      "forged"
                  :name           "Submitted Name"
                  :current-status "Rehearsing"
                  :date-of-birth  "1815-12-10"}
                 (select-keys
                  (profile-state
                   (d/db conn)
                   member-id
                   {:account-profile {:member-id      "forged"
                                      :name           "Submitted Name"
                                      :current-status "Rehearsing"
                                      :date-of-birth  "1815-12-10"}})
                  [:member-id :name :current-status :date-of-birth]))))))))

(deftest prototype-read-models-reset-in-a-fresh-tab
  (doseq [[symbol root expected]
          [['app.account.queries/preferences-page-state :account-preferences
            {:time-zone "Europe/Berlin"
             :week-start "monday"
             :time-format "24-hour"
             :_error {}
             :_saved? false}]
           ['app.account.queries/notification-page-state :account-notifications
            {:enabled? true
             :what "everything"
             :reminders {:attendance? true :polls? true}
             :delivery {:email? true
                        :browser? false
                        :browser-capable? nil
                        :browser-permission "default"}
             :unread-style "numbered"
             :when "right-away"
             :batch-time "morning"
             :_error {}
             :_saved? false}]
           ['app.account.queries/break-page-state :account-break
            {:active false
             :start-choice "now"
             :start-date ""
             :end-date ""
             :time-zone "Europe/Berlin"
             :status "available"
             :_error {}
             :_saved? false}]]]
    (let [state-fn (support/public-fn symbol)]
      (is (fn? state-fn) (str symbol " should exist"))
      (when state-fn
        (is (= expected (state-fn {})))
        (is (= "saved-in-this-tab"
               (:prototype-marker
                (state-fn {root {:prototype-marker "saved-in-this-tab"}}))))))))

(deftest time-zone-options-are-local-sorted-and-identifiable
  (let [time-zone-options (support/public-fn 'app.account.queries/time-zone-options)]
    (is (fn? time-zone-options) "app.account.queries/time-zone-options should exist")
    (when time-zone-options
      (let [options (time-zone-options #inst "2026-07-13T12:00:00.000-00:00")
            ids     (mapv :value options)
            berlin  (some #(when (= "Europe/Berlin" (:value %)) %) options)]
        (is (= ids (vec (sort ids))))
        (is (> (count options) 100))
        (is (= "Europe/Berlin" (:value berlin)))
        (is (re-find #"UTC[+-]" (:label berlin)))
        (is (re-find #"Europe/Berlin" (:label berlin)))))))
