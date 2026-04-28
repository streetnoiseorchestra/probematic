(ns app.gigs.detail.queries
  (:require
   [app.gigs.domain :as domain]
   [app.queries :as q]
   [medley.core :as m]))

(defn attendance-data [db {:gig/keys [gig-id] :as gig} show-committed?]
  (let [archived?   (domain/gig-archived? gig)
        attendances (if archived?
                      (q/attendances-for-gig db gig-id)
                      (q/attendance-for-gig-with-all-active-members db gig-id))]
    {:archived?   archived?
     :attendances attendances
     :sections    (q/attendance-plans-by-section-for-gig
                   db
                   attendances
                   (when (and (not archived?) show-committed?)
                     :committed-only?))
     :summary     (->> attendances
                       (group-by :attendance/plan)
                       (m/map-vals count))}))
