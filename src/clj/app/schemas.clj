(ns app.schemas
  (:require
   [app.schema-helpers :as schemas]
   [app.schemas.http-api :as http-api]
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [malli.transform :as mt]))

(def registry
  (mr/composite-registry
   (m/default-schemas)
   app.schema-helpers/common-registry
   http-api/registry))

(def malli-opts {:registry registry})

(defn schema->map
  "Converts a malli schema into a plain data map"
  [s]
  (m/walk
   s
   (fn [schema _ children _]
     (-> (m/properties schema malli-opts)
         (assoc :malli/type (m/type schema))
         (cond-> (seq children) (assoc :malli/children children))))
   malli-opts))

(defn schema-name [schema]
  (get (schema->map schema) :name "unknown schema name. it is missing :name"))

(defn valid? [schema value]
  (m/validate schema value malli-opts))

(defn explain [schema value]
  (m/explain schema value malli-opts))

(defn explain-human [schema value]
  (me/humanize (m/explain schema value malli-opts)))

(def humanize me/humanize)

(defn encode [schema value]
  (m/encode schema value malli-opts mt/string-transformer))

(defn throw-error [msg cause schema value]
  (throw
   (ex-info msg
            {:app/error-type :app.error.type/validation
             :schema (schema->map schema)
             :schema-name (schema-name schema)
             :value value
             :explain (explain-human schema value)}
            cause)))

(defn decode [schema value]
  (try
    (m/decode schema value malli-opts (mt/transformer mt/string-transformer mt/strip-extra-keys-transformer schemas/vectorize-transformer schemas/namespace-enum-transformer))
    (catch Exception e
      (throw-error "Decode failed" e schema value))))

(defn datomic-transformer []
  (mt/transformer
   {:name :datomic
    :decoders (assoc (mt/-string-decoders) :uuid identity)
    ;; :encoders (assoc  (mt/-string-encoders) :uuid identity)
    }))

(defn decode-datomic [schema doc]
  (m/decode schema doc malli-opts (mt/transformer datomic-transformer)))

(defn encode-datomic [schema doc]
  (m/encode schema doc malli-opts (mt/transformer datomic-transformer mt/strip-extra-keys-transformer)))

(defn schema
  ([s]
   (m/schema s malli-opts))
  ([s local-registry]
   (m/schema s
             (update malli-opts :registry mr/composite-registry local-registry))))

(defn enum-from [values]
  (into [:enum] values))

(def DatomicRefOrTempid [:or ::non-blank-string ::datomic-ref])

(comment
  (require '[malli.error :as me]
           '[malli.core :as m]
           '[malli.transform :as mt]
           '[malli.util :as mu]
           '[tick.core :as t])

  (def user1 {:user/id         1
              :user/username   "Joe"
              :user/active     true
              :user/email      "joe@example.com"
              :user/created_at (t/now)
              :user/updated_at (t/now)})
  (def invalid-user {:user/id         1
                     :user/username   "Joe"
                     :user/email      "foo@"
                     :user/created_at (t/now)
                     :user/updated_at (t/now)})
  (valid? :doc/user user1)
  (explain-human :doc/user user1)
  (valid? :doc/user invalid-user)
  (explain-human :doc/user invalid-user)
  (valid? ::instant (t/now))
  (explain-human ::email-address "foo@foo.com")

  (decode schemas/TimeSchema "13:00")

;;
  )
