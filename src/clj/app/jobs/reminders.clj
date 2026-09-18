(ns app.jobs.reminders
  (:require
   [app.datomic.shim :as datomic]
   [app.email.mailers :as mailers]
   [app.errors :as errors]
   [app.gigs.domain :as domain]
   [app.nexus :as nexus]
   [app.queries :as q]
   [app.write-runner :as writer]
   [ol.jobs-util :as jobs]
   [tick.core :as t]))

(defn- process-gig-reminder
  [db {:reminder/keys   [reminder-id member gig]
       _reminder-status :reminder/reminder-status
       _remind-at       :reminder/remind-at
       :as              _reminder}]
  ;; (tap> reminder)
  (if (or  (domain/cancelled? gig)
           (domain/in-past? gig))
    ;; gig not relevant anymore, cancel reminder
    {:reminder/reminder-status :reminder-status/cancelled :reminder/reminder-id reminder-id}
    (let [attendance (q/attendance-for-gig db (:gig/gig-id gig) (:member/member-id member))]
      (if (domain/no-response? (:attendance/plan attendance))
        ;; send reminder
        {:member member :gig gig :reminder-id reminder-id}
        ;; member already marked attendance, cancel reminder
        {:reminder/reminder-status :reminder-status/cancelled :reminder/reminder-id reminder-id}))))

(defn- group-processed-reminders [acc processed-reminder]
  ;; (tap> processed-reminder)
  (if (:reminder/reminder-status processed-reminder)
    (update acc :to-cancel conj (:reminder/reminder-id processed-reminder))
    (update-in acc [:to-send (-> processed-reminder :gig :gig/gig-id)] conj
               {:reminder-id (:reminder-id processed-reminder)
                :member      (:member processed-reminder)})))

(defn process-reminders [db reminders _as-of]
  ;; (tap> {:reminders reminders :as-of as-of})
  (->> reminders
       (remove #(nil? (:reminder/gig %)))
       (map (partial process-gig-reminder db))
       (reduce group-processed-reminders
               {:to-cancel #{} :to-send {}})))

(defn- queue-due-reminders! [system as-of]
  (writer/call!
   (get-in system [:frame-loop :write-runner])
   (fn []
     (let [conn    (get-in system [:datomic :conn])
           db      (datomic/db conn)
           {:keys [to-send to-cancel]}
           (process-reminders db (:reminder-type/gig-attendance (q/overdue-reminders-by-type db as-of)) as-of)
           tx-data (concat
                    (for [id to-cancel]
                      [:db/add [:reminder/reminder-id id] :reminder/reminder-status :reminder-status/cancelled])
                    (for [group (vals to-send) {:keys [reminder-id]} group]
                      [:db/add [:reminder/reminder-id reminder-id] :reminder/reminder-status :reminder-status/queued]))
           intents (mapv (fn [[gig-id group]]
                           (mailers/job {:current-locale :de} ::mailers/gig-reminder
                                        {:gig-id     gig-id
                                         :member-ids (vec (distinct (map #(get-in % [:member :member/member-id]) group)))}))
                         to-send)]
       (when (seq tx-data)
         (datomic/transact conn
                           {:tx-data (conj (nexus/batch-transactions [[tx-data {:jobs intents}]])
                                           {:db/id        "datomic.tx"    :audit/action ::queue-due-reminders
                                            :audit/origin :app.origin/job})}))))))

(defn send-reminders!
  ([system _]
   (send-reminders! system (t/instant) _))
  ([system as-of _]
   (try
     (queue-due-reminders! system as-of)
     :done
     (catch Throwable e
       (tap> e)
       (errors/report-error! e)))))

(defn make-reminder-job [system]
  (fn [{:job/keys [frequency initial-delay]}]
    (jobs/make-repeating-job (partial send-reminders! system) frequency initial-delay)))
