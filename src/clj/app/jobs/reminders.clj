(ns app.jobs.reminders
  (:require
   [app.email :as email]
   [app.gigs.domain :as domain]
   [app.errors :as errors]
   [app.queries :as q]
   [app.datomic.shim :as datomic]
   [ol.jobs-util :as jobs]
   [tick.core :as t]))

(defn- process-gig-reminder
  [db {:reminder/keys [reminder-id member gig]
       _reminder-status :reminder/reminder-status
       _remind-at :reminder/remind-at
       :as _reminder}]
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
                :member (:member processed-reminder)})))

(defn process-reminders [db reminders _as-of]
  ;; (tap> {:reminders reminders :as-of as-of})
  (->> reminders
       (remove #(nil? (:reminder/gig %)))
       (map (partial process-gig-reminder db))
       (reduce group-processed-reminders
               {:to-cancel #{} :to-send {}})))

(defn- send-reminder-for-gig! [sys [gig-id reminder-members]]
  ;; (tap> {:gig gig-id :m reminder-members})
  (email/send-gig-reminder-to! sys gig-id (mapv :member reminder-members))
  (mapv :reminder-id reminder-members))

(defn- send-gig-reminders! [sys reminders as-of]
  (let [{:keys [to-send to-cancel]} (process-reminders (:db sys) reminders as-of)
        ;; _ (tap> {:to-send to-send})
        sent-reminder-ids (doall (mapv (partial send-reminder-for-gig! sys) to-send))]
    ;; (tap> {:to-cancel to-cancel :sent sent-reminder-ids})
    (concat
     (map (fn [reminder-id]
            [:db/add [:reminder/reminder-id reminder-id] :reminder/reminder-status :reminder-status/cancelled]) to-cancel)
     (map (fn [reminder-id]
            [:db/add [:reminder/reminder-id (first reminder-id)] :reminder/reminder-status :reminder-status/sent]) sent-reminder-ids))))

(defn send-reminders!
  ([system _]
   (send-reminders! system (t/instant) _))
  ([{:keys [datomic] :as system} as-of _]
   (try
     (let [datomic-conn (:conn datomic)
           _ (assert datomic-conn)
           db (datomic/db datomic-conn)
           reminders (q/overdue-reminders-by-type db as-of)
           tx-data (send-gig-reminders!
                    (assoc system :db db :datomic-conn datomic-conn)
                    (:reminder-type/gig-attendance reminders)
                    as-of)]
       ;; (tap> {:send-reminder-result tx-data})
       (when (seq tx-data)
         (datomic/transact datomic-conn {:tx-data tx-data}))
       :done)
     (catch Throwable e
       (tap> e)
       (errors/report-error! e)))))

(defn make-reminder-job [system]
  (fn [{:job/keys [frequency initial-delay]}]
    (jobs/make-repeating-job (partial send-reminders! system) frequency initial-delay)))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))
    (def system {:datomic {:conn conn}
                 :redis (-> state/system :app.ig/redis)
                 :i18n-langs (-> state/system :app.ig/i18n-langs)
                 :env (-> state/system :app.ig/env)})) ;; rcf

  (q/attendance-for-gig db (parse-uuid "0187e415-7b26-8e55-9371-4391baf9fe09") (parse-uuid "01860c2a-2929-8727-af1a-5545941b1111"))
  (q/attendance-for-gig db (parse-uuid "01875c27-376f-85ab-b492-9c9677c6d224") (parse-uuid "01860c2a-2929-8727-af1a-5545941b1111"))

  (send-gig-reminders! db [{:reminder/reminder-id "test"
                            :reminder/member (q/member-by-email db "REDACTED")
                            :reminder/gig  (first (q/gigs-future db))
                            :reminder/reminder-status :reminder-status/pending
                            :reminder/remind-at (t/tomorrow)}]
                       (t/instant))
  (q/overdue-reminders-by-type db (t/>> (t/instant) (t/new-period 100 :days)))
  (let [as-of (t/>> (t/instant) (t/new-period 2 :days))]
    (process-reminders db (:reminder-type/gig-attendance (q/overdue-reminders-by-type db as-of)) as-of))
  (q/active-reminders-by-type db)
  (send-reminders! system (t/>> (t/instant) (t/new-period 2 :days)) nil)
  (datomic/transact conn {:tx-data
                          [[:db/retractEntity [:reminder/reminder-id #uuid "01882a6e-6517-8600-805b-b996bf0de9ba"]]]})
  ;;
  )
