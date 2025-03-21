(ns app.ui.core
  (:require
   [app.util.error :as u.error]
   [bling.core :refer [callout bling point-of-interest]]
   [malli.core :as m]
   [malli.experimental.lite :as l]
   [malli.error :as me]
   [clojure.string :as str]
   [clojure.set :as set]))

(defn cs [& names]
  (str/join " " (filter identity names)))

(defonce ^:dynamic *validate-opts* false)

(defn bad-opt-value-callout

  [{:keys [point-of-interest-opts callout-opts]}]
  (let [message      (point-of-interest point-of-interest-opts)
        callout-opts (merge callout-opts {:padding-top 1})]
    (callout callout-opts message)))

(defn fqns-sym
  [m]
  (symbol (str (:ns m)
               "/"
               (str/replace (name (:name m))
                            #"\*$" ""))))

(defn warning-header
  [{:keys [m opt value]}]
  (let [component                  (fqns-sym m)
        {:keys [file line column]} m]
    (apply bling
           (concat
            [[:italic "component: "] [:bold component]
             "\n\n"
             [:italic "option:    "] [:bold opt]
             "\n\n"
             [:italic "invalid:   "] [:bold value]]))))

(defn strip-should-be [msg]
  (if (re-find #"^(?i)should be" msg)
    (subs msg 10)
    msg))

(defn warning-body
  [{:keys [opt msg trace]}]
  (let [short-trace (reverse (take 5 (drop 3 trace)))
        w           (java.io.StringWriter.)]
    (u.error/print-trace short-trace w)
    (str
     (bling
      "Value for the "
      [:bold opt]
      " should be "
      [:bold (strip-should-be msg)]
      "\n\n"
      [:italic "Stacktrace preview:"] "\n")
     w)))

(defn enable-opts-validation! []
  (alter-var-root #'*validate-opts* (constantly true)))

(defn disable-opts-validation! []
  (alter-var-root #'*validate-opts* (constantly false)))

(defn error->bling-opts [trace cvar explain]
  (map (fn [[k error]]
         (let [opt (str ":-" (name k))]
           {:point-of-interest-opts {:header (warning-header {:opt   opt
                                                              :value (get-in explain [:value k])
                                                              :m     (meta cvar)})

                                     :body (warning-body {:opt   opt
                                                          :trace trace
                                                          :msg   (str/join "; " error)})}

            :callout-opts {:type  :warning
                           :label "WARNING​ Invalid option value"}}))

       (me/humanize explain)))

(defn stack-traces []
  (u.error/clean-trace (.getStackTrace (Thread/currentThread))))

(defn validate-opts [cvar opts]
  (when *validate-opts*
    (when-let [opt-defs (:opts (meta cvar))]
      (let [s (l/schema opt-defs)]
        (when-let [problem (m/explain s opts)]
          (let [trace (stack-traces)]
            (doseq [bling-opts (error->bling-opts trace cvar problem)]
              (bad-opt-value-callout bling-opts)))

          #_(throw (ex-info (str "Invalid options passed to" cvar) {:error problem
                                                                    :human (me/humanize problem)})))))))

(defn warn-on-attr-collision [cvar attrs]
  (when *validate-opts*
    (when-let [opt-defs (:opts (meta cvar))]
      (doseq [k (set/intersection (set (keys opt-defs)) (set (keys attrs)))]
        (let [opt-k          (str ":-" (name k))
              component-name (fqns-sym (meta cvar))
              msg            (str component-name " called with html attribute " k " that is also an option, did you mean " opt-k " ?")]
          (callout {:type :warning} msg))))))

(defn attr+children [coll]
  (when (coll? coll)
    (let [[a & xs] coll
          attr     (when (map? a) a)]
      [attr (if attr xs coll)])))

(defn user-attr? [x]
  (and (keyword? x)
       (->> x name (re-find #"^-[^\s\d]+"))))

(defn unwrapped-children [children]
  (let [fc (nth children 0 nil)]
    (if (and
         (seq? children)
         (= 1 (count children))
         (seq? fc)
         (seq fc))
      fc
      children)))

(defn extract
  "Extracts component options from normal html attributes and children elements.

  Returns a vector of [options html-attributes children]"
  ;; it is important that we preserve the type of the attr map
  ;; in case the user has supplied an array-map because the order of their attributes is important
  [cvar args]
  (when (coll? args)
    (let [[attr* children] (attr+children args)
          user-ks          (some->> attr*
                                    keys
                                    (filter user-attr?)
                                    (into #{}))
          ;; Calling dissoc on an array map always yields an array map
          attr             (apply dissoc attr* user-ks)
          opts             (select-keys attr* (into [] user-ks))
          supplied-opts    (->> opts
                                (map (fn [[k v]]
                                       [(-> k name (subs 1) keyword) v]))
                                (into {}))]

      (when *validate-opts*
        (validate-opts cvar supplied-opts)
        (warn-on-attr-collision cvar attr))

      [supplied-opts
       attr
       (->> children
            (remove nil?)
            unwrapped-children)])))

(defn merge-attrs [orig-map & {:as extra}]
  (reduce (fn [acc [k v]]
            (case k
              :class (update acc :class #(str v " " %))
              (assoc acc k v))) orig-map extra))

(defn easy-extract [comp args class]
  (let [[opts attrs children] (extract comp args)]
    [opts
     (merge-attrs attrs :class class) children]))
