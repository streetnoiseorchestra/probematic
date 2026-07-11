(ns app.humanize
  (:import [java.time.temporal Temporal ChronoUnit])
  (:require [tick.core :as t]
            [taoensso.tempura :as tempura]))

(def humanize-messages
  {:en
   {:missing ":en missing text"
    :now "now"
    :time
    {:second.ago "a second ago"
     :seconds.ago "%1 seconds ago"
     :minute.ago "a minute ago"
     :minutes.ago "%1 minutes ago"
     :hour.ago "an hour ago"
     :hours.ago "%1 hours ago"
     :day.ago "a day ago"
     :days.ago "%1 days ago"
     :week.ago "a week ago"
     :weeks.ago "%1 weeks ago"
     :month.ago "a month ago"
     :months.ago "%1 months ago"
     :year.ago "a year ago"
     :years.ago "%1 years ago"
     :second.within "in a second"
     :seconds.within "within %1 seconds"
     :minute.within "in a minute"
     :minutes.within "within %1 minutes"
     :hour.within "in an hour"
     :hours.within "within %1 hours"
     :day.within "in a day"
     :days.within "within %1 days"
     :week.within "in a week"
     :weeks.within "within %1 weeks"
     :month.within "in a month"
     :months.within "within %1 months"
     :year.within "in a year"
     :years.within "within %1 years"}}})

(def tr (partial tempura/tr {:dict humanize-messages} [:en]))

(defn- plural-msg [n singular plural]
  (if (= n 1)
    (tr [singular] [n])
    (tr [plural] [n])))

(defn from
  "Given a local date time or a local date, return a human-friendly representation of the amount of time difference relative
  to the current time or :now-t."
  [^Temporal then-t & {:keys [now-t]
                       :or {now-t (t/date-time)}}]
  (let [then-t (if (t/date? then-t)
                 (t/at then-t (t/midnight))
                 then-t)
        years (.between ChronoUnit/YEARS then-t now-t)
        months (.between ChronoUnit/MONTHS then-t now-t)
        weeks (.between ChronoUnit/WEEKS then-t now-t)
        days (.between ChronoUnit/DAYS then-t now-t)
        hours (.between ChronoUnit/HOURS then-t now-t)
        minutes (.between ChronoUnit/MINUTES then-t now-t)
        seconds (.between ChronoUnit/SECONDS then-t now-t)]
    (cond
      (> years 0) (plural-msg years :time/year.ago :time/years.ago)
      (> months 0) (plural-msg months :time/month.ago :time/months.ago)
      (> weeks 0) (plural-msg weeks :time/week.ago :time/weeks.ago)
      (> days 0) (plural-msg days :time/day.ago :time/days.ago)
      (> hours 0) (plural-msg hours :time/hour.ago :time/hours.ago)
      (> minutes 0) (plural-msg minutes :time/minute.ago :time/minutes.ago)
      (> seconds 0) (plural-msg seconds :time/second.ago :time/seconds.ago)

      (< years 0) (plural-msg (abs years) :time/year.within :time/years.within)
      (< months 0) (plural-msg (abs months) :time/month.within :time/months.within)
      (< weeks 0) (plural-msg (abs weeks) :time/week.within :time/weeks.within)
      (< days 0) (plural-msg (abs days) :time/day.within :time/days.within)
      (< hours 0) (plural-msg (abs hours) :time/hour.within :time/hours.within)
      (< minutes 0) (plural-msg (abs minutes) :time/minute.within :time/minutes.within)
      (< seconds 0) (plural-msg (abs seconds) :time/second.within :time/seconds.within)
      :else (tr [:now]))))

(defn logn [num base]
  (/ (Math/round (Math/log num))
     (Math/round (Math/log base))))

(defn filesize
  "Format a number of bytes as a human readable filesize (eg. 10 kB). By
   default, decimal suffixes (kB, MB) are used.  Passing :binary true will use
   binary suffixes (KiB, MiB) instead.
   Copyright © 2015 Thura Hlaing
   Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
   https://github.com/trhura/clojure-humanize
   https://github.com/trhura/clojure-humanize/blob/master/LICENSE"
  [bytes & {:keys [binary fmt]
            :or {binary false
                 fmt "%.1f"}}]

  (if (zero? bytes)
    ;; special case for zero
    "0"

    (let [decimal-sizes  [:B, :KB, :MB, :GB, :TB,
                          :PB, :EB, :ZB, :YB]
          binary-sizes [:B, :KiB, :MiB, :GiB, :TiB,
                        :PiB, :EiB, :ZiB, :YiB]

          units (if binary binary-sizes decimal-sizes)
          base  (if binary 1024 1000)

          base-pow  (int (Math/floor (logn bytes base)))
          ;; if base power shouldn't be larger than biggest unit
          base-pow  (if (< base-pow (count units))
                      base-pow
                      (dec (count units)))
          suffix (name (get units base-pow))
          value (float (/ bytes (Math/pow base base-pow)))]

      (str (format fmt value) suffix))))

(comment
  (let [then-t (t/<< (t/date-time) (t/new-duration 5 :days))
        now-t (t/date-time)]
    (from then-t :now-t now-t))

  (let [then-t (t/<< (t/date-time) (t/new-duration 100 :days))
        now-t (t/date-time)]
    (from then-t :now-t now-t))

  (from (t/date-time) :now-t (t/date-time))
  (from (t/date))

  ;; doesnt work
  (from (t/zoned-date-time))

  (t/>= (t/now) (t/>> (t/date-time) (t/new-duration 1 :minutes)))

  ;;
  )
