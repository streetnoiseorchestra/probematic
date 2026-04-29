(ns app.gigs.answer-link.service-test
  (:require
   [app.gigs.answer-link.service :as service]
   [app.gigs.domain :as domain]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn seed-gig-member! [conn gig-date]
  (let [gig-id    (random-uuid)
        member-id (random-uuid)]
    @(d/transact conn [{:section/name     "trumpets"
                        :section/active?  true
                        :section/position 10}])
    @(d/transact conn [{:member/member-id member-id
                        :member/name      "Trumpet Player"
                        :member/nick      (str "trumpet-" member-id)
                        :member/email     "trumpet@example.com"
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
  {:db           (d/db conn)
   :datomic-conn conn
   :params       {:answer "encrypted-answer"}
   :system       {:env {:app-secret-key "test-secret"}}
   ::answer      answer})

(def deps
  {:decrypt-answer (fn [req _token] (::answer req))
   :trigger-gig-edited! (fn [& _] nil)})

(deftest submit-answer-test
  (testing "creates attendance from an encrypted future-gig answer"
    (let [{:keys [conn]} (tc/new-system "gig-answer-link-attendance")
          {:keys [gig-id member-id]} (seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days)))
          edited_ (atom [])
          result  (service/submit-answer!
                   (assoc deps :trigger-gig-edited! (fn [_req gig-id edit-type]
                                                      (swap! edited_ conj [gig-id edit-type])))
                   (req conn {:gig/gig-id       gig-id
                              :member/member-id member-id
                              :attendance/plan  :plan/definitely-not}))
          attendance (q/attendance-for-gig (d/db conn) gig-id member-id)]
      (is (= gig-id (get-in result [:gig :gig/gig-id])))
      (is (= member-id (get-in result [:member :member/member-id])))
      (is (= :plan/definitely-not (:attendance/plan attendance)))
      (is (= [[gig-id :attendance]] @edited_))))

  (testing "does not change attendance for a past-gig answer"
    (let [{:keys [conn]} (tc/new-system "gig-answer-link-past")
          {:keys [gig-id member-id]} (seed-gig-member! conn (t/<< (t/date) (t/new-period 7 :days)))]
      (is (nil? (service/submit-answer!
                 deps
                 (req conn {:gig/gig-id       gig-id
                            :member/member-id member-id
                            :attendance/plan  :plan/definitely}))))
      (is (nil? (q/attendance-for-gig (d/db conn) gig-id member-id)))))

  (testing "creates and resets a pending reminder from an encrypted reminder answer"
    (let [{:keys [conn]} (tc/new-system "gig-answer-link-reminder")
          {:keys [gig-id member-id]} (seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days)))
          result (service/submit-answer!
                  deps
                  (req conn {:gig/gig-id       gig-id
                             :member/member-id member-id
                             :reminder         true}))
          reminder (q/gig-reminder-for (d/db conn) gig-id member-id)]
      (service/submit-answer!
       deps
       (req conn {:gig/gig-id       gig-id
                  :member/member-id member-id
                  :reminder         true}))
      (let [reset-reminder (q/gig-reminder-for (d/db conn) gig-id member-id)]
        (is (= gig-id (get-in result [:gig :gig/gig-id])))
        (is (= member-id (get-in result [:member :member/member-id])))
        (is (= :reminder-status/pending (:reminder/reminder-status reset-reminder)))
        (is (= :reminder-type/gig-attendance (:reminder/reminder-type reset-reminder)))
        (is (= (:reminder/reminder-id reminder)
               (:reminder/reminder-id reset-reminder)))))))
