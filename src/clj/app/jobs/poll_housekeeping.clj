(ns app.jobs.poll-housekeeping
  (:require
   [app.datomic :as db]
   [app.datomic.shim :as datomic]
   [app.errors :as errors]
   [app.poll.queries :as queries]
   [app.write-runner :as writer]
   [com.brunobonacci.mulog :as μ]
   [ol.jobs-util :as jobs]
   [tick.core :as t]))

(defn close-expired-polls! [{:keys [datomic frame-loop]} now]
  (let [conn   (:conn datomic)
        close! (fn []
                 (let [tx-data (mapcat
                                (fn [{:poll/keys [poll-id]}]
                                  (μ/log ::closing-poll :poll-id poll-id :now now)
                                  [[:db/add [:poll/poll-id poll-id] :poll/poll-status :poll.status/closed]
                                   [:db/add [:poll/poll-id poll-id] :poll/closes-at (t/inst now)]])
                                (queries/expired-open-polls (datomic/db conn) now))]
                   (when (seq tx-data)
                     (db/transact conn
                                  {:tx-data (vec tx-data)
                                   :audit   {:audit/action ::close-expired-polls
                                             :audit/origin :app.origin/job}}))))]
    (if-let [control (:write-runner frame-loop)]
      (writer/call! control close!)
      (close!))))

(defn- poll-housekeeping-job [system _]
  (try
    (close-expired-polls! system (t/instant))
    :done
    (catch Throwable e
      (errors/report-error! e))))

(defn make-poll-housekeeping-job
  [system]
  (fn [{:job/keys [frequency initial-delay]}]
    (jobs/make-repeating-job (partial poll-housekeeping-job system) frequency initial-delay)))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))
    (def system {:datomic    {:conn conn}
                 :i18n-langs (-> state/system :app.ig/i18n-langs)
                 :env        (-> state/system :app.ig/env)}))

  (poll-housekeeping-job system nil) ;; rcf

  ;;
  )
