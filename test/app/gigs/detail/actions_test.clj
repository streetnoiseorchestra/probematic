(ns app.gigs.detail.actions-test
  (:require
   [app.gigs.detail.actions :as actions]
   [app.gigs.domain :as domain]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr [[k] & _]
  (case k
    :error/gig-attendance-invalid-plan "Invalid attendance plan."
    :error/gig-attendance-invalid-motivation "Invalid attendance motivation."
    :error/gig-attendance-missing-gig "Gig is required."
    :error/gig-attendance-missing-member "Member is required."
    (name k)))

(defn seed-gig-member! [conn]
  (let [gig-id    (random-uuid)
        member-id (random-uuid)]
    @(d/transact conn [{:section/name     "flute"
                        :section/active?  true
                        :section/position 10}])
    @(d/transact conn [{:member/member-id member-id
                        :member/name      "Flute Player"
                        :member/nick      (str "flute-" member-id)
                        :member/active?   true
                        :member/section   [:section/name "flute"]}
                       (domain/gig->db {:gig/gig-id   gig-id
                                        :gig/title    "Attendance Gig"
                                        :gig/status   :gig.status/confirmed
                                        :gig/gig-type :gig.type/gig
                                        :gig/date     (t/date "2026-05-01")
                                        :gig/location "Somewhere"
                                        :gig/call-time (t/time "18:00")})])
    {:gig-id gig-id :member-id member-id}))

(defn seed-attendance! [conn gig-id member-id attrs]
  @(d/transact conn [(merge {:attendance/gig+member (q/gig+member gig-id member-id)
                             :attendance/gig        [:gig/gig-id gig-id]
                             :attendance/member     [:member/member-id member-id]
                             :attendance/updated    #inst "2026-01-01T00:00:00.000-00:00"
                             :attendance/section    [:section/name "flute"]}
                            attrs)]))

(defn state [conn]
  {:tr tr
   :db (d/db conn)})

(defn signals
  [gig-id member-id attrs]
  {:gig-attendance (merge {:gig-id    (str gig-id)
                           :member-id (str member-id)}
                          attrs)})

(defn attendance-ref [gig-id member-id]
  [:attendance/gig+member (q/gig+member gig-id member-id)])

(defn create-base [gig-id member-id]
  {:attendance/gig+member (q/gig+member gig-id member-id)
   :attendance/gig        [:gig/gig-id gig-id]
   :attendance/member     [:member/member-id member-id]
   :attendance/updated    :db/now
   :attendance/section    [:section/name "flute"]})

(defn edited-effect [gig-id]
  {:on-success [[:app.gigs/trigger-gig-edited gig-id :attendance]]})

(deftest update-attendance-plan-action-test
  (testing "creates an attendance entity when the member has no attendance yet"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-plan-create")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (is (= [[:db/transact
               [(assoc (create-base gig-id member-id)
                       :attendance/plan :plan/definitely)]
               (edited-effect gig-id)]]
             (actions/update-attendance-plan-action
              (state conn)
              (signals gig-id member-id {:plan "definitely"}))))))

  (testing "updates an existing attendance plan and touches the row"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-plan-update")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (seed-attendance! conn gig-id member-id {:attendance/plan :plan/unknown})
      (is (= [[:db/transact
               [[:db/add (attendance-ref gig-id member-id) :attendance/plan :plan/definitely-not]
                [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]]
               (edited-effect gig-id)]]
             (actions/update-attendance-plan-action
              (state conn)
              (signals gig-id member-id {:plan "definitely-not"}))))))

  (testing "invalid plan values do not transact"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-plan-invalid")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :_error]
               {:error "Invalid attendance plan."}]]
             (actions/update-attendance-plan-action
              (state conn)
              (signals gig-id member-id {:plan "maybe"})))))))

(deftest update-attendance-motivation-action-test
  (testing "creates an attendance entity when the member has no attendance yet"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-motivation-create")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (is (= [[:db/transact
               [(assoc (create-base gig-id member-id)
                       :attendance/motivation :motivation/high)]
               (edited-effect gig-id)]]
             (actions/update-attendance-motivation-action
              (state conn)
              (signals gig-id member-id {:motivation "high"}))))))

  (testing "updates an existing attendance motivation and touches the row"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-motivation-update")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (seed-attendance! conn gig-id member-id {:attendance/motivation :motivation/none})
      (is (= [[:db/transact
               [[:db/add (attendance-ref gig-id member-id) :attendance/motivation :motivation/very-high]
                [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]]
               (edited-effect gig-id)]]
             (actions/update-attendance-motivation-action
              (state conn)
              (signals gig-id member-id {:motivation "very-high"}))))))

  (testing "invalid motivation values do not transact"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-motivation-invalid")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :_error]
               {:error "Invalid attendance motivation."}]]
             (actions/update-attendance-motivation-action
              (state conn)
              (signals gig-id member-id {:motivation "too-much"})))))))

(deftest attendance-comment-page-state-action-test
  (let [gig-id    (random-uuid)
        member-id (random-uuid)]
    (is (= [[:app.datastar/assoc-state
             [:gig-detail :attendance :comment-edit]
             {:gig-id (str gig-id) :member-id (str member-id) :comment "late"}]]
           (actions/open-attendance-comment-action
            {:tr tr}
            (signals gig-id member-id {:comment "late"}))))
    (is (= [[:app.datastar/assoc-state
             [:gig-detail :attendance :comment-edit]
             nil]]
           (actions/close-attendance-comment-action {:tr tr} {})))))

