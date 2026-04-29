(ns app.gigs.effects
  (:require
   [app.email :as email]
   [app.jobs.gig-events :as gig.events]
   [app.probeplan.stats :as stats]
   [app.queries :as q]))

(defn- conn [{:keys [request system]}]
  (or (:datomic-conn request)
      (-> system :datomic :conn)))

(defn trigger-gig-details-edited-fx
  [{:keys [dispatch-data]} {:keys [request]} gig-id notify? takeover-topic?]
  (let [{:keys [db-before db-after]} (:tx-result dispatch-data)
        result {:gig        (q/retrieve-gig db-after gig-id)
                :gig-before (q/retrieve-gig db-before gig-id)
                :db-after   db-after}]
    (gig.events/trigger-gig-details-edited request notify? takeover-topic? result))
  nil)

(defn trigger-gig-created-fx
  [_ {:keys [request]} gig-id notify? thread?]
  (gig.events/trigger-gig-created request notify? thread? gig-id)
  nil)

(defn trigger-gig-deleted-fx
  [_ context gig-id recalc-play-stats?]
  (when recalc-play-stats?
    (stats/calc-play-stats-in-bg! (conn context)))
  (gig.events/trigger-gig-deleted (:request context) gig-id)
  nil)

(defn trigger-gig-edited-fx
  [_ {:keys [request]} gig-id edit-type]
  (gig.events/trigger-gig-edited request gig-id edit-type)
  nil)

(defn recalc-play-stats-fx
  [_ context]
  (stats/calc-play-stats-in-bg! (conn context))
  nil)

(defn send-reminder-to-all-fx
  [_ {:keys [request]} gig-id]
  (email/send-gig-reminder-to-all! request gig-id)
  nil)
