(ns app.jobs.poll-housekeeping
  (:require
   [com.brunobonacci.mulog :as μ]
   [app.datomic :as d]
   [app.errors :as errors]
   [app.poll.queries :as poll.queries]
   [app.datomic.shim :as datomic]
   [app.poll.domain :as domain]
   [ol.jobs-util :as jobs]
   [tick.core :as t]))

(defn- poll-housekeeping-job
  [{:keys [datomic]} _]
  (try
    (let [conn (:conn datomic)
          open-polls (poll.queries/find-open-polls (datomic/db conn))
          now (t/instant)
          polls-to-close (filter (fn [{:poll/keys [closes-at]}]
                                   (let [closes-at (domain/closes-at-instant closes-at)]
                                     (t/< closes-at now))) open-polls)
          polls-to-close-txns (mapcat #(into [] %)
                                      (map (fn [poll]
                                             (μ/log ::closing-poll
                                                    :poll-id (:poll/poll-id poll)
                                                    :poll-title (:poll/title poll)
                                                    :poll-closes-at (:poll/closes-at poll)
                                                    :poll-closes-at-inst (domain/closes-at-instant (:poll/closes-at poll))
                                                    :now now)
                                             [[:db/add (d/ref poll) :poll/poll-status :poll.status/closed]
                                              [:db/add (d/ref poll) :poll/closes-at (domain/closes-at-inst (t/date-time))]]) polls-to-close))]

      (datomic/transact conn  {:tx-data polls-to-close-txns})
      :done)
    (catch Throwable e
      (tap> e)
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
    (def system {:datomic {:conn conn}
                 :redis (-> state/system :app.ig/redis)
                 :i18n-langs (-> state/system :app.ig/i18n-langs)
                 :env (-> state/system :app.ig/env)}))

  (poll-housekeeping-job system nil) ;; rcf

  ;;
  )
