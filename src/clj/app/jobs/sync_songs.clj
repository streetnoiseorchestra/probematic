(ns app.jobs.sync-songs
  (:require
   [app.datomic :as db]
   [app.jobs.integrations :as integrations]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.write-runner :as writer]
   [ol.jobs-util :as jobs]))

(defn song-sync-job [{:keys [frame-loop datomic]} _]
  (writer/call! (:write-runner frame-loop)
                #(db/transact (:conn datomic)
                              {:tx-data (log-dispatch/intent-tx [integrations/sync-all-songs-job])
                               :audit   {:audit/action ::song-sync
                                         :audit/origin :app.origin/job}})))

(defn make-songs-sync-job [system]
  (fn [{:job/keys [frequency initial-delay]}]
    (jobs/make-repeating-job
     (partial #'song-sync-job system) frequency initial-delay)))
