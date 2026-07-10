(ns app.i18n.fluent
  (:require
   [clojure.java.io :as io]
   [noahtheduke.fluent :as fluent]))

(def ^:private default-resource-ns :app)

(defn resource-path
  "Returns the classpath resource for `locale` and `resource-ns`."
  [locale resource-ns]
  (format "lang/%s/%s.ftl" (name locale) (name resource-ns)))

(defn new-locale
  "Creates lazy Fluent resource state for `locale`."
  [locale]
  {:locale   (keyword locale)
   :bundles_ (atom {})})

(defn- load-bundle [locale resource-ns]
  (when-let [resource (io/resource (resource-path locale resource-ns))]
    (fluent/build (name locale) (slurp resource))))

(defn- bundle-for [{:keys [locale bundles_]} resource-ns]
  (if (contains? @bundles_ resource-ns)
    (get @bundles_ resource-ns)
    (locking bundles_
      (if (contains? @bundles_ resource-ns)
        (get @bundles_ resource-ns)
        (let [bundle (load-bundle locale resource-ns)]
          (swap! bundles_ assoc resource-ns bundle)
          bundle)))))

(defn- missing-message? [exception]
  (let [{:keys [id errors]} (ex-data exception)]
    (and id (not errors))))

(defn translate
  "Translates keyword `id` with map `data`.

  Qualified keywords select `lang/<locale>/<namespace>.ftl`.
  Unqualified keywords select `lang/<locale>/app.ftl`.
  The keyword name selects the Fluent message identifier.
  Returns `nil` when the resource or message does not exist."
  [locale-state id data]
  (let [resource-ns (or (some-> id namespace keyword) default-resource-ns)]
    (when-let [bundle (bundle-for locale-state resource-ns)]
      (try
        (fluent/format bundle (name id) data)
        (catch clojure.lang.ExceptionInfo exception
          (cond
            (missing-message? exception)
            nil

            (not (or (nil? data) (map? data)))
            (throw (ex-info "Fluent translation data must be a map"
                            {:id id :data data}
                            exception))

            :else
            (throw exception)))))))
