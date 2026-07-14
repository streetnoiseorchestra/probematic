(ns app.caldav
  (:require [tick.core :as t]
            [app.i18n :as i18n]
            [app.queries :as q]
            [app.urls :as urls])
  (:import
   (com.outskirtslabs.nextcloudcal4j Event NextcloudConnector)))

(defn gig-date-to-inst
  [date time]
  (if time
    (t/instant (-> date (t/at time) (t/in (t/zone "Europe/Vienna"))))
    (t/instant (-> date t/midnight (t/in (t/zone "Europe/Vienna"))))))

(defn event-from-gig
  [env tr {:gig/keys [title status location more-details gig-id call-time date end-date end-time]}]
  (let [start (gig-date-to-inst date call-time)
        end (if (nil? end-date)
              (gig-date-to-inst date (or end-time (t/>> call-time (t/new-duration 2 :hours))))
              (gig-date-to-inst end-date end-time))
        end-estimated? (nil? end-time)
        description (if end-estimated?
                      (format "%s\n%s" (tr [:gig/end-time-warning]) (or  more-details ""))
                      more-details)]
    {:ical.event/created-at (t/instant)
     :ical.event/start-time start
     :ical.event/end-time end
     :ical.event/description description
     :ical.event/summary title
     :ical.event/timezone (t/zone "Europe/Vienna")
     :ical.event/uid (str gig-id)
     :ical.event/url (urls/absolute-link-gig env gig-id)
     :ical.event/location location
     :ical.event/status (condp = status
                          :gig.status/unconfirmed :ical.event.status/tentative
                          :gig.status/confirmed :ical.event.status/confirmed
                          :gig.status/cancelled :ical.event.status/cancelled)}))

(defn create-gig-event!
  [{:keys [env db i18n-langs ^NextcloudConnector calendar] :as _sys} gig-id]
  (assert calendar)
  (assert i18n-langs)
  (let [tr (i18n/tr-with i18n-langs [:de])
        gig (q/retrieve-gig db gig-id)
        event (event-from-gig env tr gig)]
    ;; (tap> {:g gig :e event})
    (-> calendar (.createEvent (Event/fromClojure event)))))

(defn update-gig-event!
  [{:keys [env db i18n-langs ^NextcloudConnector calendar] :as _sys} gig-id]
  (assert calendar)
  (assert i18n-langs)
  (let [tr (i18n/tr-with i18n-langs [:de])
        gig (q/retrieve-gig db gig-id)
        cancelled? (= :gig.status/cancelled (:gig/status gig))
        event (event-from-gig env tr gig)
        existing-event (-> calendar (.getEventByUID (str gig-id)))]
    (if existing-event
      (if cancelled?
        (-> calendar (.deleteEvent (str gig-id)))
        (-> calendar (.updateEvent (Event/fromClojure event))))
      (when-not cancelled?
        (-> calendar (.createEvent (Event/fromClojure event)))))))

(defn delete-gig-event!
  [{_env :env
    _db :db
    _i18n-langs :i18n-langs
    :keys [^NextcloudConnector calendar]
    :as _sys}
   gig-id]
  (assert calendar)
  (-> calendar (.deleteEvent (str gig-id))))

(defn init-calendar
  [{:keys [nextcloud]}]
  (let [{:keys [host username password calendar-path]} nextcloud]
    (assert calendar-path)
    (assert host)
    (assert username)
    (assert password)
    (NextcloudConnector. host username password calendar-path)))

(comment

  (->
   (Event/fromClojure
    {:ical.event/created-at (t/instant)
     :ical.event/start-time (t/instant)
     :ical.event/end-time (t/instant)
     :ical.event/timezone (t/zone "Europe/Vienna")}) (.toVEvent))
  (t/instant)

  (gig-date-to-inst (t/date "2023-10-17") (t/time "18:45"))

  (def client (create-http-client))

  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (require '[app.queries :as q])
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))) ;; rcf

  (def gig
    (nth
     (q/gigs-after db (q/date-midnight-today!))
     11))

;;
  )
