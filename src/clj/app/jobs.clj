(ns app.jobs
  (:require
   [app.jobs.avatar-cutover :refer [make-avatar-cutover-job]]
   [app.jobs.sync-songs :refer [make-songs-sync-job]]
   [app.jobs.probe-housekeeping :refer [make-probe-housekeeping-job]]
   [app.jobs.poll-housekeeping :refer [make-poll-housekeeping-job]]
   [app.jobs.reminders :refer [make-reminder-job]]
   [app.jobs.ping :refer [make-job-ping]]))

;; Add your job constructors here
(defn job-defs [opts]
  {:job/ping               make-job-ping
   :job/probe-housekeeping (make-probe-housekeeping-job opts)
   :job/sync-songs          (make-songs-sync-job opts)
   :job/poll-housekeeping  (make-poll-housekeeping-job opts)
   :job/reminders          (make-reminder-job opts)
   :job/avatar-cutover     (make-avatar-cutover-job opts)})
