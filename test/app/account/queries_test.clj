(ns app.account.queries-test
  (:require
   [app.account.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn seed-current-member!
  ([conn member-id]
   (seed-current-member! conn member-id {}))
  ([conn member-id overrides]
   @(d/transact
     conn
     [(merge {:db/id                   [:member/member-id member-id]
              :member/name             "Ada Lovelace"
              :member/nick             "ada"
              :member/email            "ADA@EXAMPLE.TEST"
              :member/username         "Ada_L"
              :member/phone            "+436601234567"
              :member/active?          true
              :member/avatar-template  "/user_avatar/ada/{size}/1.png"
              :member/current-status   "Rehearsing"
              :member/date-of-birth    "1815-12-10"
              :member/timezone         "Europe/Vienna"
              :member/week-start       :week-start/sunday
              :member/clock-format     :clock-format/hour-12
              :member.notify/enabled?  false
              :member.notify/scope     :notify.scope/gigs
              :member.notify/attendance-reminders? false
              :member.notify/poll-reminders? true
              :member.notify/email?    false
              :member.notify/browser?  true
              :member.notify/unread-style :notify.unread-style/unnumbered
              :member.notify/schedule  :notify.schedule/daily-batch
              :member.notify/batch-time "20:00"
              :member.break/start-date "2026-07-01"
              :member.break/end-date   "2026-07-20"}
             (into {} (remove (comp nil? val)) overrides))])))

(deftest current-member-read-model-includes-managed-avatar-and-account-settings
  (let [{:keys [conn member-id]} (tc/new-system "account-current-member")
        avatar-id (random-uuid)]
    (seed-current-member!
     conn
     member-id
     {:member/avatar {:image/image-id avatar-id
                      :image/width 1000
                      :image/height 849}})
    (let [member (queries/current-member (d/db conn) member-id)]
      (is (= {:member/member-id member-id
              :member/current-status "Rehearsing"
              :member/date-of-birth "1815-12-10"
              :member/timezone "Europe/Vienna"
              :member/avatar-template "/user_avatar/ada/{size}/1.png"}
             (select-keys member
                          [:member/member-id
                           :member/current-status
                           :member/date-of-birth
                           :member/timezone
                           :member/avatar-template])))
      (is (= avatar-id (get-in member [:member/avatar :image/image-id])))
      (is (= :week-start/sunday
             (get-in member [:member/week-start :db/ident])))
      (is (= :notify.schedule/daily-batch
             (get-in member [:member.notify/schedule :db/ident]))))))

(deftest profile-page-state-starts-from-durable-profile-values
  (let [{:keys [conn member-id]} (tc/new-system "account-profile-state")]
    (seed-current-member! conn member-id)
    (is (= {:name "Ada Lovelace"
            :nick "ada"
            :email "ADA@EXAMPLE.TEST"
            :username "Ada_L"
            :phone "+436601234567"
            :current-status "Rehearsing"
            :date-of-birth "1815-12-10"
            :avatar nil
            :avatar-removed? false
            :_error {}
            :_saved? false}
           (queries/profile-page-state (d/db conn) member-id {})))
    (testing "transient form state overlays only the current tab"
      (is (= "Submitted Name"
             (:name
              (queries/profile-page-state
               (d/db conn)
               member-id
               {:account-profile {:name "Submitted Name"}})))))))

(deftest preferences-read-model-uses-datomic-and-marks-a-missing-time-zone
  (let [{:keys [conn member-id]} (tc/new-system "account-preferences-state")]
    (seed-current-member! conn member-id)
    (is (= {:time-zone "Europe/Vienna"
            :week-start "sunday"
            :time-format "12-hour"
            :time-zone-persisted? true
            :_error {}
            :_saved? false}
           (queries/preferences-page-state (d/db conn) member-id {})))
    @(d/transact conn [[:db/retract
                        [:member/member-id member-id]
                        :member/timezone
                        "Europe/Vienna"]])
    (is (= {:time-zone "Europe/Berlin"
            :week-start "sunday"
            :time-format "12-hour"
            :time-zone-persisted? false
            :_error {}
            :_saved? false}
           (queries/preferences-page-state (d/db conn) member-id {})))))

(deftest notification-read-model-uses-complete-persisted-policy-or-complete-default
  (let [{:keys [conn member-id]} (tc/new-system "account-notification-state")]
    (seed-current-member! conn member-id)
    (is (= {:enabled? false
            :what "gigs"
            :reminders {:attendance? false :polls? true}
            :delivery {:email? false
                       :browser? true
                       :browser-capable? nil
                       :browser-permission "default"}
            :unread-style "unnumbered"
            :when "daily-batch"
            :batch-time "20:00"
            :_error {}
            :_saved? false}
           (queries/notification-page-state (d/db conn) member-id {})))
    (let [{:keys [conn member-id]} (tc/new-system "account-notification-default")]
      (is (= queries/default-notifications
             (queries/notification-page-state (d/db conn) member-id {}))))
    (let [{:keys [conn member-id]} (tc/new-system "account-notification-partial")]
      @(d/transact conn [{:member/member-id member-id
                          :member/name "Partial Policy"
                          :member.notify/enabled? false}])
      (is (= (assoc queries/default-notifications :enabled? false)
             (queries/notification-page-state (d/db conn) member-id {}))))))

(deftest break-read-model-derives-active-choice-status-and-member-time-zone
  (let [{:keys [conn member-id]} (tc/new-system "account-break-state")]
    (seed-current-member! conn member-id)
    (is (= {:active true
            :start-choice "date"
            :start-date "2026-07-01"
            :end-date "2026-07-20"
            :time-zone "Europe/Vienna"
            :status "away"
            :_error {}
            :_saved? false}
           (queries/break-page-state
            (d/db conn)
            member-id
            {}
            #inst "2026-07-13T12:00:00.000-00:00")))
    @(d/transact conn [[:db/retract
                        [:member/member-id member-id]
                        :member.break/start-date
                        "2026-07-01"]
                       [:db/retract
                        [:member/member-id member-id]
                        :member.break/end-date
                        "2026-07-20"]])
    (is (= (assoc queries/default-break :time-zone "Europe/Vienna")
           (queries/break-page-state
            (d/db conn)
            member-id
            {}
            #inst "2026-07-13T12:00:00.000-00:00")))))

(deftest break-read-model-shows-right-now-when-the-stored-start-is-today
  (let [{:keys [conn member-id]} (tc/new-system "account-break-today")]
    (seed-current-member! conn member-id
                          {:member/timezone "Pacific/Auckland"
                           :member.break/start-date "2026-07-14"
                           :member.break/end-date nil})
    (let [state (queries/break-page-state
                 (d/db conn)
                 member-id
                 {}
                 #inst "2026-07-13T22:30:00.000-00:00")]
      (is (= "now" (:start-choice state)))
      (is (= "away" (:status state))))))

(deftest time-zone-options-are-local-sorted-and-identifiable
  (let [options (queries/time-zone-options
                 #inst "2026-07-13T12:00:00.000-00:00")
        ids (mapv :value options)
        berlin (some #(when (= "Europe/Berlin" (:value %)) %) options)]
    (is (= ids (vec (sort ids))))
    (is (> (count options) 100))
    (is (re-find #"UTC[+-]" (:label berlin)))))
