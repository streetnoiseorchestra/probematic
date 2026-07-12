(ns app.dashboard.queries-test
  (:require
   [app.dashboard.queries :as queries]
   [app.gigs.domain :as gig.domain]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn seed-member! [conn member-id]
  @(d/transact conn [{:section/name     "flute"
                      :section/active?  true
                      :section/position 10}])
  @(d/transact conn [{:member/member-id member-id
                      :member/name      "Flute Player"
                      :member/nick      "flute"
                      :member/active?   true
                      :member/section   [:section/name "flute"]}]))

(defn seed-gig! [conn title date]
  (let [gig-id (random-uuid)]
    @(d/transact conn [(gig.domain/gig->db {:gig/gig-id    gig-id
                                            :gig/title     title
                                            :gig/status    :gig.status/confirmed
                                            :gig/gig-type  :gig.type/gig
                                            :gig/date      date
                                            :gig/location  "Somewhere"
                                            :gig/call-time (t/time "18:00")})])
    gig-id))

(defn seed-attendance! [conn gig-id member-id attrs]
  @(d/transact conn [(merge {:attendance/gig+member (q/gig+member gig-id member-id)
                             :attendance/gig        [:gig/gig-id gig-id]
                             :attendance/member     [:member/member-id member-id]
                             :attendance/updated    #inst "2026-01-01T00:00:00.000-00:00"
                             :attendance/section    [:section/name "flute"]}
                            attrs)]))

(defn seed-insurance-team! [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn seed-policy! [conn {:keys [policy-id name coverages]}]
  (let [coverage-tempids (mapv (fn [_] (str "coverage-" (random-uuid))) coverages)]
    @(d/transact conn (concat
                       (mapv (fn [tempid coverage]
                               (merge {:db/id                       tempid
                                       :instrument.coverage/coverage-id (random-uuid)}
                                      coverage))
                             coverage-tempids
                             coverages)
                       [{:insurance.policy/policy-id           policy-id
                         :insurance.policy/name                name
                         :insurance.policy/status              :insurance.policy.status/draft
                         :insurance.policy/currency            :currency/EUR
                         :insurance.policy/effective-at        #inst "2026-01-01T00:00:00.000-00:00"
                         :insurance.policy/effective-until     #inst "2026-12-31T00:00:00.000-00:00"
                         :insurance.policy/premium-factor      0.01M
                         :insurance.policy/covered-instruments coverage-tempids}]))))

(defn titles [gigs]
  (mapv :gig/title gigs))

(defn policy-names [policies]
  (mapv :insurance.policy/name policies))

(deftest dashboard-gig-buckets-test
  (testing "answered and unanswered dashboard gigs exclude cancelled and past gigs"
    (let [{:keys [conn member-id]} (tc/new-system "dashboard-gig-buckets")]
      (seed-member! conn member-id)
      (let [answered-id      (seed-gig! conn "Answered future" (t/date "2099-05-01"))
            _unanswered-id   (seed-gig! conn "Unanswered future" (t/date "2099-05-02"))
            unknown-id       (seed-gig! conn "Unknown future" (t/date "2099-05-03"))
            cancelled-id     (seed-gig! conn "Cancelled future" (t/date "2099-05-04"))
            past-id          (seed-gig! conn "Past answered" (t/date "2000-05-01"))]
        (seed-attendance! conn answered-id member-id {:attendance/plan :plan/definitely
                                                      :attendance/motivation :motivation/none})
        (seed-attendance! conn unknown-id member-id {:attendance/plan :plan/unknown})
        (seed-attendance! conn cancelled-id member-id {:attendance/plan :plan/definitely})
        @(d/transact conn [[:db/add [:gig/gig-id cancelled-id] :gig/status :gig.status/cancelled]])
        (seed-attendance! conn past-id member-id {:attendance/plan :plan/definitely})
        (let [db      (d/db conn)
              member  (q/retrieve-member db member-id)
              buckets (queries/gig-buckets db member)]
          (is (= ["Answered future"] (titles (:answered buckets))))
          (is (= ["Unanswered future" "Unknown future"] (titles (:unanswered buckets))))
          (is (= ["Answered future" "Unanswered future" "Unknown future"]
                 (titles (:upcoming buckets))))
          (is (= [:plan/definitely :plan/no-response :plan/unknown]
                 (mapv #(get-in % [:attendance :attendance/plan])
                       (:upcoming buckets)))))))))

(deftest policies-with-todos-test
  (testing "returns only policies with coverages needing review and includes dashboard totals"
    (let [{:keys [conn]} (tc/new-system "dashboard-insurance-todos")
          todo-policy-id (random-uuid)]
      (seed-policy! conn {:policy-id todo-policy-id
                          :name      "Policy with todos"
                          :coverages [{:instrument.coverage/status :instrument.coverage.status/needs-review
                                       :instrument.coverage/change :instrument.coverage.change/changed}
                                      {:instrument.coverage/status :instrument.coverage.status/needs-review
                                       :instrument.coverage/change :instrument.coverage.change/new}
                                      {:instrument.coverage/status :instrument.coverage.status/reviewed
                                       :instrument.coverage/change :instrument.coverage.change/removed}]})
      (seed-policy! conn {:policy-id (random-uuid)
                          :name      "Policy without todos"
                          :coverages [{:instrument.coverage/status :instrument.coverage.status/reviewed
                                       :instrument.coverage/change :instrument.coverage.change/changed}]})
      (let [policies (queries/policies-with-todos (d/db conn))]
        (is (= ["Policy with todos"] (policy-names policies)))
        (is (= {:total-needs-review 2
                :total-changed      1
                :total-new          1
                :total-removed      1}
               (select-keys (first policies)
                            [:total-needs-review
                             :total-changed
                             :total-new
                             :total-removed])))))))

(deftest dashboard-data-insurance-todos-visibility-test
  (testing "insurance todos are present only for insurance team members"
    (let [{:keys [conn member-id]} (tc/new-system "dashboard-insurance-todos-visibility")]
      (seed-member! conn member-id)
      (seed-policy! conn {:policy-id (random-uuid)
                          :name      "Insurance Team Work"
                          :coverages [{:instrument.coverage/status :instrument.coverage.status/needs-review
                                       :instrument.coverage/change :instrument.coverage.change/new}]})
      (let [db     (d/db conn)
            member (q/retrieve-member db member-id)]
        (is (= [] (:insurance-todos (queries/dashboard-data db member)))))
      (seed-insurance-team! conn member-id)
      (let [db     (d/db conn)
            member (q/retrieve-member db member-id)]
        (is (= ["Insurance Team Work"]
               (policy-names (:insurance-todos (queries/dashboard-data db member)))))))))
