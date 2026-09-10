(ns ol.jobs-util
  (:require
   [clojure.tools.logging :as log]
   [nano-id.core :refer [nano-id]]
   [chime.core :as chime]
   [tick.core :as t]))
(declare stop-all-schedules)
(defonce schedules (atom []))

(defn time-from-now
  "A time from now in duration

   Args:
   duration - A duration to offset now from
   Example:
   (time-from-now (t/new-duration 5 :seconds))
   "
  [duration]
  (t/>> (-> (t/now)
            (t/in "America/Los_Angeles"))
        duration))

(defn create-schedule
  "Create a schedule that calls a handler

   Required keys:

   :name - Name for this schedule
   :handler - Handler fn to be called at the schedule
   :frequency - Duration between calls, unless :times is supplied
   Optional keys:
   :start-at - An inst to start the schedule
   :times - Explicit sequence of times instead of a fixed frequency

   Example:

   (create-schedule
     :handler (fn [time] (tap> time))
     :frequency (t/new-duration 10 :seconds)
     :start-at (time-from-now (t/new-duration 5 :seconds)))
   "
  [& {:keys [name handler frequency start-at times]
      :or   {start-at (t/now)}}]
  (let [schedule-id (nano-id)
        gate        (Object.)
        stopping?   (atom false)]
    (locking gate
      (let [schedule (chime/chime-at
                      (or times (chime/periodic-seq start-at frequency))
                      (fn [time]
                        (locking gate
                          (when-not @stopping? (handler time))))
                      {:on-finished #(swap! schedules (fn [entries] (filterv (fn [entry] (not= schedule-id (:id entry))) entries)))})]
        (when-not (realized? schedule)
          (swap! schedules conj {:id         schedule-id
                                 :name       name
                                 :frequency  frequency
                                 :started-at start-at
                                 :gate       gate
                                 :stopping?  stopping?
                                 :closeable  schedule}))))))

(defn stop-schedule
  "Stop a running schedule based on it's id"
  [schedule-id]
  (when-let [{:keys [gate stopping? closeable]} (first (filter #(= schedule-id (:id %)) @schedules))]
    (when stopping? (reset! stopping? true))
    (when gate (locking gate nil))
    (.close ^java.lang.AutoCloseable closeable)
    (swap! schedules (fn [entries] (filterv #(not= schedule-id (:id %)) entries)))))

(defn stop-all-schedules
  "Stop all running schedules"
  []
  (doseq [schedule @schedules]
    (stop-schedule (:id schedule))))

(defn current-schedules
  []
  (map #(select-keys % [:id :name :frequency]) @schedules))

(defn healthy?
  []
  (boolean (seq @schedules)))

(defn make-repeating-job [handler frequency initial-delay]
  (create-schedule
   :handler handler
   :frequency (apply t/new-duration frequency)
   :start-at (time-from-now (apply t/new-duration initial-delay))))

(defn make-one-shot-job
  "Schedules `handler` once after `initial-delay`.

  `initial-delay` is a two-item Tick duration argument such as
  `[30 :seconds]`.
  The completed schedule removes itself from the live schedule registry."
  [handler initial-delay]
  (let [start-at (time-from-now (apply t/new-duration initial-delay))]
    (create-schedule :handler handler :times [start-at] :start-at start-at)))

(defn start-jobs [jobs-def jobs-config]
  (run!
   (fn [job-key]
     (if-let [job-fn (get jobs-def job-key)]
       (job-fn (job-key jobs-config))
       (log/warn (format "job definition for %s not found. skipping job." (str job-key)))))

   (keys jobs-config)))

(comment
  (create-schedule
   :handler (fn [time] (println "ping" time))
   :frequency (t/new-duration 1 :seconds)
   :start-at (time-from-now (t/new-duration 1 :seconds)))

  @schedules

  (stop-all-schedules)
  (stop-schedule "lp5__ebnbiTLWGzXS2pb6")

  (tap> @schedules)

  (start-jobs {:ping (fn [_] (println "ping"))}
              {:ping {:frequency     [2 :seconds]
                      :initial-delay [0 :seconds]}})
  ;;
  )
