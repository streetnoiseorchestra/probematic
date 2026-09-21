(ns app.jobs.integrations
  "Runs durable gig and song integration requests outside the application writer."
  (:require
   [app.caldav :as caldav]
   [app.cms :as cms]
   [app.config :as config]
   [app.discourse :as discourse]
   [app.queries :as q]
   [app.write-runner :as writer]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(defn gig-job [gig-id options]
  ["sync-gig" (assoc options :gig-id gig-id) {:queue "integrations" :max-attempts 25}])

(defn gig-update-options [state gig-id _trigger]
  (if (config/prod-mode? (:env state))
    {:jobs [(gig-job gig-id {:operation :updated})]}
    {}))

(defn song-job [song-id]
  ["sync-song" {:song-id song-id} {:queue "integrations" :max-attempts 25}])

(def sync-all-songs-job ["sync-all-songs" {} {:queue "integrations" :max-attempts 25}])

(defn- sync-gig! [system {:keys [gig-id operation source-t thread? takeover-topic?]}]
  (case operation
    :deleted (do
               (discourse/maybe-delete-topic-for-gig! system gig-id)
               (caldav/delete-gig-event! system gig-id))
    (:created :updated)
    (when (q/retrieve-gig (:db system) gig-id)
      (if (= :created operation)
        (when thread? (discourse/create-topic-for-gig! system gig-id source-t))
        (discourse/update-topic-for-gig! system gig-id (boolean takeover-topic?) source-t))
      (caldav/update-gig-event! system gig-id))))

(defn handle! [system kind client {:keys [id args]}]
  (when (config/prod-mode? (:env system))
    (let [system (assoc system :db (writer/call! (get-in system [:frame-loop :write-runner])
                                                 #(d/db (get-in system [:datomic :conn]))))]
      (case kind
        :gig (sync-gig! system args)
        :song (when (q/retrieve-song (:db system) (:song-id args))
                (cms/sync-song! system (:song-id args)))
        :all-songs (cms/sync-all-songs! system))))
  (drip/complete-job client id))
