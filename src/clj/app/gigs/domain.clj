(ns app.gigs.domain
  (:require
   [app.schemas :as s]
   [medley.core :as m]
   [tick.core :as t]))

(def statuses
  [:gig.status/unconfirmed
   :gig.status/confirmed
   :gig.status/cancelled])

(def create-statuses
  [:gig.status/unconfirmed
   :gig.status/confirmed])

(def plans
  [:plan/no-response
   :plan/definitely
   :plan/probably
   :plan/unknown
   :plan/probably-not
   :plan/definitely-not
   :plan/not-interested])

(def plan-priority-sorting
  [:plan/definitely
   :plan/probably
   :plan/unknown
   :plan/probably-not
   :plan/definitely-not
   :plan/not-interested
   :plan/no-response])

(def plan-priority-optional-display #{:plan/probably-not :plan/probably})

(def motivations
  [:motivation/none
   :motivation/very-high
   :motivation/high
   :motivation/medium
   :motivation/low
   :motivation/very-low])

(def gig-types [:gig.type/probe :gig.type/extra-probe :gig.type/meeting :gig.type/gig])

(def gig-status-label-key
  {:gig.status/cancelled   :gigs/status-cancelled
   :gig.status/confirmed   :gigs/status-confirmed
   :gig.status/unconfirmed :gigs/status-unconfirmed})

(def gig-type-label-key
  {:gig.type/extra-probe :gigs/type-extra-probe
   :gig.type/gig         :gigs/type-gig
   :gig.type/meeting     :gigs/type-meeting
   :gig.type/probe       :gigs/type-probe})

(def ^:private plan-label-keys
  {:plan/definitely     :gigs/plan-definitely
   :plan/definitely-not :gigs/plan-definitely-not
   :plan/no-response    :gigs/plan-no-response
   :plan/not-interested :gigs/plan-not-interested
   :plan/probably       :gigs/plan-probably
   :plan/probably-not   :gigs/plan-probably-not
   :plan/unknown        :gigs/plan-unknown})

(def ^:private motivation-label-keys
  {:motivation/high      :gigs/motivation-high
   :motivation/low       :gigs/motivation-low
   :motivation/medium    :gigs/motivation-medium
   :motivation/none      :gigs/motivation-none
   :motivation/very-high :gigs/motivation-very-high
   :motivation/very-low  :gigs/motivation-very-low})

(def gig-attribute-label-key
  {:gig/call-time         :gigs/call-time
   :gig/contact           :gigs/contact
   :gig/date              :gigs/date
   :gig/description       :gigs/description
   :gig/end-date          :gigs/end-date
   :gig/end-time          :gigs/end-time
   :gig/gig-type          :gigs/type-label
   :gig/leader            :gigs/leader
   :gig/location          :gigs/location
   :gig/more-details      :gigs/more-details
   :gig/outfit            :gigs/outfit
   :gig/pay-deal          :gigs/pay-deal
   :gig/post-gig-plans    :gigs/post-gig-plans
   :gig/rehearsal-leader1 :gigs/rehearsal-leader-1
   :gig/rehearsal-leader2 :gigs/rehearsal-leader-2
   :gig/set-time          :gigs/set-time
   :gig/setlist           :gigs/setlist
   :gig/status            :gigs/status-label
   :gig/title             :gigs/title-label})

(defn plan-label-key [plan]
  (get plan-label-keys (or plan :plan/no-response)))

(defn motivation-label-key [motivation]
  (get motivation-label-keys (or motivation :motivation/none)))

(defn setlist-gig?
  [{:gig/keys [gig-type]}]
  (contains? #{:gig.type/gig} gig-type))

(def setlist-versions [:setlist.version/v1])

(def SetlistV1Entity
  (s/schema
   [:map {:name :app.entity/setlist.v1}
    [:setlist/gig ::s/datomic-ref]
    [:setlist/version (s/enum-from setlist-versions)]
    [:setlist.v1/ordered-songs [:sequential [:tuple ::s/datomic-ref :int]]]]))

(def GigEntity
  (s/schema
   [:map {:name :app.entity/gig}
    [:gig/gig-id :uuid]
    [:gig/title {:max 4096} ::s/non-blank-string]
    [:gig/status (s/enum-from statuses)]
    [:gig/date ::s/instdate]
    [:gig/end-date {:optional true} ::s/instdate]
    [:gig/gig-type (s/enum-from gig-types)]
    [:gig/gigo-id {:optional true} ::s/non-blank-string]
    [:gig/location {:optional true :max 4096} ::s/non-blank-string]
    [:gig/contact {:optional true} ::s/datomic-ref]
    [:gig/call-time {:optional true} ::s/minute-time]
    [:gig/set-time {:optional true} ::s/minute-time]
    [:gig/end-time {:optional true} ::s/time]
    [:gig/leader {:optional true :max 4096} :string]
    [:gig/rehearsal-leader1 {:optional true} ::s/datomic-ref]
    [:gig/rehearsal-leader2 {:optional true} ::s/datomic-ref]
    [:gig/pay-deal {:optional true :max 4096} :string]
    [:gig/outfit {:optional true :max 4096} :string]
    [:gig/more-details {:optional true :max 4096} :string]
    [:gig/setlist {:optional true :max 4096} :string]
    [:gig/description {:optional true :max 4096} :string]
    [:gig/post-gig-plans {:optional true :max 4096} :string]
    [:gig/gigo-plan-archive  {:optional true :max 4096} :string]
    [:forum.topic/topic-id {:optional true :max 30} :string]]))

(def GigEntityFromGigo
  (s/schema
   [:map {:name :app.entity/gig}
    [:gig/gigo-id ::s/non-blank-string]
    [:gig/title {:max 4096} ::s/non-blank-string]
    [:gig/status (s/enum-from statuses)]
    [:gig/date ::s/instdate]
    [:gig/end-date {:optional true} ::s/instdate]
    [:gig/gig-type (s/enum-from gig-types)]
    [:gig/location {:optional true :max 4096} ::s/non-blank-string]
    [:gig/contact {:optional true} ::s/datomic-ref]
    [:gig/call-time {:optional true} ::s/minute-time]
    [:gig/set-time {:optional true} ::s/minute-time]
    [:gig/end-time {:optional true} ::s/time]
    [:gig/leader {:optional true :max 4096} :string]
    [:gig/pay-deal {:optional true :max 4096} :string]
    [:gig/outfit {:optional true :max 4096} :string]
    [:gig/more-details {:optional true :max 4096} :string]
    [:gig/setlist {:optional true :max 4096} :string]
    [:gig/description {:optional true :max 4096} :string]
    [:gig/post-gig-plans {:optional true :max 4096} :string]
    [:gig/gigo-plan-archive  {:optional true :max 4096} :string]
    [:forum.topic/topic-id {:optional true :max 30} :string]]))

(defn gig->db
  ([gig]
   (gig->db GigEntity gig))
  ([schema gig]
   (when-not (s/valid? schema gig)
     (throw
      (ex-info "Gig not valid" {:gig gig
                                :schema schema
                                :error (s/explain schema gig)
                                :human (s/explain-human schema gig)})))
   (s/encode-datomic schema gig)))

(defn ->comment
  [comment]
  (-> comment
      (m/update-existing :comment/created-at t/date-time)))

(defn db->gig
  [gig]
  (-> (s/decode-datomic GigEntity gig)
      (m/update-existing :gig/comments #(->> %
                                             (map ->comment)
                                             (sort-by :comment/created-at)))))

(defn in-future?
  [{:gig/keys [date]}]
  (t/>= date (t/date)))

(defn in-past?
  [{:gig/keys [date] :as _gig}]
  (t/< date (t/date)))

(defn cancelled?
  [{:gig/keys [status]}]
  (= status :gig.status/cancelled))

(defn gig-archived?
  [{:gig/keys [date]}]
  (when date
    (t/< date (t/<< (t/date) (t/new-period 14 :days)))))

(defn probe?
  [gig]
  (#{:gig.type/probe :gig.type/extra-probe} (:gig/gig-type gig)))

(defn normal-probe?
  [gig]
  (#{:gig.type/probe} (:gig/gig-type gig)))

(defn meeting?
  [gig]
  (#{:gig.type/meeting} (:gig/gig-type gig)))

(defn gig?
  [gig]
  (#{:gig.type/gig} (:gig/gig-type gig)))

(defn confirmed?
  [gig]
  (= :gig.status/confirmed (:gig/status gig)))

(defn no-response?
  "Whether or not the member needs to update their plan or not"
  [plan-or-attendance]
  (let [plan (or (:attendance/plan plan-or-attendance) plan-or-attendance)]
    (or (nil? plan)
        (= plan :plan/unknown)
        (= plan :plan/no-response))))

(defn committed?
  [plan-or-attendance]
  (let [plan (or (:attendance/plan plan-or-attendance) plan-or-attendance)]
    (= plan :plan/definitely)))

(defn uncommitted?
  [plan-or-attendance]
  (let [plan (or (:attendance/plan plan-or-attendance) plan-or-attendance)]
    (or (nil? plan)
        (#{nil :plan/definitely-not :plan/unknown :plan/no-response :plan/not-interested :plan/probably-not
           :plan/probably} plan))))

(def reminder-states
  #{:reminder-status/pending
    :reminder-status/sent
    :reminder-status/error
    :reminder-status/cancelled})

(def reminder-types #{:reminder-type/gig-attendance})

(def ReminderEntity
  (s/schema
   [:map {:name :app.entity/reminder}
    [:reminder/reminder-id :uuid]
    [:reminder/reminder-status (s/enum-from reminder-states)]
    [:reminder/reminder-type (s/enum-from reminder-types)]
    [:reminder/remind-at ::s/instant]
    [:reminder/member ::s/datomic-ref]
    [:reminder/gig ::s/datomic-ref]]))

(defn reminder->db
  [reminder]
  (when-not (s/valid? ReminderEntity reminder)
    (throw
     (ex-info "Reminder not valid" {:reminder reminder
                                    :schema ReminderEntity
                                    :error (s/explain ReminderEntity reminder)
                                    :human (s/explain-human ReminderEntity reminder)})))
  (s/encode-datomic ReminderEntity reminder))

(defn db->reminder
  [reminder]
  (update
   (s/decode-datomic ReminderEntity reminder)
   :reminder/gig db->gig))
