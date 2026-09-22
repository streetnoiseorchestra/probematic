(ns app.gigs.answer-link.service-test
  (:require
   [app.gigs.answer-link.actions :as actions]
   [app.gigs.answer-link.service :as service]
   [app.gigs.domain :as domain]
   [app.queries :as q]
   [app.secret-box :as secret-box]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]
   [tick.core :as t]))

(use-fixtures :each tc/with-released-test-connections)

(def test-secret "answer-link-test-secret")

(defn seed-gig-member! [conn gig-date]
  (let [gig-id    (random-uuid)
        member-id (random-uuid)]
    @(d/transact conn [{:section/name     "trumpets"
                        :section/active?  true
                        :section/position 10}])
    @(d/transact conn [{:member/member-id member-id
                        :member/name      "Trumpet Player"
                        :member/nick      (str "trumpet-" member-id)
                        :member/email     (str "trumpet-" member-id "@example.com")
                        :member/active?   true
                        :member/section   [:section/name "trumpets"]}
                       (domain/gig->db {:gig/gig-id    gig-id
                                        :gig/title     "Answer Link Gig"
                                        :gig/status    :gig.status/confirmed
                                        :gig/gig-type  :gig.type/gig
                                        :gig/date      gig-date
                                        :gig/location  "Somewhere"
                                        :gig/call-time (t/time "18:00")})])
    {:gig-id gig-id :member-id member-id}))

(defn req [conn answer]
  (let [env {:app-secret-key test-secret
             :ig/system      {:app.ig/profile :test}}]
    {:db           (d/db conn)
     :datomic-conn conn
     :env          env
     :params       {:answer (secret-box/encrypt answer test-secret)}
     :system       {:env env}}))

(defn- command [submission]
  (merge {:actor-id    nil
          :env         {:ig/system {:app.ig/profile :test}}
          :plan        :plan/definitely
          :reminder-id (random-uuid)
          :reminder?   false}
         submission))

(deftest plans-attendance-without-performing-the-transaction
  (let [{:keys [conn]}             (tc/new-system "answer-link-plan-attendance")
        submitted-at               (t/instant "2026-09-22T10:00:00Z")
        submitted-on               (t/date)
        {:keys [gig-id member-id]} (seed-gig-member! conn (t/>> submitted-on (t/new-period 7 :days)))
        actor-id                   (random-uuid)
        create-plan                (actions/plan-submission
                                    (d/db conn)
                                    (command {:actor-id     actor-id
                                              :gig-id       gig-id
                                              :member-id    member-id
                                              :plan         :plan/definitely-not
                                              :submitted-at submitted-at
                                              :submitted-on submitted-on}))]
    (is (= {:audit/action ::actions/submit-attendance
            :audit/origin :app.origin/browser
            :audit/user   [:member/member-id actor-id]}
           (:audit create-plan)))
    (is (= [{:attendance/gig+member (q/gig+member gig-id member-id)
             :attendance/gig        [:gig/gig-id gig-id]
             :attendance/member     [:member/member-id member-id]
             :attendance/updated    (t/inst submitted-at)
             :attendance/section    [:section/name "trumpets"]
             :attendance/plan       :plan/definitely-not}]
           (:tx-data create-plan)))
    (is (nil? (q/attendance-for-gig (d/db conn) gig-id member-id)))
    @(d/transact conn (:tx-data create-plan))
    (let [later       (t/>> submitted-at (t/new-duration 1 :minutes))
          attendance  (q/attendance-for-gig (d/db conn) gig-id member-id)
          update-plan (actions/plan-submission
                       (d/db conn)
                       (command {:gig-id       gig-id
                                 :member-id    member-id
                                 :plan         :plan/probably
                                 :submitted-at later
                                 :submitted-on submitted-on}))]
      (is (= [[:db/add
               [:attendance/gig+member (:attendance/gig+member attendance)]
               :attendance/plan
               :plan/probably]
              [:db/add
               [:attendance/gig+member (:attendance/gig+member attendance)]
               :attendance/updated
               (t/inst later)]]
             (:tx-data update-plan))))))