(deftest update-attendance-comment-action-test
  (testing "creates an attendance entity for a nonblank comment when none exists"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-comment-create")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (is (= [[:db/transact
               [(assoc (create-base gig-id member-id)
                       :attendance/comment "I will be late")]
               (edited-effect gig-id)]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :comment-edit]
               nil]]
             (actions/update-attendance-comment-action
              (state conn)
              (signals gig-id member-id {:comment "I will be late"}))))))

  (testing "updates an existing nonblank comment"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-comment-update")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (seed-attendance! conn gig-id member-id {:attendance/comment "old"})
      (is (= [[:db/transact
               [[:db/add (attendance-ref gig-id member-id) :attendance/comment "new"]
                [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]]
               (edited-effect gig-id)]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :comment-edit]
               nil]]
             (actions/update-attendance-comment-action
              (state conn)
              (signals gig-id member-id {:comment "new"}))))))

  (testing "retracts an existing comment when the submitted comment is blank"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-comment-retract")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (seed-attendance! conn gig-id member-id {:attendance/comment "old"})
      (is (= [[:db/transact
               [[:db/retract (attendance-ref gig-id member-id) :attendance/comment]
                [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]]
               (edited-effect gig-id)]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :comment-edit]
               nil]]
             (actions/update-attendance-comment-action
              (state conn)
              (signals gig-id member-id {:comment "  "}))))))

  (testing "does not create an attendance entity for a blank comment"
    (let [{:keys [conn]}         (tc/new-system "gig-attendance-comment-nop")
          {:keys [gig-id member-id]} (seed-gig-member! conn)]
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :comment-edit]
               nil]]
             (actions/update-attendance-comment-action
              (state conn)
              (signals gig-id member-id {:comment ""})))))))

(deftest switch-attendance-comment-action-test
  (testing "saves the current draft and opens the requested row in one action"
    (let [{:keys [conn]} (tc/new-system "gig-attendance-comment-switch")
          {:keys [gig-id member-id]} (seed-gig-member! conn)
          next-member-id (random-uuid)]
      @(d/transact conn [{:member/member-id next-member-id
                          :member/name      "Next Player"
                          :member/nick      (str "next-" next-member-id)
                          :member/active?   true
                          :member/section   [:section/name "flute"]}])
      (seed-attendance! conn gig-id member-id {:attendance/comment "old"})
      (is (= [[:db/transact
               [[:db/add (attendance-ref gig-id member-id) :attendance/comment "draft"]
                [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]]
               (edited-effect gig-id)]
              [:app.datastar/assoc-state
               [:gig-detail :attendance :comment-edit]
               {:gig-id (str gig-id) :member-id (str next-member-id) :comment "next"}]
              [:app.datastar/merge-signals {:gig-attendance {:switching-comment false}}]]
             (actions/switch-attendance-comment-action
              (state conn)
              {:gig-attendance {:comment-gig-id    (str gig-id)
                                :comment-member-id (str member-id)
                                :comment           "draft"
                                :next-gig-id       (str gig-id)
                                :next-member-id    (str next-member-id)
                                :next-comment      "next"}})))))

  (testing "opens the requested row when the current blank draft has no attendance"
    (let [{:keys [conn]} (tc/new-system "gig-attendance-comment-switch-noop")
          {:keys [gig-id member-id]} (seed-gig-member! conn)
          next-member-id (random-uuid)]
      @(d/transact conn [{:member/member-id next-member-id
                          :member/name      "Next Player"
                          :member/nick      (str "next-" next-member-id)
                          :member/active?   true
                          :member/section   [:section/name "flute"]}])
      (is (= [[:app.datastar/assoc-state
               [:gig-detail :attendance :comment-edit]
               {:gig-id (str gig-id) :member-id (str next-member-id) :comment ""}]
              [:app.datastar/merge-signals {:gig-attendance {:switching-comment false}}]]
             (actions/switch-attendance-comment-action
              (state conn)
              {:gig-attendance {:comment-gig-id    (str gig-id)
                                :comment-member-id (str member-id)
                                :comment           ""
                                :next-gig-id       (str gig-id)
                                :next-member-id    (str next-member-id)
                                :next-comment      ""}}))))))

(deftest send-reminder-to-all-action-test
  (let [gig-id #uuid "01844740-3eed-856d-84c1-c26f07068210"
        now    #inst "2026-04-28T10:00:00.000-00:00"]
    (is (= [[:app.gigs/send-reminder-to-all gig-id]
            [:app.datastar/assoc-state
             [:gig-detail :attendance :remind-all-sent-at]
             now]]
           (actions/send-reminder-to-all-action
            {:tr tr :now now}
            {:gig-attendance {:gig-id (str gig-id)}})))))

(deftest toggle-attendance-committed-action-test
  (is (= [[:app.datastar/assoc-state
           [:gig-detail :attendance :show-committed?]
           true]]
         (actions/toggle-attendance-committed-action
          {:tr tr}
          {:gig-attendance {:show-committed true}})))
  (is (= [[:app.datastar/assoc-state
           [:gig-detail :attendance :show-committed?]
           false]]
         (actions/toggle-attendance-committed-action
          {:tr tr}
          {:gig-attendance {:show-committed false}}))))
