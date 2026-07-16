(ns app.account.queries
  (:require
   [app.queries :as queries]
   [cljc.java-time.zone-id :as jt.zone-id]
   [tick.core :as t]))

(def default-preferences
  {:time-zone "Europe/Berlin"
   :week-start "monday"
   :time-format "24-hour"
   :_error {}
   :_saved? false})

(def default-notifications
  {:enabled? true
   :what "everything"
   :reminders {:attendance? true
               :polls? true}
   :delivery {:email? true
              :browser? false
              :browser-capable? nil
              :browser-permission "default"}
   :unread-style "numbered"
   :when "right-away"
   :batch-time "08:00"
   :_error {}
   :_saved? false})

(def default-break
  {:active false
   :start-choice "now"
   :start-date ""
   :end-date ""
   :time-zone "Europe/Berlin"
   :status "available"
   :_error {}
   :_saved? false})

(defn profile-page-state [db member-id page-state]
  (let [member (queries/retrieve-member db member-id)]
    (merge {:name            (or (:member/name member) "")
            :nick            (or (:member/nick member) "")
            :email           (or (:member/email member) "")
            :username        (or (:member/username member) "")
            :phone           (or (:member/phone member) "")
            :current-status  (or (:member/current-status member) "")
            :date-of-birth   (or (:member/date-of-birth member) "")
            :avatar          nil
            :avatar-removed? false
            :_error          {}
            :_saved?         false}
           (:account-profile page-state))))

(defn- enum-name [member attr]
  (some-> (get-in member [attr :db/ident]) name))

(defn- attribute-or-default [member attr default]
  (if (contains? member attr)
    (get member attr)
    default))

(defn- clock-format-name [member]
  (case (get-in member [:member/clock-format :db/ident])
    :clock-format/hour-12 "12-hour"
    :clock-format/hour-24 "24-hour"
    nil))

(defn preferences-page-state [db member-id page-state]
  (let [member (queries/retrieve-member db member-id)
        timezone (:member/timezone member)
        persisted {:time-zone (or timezone (:time-zone default-preferences))
                   :week-start (or (enum-name member :member/week-start)
                                   (:week-start default-preferences))
                   :time-format (or (clock-format-name member)
                                    (:time-format default-preferences))
                   :time-zone-persisted? (boolean timezone)
                   :_error {}
                   :_saved? false}]
    (merge persisted (:account-preferences page-state))))

(defn- merge-notifications [persisted transient]
  (-> (merge persisted transient)
      (assoc :reminders (merge (:reminders persisted) (:reminders transient)))
      (assoc :delivery (merge (:delivery persisted) (:delivery transient)))))

(defn notification-page-state [db member-id page-state]
  (let [member (queries/retrieve-member db member-id)
        persisted?
        (contains? member :member.notify/enabled?)
        persisted
        (if-not persisted?
          default-notifications
          {:enabled? (attribute-or-default
                      member
                      :member.notify/enabled?
                      (:enabled? default-notifications))
           :what (or (enum-name member :member.notify/scope)
                     (:what default-notifications))
           :reminders
           {:attendance?
            (attribute-or-default
             member
             :member.notify/attendance-reminders?
             (get-in default-notifications [:reminders :attendance?]))
            :polls?
            (attribute-or-default
             member
             :member.notify/poll-reminders?
             (get-in default-notifications [:reminders :polls?]))}
           :delivery
           {:email?
            (attribute-or-default
             member
             :member.notify/email?
             (get-in default-notifications [:delivery :email?]))
            :browser?
            (attribute-or-default
             member
             :member.notify/browser?
             (get-in default-notifications [:delivery :browser?]))
            :browser-capable? nil
            :browser-permission "default"}
           :unread-style
           (or (enum-name member :member.notify/unread-style)
               (:unread-style default-notifications))
           :when (or (enum-name member :member.notify/schedule)
                     (:when default-notifications))
           :batch-time (or (:member.notify/batch-time member)
                           (:batch-time default-notifications))
           :_error {}
           :_saved? false})]
    (merge-notifications persisted (:account-notifications page-state))))

(defn- ->instant [value]
  (cond
    (t/instant? value) value
    (inst? value)       (t/instant value)
    :else               (t/instant)))

(defn- today [now timezone]
  (-> (->instant now)
      (t/in timezone)
      t/date))

(defn- parse-date [value]
  (when (seq value)
    (t/date value)))

(defn- break-status [today start-date end-date]
  (let [start (parse-date start-date)
        end (parse-date end-date)]
    (cond
      (nil? start) "available"
      (and end (t/< end today)) "ended"
      (t/> start today) "scheduled"
      :else "away")))

(defn break-page-state
  ([db member-id page-state]
   (break-page-state db member-id page-state (t/instant)))
  ([db member-id page-state now]
   (let [member (queries/retrieve-member db member-id)
         timezone (or (:member/timezone member) (:time-zone default-break))
         today (today now timezone)
         start-date (or (:member.break/start-date member) "")
         end-date (or (:member.break/end-date member) "")
         persisted {:active (boolean (seq start-date))
                    :start-choice (if (or (empty? start-date)
                                          (= start-date (str today)))
                                    "now"
                                    "date")
                    :start-date start-date
                    :end-date end-date
                    :time-zone timezone
                    :status (break-status today start-date end-date)
                    :_error {}
                    :_saved? false}]
     (merge persisted (:account-break page-state)))))

(defn time-zone-options
  ([]
   (time-zone-options (t/instant)))
  ([now]
   (let [instant (->instant now)]
     (mapv (fn [id]
             (let [offset (-> instant
                              (t/in id)
                              t/zone-offset)]
               {:value id
                :label (format "UTC%s — %s" offset id)}))
           (sort (jt.zone-id/get-available-zone-ids))))))
