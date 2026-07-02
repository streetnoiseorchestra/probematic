(ns app.ui2.core
  (:require
   [app.util.error :as u.error]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.core]
   [flatland.ordered.map :refer [ordered-map]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.experimental.lite :as l]
   [malli.util :as mu]
   [medley.core :as medley]))

(defn cs [& names]
  (str/join " " (filter identity names)))

(defn raw? [v]
  (= dev.onionpancakes.chassis.core.RawString
     (type v)))

(defonce ^:dynamic *validate-opts* false)

(defmacro attr-map
  "Creates an ordered map type hinted with IPersistentMap for use with chassis."
  [& args]
  `^clojure.lang.IPersistentMap (ordered-map ~@args))

(defn wrap-text-node
  ([children]
   (wrap-text-node :span children))
  ([tag children]
   (if (or (raw? (first children))
           (and (string? (first children))
                (not= \< (first children))))
     [tag children]
     children)))

(defn bad-opt-value-callout
  [{:keys [point-of-interest-opts callout-opts]}]
  (let [{:keys [header body]} point-of-interest-opts
        source      ((requiring-resolve 'bling.core/point-of-interest) point-of-interest-opts)
        message     (not-empty (str/join "\n\n" (remove str/blank? [header source body])))
        callout-opts (merge callout-opts {:padding-top 1})]
    (tap> (with-meta [(str (:label callout-opts) "\n" message)] {:ansi/color true}))
    ((requiring-resolve 'bling.core/callout) callout-opts message)))

(defn warning-header
  [{:keys [component opt value]}]
  (apply (requiring-resolve 'bling.core/bling)
         (concat
          [[:italic "component: "] [:bold.blue (:name component)]
           "\n\n"
           [:italic "option:    "] [:bold.warning opt]
           "\n\n"
           [:italic "invalid:   "] [:bold (if (nil? value) "nil" value)]])))

(defn strip-should-be [msg]
  (if (re-find #"^(?i)should be" msg)
    (subs msg 10)
    msg))

(defn warning-body
  [{:keys [opt msg trace]}]
  (let [w (java.io.StringWriter.)]
    (u.error/print-trace trace w)
    (str
     ((requiring-resolve 'bling.core/bling)
      "Value for the attribute "
      [:bold.warning opt]
      " "
      msg
      "\n\n"
      [:italic "Compacted stacktrace"] "\n")
     w)))

(defn enable-opts-validation! []
  (alter-var-root #'*validate-opts* (constantly true)))

(defn disable-opts-validation! []
  (alter-var-root #'*validate-opts* (constantly false)))

(defn error->bling-opts [trace component explain]
  (map (fn [[k error]]
         {:point-of-interest-opts {:header (warning-header {:opt       k
                                                            :value     (get-in explain [:value k])
                                                            :component component})

                                   :body (warning-body {:opt   k
                                                        :trace trace
                                                        :msg   (str/join "; " error)})}
          :callout-opts           {:type  :warning
                                   :theme :gutter
                                   :label "WARNING​ Invalid ui attribute"}})
       (me/humanize explain
                    {:resolve me/-resolve-root-error
                     :errors  (-> me/default-errors
                                  (assoc ::m/missing-key {:error/fn (fn [_ _]
                                                                      "is missing")}))})))

(defn- stack-frame->trace-element
  [[cls method file line]]
  (let [cls                  (str cls)
        [ns separator method] (if (str/includes? cls "/")
                                (let [[ns method] (str/split cls #"/" 2)]
                                  [ns "/" method])
                                [cls "." (str method)])]
    {:file      (when-not (= "NO_SOURCE_FILE" file) file)
     :line      line
     :ns        ns
     :separator separator
     :method    method}))

(defn stack-trace []
  (->> (.getStackTrace (Thread/currentThread))
       (map StackTraceElement->vec)
       (u.error/clean-trace)
       (map stack-frame->trace-element)))

(defn- component-schema [doc-or-schema]
  (if (and (map? doc-or-schema) (contains? doc-or-schema :schema))
    (:schema doc-or-schema)
    doc-or-schema))

(defn- component-doc [doc-or-schema]
  (if (and (map? doc-or-schema) (contains? doc-or-schema :schema))
    doc-or-schema
    (meta doc-or-schema)))

(defn validate-opts [doc-or-schema opts]
  (let [schema (component-schema doc-or-schema)
        s      (if (map? schema)
                 (l/schema schema)
                 schema)]
    (when-let [problem (m/explain s opts)]
      (let [trace (stack-trace)]
        (doseq [bling-opts (error->bling-opts trace (component-doc doc-or-schema) problem)]
          (bad-opt-value-callout bling-opts))))))

(defn validate-opts! [doc attrs]
  (when *validate-opts*
    (validate-opts doc (or attrs {}))))

(defn merge-attrs*
  ^clojure.lang.IPersistentMap [orig-map & {:as extra}]
  (reduce (fn [acc [k v]]
            (case k
              :class (update acc :class #(cs v %))
              (assoc acc k v)))
          (or orig-map (ordered-map))
          extra))

(defmacro merge-attrs
  "Intelligently merges HTML attributes returning a map that is unambiguous to chassis."
  [& args]
  `^clojure.lang.IPersistentMap (merge-attrs* ~@args))

