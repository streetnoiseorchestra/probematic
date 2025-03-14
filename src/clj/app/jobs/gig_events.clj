(ns app.jobs.gig-events
  (:require
   [com.brunobonacci.mulog :as μ]
   [app.cms :as cms]
   [app.caldav :as caldav]
   [app.discourse :as discourse]
   [app.email :as email]
   [app.errors :as errors]
   [chime.core :as chime]
   [clojure.data]
   [app.datomic.shim :as datomic])
  (:import
   (java.time Instant)))

(defn update-system
  [{:keys [system]}]
  (assoc system :db (datomic/db (-> system :datomic :conn))))

(defn handle-gig-details-edited
  [req notify? takeover-topic? gig-id {:keys [gig-before gig db-after]}]
  (let [new-system (update-system req)]
    (when notify?
      (email/send-gig-updated! req gig-id
                               (keys (second (clojure.data/diff gig-before gig)))))
    (discourse/update-topic-for-gig! new-system gig-id takeover-topic?)
    (caldav/update-gig-event! new-system gig-id)))

(defn handle-gig-edited
  [req gig-id edit-type]
  (let [new-system (update-system req)]
    (discourse/update-topic-for-gig! new-system gig-id false)
    (caldav/update-gig-event! new-system gig-id)))

(defn handle-gig-created
  [req notify? thread? gig-id]
  (let [new-system (update-system req)]
    (when notify?
      (email/send-gig-created! req gig-id))
    (when thread?
      (discourse/create-topic-for-gig! new-system gig-id))
    (caldav/create-gig-event! new-system gig-id)))

(defn handle-gig-deleted
  [req gig-id]
  (let [new-system (update-system req)]
    (discourse/maybe-delete-topic-for-gig! new-system gig-id)
    (caldav/delete-gig-event! new-system gig-id)))

(defn exec-later
  [fn-name & args]
  (chime/chime-at [(.plusSeconds (Instant/now) 1)]
                  (fn [_]
                    (try
                      (apply fn-name args)
                      (catch Throwable e
                        (tap> e)
                        (errors/report-error! e))))))

(defn trigger-gig-details-edited
  [req notify? takeover-topic? transact-result]
  (exec-later handle-gig-details-edited req notify? takeover-topic? (-> transact-result :gig :gig/gig-id) transact-result))

(defn trigger-gig-edited
  [req gig-id edit-type]
  (exec-later handle-gig-edited req gig-id edit-type))

(defn trigger-gig-deleted
  [req gig-id]
  (exec-later handle-gig-deleted req gig-id))

(defn trigger-gig-created
  [req notify? thread? gig-id]
  (exec-later handle-gig-created req notify? thread? gig-id))

(defn handle-song-edited [req song-id]
  (μ/log ::handle-song-edited :song-id song-id)
  (cms/sync-song! (update-system req) song-id))

(defn trigger-song-edited
  [req song-id]
  (exec-later handle-song-edited req song-id))

(defn trigger-sync-all-songs [req]
  (exec-later (fn [req]
                (cms/sync-all-songs! (update-system req))) req))
