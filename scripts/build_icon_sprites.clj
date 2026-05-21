#!/usr/bin/env bb
(ns build-icon-sprites
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.string :as str]))

(def default-source-dir "resources/public/img/snoico")
(def default-output-dir "resources/public/img/sprites")
(def sprite-filename "snoico.svg")
(def viewboxes-filename "snoico-viewboxes.edn")

(def svg-namespace "http://www.w3.org/2000/svg")

(def presentation-attrs
  #{"clip-rule"
    "color"
    "fill"
    "fill-opacity"
    "fill-rule"
    "opacity"
    "overflow"
    "stroke"
    "stroke-dasharray"
    "stroke-dashoffset"
    "stroke-linecap"
    "stroke-linejoin"
    "stroke-miterlimit"
    "stroke-opacity"
    "stroke-width"
    "style"
    "vector-effect"})

(defn- element? [node]
  (and (map? node) (contains? node :tag)))

(defn- local-name [x]
  (cond
    (keyword? x) (name x)
    (symbol? x)  (name x)
    :else        (str x)))

(defn- attr-value [attrs attr-name]
  (some (fn [[k v]]
          (when (= attr-name (local-name k))
            v))
        attrs))

(defn- viewbox [svg]
  (or (attr-value (:attrs svg) "viewBox")
      (attr-value (:attrs svg) "viewbox")
      (throw (ex-info "SVG is missing viewBox" {:tag (:tag svg)}))))

(defn- root-presentation-attrs [svg]
  (into (array-map)
        (filter (fn [[k _v]]
                  (contains? presentation-attrs (local-name k))))
        (:attrs svg)))

(defn- collect-ids [node]
  (cond
    (element? node)
    (let [id (attr-value (:attrs node) "id")]
      (cond-> (mapcat collect-ids (:content node))
        id (conj id)))

    :else
    []))

(defn- rewrite-id-reference [value old-id new-id]
  (let [quoted-id (java.util.regex.Pattern/quote old-id)
        value'    (str/replace value
                               (re-pattern (str "url\\(#" quoted-id "\\)"))
                               (str "url(#" new-id ")"))]
    (if (= value (str "#" old-id))
      (str "#" new-id)
      value')))

(defn- rewrite-references [value id-map]
  (if (string? value)
    (reduce-kv rewrite-id-reference value id-map)
    value))

(defn- prefixed-id [icon-name id]
  (str icon-name "-" id))

(defn- logo-fill [class]
  (cond
    (and class (contains? (set (str/split class #"\s+")) "logotype-text")) "#f97316"
    (and class (contains? (set (str/split class #"\s+")) "logotype-snoman")) "#22c55e"))

(defn- strip-attr? [k]
  (let [attr-name (local-name k)]
    (or (= attr-name "role")
        (= attr-name "focusable")
        (str/starts-with? attr-name "aria-"))))

(defn- transform-attrs [icon-name id-map attrs]
  (let [class (attr-value attrs "class")
        fill  (logo-fill class)]
    (cond-> (into (array-map)
                  (keep (fn [[k v]]
                          (when-not (strip-attr? k)
                            [k (if (= "id" (local-name k))
                                 (prefixed-id icon-name v)
                                 (rewrite-references v id-map))])))
                  attrs)
      fill (assoc :fill fill))))

(declare transform-node)

(defn- strip-element? [node]
  (contains? #{"title" "style"} (local-name (:tag node))))

(defn- transform-node [icon-name id-map node]
  (cond
    (element? node)
    (when-not (strip-element? node)
      (assoc node
             :attrs (transform-attrs icon-name id-map (:attrs node))
             :content (keep (partial transform-node icon-name id-map) (:content node))))

    (string? node)
    (when-not (str/blank? node)
      node)

    :else
    node))

(defn- icon-symbol-data [[icon-name svg-source]]
  (let [svg                (xml/parse-str svg-source)
        ids                (distinct (collect-ids svg))
        id-map             (into {} (map (juxt identity (partial prefixed-id icon-name))) ids)
        presentation-attrs (transform-attrs icon-name id-map (root-presentation-attrs svg))
        content            (keep (partial transform-node icon-name id-map) (:content svg))
        content            (if (seq presentation-attrs)
                             [(apply xml/element :g presentation-attrs content)]
                             content)
        viewbox            (viewbox svg)]
    {:name    icon-name
     :viewbox viewbox
     :symbol  (apply xml/element :symbol (array-map :id icon-name :viewBox viewbox) content)}))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- render-attr-name [attr]
  (local-name attr))

(defn- render-attrs [attrs]
  (apply str
         (for [[k v] attrs]
           (str " " (render-attr-name k) "=\"" (xml-escape v) "\""))))

(defn- render-node [node]
  (cond
    (nil? node)
    ""

    (element? node)
    (let [tag      (local-name (:tag node))
          attrs    (render-attrs (:attrs node))
          children (apply str (map render-node (:content node)))]
      (if (seq children)
        (str "<" tag attrs ">" children "</" tag ">")
        (str "<" tag attrs "/>")))

    (string? node)
    (xml-escape node)

    :else
    (xml-escape node)))

(defn- sprite-data-from-icons [icons]
  (let [icons' (->> icons
                    (sort-by key)
                    (map icon-symbol-data)
                    vec)]
    {:sprite    (str "<svg xmlns=\"" svg-namespace "\">"
                     (apply str (map (comp render-node :symbol) icons'))
                     "</svg>\n")
     :viewboxes (into (sorted-map)
                      (map (juxt :name :viewbox))
                      icons')}))

(defn build-sprite [icons]
  (:sprite (sprite-data-from-icons icons)))

(defn- read-icons [source-dir]
  (into (array-map)
        (map (fn [path]
               [(fs/strip-ext (fs/file-name path)) (slurp (str path))]))
        (sort (fs/glob source-dir "*.svg"))))

(defn sprite-data [source-dir]
  (sprite-data-from-icons (read-icons source-dir)))

(defn- write-sprite-files! [source-dir output-dir]
  (let [{:keys [sprite viewboxes]} (sprite-data source-dir)
        sprite-path                (fs/path output-dir sprite-filename)
        viewboxes-path             (fs/path output-dir viewboxes-filename)]
    (fs/create-dirs output-dir)
    (spit (str sprite-path) sprite)
    (spit (str viewboxes-path) (str (pr-str viewboxes) "\n"))
    {:sprite-path    (str sprite-path)
     :viewboxes-path (str viewboxes-path)
     :icon-count     (count viewboxes)}))

(defn -main
  ([]
   (-main default-source-dir default-output-dir))
  ([source-dir]
   (-main source-dir default-output-dir))
  ([source-dir output-dir]
   (let [{:keys [sprite-path viewboxes-path icon-count]} (write-sprite-files! source-dir output-dir)]
     (println "wrote" icon-count "icons to" sprite-path)
     (println "wrote viewBox manifest to" viewboxes-path))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
