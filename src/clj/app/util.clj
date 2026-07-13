(ns app.util
  (:refer-clojure :exclude [hash])
  (:require
   [app.util.sanitize-filename :as sanitize-filename]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [app.datomic.shim :as datomic]
   [medley.core :as m]
   [ring.util.codec :as codec]
   [tick.core :as t])

  (:import
   (java.net URLDecoder URLEncoder)
   (java.net URLEncoder)
   (java.security SecureRandom)
   (java.text NumberFormat ParsePosition)
   (java.time LocalDate)
   (java.time.format DateTimeFormatter DateTimeFormatterBuilder ResolverStyle)
   (java.util Locale)))

(defn url-encode [^String v]
  (URLEncoder/encode v "UTF-8"))

(defn url-decode [^String v]
  (URLDecoder/decode v "UTF-8"))

(defn random-bytes [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(defn clean-number [n]
  (str/replace n #"[^+\d]" ""))

(defn no-blanks [s]
  (if
   (and (string? s) (str/blank? s))
    nil
    s))

(defn namespace-keys
  "Returns a function that takes a map and returns a map with all the keys renamed to namespace.

  ((namespace-keys \"foo\") {:a 1 :b 2}) => {:foo/a 1 :foo/b 2}
  "
  [namespace]
  (fn [m]
    (m/map-keys (fn [k]
                  (keyword namespace (name k))) m)))

(defn xxx>>
  ([msg x]
   (tap> msg)
   (tap> x)
   x)
  ([x]
   (tap> x)
   x))
(defn xxx
  ([x msg]
   (tap> msg)
   (tap> x)
   x)
  ([x]
   (tap> x)
   x))

(defn isort-by
  "Like sort-by but for a case-insenstive string sort" [keyfn coll]
  (sort-by
   (comp clojure.string/lower-case keyfn) coll))

(defn remove-nils
  "Returns the list/vec/map less any keys that have nil values"
  [m]
  (cond (map? m)
        (into {} (filter #(not (nil? (val %))) m))
        (list? m)
        (remove #(nil? %) m)
        (vector? m)
        (filterv #(some? %) m)
        (sequential? m)
        (remove #(nil? %) m)
        :else
        (throw (ex-info "remove-nils: Not implemented" {:type (type m)}))))

(defn blank->nil
  "If arg is an empty string, returns nil. Otherwise returns arg."
  [arg]
  (if (and (string? arg) (str/blank? arg))
    nil
    arg))

(defn remove-empty-strings
  "Returns the list/vec/map less any keys that have empty-strings as values values"
  [m]
  (let [seq-pred (fn [v] (not (if (string? v) (str/blank? v) false)))]
    (cond (map? m)
          (into {} (filter (fn [e]
                             (if (string? (val e))
                               (not (str/blank? (val e)))
                               true))
                           m))
          (list? m) (filter seq-pred m)
          (vector? m) (filterv seq-pred m)
          (sequential? m) (filter seq-pred m))))

(defn qp-bool
  "Parse the query parameter specified by k as a boolean"
  [req k]
  (Boolean/valueOf (-> req :query-params k)))

(defn post? [{:keys [request-method]}]
  (= :post request-method))

(defn put? [{:keys [request-method]}]
  (= :put request-method))

(defn delete? [{:keys [request-method]}]
  (= :delete request-method))

(defn make-get-request
  "Returns a new GET request based on `old-req` with a refreshed database value."
  ([old-req]
   (-> old-req
       (assoc :request-method :get)
       (assoc :db (datomic/db (:datomic-conn old-req)))))
  ([old-req extra]
   (-> old-req
       (assoc :db (datomic/db (:datomic-conn old-req)))
       (assoc :request-method :get)
       (merge extra))))

(defn comp-namer [var]
  (fn
    ([]
     (-> var meta :name str))
    ([s]
     (str (-> var meta :name) s))))

(defn comp-name
  [var]
  ((comp-namer var)))

(defn hash [kw]
  (str "#" (name kw)))

(defn id [kw]
  (name kw))

(defmacro endpoint-path
  [f]
  `(-> ~f var meta :name str))

(defn kw->str [kw]
  (when kw
    (str (namespace kw)
         "/"
         (name kw))))

(defn remove-deep
  "Walk data and in every ecountered map, dissoc all key/values in key-set"
  [key-set data]
  (walk/prewalk (fn [node] (if (map? node)
                             (apply dissoc node key-set)
                             node))
                data))

(defn replace-values [m key-set new-value]
  (m/map-kv (fn [k v]
              (if (contains? key-set k)
                [k new-value]
                [k v])) m))

(defn replace-deep
  "Walk data and in every ecountered map, replace the value of all keys in key-set with redaction-value"
  [key-set redaction-value data]
  (walk/prewalk (fn [node] (if (map? node)
                             (replace-values node key-set redaction-value)
                             node))
                data))
(comment
  (replace-values {:foo 1 :password "hunter2"} #{:password} "<REDACTED>")
  (replace-deep #{:password} "<REDACTED>" [{:user {:name "alice" :password "hunter2"}}])
  ;;
  )

(defn local-time-austria!
  "Returns a zoned date time for the local austrian time"
  [] (t/in (t/instant) "Europe/Vienna"))

(defn time-inside?
  "Returns true if   start <= reference < end"
  [start end reference]
  (and (t/>= reference start)
       (t/< reference end)))

(defn time-window [zdt]
  (let [time (t/time zdt)]
    (cond
      (time-inside? (t/time "00:00") (t/time "05:00") time)
      :nightowl

      (time-inside? (t/time "05:00") (t/time "12:00") time)
      :morning

      (time-inside? (t/time "12:00") (t/time "17:00") time)
      :afternoon

      (time-inside? (t/time "17:00") (t/time "23:59") time)
      :evening
      :else :wut?)))

(defn ensure-coll
  "If v is nil, returns nil. If v is a collection, returns a collection. Otherwise returns [v].
  Useful when you have a value that may be a collection or a single value, but you want to operate on a collection in any case.
  "
  [v]
  (when v
    (if (coll? v)
      v
      [v])))

(defn index-sort-by
  "Sorts the items in coll by k according to the order of values of k in manifest.
  Probably too slow on large collections."
  [manifest k coll]
  (sort-by (fn [item]
             (.indexOf manifest (get item k)))
           coll))

(defn ensure-uuid [v]
  (if (string? v)
    (parse-uuid v)
    v))

(defn ensure-uuid! [v]
  (if (uuid? v)
    v
    (parse-uuid v)))

(defn log-pprint
  "A pretty-print function suitable for use with
  `clojure.tools.logging` functions."
  [& args]
  (->> args
       (apply pprint/pprint)
       (with-out-str)
       (str "\n")))

(defn group-by-into-list [new-k f coll]
  (->> coll
       (group-by f)
       (mapv (fn [[k v]] (assoc k new-k v)))))

(def dummy-uuid #uuid "00000000-0000-0000-0000-000000000000")

(defn remove-dummy-uuid [coll]
  (remove #(= dummy-uuid %) coll))

(def currency-regex #"[$€£¥₹₪₩₿¢\s]")

(defn- remove-currency-signs
  "Remove any recognized currency signs from the string (c.f. [[currency-regex]])."
  [s]
  (str/replace s currency-regex ""))

(let [us (NumberFormat/getInstance (Locale. "en" "US"))
      de (NumberFormat/getInstance (Locale. "de" "DE"))
      fr (NumberFormat/getInstance (Locale. "fr" "FR"))
      ch (NumberFormat/getInstance (Locale. "de" "CH"))]
  (defn- parse-plain-number [number-separators s]
    (let [has-parens?       (re-matches #"\(.*\)" s)
          deparenthesized-s (str/replace s #"[()]" "")
          parse-pos         (ParsePosition. 0)
          parsed-number     (case number-separators
                              ("." ".,") (. us parse deparenthesized-s parse-pos)
                              ",."       (. de parse deparenthesized-s parse-pos)
                              ", "       (. fr parse (str/replace deparenthesized-s \space \u00A0) parse-pos) ; \u00A0 is a non-breaking space
                              ".’"       (. ch parse deparenthesized-s parse-pos))]
      (let [parsed-idx (.getIndex parse-pos)]
        (when-not (= parsed-idx (count deparenthesized-s))
          (throw (ex-info "Unexpected trailing characters - this is probably not a number"
                          {:full-string    s
                           :parsed-number  parsed-number
                           :parsed-string  (.substring deparenthesized-s 0 parsed-idx)
                           :ignored-string (.substring deparenthesized-s parsed-idx)}))))
      (if has-parens?
        ;; By casting to double we ensure that the sign is preserved for 0.0
        (- (double parsed-number))
        parsed-number))))

(def NUMBER-SEPARATORS-US  ".,")
(def NUMBER-SEPARATORS-DE  ",.")

(defn parse-number
  "Parse an integer or float"
  ([s]
   (parse-number NUMBER-SEPARATORS-DE s))
  ([number-separators s]
   (try
     (->> s
          (str/trim)
          (remove-currency-signs)
          (parse-plain-number number-separators))
     (catch Exception e
       (tap> [:error (format "''%s'' is not a recognizable number" s) :ex e])
       nil))))

(defn sanitize-filename [s]
  (sanitize-filename/sanitize s))

(defn content-disposition-filename
  "Format a filename for use in a Content-Disposition header. The filename is URL-encoded and prefixed with either 'inline' or 'attachment' depending on the value of inline?"
  ([name]
   (content-disposition-filename name true))
  ([name inline?]
   (let [prefix (if inline? "inline" "attachment")]
     (format "%s; filename*=UTF-8''%s" prefix (codec/url-encode (sanitize-filename name) "utf-8")))))

(defn last-n [n ^String s]
  (when s
    (apply str (take-last n s))))

(comment
  (index-sort-by [3 2 1] :id [{:id 1} {:id 2} {:id 3}])

  (parse-number "100.200,50")

  ;;
  )

(defmacro thread [& body]
  `(Thread/startVirtualThread
    (fn [] ~@body)))

(defmacro while-some
  {:clj-kondo/lint-as 'clojure.core/let}
  [bindings & body]
  `(loop []
     (when-some ~bindings
       ~@body
       (recur))))