(defn norm
  "Normalizes the hiccup element to a vector of [tag attrs & children]."
  [hiccup]
  (let [[tag & children] hiccup
        attrs            (first children)]
    (if (map? attrs)
      hiccup
      (into [tag nil] children))))

(defn assoc-attr
  "Assoc attributes to the hiccup element."
  [hiccup & {:as args}]
  (update-in (norm hiccup) [1]
             merge-attrs*
             args))

(defn add-class
  "Appends a class to the hiccup element."
  [hiccup cls]
  (update-in (norm hiccup) [1 :class]
             #(cs % cls)))

(defn properties-for [schema k]
  (second
   (medley/find-first #(= k (first %)) (m/children schema))))

(defn join-with-wrap
  "Join a sequence of strings with separator, wrapping at n columns."
  [items separator n indent]
  (if (empty? items)
    ""
    (loop [result         (str (first items))
           current-length (+ indent (count (first items)))
           remaining      (rest items)]
      (if (empty? remaining)
        result
        (let [next-item     (first remaining)
              separator-len (count separator)
              next-item-len (count next-item)
              would-exceed? (> (+ current-length separator-len next-item-len) n)]
          (recur
           (str result
                (if would-exceed?
                  (str "\n" (apply str (repeat indent " ")) next-item)
                  (str separator next-item)))
           (if would-exceed?
             (+ indent next-item-len)
             (+ current-length separator-len next-item-len))
           (rest remaining)))))))

(defn wrap-string
  "Wraps a string to fit within specified column width."
  [text cols indent]
  (if (or (nil? text) (empty? text))
    ""
    (let [words      (str/split text #"\s+")
          indent-str (apply str (repeat indent " "))
          lines      (loop [remaining     words
                            current-line  nil
                            current-width 0
                            result        []]
                       (if-let [word (first remaining)]
                         (let [word-len     (count word)
                               space-needed (if current-line 1 0)
                               new-width    (+ current-width space-needed word-len)]
                           (if (or (nil? current-line)
                                   (<= new-width cols))
                             (recur (rest remaining)
                                    (if current-line
                                      (str current-line " " word)
                                      word)
                                    new-width
                                    result)
                             (recur remaining
                                    nil
                                    0
                                    (conj result current-line))))
                         (cond-> result
                           current-line (conj current-line))))]
      (if (empty? lines)
        ""
        (str (first lines)
             (when (> (count lines) 1)
               (str "\n" (str/join "\n" (map #(str indent-str %) (rest lines))))))))))

(defn format-option-values
  [schema option-key indent]
  (let [enum-values      (m/children (mu/get-in schema [option-key]))
        formatted-values (map #(str ":" (name %)) enum-values)]
    (join-with-wrap formatted-values ", " 80 indent)))

(defn generate-header
  [{:keys [name desc]}]
  (str name (when desc (str " - " desc))))

(defn generate-usage
  [{:keys [name]}]
  (str "\nUsage:\n"
       "  [" name " attributes & children]"
       "\n\n"
       (wrap-string
        "The attributes map can include standard HTML attributes (non-namespaced keys), and the following namespaced-keys as options to this component."
        80 0)))

(defn indent
  ([]
   (indent 2))
  ([n]
   (str/join (take n (repeat " ")))))

(defn generate-option-doc
  [schema as option-key]
  (let [properties                     (properties-for schema option-key)
        option-name                    (str "::" as "/" (name option-key))
        option-type                    (m/type (mu/get-in schema [option-key]))
        {:keys [doc optional default]} properties
        optional-str                   (cond (and optional default) (format "(optional, default %s)" default)
                                             optional               "(optional)"
                                             default                (format "(default `%s`)" default)
                                             :else                  nil)
        type-indent                    (indent (+ (count (str option-key)) 6))
        type-str                       (cond
                                         (= option-type :enum)    (format-option-values schema option-key (count type-indent))
                                         (= option-type :boolean) "boolean"
                                         :else                    (name option-type))]
    (str
     (format "%s %s - %s%s"
             (indent) option-name doc (if optional-str (str " " optional-str) ""))
     "\n"
     type-indent
     type-str)))

(defn generate-options
  [{:keys [schema as]}]
  (let [option-keys  (mu/keys schema)
        options-docs (map #(generate-option-doc schema as %) option-keys)]
    (str "\nOptions:\n"
         (str/join "\n\n" options-docs))))

(defn generate-examples
  [{:keys [examples ns as]}]
  (str "\nExamples:\n"
       (format "  (require '[%s :as %s])\n" ns as)
       (str/join "\n" (map #(str "  " %) examples))))

(defn doc-map-apply-defaults [doc-map]
  (if (:as doc-map)
    doc-map
    (assoc doc-map :as
           (as-> (:ns doc-map) %
             (str %)
             (clojure.string/split % #"\.")
             (last %)))))

(defn generate-docstring
  "Generate a complete docstring from a component doc map."
  [doc-map]
  (let [doc-map (doc-map-apply-defaults doc-map)]
    (str (generate-header doc-map) "\n"
         (generate-usage doc-map) "\n"
         (generate-options doc-map) "\n"
         (generate-examples doc-map))))
