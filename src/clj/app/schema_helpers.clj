(ns app.schema-helpers
  (:require
   [cljc.java-time.duration :as jt.duration]
   [clojure.string :as string]
   [malli.core :as m]
   [malli.transform :as mt]
   [tick.core :as t]))

(def EmailAddress (m/-simple-schema {:type            :email-address
                                     :pred            #(and (string? %)
                                                            (re-matches #"^.+\@.+\..+$" %))
                                     :type-properties {:error/fn           '(fn [{:keys [schema value]} _]
                                                                              (str value " does not match regex " #"^.+\@.+\..+$"))
                                                       :decode/string      string/lower-case
                                                       :encode/string      string/lower-case
                                                       :decode/json        string/lower-case
                                                       :encode/json        string/lower-case
                                                       :json-schema/type   "string"
                                                       :json-schema/format "string"}}))
(def DurationSchema (m/-simple-schema {:type            :duration
                                       :pred            t/duration?
                                       :type-properties {:error/message      "should be a valid duration"
                                                         :decode/string      #(jt.duration/parse %)
                                                         :encode/string      #(when % (str %))
                                                         :decode/json        #(jt.duration/parse %)
                                                         :encode/json        #(when % (str %))
                                                         :json-schema/type   "string"
                                                         :json-schema/format "duration"
                                                         :swagger/example    "PT6H"}}))
(def InstantSchema (m/-simple-schema {:type            :instant
                                      :pred            t/instant?
                                      :type-properties {:error/message      "should be a valid instant"
                                                        :decode/string      #(some-> % t/instant)
                                                        :encode/string      str
                                                        :decode/json        #(some-> % t/instant)
                                                        :encode/json        str
                                                        :decode/datomic     #(some-> % t/instant)
                                                        :encode/datomic     #(some-> % t/inst)
                                                        :json-schema/type   "string"
                                                        :json-schema/format "date-time"}}))

(def InstSchema (m/-simple-schema {:type            :app.schemas/inst
                                   :pred            inst?
                                   :type-properties {:error/message "should be a valid inst"
                                                     :decode/string #(some-> % t/inst)
                                                     :encode/string str
                                                     :decode/json #(some-> % t/inst)
                                                     :encode/json str
                                                     :json-schema/type "string"
                                                     :json-schema/format "date-time"}}))

;; This is a date stored as an instant. So the time information should always be midnight and not used
(def InstDateSchema (m/-simple-schema {:type            :app.schemas/instdate
                                       :pred            t/date?
                                       :type-properties {:error/message      "should be a valid inst with midnight as the time part"
                                                         :decode/string      #(some-> % t/inst)
                                                         :encode/string      str
                                                         :decode/datomic     #(some-> % t/date)
                                                         :encode/datomic     #(some-> % (t/at (t/midnight)) (t/in "UTC") t/inst)
                                                         :decode/json        #(some-> % t/date)
                                                         :encode/json        str
                                                         :json-schema/type   "string"
                                                         :json-schema/format "date"}}))

(def DateSchema (m/-simple-schema {:type            :app.schemas/date
                                   :pred            t/date?
                                   :type-properties {:error/message      "should be a valid date"
                                                     :decode/string      #(some-> % t/date)
                                                     :encode/string      str
                                                     :decode/json        #(some-> % t/date)
                                                     :encode/json        str
                                                     :json-schema/type   "string"
                                                     :json-schema/format "date"}}))

(def DateTimeSchema (m/-simple-schema {:type            :app.schemas/datetime
                                       :pred            t/date-time?
                                       :type-properties {:error/message      "should be a valid datetime"
                                                         :decode/string      #(some-> % t/date-time)
                                                         :encode/string      str
                                                         :decode/json        #(some-> % t/date-time)
                                                         :encode/json        str
                                                         :json-schema/type   "string"
                                                         :json-schema/format "date-time"}}))

(defn blank->nil [v]
  (if (string/blank? v)
    nil
    v))

;; This schema can store time with sub-minute resolution
(def TimeSchema (m/-simple-schema {:type            :app.schemas/time
                                   :pred            t/time?
                                   :type-properties {:error/message      "should be a valid time"
                                                     :decode/string      #(some-> % blank->nil  t/time)
                                                     :encode/string      str
                                                     :decode/json        #(some-> % blank->nil t/time)
                                                     :encode/json        str
                                                     :decode/datomic     #(some-> % t/time)
                                                     :encode/datomic     str
                                                     :json-schema/type   "string"
                                                     :json-schema/format "time"}}))
;; This schema stores time with only minute resolution
(def MinuteTimeSchema (m/-simple-schema {:type            :app.schemas/minute-time
                                         :pred            (fn [v]
                                                            (and (t/time? v)
                                                                 (= 0 (t/second v))
                                                                 (= 0 (t/millisecond v))
                                                                 (= 0 (t/microsecond v))
                                                                 (= 0 (t/nanosecond v))))
                                         :type-properties {:error/message      "should be a valid time with minute resolution"
                                                           :decode/string      #(some-> % t/time)
                                                           :encode/string      str
                                                           :decode/json        #(some-> % t/time)
                                                           :encode/json        str
                                                           :decode/datomic     #(some-> % t/time)
                                                           :encode/datomic     str
                                                           :json-schema/type   "string"
                                                           :json-schema/format "time"}}))
(def NonBlankString
  (m/-simple-schema {:type :app.schemas/non-blank-string
                     :pred #(and (string? %) (not (string/blank? %)))
                     :type-properties {:error/message "should not be blank"
                                       :decode/string str
                                       :encode/string str
                                       :decode/json str
                                       :encode/json str
                                       :json-schema/type "string"
                                       :json-schema/format "string"}}))

(def parse-boolean-checkbox
  #(case %
     true true
     false false
     (contains? #{"true" "on"} %)))

(def CheckboxBoolean
  (m/-simple-schema {:type :app.schemas/checkbox-boolean
                     :pred boolean?
                     :type-properties {:error/message "Should be true or false"
                                       :decode/string parse-boolean-checkbox
                                       :encode/string str}}))

(def DatomicRef
  (m/-simple-schema {:type :app.schemas/datomic-ref
                     :pred (fn [v]
                             (and (vector? v)
                                  (= 2 (count v))
                                  (keyword? (first v))
                                  (some? (second v))))
                     :type-properties {:error/message "should be a valid datomic ref, e.g., [:a/keyword value] "
                                       :decode/string str
                                       :encode/string str
                                       :decode/datomic identity
                                       :encode/datomic identity
                                       :decode/json str
                                       :encode/json str
                                       :json-schema/type "string"
                                       :json-schema/format "string"}}))

(def common-registry
  {:app.schemas/email-address    EmailAddress
   :app.schemas/duration         DurationSchema
   :app.schemas/instant          InstantSchema
   :app.schemas/inst             InstSchema
   :app.schemas/instdate         InstDateSchema
   :app.schemas/date             DateSchema
   :app.schemas/date-time        DateTimeSchema
   :app.schemas/time             TimeSchema
   :app.schemas/minute-time      MinuteTimeSchema
   :app.schemas/non-blank-string NonBlankString
   :app.schemas/datomic-ref      DatomicRef
   :app.schemas/checkbox-boolean CheckboxBoolean})

(def interval?
  "Represents an interval as a map with two properties,
   a beginning and an end. Converts into Interval record on decode.
   On encode just uses the default encoding strategy for a map with
   two instants."
  [:map
   {:registry common-registry :decode/json {:enter '#(with-meta % {:ol/type :interval})}}
   [:beginning :app.schemas/instant]
   [:end {:optional true} [:maybe :app.schemas/instant]]])

(def vectorize-transformer
  (let [coders {:vector {:compile
                         (fn [schema _]
                           (when (some-> schema m/properties (find :vectorize))
                             #(cond-> % (not (vector? %)) (vector))))}}]
    (mt/transformer
     {:decoders coders
      :encoders coders})))

(def namespace-enum-transformer
  (let [coders {:enum {:compile
                       (fn [schema _]
                         (when (some-> schema m/properties (find :kw-namespace))
                           (let [ns (namespace (nth  (m/form schema) 2))]
                             (fn [v]
                               (keyword  (name ns) (name v))))))}}]
    (mt/transformer
     {:decoders coders
      :encoders coders})))

(comment
  (require
   '[clojure.test :refer [is]]
   '[malli.error :as me]
   '[malli.core :as m]
   '[malli.transform :as mt]
   '[malli.util :as mu])

  (meta (with-meta {:beginning (t/now)} {:type :interval}))

  ;; Invalid email returns nil
  (me/humanize (m/explain EmailAddress "hello@world@"))

  (t/date-time)
  (t/new-duration 3600000 :millis)
  (t/duration? (jt.duration/parse "PT1H"))

  (jt.duration/parse (str (t/new-duration 3600000 :millis)))

  (t/duration "PT1H")

  (str (t/new-duration 3600000 :millis))

  (str (t/new-duration 3600000 :millis)))
