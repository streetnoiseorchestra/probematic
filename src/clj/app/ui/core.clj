(ns app.ui.core
  (:require
   [malli.core :as m]
   [malli.experimental.lite :as l]
   [malli.error :as me]
   [clojure.string :as str]
   [clojure.set :as set]))

(defn cs [& names]
  (str/join " " (filter identity names)))

(defonce ^:dynamic *validate-opts* false)

(defn enable-opts-validation! []
  (alter-var-root #'*validate-opts* (constantly true)))

(defn disable-opts-validation! []
  (alter-var-root #'*validate-opts* (constantly false)))

(defn validate-opts [cvar opts]
  (when *validate-opts*
    (when-let [opt-defs (:opts (meta cvar))]
      (let [s (l/schema opt-defs)]
        (when-let [problem (m/explain s opts)]
          (throw (ex-info (str "Invalid options passed to" cvar) {:error problem
                                                                  :human (me/humanize problem)})))))))

(defn warn-on-attr-collision [cvar attrs]
  (when *validate-opts*
    (when-let [opt-defs (:opts (meta cvar))]
      (doseq [k  (set/intersection (set (keys opt-defs)) (set (keys attrs)))]
        (let [opt-k (str ":-" (name k))]
          (tap> (str "Warning: " cvar " called with html attribute " k " that is also an option, did you mean " opt-k " ?"))
          (println (str "Warning: " cvar " called with html attribute " k " that is also an option, did you mean " opt-k " ?")))))))

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
