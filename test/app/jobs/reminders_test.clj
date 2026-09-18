(ns app.jobs.reminders-test
  (:require
   [app.gigs.domain :as gigs]
   [app.i18n :as i18n]
   [app.jobs.reminders :as reminders]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]
   [tick.core :as t]))

(use-fixtures :each tc/with-released-test-connections)

(deftest due-reminders-mark-every-reminder-and-do-not-repeat
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [gig-id       (random-uuid)
            member-ids   [(random-uuid) (random-uuid)]
            reminder-ids [(random-uuid) (random-uuid) (random-uuid)]
            system       {:frame-loop runtime                                                                       :datomic {:conn conn} :job-queue {:client client}
                          :i18n-langs (i18n/read-langs)
                          :env        {:app-base-url "https://example.test" :app-secret-key "reminder-test-secret"}}]
        (writer/call!
         (:write-runner runtime)
         (fn []
           @(d/transact conn
                        (into [(gigs/gig->db {:gig/gig-id   gig-id                :gig/title  "Future rehearsal"
                                              :gig/gig-type :gig.type/probe       :gig/status :gig.status/confirmed
                                              :gig/date     (t/date "2099-09-20")})]
                              (map-indexed (fn [i id] {:member/member-id id                               :member/name (str "Member " i)
                                                       :member/email     (str "member" i "@example.test")}) member-ids)))
           @(d/transact conn
                        (mapv (fn [id member-id]
                                {:reminder/reminder-id   id                            :reminder/reminder-status :reminder-status/pending
                                 :reminder/reminder-type :reminder-type/gig-attendance
                                 :reminder/remind-at     #inst "2026-01-01"
                                 :reminder/gig           [:gig/gig-id gig-id]
                                 :reminder/member        [:member/member-id member-id]})
                              reminder-ids [(first member-ids) (second member-ids) (first member-ids)]))))
        (is (= :done (reminders/send-reminders! system #inst "2026-06-01" nil)))
        (let [db (d/db conn)
              tx (d/entity db (d/t->tx (d/basis-t db)))]
          (is (= (repeat 3 :reminder-status/queued)
                 (mapv #(:reminder/reminder-status (d/entity db [:reminder/reminder-id %])) reminder-ids)))
          (is (string? (:audit/jobs tx))))
        (writer/call! (:write-runner runtime) (constantly nil))
        (let [jobs       (drip/list-jobs client {})
              invocation (:args (first jobs))]
          (is (= 1 (count jobs)))
          (is (= :app.email.mailers/gig-reminder (:mailer invocation)))
          (is (= (set member-ids) (set (get-in invocation [:arguments :member-ids]))))
          (is (= 2 (count (get-in invocation [:arguments :member-ids])))))
        (reminders/send-reminders! system #inst "2026-06-01" nil)
        (is (= 1 (count (drip/list-jobs client {}))))))))
