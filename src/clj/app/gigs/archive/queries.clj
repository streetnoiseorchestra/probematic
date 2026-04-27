(ns app.gigs.archive.queries
  (:require
   app.gigs.service
   [clojure.string :as str]
   [tick.core :as t]))

(defn current-year []
  (str (t/year (t/date))))

(defn- year-string [{:gig/keys [date]}]
  (some-> date t/year str))

(defn- year-long [year]
  (try
    (Long/parseLong (str year))
    (catch NumberFormatException _
      nil)))

(defn normalize-year [year]
  (let [year (some-> year str/trim)]
    (if (and (seq year) (year-long year))
      year
      (current-year))))

(defn past-gigs [db]
  (app.gigs.service/gigs-past-page db 0 ##Inf))

(defn archive-years [past-gigs selected-year]
  (->> past-gigs
       (keep year-string)
       (cons selected-year)
       distinct
       (sort-by year-long >)))

(defn gigs-for-year [past-gigs selected-year]
  (filter #(= selected-year (year-string %)) past-gigs))

(defn page-data [db ?year]
  (let [selected-year (normalize-year ?year)
        past-gigs     (past-gigs db)]
    {:selected-year selected-year
     :years         (archive-years past-gigs selected-year)
     :gigs          (gigs-for-year past-gigs selected-year)}))
