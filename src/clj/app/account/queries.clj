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
   :batch-time "morning"
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

(defn preferences-page-state [page-state]
  (merge default-preferences (:account-preferences page-state)))

(defn notification-page-state [page-state]
  (merge default-notifications (:account-notifications page-state)))

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
