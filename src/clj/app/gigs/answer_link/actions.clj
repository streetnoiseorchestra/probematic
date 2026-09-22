(ns app.gigs.answer-link.actions
  "Plans attendance and reminder changes submitted through answer links."
  (:require
   [app.gigs.domain :as domain]
   [app.jobs.integrations :as integrations]
   [app.jobs.log-dispatch :as log-dispatch]
   [app.queries :as q]
   [tick.core :as t]))

(defn- attendance-ref [attendance]
  (if-let [gig+member (:attendance/gig+member attendance)]
    [:attendance/gig+member gig+member]
    (throw (ex-info "Attendance entity is missing :attendance/gig+member"
                    {:attendance attendance}))))

(defn- touch-attendance-tx [attendance submitted-at]
  [:db/add (attendance-ref attendance) :attendance/updated (t/inst submitted-at)])

(defn- update-attendance-plan-tx [attendance plan]
  [:db/add (attendance-ref attendance) :attendance/plan plan])

(defn- create-attendance-tx
  [db {:keys [gig-id member-id plan submitted-at]}]
  {:attendance/gig+member (q/gig+member gig-id member-id)
   :attendance/gig        [:gig/gig-id gig-id]
   :attendance/member     [:member/member-id member-id]
   :attendance/updated    (t/inst submitted-at)
   :attendance/section    [:section/name (q/section-for-member db member-id)]
   :attendance/plan       plan})

(defn- attendance-tx-data
  [db {:keys [gig-id member-id plan submitted-at] :as submission}]
  (if-let [attendance (q/attendance-for-gig db gig-id member-id)]
    [(update-attendance-plan-tx attendance plan)
     (touch-attendance-tx attendance submitted-at)]
    [(create-attendance-tx db submission)]))

(defn- make-reminder
  [{:keys [gig-id member-id reminder-id submitted-at]}]
  (domain/reminder->db
   {:reminder/reminder-id     reminder-id
    :reminder/gig             [:gig/gig-id gig-id]
    :reminder/member          [:member/member-id member-id]
    :reminder/reminder-status :reminder-status/pending
    :reminder/reminder-type   :reminder-type/gig-attendance
    :reminder/remind-at       (t/>> submitted-at (t/new-period 2 :days))}))

(defn- reset-reminder [reminder submitted-at]
  (domain/reminder->db
   (-> reminder
       (update :reminder/gig (fn [{:gig/keys [gig-id]}] [:gig/gig-id gig-id]))
       (update :reminder/member (fn [{:member/keys [member-id]}] [:member/member-id member-id]))
       (assoc :reminder/reminder-status :reminder-status/pending)
       (assoc :reminder/remind-at (t/>> submitted-at (t/new-period 2 :days))))))

(defn- reminder-tx-data
  [db {:keys [gig-id member-id submitted-at] :as submission}]
  (if-let [reminder (q/gig-reminder-for db gig-id member-id)]
    [(reset-reminder reminder submitted-at)]
    [(make-reminder submission)]))

(defn- audit-metadata [action actor-id]
  (cond-> {:audit/action action
           :audit/origin :app.origin/browser}
    actor-id (assoc :audit/user [:member/member-id actor-id])))

(defn plan-submission
  "Returns a transaction plan or an `:error` map for decoded answer-link `command`.

  The function reads only `db`. The command must contain normalized identifiers,
  `:submitted-at`, and `:submitted-on`."
  [db {:keys [actor-id env gig-id member-id plan reminder? submitted-on]
       :as   submission}]
  (let [gig     (q/retrieve-gig db gig-id)
        member  (q/retrieve-member db member-id)
        plan-kw ((set domain/plans) plan)]
    (cond
      (nil? gig)
      {:error {:type   ::gig-not-found
               :gig-id gig-id}}

      (nil? member)
      {:error {:type      ::member-not-found
               :member-id member-id}}

      (and (not reminder?) (nil? plan-kw))
      {:error {:type ::invalid-attendance-plan
               :plan plan}}

      (and (not reminder?) (t/< (:gig/date gig) submitted-on))
      {:error {:type   ::gig-not-open
               :gig-id gig-id}}

      reminder?
      {:tx-data (reminder-tx-data db submission)
       :audit   (audit-metadata ::submit-reminder actor-id)}

      :else
      (let [jobs (:jobs (integrations/gig-update-options {:env env} gig-id :attendance))]
        {:tx-data (cond-> (attendance-tx-data db (assoc submission :plan plan-kw))
                    (seq jobs) (into (log-dispatch/intent-tx jobs)))
         :audit   (audit-metadata ::submit-attendance actor-id)}))))
