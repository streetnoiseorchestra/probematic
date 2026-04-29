(ns app.songs.effects
  (:require
   [app.jobs.gig-events :as gig.events]
   [app.probeplan.stats :as stats]))

(defn- conn [context]
  (-> context :system :datomic :conn))

(defn trigger-song-edited-fx
  [_ {request :request} song-id]
  (gig.events/trigger-song-edited request song-id))

(defn recalc-play-stats-fx
  [_ context]
  (stats/calc-play-stats-in-bg! (conn context)))
