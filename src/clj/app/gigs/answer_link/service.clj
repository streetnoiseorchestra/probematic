(ns app.gigs.answer-link.service
  (:require
   [app.config :as config]
   [app.datomic.shim :as datomic]
   [app.gigs.domain :as domain]
   [app.jobs.gig-events :as gig.events]
   [app.queries :as q]
   [app.secret-box :as secret-box]
   [app.util :as util]
   [app.util.http :as http.util]
   [com.yetanalytics.squuid :as sq]
   [tick.core :as t]))

(defn- param [req k]
  (or (get-in req [:params k])
      (get-in req [:params (name k)])))

(defn answer-token [req]
  (param req :answer))

(defn- decrypt-answer [req token]
  (secret-box/decrypt token (config/app-secret-key (get-in req [:system :env]))))

(defn- str->plan [plan]
  ((set domain/plans) plan))

(defn- attendance-eid [attendance]
  (or (:db/id attendance)
      (throw (ex-info "Attendance entity is missing :db/id" {:attendance attendance}))))

(defn- touch-attendance-tx [attendance]
  [:db/add (attendance-eid attendance) :attendance/updated (t/inst)])

(defn- update-attendance-plan-tx [attendance plan]
  [:db/add (attendance-eid attendance) :attendance/plan plan])

(defn- create-attendance-tx [db gig-id member-id plan]
  {:attendance/gig+member (q/gig+member gig-id member-id)
   :attendance/gig        [:gig/gig-id gig-id]
   :attendance/member     [:member/member-id member-id]
   :attendance/updated    (t/inst)
   :attendance/section    [:section/name (q/section-for-member db member-id)]
   :attendance/plan       plan})

(defn attendance-plan-tx-data [db gig-id member-id plan]
  (if-let [attendance (q/attendance-for-gig db gig-id member-id)]
    [(update-attendance-plan-tx attendance plan)
     (touch-attendance-tx attendance)]
    [(create-attendance-tx db gig-id member-id plan)]))

(defn make-reminder [gig-id member-id remind-in-days]
  (domain/reminder->db
   {:reminder/reminder-id     (sq/generate-squuid)
    :reminder/gig             [:gig/gig-id (http.util/ensure-uuid gig-id)]
    :reminder/member          [:member/member-id (http.util/ensure-uuid member-id)]
    :reminder/reminder-status :reminder-status/pending
    :reminder/reminder-type   :reminder-type/gig-attendance
    :reminder/remind-at       (t/>> (t/instant) (t/new-period remind-in-days :days))}))

(defn reset-reminder [reminder remind-in-days]
  (domain/reminder->db
   (-> reminder
       (update :reminder/gig (fn [{:gig/keys [gig-id]}] [:gig/gig-id gig-id]))
       (update :reminder/member (fn [{:member/keys [member-id]}] [:member/member-id member-id]))
       (assoc :reminder/reminder-status :reminder-status/pending)
       (assoc :reminder/remind-at (t/>> (t/instant) (t/new-period remind-in-days :days))))))

(defn reminder-tx-data [db gig-id member-id remind-in-days]
  (if-let [existing-reminder (q/gig-reminder-for db gig-id member-id)]
    [(reset-reminder existing-reminder remind-in-days)]
    [(make-reminder gig-id member-id remind-in-days)]))

(def default-deps
  {:decrypt-answer      decrypt-answer
   :transact!           datomic/transact
   :trigger-gig-edited! gig.events/trigger-gig-edited})

(defn submit-answer!
  ([req]
   (submit-answer! default-deps req))
  ([deps {:keys [db datomic-conn] :as req}]
   (let [deps                 (merge default-deps deps)
         decrypt-answer       (:decrypt-answer deps)
         transact!            (:transact! deps)
         trigger-gig-edited!  (:trigger-gig-edited! deps)
         answer               (decrypt-answer req (answer-token req))
         member-id            (util/ensure-uuid! (:member/member-id answer))
         gig-id               (util/ensure-uuid! (:gig/gig-id answer))
         gig                  (q/retrieve-gig db gig-id)
         member               (q/retrieve-member db member-id)
         reminder?            (:reminder answer)
         plan                 (:attendance/plan answer)
         plan-kw              (str->plan plan)]
     (assert gig)
     (assert member)
     (when-not reminder?
       (assert plan-kw (str "Unknown answer-link attendance plan: " plan)))
     (cond
       reminder?
       (do
         (transact! datomic-conn {:tx-data (reminder-tx-data db gig-id member-id 2)})
         {:gig gig :member member :reminder? true})

       (domain/in-future? gig)
       (let [result (transact! datomic-conn {:tx-data (attendance-plan-tx-data db gig-id member-id plan-kw)})]
         (trigger-gig-edited! req gig-id :attendance)
         {:gig    (q/retrieve-gig (:db-after result) gig-id)
          :member member})

       :else
       nil))))
