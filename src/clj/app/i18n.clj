(ns app.i18n
  (:require
   [app.i18n.fluent :as fluent]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [dev.onionpancakes.chassis.core :as chassis])
  (:import
   [java.util Locale]))

(def default-locale :en)

(def ^:private supported-locales [default-locale :de])

(def ^:dynamic *translator*
  "Provides the translator while Chassis resolves component-generated translation nodes."
  nil)

(defn java-locale
  "Returns a [[java.util.Locale]] for `locale`, defaulting to English when blank."
  [locale]
  (cond
    (instance? Locale locale) locale
    (nil? locale) Locale/ENGLISH
    :else
    (let [tag (-> (cond
                    (keyword? locale) (name locale)
                    (string? locale)  locale
                    :else             (str locale))
                  (str/replace "_" "-"))]
      (case tag
        ""      Locale/ENGLISH
        "en"    Locale/ENGLISH
        "de"    Locale/GERMAN
        "en-US" Locale/US
        "de-DE" Locale/GERMANY
        (let [parsed (Locale/forLanguageTag tag)]
          (if (str/blank? (.getLanguage parsed))
            Locale/ENGLISH
            parsed))))))

(defn req-locale
  "Returns the request [[java.util.Locale]], defaulting to English when blank."
  [req]
  (java-locale (:current-locale req)))

(defn read-langs
  "Creates lazy Fluent resource state for each supported locale."
  []
  (into {}
        (map (fn [locale]
               [locale (fluent/new-locale locale)]))
        supported-locales))

(defn tr-opts [param-langs]
  {:dict param-langs :default-locale default-locale})

(defn supported-lang [param-langs accept-langs]
  (if-not (empty? accept-langs)
    (let [accepted-empty-removed (filter #(>= (count %1) 2) accept-langs)
          keyword-accepted-langs (vec
                                  (map
                                   #(keyword (subs % 0 2))
                                   accepted-empty-removed))
          accept-langs-set (into #{} keyword-accepted-langs)
          langs-set (into #{} (keys param-langs))
          lang-intersection (set/intersection langs-set accept-langs-set)
          lang-match (first
                      (filter
                       #(contains? lang-intersection %)
                       keyword-accepted-langs))]
      (or lang-match default-locale))
    default-locale))

(defn- locale-name [locale]
  (cond
    (keyword? locale) (name locale)
    (string? locale)  locale
    :else             (str locale)))

(defn- candidate-locales [lang-data locales fallback-locale]
  (let [selected-locale (supported-lang lang-data (mapv locale-name (or locales [])))]
    (distinct [selected-locale fallback-locale])))

(defn- fluent-translation [lang-data locales fallback-locale resource-ids resource-data]
  (some (fn [resource-id]
          (cond
            (keyword? resource-id)
            (some (fn [locale]
                    (some-> (get lang-data locale)
                            (fluent/translate resource-id resource-data)))
                  (candidate-locales lang-data locales fallback-locale))

            (string? resource-id)
            resource-id))
        resource-ids))

(defn tr
  "Translates the first available candidate in `resource-ids` with Fluent.

  Qualified Fluent keys select a matching FTL filename; unqualified Fluent
  keys select `app.ftl`. A string candidate is returned as a literal fallback.
  Translation data must be a map."
  ([opts locales resource-ids]
   (tr opts locales resource-ids nil))
  ([opts locales resource-ids resource-data]
   (when-not (or (nil? resource-data) (map? resource-data))
     (throw (ex-info "Fluent translation data must be a map"
                     {:resource-ids resource-ids
                      :data resource-data})))
   (fluent-translation (:dict opts)
                       locales
                       (:default-locale opts default-locale)
                       (if (keyword? resource-ids) [resource-ids] resource-ids)
                       resource-data)))

(defn tr-with
  ([param-langs langs]
   (when (first langs)
     (partial tr (tr-opts param-langs) langs))))

(defn tr-from-req [req]
  (:tr req))

(defn- translation-node? [value]
  (and (vector? value)
       (= :i18n/tr (first value))))

(defn- translation-resource-ids [node]
  (let [resource-ids (second node)]
    (cond
      (keyword? resource-ids) [resource-ids]
      (vector? resource-ids)  resource-ids
      :else
      (throw (ex-info "Malformed :i18n/tr translation data node"
                      {:node node})))))

(defn- resolve-translation-node [translator node]
  (when-not (<= 2 (count node) 3)
    (throw (ex-info "Malformed :i18n/tr translation data node"
                    {:node node})))
  (when-not translator
    (throw (ex-info "Cannot resolve :i18n/tr without a translator"
                    {:node node})))
  (let [resource-ids (translation-resource-ids node)]
    (if (= 2 (count node))
      (translator resource-ids)
      (translator resource-ids (nth node 2)))))

(defmethod chassis/resolve-alias :i18n/tr
  [_tag _attrs children]
  (resolve-translation-node *translator* (into [:i18n/tr] children)))

(defn resolve-translations
  "Resolves every `:i18n/tr` data node in `value` with `translator`.

  A node contains a translation key or candidate vector and optional data.
  Resolution traverses children, attributes, component props, and nested
  collections. Trees without translation nodes do not require a translator."
  [translator value]
  (walk/postwalk
   (fn [node]
     (if (translation-node? node)
       (resolve-translation-node translator node)
       node))
   value))

(defn parse-http-accept-header
  "Parses HTTP Accept `header` into `[choice weight]` pairs sorted by weight."
  [header]
  (sort-by second #(compare %2 %1)
           (for [choice (remove str/blank? (str/split (str header) #","))]
             (let [[lang q] (str/split choice #";")]
               [(str/trim lang)
                (or (when q
                      (try
                        (some-> q
                                (str/split #"=" 2)
                                second
                                parse-double)
                        (catch NumberFormatException _exception
                          nil)))
                    1)]))))

(defn browser-lang [headers]
  (->> (get headers "accept-language")
       (parse-http-accept-header)
       (mapv first)))
