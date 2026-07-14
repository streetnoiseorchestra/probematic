(ns app.account.queries
  (:require
   [app.queries :as queries])
  (:import
   [java.time Instant ZoneId]
   [java.util Date]))

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

(defn current-member [db member-id]
  (when (and db member-id)
    (queries/retrieve-member db member-id)))

(defn profile-page-state [db member-id page-state]
  (let [member (current-member db member-id)]
    (merge {:name            (or (:member/name member) "")
            :nick            (or (:member/nick member) "")
            :email           (or (:member/email member) "")
            :username        (or (:member/username member) "")
            :phone           (or (:member/phone member) "")
            :current-status  ""
            :date-of-birth   ""
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
  (let [member (current-member db member-id)
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
  (let [member (current-member db member-id)
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

(defn break-page-state [page-state]
  (merge default-break (:account-break page-state)))

(defn- ->instant ^Instant [value]
  (cond
    (instance? Instant value) value
    (instance? Date value)    (.toInstant ^Date value)
    :else                     (Instant/now)))

(defn time-zone-options
  ([]
   (time-zone-options (Date.)))
  ([now]
   (let [^Instant instant (->instant now)]
     (mapv (fn [id]
             (let [^ZoneId zone (ZoneId/of id)
                   offset (.getOffset (.getRules zone) instant)]
               {:value id
                :label (format "UTC%s — %s" offset id)}))
           (sort (ZoneId/getAvailableZoneIds))))))
