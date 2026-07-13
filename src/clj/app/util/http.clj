(ns app.util.http
  (:require
   [clojure.set :as set]
   [app.util :as util]
   [clojure.string :as str]
   [medley.core :as m]))

(defn path-param
  "Fetches the path param k"
  [req k]
  (-> req :path-params k))

(defn query-param
  "Fetches the query param k"
  [req k]
  (-> req :params k))

(defn query-param-uuid
  "Fetches the  param k coerced as a uuid"
  [req k]
  (util/ensure-uuid (query-param req k)))

(defn query-param-uuid!
  "Like query-param-uuid but throws if the param doesn't exist."
  [req k]
  (util/ensure-uuid! (query-param req k)))

(defn path-param-uuid
  "Fetches the path param k coerced as a uuid"
  [req k]
  (util/ensure-uuid (path-param req k)))

(defn path-param-uuid!
  "Like path-param-uuid but throws if the param doesn't exist."
  [req k]
  (util/ensure-uuid! (path-param req k)))

(defn no-blanks [m]
  (m/map-vals #(if (and (string? %) (str/blank? %)) nil %) m))

(defn ns-qualify-key
  [m ns]
  (m/map-keys #(keyword  (name ns) (name %)) m))

;;; TODO refactor usages of this fn to util/ensure-uuid
(defn ensure-uuid
  "DEPRECATED. use util/ensure-uuid"
  [v]
  (if (string? v)
    (parse-uuid v)
    v))

(defn check->bool [v]
  (= "on" v))

(defn order-invert [o]
  (get {:asc :desc
        :desc :asc} o))

(defn serialize-sort-param [query-param-field-mapping {:keys [field order]}]
  (when field
    (str "?sort=" (get (set/map-invert query-param-field-mapping) field)
         (order-invert (or order :desc)))))

(defn sort-param-by-field [sort-spec field]
  (or
   (m/find-first #(= field (:field %)) sort-spec)
   {:field field :order :asc}))

(defn parse-sort-param [query-param-field-mapping v]
  (let [[param order] (str/split v #":")
        order (if (= "desc" order) :desc :asc)
        field (get query-param-field-mapping param nil)]
    (when field
      {:field  field
       :order order})))

(defn sort-param [{:keys [query-params]} query-param-field-mapping]
  (let [sort-spec (->> (util/ensure-coll (get query-params "sort" []))
                       (remove str/blank?)
                       (mapv (partial parse-sort-param query-param-field-mapping)))]
    (when (seq sort-spec)
      sort-spec)))

(defn sort-by-spec [sorting coll]
  #_(tap> {:sorting sorting
           :fields (mapv :field sorting)
           :dir (if (= :asc (-> sorting first :order)) :asc :desc)})
  (let [asc? (= :asc (-> sorting first :order))
        r (sort-by (fn [v]
                     (mapv (fn [s]
                             (if (string? s)
                               (str/lower-case s)
                               s))
                           ((apply juxt (map :field sorting)) v))) coll)]
    (if asc? r (reverse r))))
