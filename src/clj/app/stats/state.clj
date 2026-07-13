(ns app.stats.state
  (:require
   [app.urls :as urls]
   [clojure.set :as set]
   [clojure.string :as str]
   [tick.core :as t]))

(def default-timespan-id "last-three-months")

(def timespan-options
  [{:id "last-three-months"
    :label-key :statistics/last-three-months
    :period (t/new-period 3 :months)}
   {:id "last-six-months"
    :label-key :statistics/last-six-months
    :period (t/new-period 6 :months)}
   {:id "last-one-year"
    :label-key :statistics/last-year
    :period (t/new-period 12 :months)}])

(def timespan-ids
  (into #{} (map :id) timespan-options))

(def query-param-field-mapping
  {"name" :member/name
   "gigs-attended" :gigs-attended
   "gigs-percent" :gig-rate
   "probes-attended" :probes-attended
   "probes-percent" :probe-rate
   "last-seen" :last-seen})

(def field-query-param-mapping
  (set/map-invert query-param-field-mapping))

(def default-sort-spec
  [{:field :member/name :order :asc}])

(defn- ensure-coll [value]
  (cond
    (nil? value) []
    (sequential? value) value
    :else [value]))

(defn selected-timespan-id [{:keys [query-params]}]
  (let [timespan-id (get query-params "timespan")]
    (if (contains? timespan-ids timespan-id)
      timespan-id
      default-timespan-id)))

(defn selected-timespan [req]
  (let [timespan-id (selected-timespan-id req)]
    (first (filter #(= timespan-id (:id %)) timespan-options))))

(defn selected-range [req]
  (let [{:keys [period]} (selected-timespan req)
        now              (t/inst (t/<< (t/zoned-date-time) (t/new-period 1 :days)))
        then             (t/inst (t/<< (t/zoned-date-time) period))]
    {:from then
     :to   now}))

(defn- parse-sort-param [value]
  (let [[param order] (str/split value #":")
        field         (get query-param-field-mapping param)]
    (when field
      {:field field
       :order (if (= "desc" order) :desc :asc)})))

(defn sort-spec [{:keys [query-params]}]
  (let [parsed (->> (ensure-coll (get query-params "sort"))
                    (remove str/blank?)
                    (keep parse-sort-param)
                    vec)]
    (if (seq parsed)
      parsed
      default-sort-spec)))

(defn- sort-token [{:keys [field order]}]
  (when-let [param (get field-query-param-mapping field)]
    (str param ":" (name order))))

(defn- current-sort-token [req]
  (some-> (first (sort-spec req)) sort-token))

(defn- stats-url [params]
  (str "/stats" (urls/append-qps (into (sorted-map) params))))

(defn timespan-url [req timespan-id]
  (let [timespan-id (if (contains? timespan-ids timespan-id)
                      timespan-id
                      default-timespan-id)]
    (stats-url (cond-> {"timespan" timespan-id}
                 (current-sort-token req) (assoc "sort" (current-sort-token req))))))

(defn- sort-spec-by-field [spec field]
  (or (some #(when (= field (:field %)) %) spec)
      {:field field
       :order :asc}))

(defn sort-url [req field]
  (stats-url {"timespan" (selected-timespan-id req)
              "sort"     (sort-token (update (sort-spec-by-field (sort-spec req) field)
                                             :order
                                             {:asc :desc
                                              :desc :asc}))}))
