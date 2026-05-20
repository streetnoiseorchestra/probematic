(ns app.gigs.queries
  (:require
   [app.datomic :as d]
   [app.gigs.archive.actions :as archive.actions]
   [app.gigs.domain :as domain]
   [app.queries :as q]
   [clojure.string :as str]
   [medley.core :as m]
   [tick.core :as t]))

(def past-gigs-limit 20)

(def default-archive-page-state
  {:search ""})

(defn- page [offset limit coll]
  (if (= limit ##Inf)
    (drop offset coll)
    (take limit (drop offset coll))))

(defn gigs-past-page [db offset limit]
  (->>
   (d/q '[:find (pull ?e pattern)
          :in $ ?time pattern
          :where
          [?e :gig/gig-id _]
          [?e :gig/date ?date]
          [(< ?date ?time)]]
        db (q/date-midnight-today!) [:gig/gig-id :gig/date :db/id])
   (map first)
   (sort-by :gig/date t/>)
   (page offset limit)
   (map :db/id)
   (d/pull-many db q/gig-pattern)
   (sort-by :gig/date t/>)
   (mapv domain/db->gig)))

(defn index-page-data [db]
  {:future-gigs (q/gigs-future db)
   :past-gigs   (gigs-past-page db 0 past-gigs-limit)})

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
  (gigs-past-page db 0 ##Inf))

(defn archive-years [past-gigs selected-year]
  (->> past-gigs
       (keep year-string)
       (cons selected-year)
       distinct
       (sort-by year-long >)))

(defn gigs-for-year [past-gigs selected-year]
  (filter #(= selected-year (year-string %)) past-gigs))

(defn normalize-archive-page-state [page-state]
  (-> default-archive-page-state
      (merge page-state)
      (update :search archive.actions/normalize-search)))

(defn- normalize-search-term [search]
  (some-> search str/trim str/lower-case not-empty))

(defn- matches-gig-search? [search gig]
  (if-let [search (normalize-search-term search)]
    (str/includes? (str/lower-case (or (:gig/title gig) "")) search)
    true))

(defn gigs-for-search [gigs search]
  (filter #(matches-gig-search? search %) gigs))

(defn archive-page-data
  ([db ?year]
   (archive-page-data db ?year nil))
  ([db ?year page-state]
   (let [page-state    (normalize-archive-page-state page-state)
         selected-year (normalize-year ?year)
         past-gigs     (past-gigs db)]
     {:selected-year selected-year
      :years         (archive-years past-gigs selected-year)
      :page-state    page-state
      :gigs          (-> (gigs-for-year past-gigs selected-year)
                         (gigs-for-search (:search page-state)))})))

(defn attendance-data [db {:gig/keys [gig-id] :as gig} show-committed?]
  (let [archived?   (domain/gig-archived? gig)
        attendances (if archived?
                      (q/attendances-for-gig db gig-id)
                      (q/attendance-for-gig-with-all-active-members db gig-id))]
    {:archived?   archived?
     :attendances attendances
     :sections    (q/attendance-plans-by-section-for-gig
                   db
                   attendances
                   (when (and (not archived?) show-committed?)
                     :committed-only?))
     :summary     (->> attendances
                       (group-by :attendance/plan)
                       (m/map-vals count))}))
