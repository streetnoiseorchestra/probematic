(ns app.humanize
  (:import [java.time.temporal Temporal ChronoUnit])
  (:require [tick.core :as t]))

(defn from
  "Returns a Fluent translation node describing the time from `then-t` to
  `:now-t`, which defaults to the current local date and time."
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
      (> years 0) [:i18n/tr :relative-time-years-ago {:count years}]
      (> months 0) [:i18n/tr :relative-time-months-ago {:count months}]
      (> weeks 0) [:i18n/tr :relative-time-weeks-ago {:count weeks}]
      (> days 0) [:i18n/tr :relative-time-days-ago {:count days}]
      (> hours 0) [:i18n/tr :relative-time-hours-ago {:count hours}]
      (> minutes 0) [:i18n/tr :relative-time-minutes-ago {:count minutes}]
      (> seconds 0) [:i18n/tr :relative-time-seconds-ago {:count seconds}]

      (< years 0) [:i18n/tr :relative-time-years-within {:count (abs years)}]
      (< months 0) [:i18n/tr :relative-time-months-within {:count (abs months)}]
      (< weeks 0) [:i18n/tr :relative-time-weeks-within {:count (abs weeks)}]
      (< days 0) [:i18n/tr :relative-time-days-within {:count (abs days)}]
      (< hours 0) [:i18n/tr :relative-time-hours-within {:count (abs hours)}]
      (< minutes 0) [:i18n/tr :relative-time-minutes-within {:count (abs minutes)}]
      (< seconds 0) [:i18n/tr :relative-time-seconds-within {:count (abs seconds)}]
      :else [:i18n/tr :relative-time-now])))

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
