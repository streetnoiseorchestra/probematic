(ns app.jobs.reminders-test
  (:require
   [app.gigs.domain :as gigs]
   [app.email.mailers :as mailers]
   [app.i18n :as i18n]
   [app.jobs.reminders :as reminders]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]
   [tick.core :as t]))

(use-fixtures :each tc/with-released-test-connections)

(deftest due-reminders-keep-the-due-set-and-recheck-current-eligibility
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [gig-id          (random-uuid)
            member-ids      [(random-uuid) (random-uuid)]
            other-member-id (random-uuid)
            reminder-ids    (vec (repeatedly 501 random-uuid))
            system          {:frame-loop runtime
                             :datomic    {:conn conn}
                             :job-queue  {:client client}
                             :i18n-langs (i18n/read-langs)
                             :env        {:app-base-url   "https://example.test"
                                          :app-secret-key "reminder-test-secret"}}]
        (writer/call!
         (:write-runner runtime)
         (fn []
           @(d/transact conn
                        (into [(gigs/gig->db {:gig/gig-id   gig-id
                                              :gig/title    "Future rehearsal"
                                              :gig/gig-type :gig.type/probe
                                              :gig/status   :gig.status/confirmed
                                              :gig/date     (t/date "2099-09-20")})
                               {:db/id            "section"
                                :section/name     "test"
                                :section/active?  true
                                :section/position 1}]
                              (map-indexed (fn [i id]
                                             {:member/member-id id
                                              :member/name      (str "Member " i)
                                              :member/email     (str "member" i "@example.test")
                                              :member/active?   true
                                              :member/section   "section"})
                                           (conj member-ids other-member-id))))
           @(d/transact conn
                        (mapv (fn [id member-id]
                                {:reminder/reminder-id     id
                                 :reminder/reminder-status :reminder-status/pending
                                 :reminder/reminder-type   :reminder-type/gig-attendance
                                 :reminder/remind-at       #inst "2026-01-01"
                                 :reminder/gig             [:gig/gig-id gig-id]
                                 :reminder/member          [:member/member-id member-id]})
                              reminder-ids
                              (mapv #(nth member-ids (mod % 2))
                                    (range (count reminder-ids)))))))
        (is (= :done (reminders/send-reminders! system #inst "2026-06-01" nil)))
        (let [db (d/db conn)
              tx (d/entity db (d/t->tx (d/basis-t db)))]
          (is (= (repeat 501 :reminder-status/queued)
                 (mapv #(:reminder/reminder-status
                         (d/entity db [:reminder/reminder-id %]))
                       reminder-ids)))
          (is (string? (:audit/jobs tx))))
        (writer/call! (:write-runner runtime) (constantly nil))
        (let [jobs       (drip/list-jobs client {})
              invocation (:args (first jobs))]
          (is (= 1 (count jobs)))
          (is (= :app.email.mailers/gig-reminder (:mailer invocation)))
          (is (= (set reminder-ids)
                 (set (get-in invocation [:arguments :reminder-ids]))))
          (is (= 501 (count (get-in invocation [:arguments :reminder-ids]))))
          (is (nil? (get-in invocation [:arguments :member-ids])))
          @(d/transact conn [{:attendance/gig+member (q/gig+member gig-id (first member-ids))
                              :attendance/gig        [:gig/gig-id gig-id]
                              :attendance/member     [:member/member-id (first member-ids)]
                              :attendance/plan       :plan/definitely}])
          (let [message (mailers/prepare! system invocation)]
            (is (= [["member1@example.test"]]
                   (mapv :to (:email/messages message))))))
        (reminders/send-reminders! system #inst "2026-06-01" nil)
        (is (= 1 (count (drip/list-jobs client {}))))))))
