(ns app.jobs.sync-songs
  (:require
   [app.cms :as cms]
   [app.jobs.integrations :as integrations]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.write-runner :as writer]
   [datomic.api :as d]
   [ol.jobs-util :as jobs]))

(defn song-sync-job [{:keys [frame-loop datomic] :as system} _]
  (if (:durable-jobs? frame-loop)
    (writer/call! (:write-runner frame-loop)
                  #(deref (d/transact (:conn datomic)
                                      (into [{:db/id "datomic.tx" :audit/action ::song-sync :audit/origin :app.origin/job}]
                                            (log-dispatch/intent-tx [integrations/sync-all-songs-job])))))
    (cms/sync-all-songs! system)))

(defn make-songs-sync-job [system]
  (fn [{:job/keys [frequency initial-delay]}]
    (jobs/make-repeating-job
     (partial #'song-sync-job system) frequency initial-delay)))
