(ns app.dashboard.queries
  (:require
   [app.datomic :as d]
   [app.insurance.survey.queries :as insurance-survey.queries]
   [app.poll.queries :as poll.queries]
   [app.queries :as q]))

(defn- attach-attendance [db member {:gig/keys [gig-id] :as gig}]
  (assoc gig :attendance
         (q/attendance-for-gig db gig-id (:member/member-id member))))

(defn answered-gigs
  "Returns future non-cancelled gigs where `member` has supplied a concrete plan."
  [db member]
  (assert member)
  (->>
   (q/results->gigs (d/q '[:find (pull ?gig pattern)
                           :in $ ?member ?reference-time pattern
                           :where
                           [?gig :gig/date ?date]
                           [(>= ?date ?reference-time)]
                           (not [?gig :gig/status :gig.status/cancelled])
                           [?a :attendance/gig ?gig]
                           [?a :attendance/member ?member]
                           [?a :attendance/plan ?plan]
                           [(!= ?plan :plan/no-response)]
                           [(!= ?plan :plan/unknown)]]
                         db (d/ref member) (q/date-midnight-today!) q/gig-pattern))
   (mapv (partial attach-attendance db member))))

(defn unanswered-gigs
  "Returns future non-cancelled gigs where `member` still needs to answer."
  [db member]
  (assert member)
  (let [gigs-with-no-attendance
        (->>
         (d/q '[:find (pull ?gig pattern)
                :in $ ?member ?reference-time pattern
                :where
                [?gig :gig/date ?date]
                [(>= ?date ?reference-time)]
                [?gig :gig/gig-id ?gig-id]
                (not [?gig :gig/status :gig.status/cancelled])
                (not-join [?gig ?member]
                          [?a :attendance/gig ?gig]
                          [?a :attendance/member ?member])]
              db (d/ref member) (q/date-midnight-today!) q/gig-detail-pattern)
         q/results->gigs
         (map (fn [gig]
                (assoc gig :attendance {:attendance/section (:member/section member)
                                        :attendance/member  member
                                        :attendance/plan    :plan/no-response}))))
        gigs-with-unknown-attendance
        (->>
         (d/q '[:find (pull ?gig pattern)
                :in $ ?member ?reference-time pattern
                :where
                [?gig :gig/date ?date]
                (not [?gig :gig/status :gig.status/cancelled])
                [(>= ?date ?reference-time)]
                [?a :attendance/gig ?gig]
                [?a :attendance/member ?member]
                (or
                 [(missing? $ ?a :attendance/plan)]
                 [?a :attendance/plan :plan/no-response]
                 [?a :attendance/plan :plan/unknown])]
              db (d/ref member) (q/date-midnight-today!) q/gig-detail-pattern)
         q/results->gigs
         (map (partial attach-attendance db member)))]
    (->> (concat gigs-with-no-attendance gigs-with-unknown-attendance)
         (sort-by :gig/call-time)
         (sort-by :gig/date)
         vec)))

(defn gig-buckets [db member]
  (let [answered   (answered-gigs db member)
        unanswered (unanswered-gigs db member)]
    {:answered   answered
     :unanswered unanswered
     :upcoming   (->> (concat answered unanswered)
                      (sort-by :gig/call-time)
                      (sort-by :gig/date)
                      vec)}))

(defn- policy-totals [{:insurance.policy/keys [covered-instruments]}]
  {:total-needs-review (count (filter #(= :instrument.coverage.status/needs-review
                                          (:instrument.coverage/status %))
                                      covered-instruments))
   :total-changed      (count (filter #(= :instrument.coverage.change/changed
                                          (:instrument.coverage/change %))
                                      covered-instruments))
   :total-removed      (count (filter #(= :instrument.coverage.change/removed
                                          (:instrument.coverage/change %))
                                      covered-instruments))
   :total-new          (count (filter #(= :instrument.coverage.change/new
                                          (:instrument.coverage/change %))
                                      covered-instruments))})

(defn policies-with-todos [db]
  (->> (q/policies db)
       (mapv (fn [policy]
               (merge policy (policy-totals policy))))
       (filterv #(pos? (:total-needs-review %)))))

(defn dashboard-data [db member]
  (assoc (gig-buckets db member)
         :ledger (q/retrieve-ledger db (:member/member-id member))
         :insurance-surveys (insurance-survey.queries/pending-responses-for-member
                             db member)
         :unanswered-polls (poll.queries/unanswered-open-polls db member)
         :insurance-todos (if (q/insurance-team-member? db member)
                            (policies-with-todos db)
                            [])))
