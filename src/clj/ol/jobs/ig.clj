(ns ol.jobs.ig
  (:require [ol.jobs-util :as jobs]
            [integrant.core :as ig]))

(defmethod ig/init-key :ol.ig/jobs
  [_ {:keys [job-defs env]}]
  (jobs/start-jobs job-defs (:jobs env)))

(defmethod ig/halt-key! :ol.ig/jobs
  [_ _]
  (jobs/stop-all-schedules))
