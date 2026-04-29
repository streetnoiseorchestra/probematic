(ns app.insurance.index.queries
  (:require
   [app.queries :as q]
   [tick.core :as t]))

(defn policy-totals [{:insurance.policy/keys [covered-instruments]}]
  {:total-instruments    (count covered-instruments)
   :total-needs-review   (count (filter #(= :instrument.coverage.status/needs-review
                                            (:instrument.coverage/status %))
                                        covered-instruments))
   :total-changed        (count (filter #(= :instrument.coverage.change/changed
                                            (:instrument.coverage/change %))
                                        covered-instruments))
   :total-removed        (count (filter #(= :instrument.coverage.change/removed
                                            (:instrument.coverage/change %))
                                        covered-instruments))
   :total-new            (count (filter #(= :instrument.coverage.change/new
                                            (:instrument.coverage/change %))
                                        covered-instruments))})

(defn policies [db]
  (mapv #(merge % (policy-totals %))
        (q/policies db)))

(defn retrieve-policy [db policy-id]
  (q/retrieve-policy db policy-id))

(defn active-policy [db]
  (q/insurance-policy-effective-as-of db (t/inst) q/policy-pattern))

(defn member-coverages [db member active-policy]
  (when (and member active-policy)
    (q/instruments-for-member-covered-by db member active-policy q/instrument-coverage-detail-pattern)))

(defn insurance-team-members [db]
  (:team/members (q/retrieve-team-type db :team.type/insurance)))