(deftest plans-new-and-reset-reminders-with-explicit-time-and-id
  (let [{:keys [conn]}             (tc/new-system "answer-link-plan-reminder")
        submitted-at               (t/instant "2026-09-22T10:00:00Z")
        submitted-on               (t/date)
        reminder-id                (random-uuid)
        {:keys [gig-id member-id]} (seed-gig-member! conn (t/>> submitted-on (t/new-period 7 :days)))
        create-plan                (actions/plan-submission
                                    (d/db conn)
                                    (command {:gig-id       gig-id
                                              :member-id    member-id
                                              :plan         nil
                                              :reminder-id  reminder-id
                                              :reminder?    true
                                              :submitted-at submitted-at
                                              :submitted-on submitted-on}))]
    (is (= {:audit/action ::actions/submit-reminder
            :audit/origin :app.origin/browser}
           (:audit create-plan)))
    (is (= [{:reminder/reminder-id     reminder-id
             :reminder/gig             [:gig/gig-id gig-id]
             :reminder/member          [:member/member-id member-id]
             :reminder/reminder-status :reminder-status/pending
             :reminder/reminder-type   :reminder-type/gig-attendance
             :reminder/remind-at       (t/inst (t/>> submitted-at (t/new-period 2 :days)))}]
           (:tx-data create-plan)))
    (is (nil? (q/gig-reminder-for (d/db conn) gig-id member-id)))
    @(d/transact conn (:tx-data create-plan))
    (let [later      (t/>> submitted-at (t/new-duration 1 :hours))
          reset-plan (actions/plan-submission
                      (d/db conn)
                      (command {:gig-id       gig-id
                                :member-id    member-id
                                :plan         nil
                                :reminder-id  (random-uuid)
                                :reminder?    true
                                :submitted-at later
                                :submitted-on submitted-on}))]
      (is (= reminder-id
             (get-in reset-plan [:tx-data 0 :reminder/reminder-id])))
      (is (= (t/inst (t/>> later (t/new-period 2 :days)))
             (get-in reset-plan [:tx-data 0 :reminder/remind-at]))))))

(deftest returns-expected-rejections-as-data
  (let [{:keys [conn]}             (tc/new-system "answer-link-plan-errors")
        submitted-at               (t/instant)
        submitted-on               (t/date)
        {:keys [gig-id member-id]} (seed-gig-member! conn (t/>> submitted-on (t/new-period 7 :days)))
        db                         (d/db conn)
        submission                 (command {:gig-id       gig-id
                                             :member-id    member-id
                                             :submitted-at submitted-at
                                             :submitted-on submitted-on})]
    (testing "missing gig"
      (is (= ::actions/gig-not-found
             (get-in (actions/plan-submission
                      db
                      (assoc submission :gig-id (random-uuid)))
                     [:error :type]))))
    (testing "missing member"
      (is (= ::actions/member-not-found
             (get-in (actions/plan-submission
                      db
                      (assoc submission :member-id (random-uuid)))
                     [:error :type]))))
    (testing "invalid attendance plan"
      (is (= {:type ::actions/invalid-attendance-plan
              :plan :plan/not-real}
             (:error (actions/plan-submission
                      db
                      (assoc submission :plan :plan/not-real))))))
    (testing "past gig"
      (let [{past-gig-id    :gig-id
             past-member-id :member-id}
            (seed-gig-member! conn (t/<< submitted-on (t/new-period 1 :days)))]
        (is (= ::actions/gig-not-open
               (get-in (actions/plan-submission
                        (d/db conn)
                        (assoc submission
                               :gig-id past-gig-id
                               :member-id past-member-id))
                       [:error :type])))
        (is (seq (:tx-data (actions/plan-submission
                            (d/db conn)
                            (assoc submission
                                   :gig-id past-gig-id
                                   :member-id past-member-id
                                   :plan nil
                                   :reminder? true)))))))))

(deftest malformed-answer-links-return-data-errors
  (let [{:keys [conn]} (tc/new-system "answer-link-invalid-token")
        env            {:app-secret-key test-secret}]
    (is (= {:type ::service/invalid-answer-link}
           (:error
            (service/submit-answer!
             {:datomic-conn conn
              :env          env
              :params       {:answer "not-an-encrypted-answer"}
              :system       {:env env}}))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"application secret"
         (service/submit-answer!
          {:env    {}
           :system {:env {}}})))))
