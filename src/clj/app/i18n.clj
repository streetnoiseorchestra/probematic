(ns app.i18n
  (:require
   [app.i18n.fluent :as fluent]
   [app.i18n.tempura :as tempura]
   [clojure.set :refer [intersection]]
   [clojure.string :as str]
   [taoensso.encore :as enc])
  (:import
   [java.util Locale]))

(def default-locale tempura/default-locale)

(defrecord LocaleTranslations [tempura fluent])

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
  "Loads the Tempura dictionaries and creates lazy Fluent locale states."
  []
  (into {}
        (map (fn [[locale dictionary]]
               [locale (->LocaleTranslations dictionary
                                             (fluent/new-locale locale))]))
        (tempura/read-langs)))

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
          lang-intersection (intersection langs-set accept-langs-set)
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

(defn- fluent-locale [lang-data locale]
  (let [locale-data (get lang-data locale)]
    (when (instance? LocaleTranslations locale-data)
      (:fluent locale-data))))

(defn- fluent-translation [lang-data locales fallback-locale resource-ids resource-data]
  (some (fn [resource-id]
          (when (keyword? resource-id)
            (some (fn [locale]
                    (some-> (fluent-locale lang-data locale)
                            (fluent/translate resource-id resource-data)))
                  (candidate-locales lang-data locales fallback-locale))))
        resource-ids))

(defn- tempura-langs [lang-data]
  (update-vals lang-data
               #(if (instance? LocaleTranslations %)
                  (:tempura %)
                  %)))

(defn tr
  "Translates `resource-ids` with Fluent first and Tempura second.

  Namespaced Fluent keys select a matching FTL filename. Fluent translation
  data must be a map. Tempura translation data remains a vector."
  ([opts locales resource-ids]
   (tr opts locales resource-ids nil))
  ([opts locales resource-ids resource-data]
   (or (fluent-translation (:dict opts)
                           locales
                           (:default-locale opts default-locale)
                           resource-ids
                           resource-data)
       (tempura/tr (assoc opts :dict (tempura-langs (:dict opts)))
                   locales
                   resource-ids
                   (when-not (map? resource-data) resource-data)))))

(defn tr-with
  ([param-langs langs]
   (when (first langs)
     (partial tr (tr-opts param-langs) langs))))

(defn tr-from-req [req]
  (:tr req))

(defn parse-http-accept-header
  "Parses HTTP Accept header and returns sequence of [choice weight] pairs
  sorted by weight."
  [header]
  (sort-by second enc/rcompare
           (for [choice (remove str/blank? (str/split (str header) #","))]
             (let [[lang q] (str/split choice #";")]
               [(str/trim lang)
                (or (when q (enc/as-?float (get (str/split q #"=") 1)))
                    1)]))))

(defn browser-lang [headers]
  (->> (get headers "accept-language")
       (parse-http-accept-header)
       (mapv first)))
