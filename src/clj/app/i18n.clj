(ns app.i18n
  (:require
   [tick.core :as t]
   [clojure.edn :as edn]
   [clojure.java.io :as clojure.java.io]
   [clojure.pprint :as clojure.pprint]
   [clojure.set :refer [intersection]]
   [clojure.string :as str]
   [taoensso.encore :as enc]
   [taoensso.tempura :as tempura :refer [tr]])
  (:import
   [java.util Locale]))

(def default-locale :en)

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

(defn load-resource [filename & second]
  (try
    (let [content (enc/read-edn (slurp (clojure.java.io/resource filename)))]
      (if (not content)
        (if (not second)
          (load-resource (str "/" filename) :second)
          (throw
           (ex-info "Failed to load dictionary resource"
                    {:filename filename})))
        content))
    (catch Exception _
      (throw
       (ex-info "Failed to load dictionary resource"
                {:filename filename})))))

(defn read-langs []
  {:en (load-resource "lang/en.edn")
   :de (load-resource "lang/de.edn")})

(defn tr-opts [param-langs] {:dict param-langs :default-locale default-locale})

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
(comment (parse-http-accept-header nil)
         (parse-http-accept-header "en-GB")
         (parse-http-accept-header "en-GB,en;q=0.8,en-US;q=0.6")
         (parse-http-accept-header "en-GB  ,  en; q=0.8, en-US;  q=0.6")
         (parse-http-accept-header "a,")
         (parse-http-accept-header "es-ES, en-US"))

(defn browser-lang [headers]
  (->> (get headers "accept-language")
       (parse-http-accept-header)
       (mapv first)))

;;;;;;  EDN <-> PO/POT

(def ^:private remove-empty-lines
  "Transducer that remove groups of empty lines."
  (filter #(not= "" (first %))))

(def ^:private split-on-blank
  "Transducer that splits on blank lines."
  (partition-by #(= % "")))

(declare parse-comments)
(declare parse-keys)

(def ^:private parse-entries
  (let [comment-line? (fn [line] (str/starts-with? line "#"))]
    (map (fn [lines]
           (let [[comments keys] (partition-by comment-line? lines)]
             {:comments (parse-comments comments)
              :keys     (parse-keys keys)})))))

(defn- parse-comments [comments]
  (into {}
        (for [comment comments]
          (let [len          (count comment)
                proper?      (>= len 2)
                start        (when proper? (subs comment 0 2))
                rest         (when proper? (subs comment 2))
                remove-empty #(filter (partial not= "") %)]
            (case start
              "#:" [:reference (remove-empty (str/split rest #" +"))]
              "#," [:flags (remove-empty (str/split rest #" +"))]
              "# " [:translator-comment rest]
              ;; TODO: add other types
              [:unknown-comment comment])))))

(defn- join-sequential-strings [rf]
  (let [acc (volatile! nil)]
    (fn
      ([] (rf))
      ([res]
       (if-let [a @acc]
         (do (vreset! acc nil)
             (rf res (apply str (reverse a))))
         (rf res)))
      ([res i]
       (if (string? i)
         (do (vswap! acc conj i)
             res)
         (rf (or (when-let [a @acc]
                   (vreset! acc nil)
                   (rf res (apply str a)))
                 res)
             i))))))

(def ^:private keywordize-things
  (map #(if (string? %) % (keyword %))))

(defn- parse-keys [keys]
  (apply hash-map
         (transduce (comp join-sequential-strings
                          keywordize-things)
                    conj
                    []
                    ;; XXX: double hack for double fun!
                    (edn/read-string (str "[" (apply str (interpose " " keys)) "]")))))

(def ^:private parser
  (comp split-on-blank
        remove-empty-lines
        parse-entries))

(defn parse
  "Parse the PO file given as stream of lines `l`."
  [l]
  (transduce parser conj [] l))

(defn parse-from-string
  "Parse the PO file given as string."
  [s]
  (parse (str/split-lines s)))

(defn escape-quotes [s]
  (clojure.string/escape s {\\ "\\\\" \" "\\\""}))

(defn ->po-entry [[key untranslated-str]]
  (format "#: %s
#, ycp-format
msgctxt \"%s\"
msgid \"%s\"
msgstr \"\"" (str key) (escape-quotes (str key)) (escape-quotes untranslated-str)))

(defn pot-header []
  (format "msgid \"\"
msgstr \"\"
\"Project-Id-Version: Probematic\\n\"
\"POT-Creation-Date: %s\\n\"
\"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\\n\"
\"Content-Type: text/plain; charset=UTF-8\\n\"
\"Content-Transfer-Encoding: 8bit\\n\"
" (t/format (t/formatter "YYYY-MM-dd HH:mmxx") (t/zoned-date-time))))

(defn locale->seq
  "Convert a tempura locale map into a list of key/string tuples"
  [m]
  (->> m
       (reduce (fn [acc [k sub-m]]
                 (concat acc
                         (if (map? sub-m)
                           (map (fn [[sub-k string]]
                                  [(keyword (name k) (name sub-k)) string]) sub-m)
                           [[k sub-m]])))
               [])
       ;;  Cannot have blank msgids
       (filter (fn [[_k untranslated-str]]
                 (not (str/blank? untranslated-str))))))

(defn gen-pot [fname dname]
  (let [m (load-resource fname)
        entries (map ->po-entry (locale->seq m))]
    (spit (str "resources/" dname)
          (str (pot-header)
               (str/join "\n\n" entries)))))

(defn convert-po [locale]
  (let [fname (str "resources/lang/" locale ".po")
        dname (str "resources/lang/" locale ".edn")
        fallback-m (load-resource "lang/en.edn")
        po-contents (slurp fname)
        parsed (parse-from-string po-contents)
        translated-map (->> parsed
                            (reduce (fn [acc {:keys [keys]}]
                                      (let [{:keys [msgid msgstr msgctxt]} keys]
                                        (if msgid
                                          (let [kw (edn/read-string msgctxt)
                                                ns (keyword (namespace kw))
                                                n (keyword (name kw))
                                                path (if ns [ns n] [n])
                                                finalstr (if-not (str/blank? msgstr) msgstr (get-in fallback-m path))]
                                            (assoc-in acc path finalstr))
                                          acc)))

                                    {}))]
    (clojure.pprint/pprint translated-map (clojure.java.io/writer dname))))

(comment
  ;; create pot
  (gen-pot "lang/en.edn" "lang/app.pot") ;; rcf
  ;; convert translated po to edn
  (convert-po "de")
  (let [po-contents (slurp "resources/lang/test.po")
        parsed (parse-from-string po-contents)]
    parsed)

;;
  )
