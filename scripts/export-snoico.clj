#!/usr/bin/env bb
(ns export-snoico
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [snoico.icons :as snoico]))

(def layout2-path "src/clj/app/layout2.clj")
(def out-dir "resources/public/img/snoico")
(def extra-icons [:snoman])

(defn icon-names-from-layout2 []
  (->> (slurp layout2-path)
       (re-seq #"icon/([A-Za-z0-9\-]+)")
       (map second)
       (map keyword)
       distinct
       vec))

(defn requested-icons []
  (->> (concat snoico/logo-icons extra-icons (icon-names-from-layout2))
       distinct
       vec))

(defn normalize-tag-name [tag]
  (let [name (cond
               (keyword? tag) (name tag)
               (symbol? tag)  (name tag)
               :else          (str tag))]
    (case name
      "lineargradient" "linearGradient"
      "radialgradient" "radialGradient"
      name)))

(defn normalize-attr-name [attr]
  (let [name (cond
               (keyword? attr)
               (if-let [ns (namespace attr)]
                 (str ns ":" (name attr))
                 (name attr))

               (symbol? attr) (name attr)
               :else          (str attr))]
    (case name
      "viewbox" "viewBox"
      name)))

(defn xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn render-attrs [attrs]
  (->> attrs
       (map (fn [[k v]]
              (str " " (normalize-attr-name k) "=\"" (xml-escape v) "\"")))
       (apply str)))

(declare render-node)

(defn render-element [[tag & tail]]
  (let [[attrs children] (if (map? (first tail))
                           [(first tail) (rest tail)]
                           [{} tail])
        tag-name         (normalize-tag-name tag)
        body             (apply str (map render-node children))]
    (if (seq children)
      (str "<" tag-name (render-attrs attrs) ">" body "</" tag-name ">")
      (str "<" tag-name (render-attrs attrs) "/>"))))

(defn render-node [node]
  (cond
    (nil? node) ""
    (vector? node) (render-element node)
    (seq? node) (apply str (map render-node node))
    (string? node) (if (re-find #"^\s*<!--" node) "" (xml-escape node))
    :else (xml-escape node)))

(defn svg-string [hiccup]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       (render-node hiccup)
       "\n"))

(defn clean-out-dir! []
  (fs/create-dirs out-dir)
  (doseq [path (fs/glob out-dir "*.svg")]
    (fs/delete-if-exists path)))

(defn write-icon! [icon-name]
  (let [hiccup (get snoico/icons icon-name)]
    (when-not hiccup
      (throw (ex-info "Missing icon in snoico workspace"
                      {:icon icon-name})))
    (let [path (fs/path out-dir (str (name icon-name) ".svg"))]
      (spit (str path) (svg-string hiccup))
      path)))

(defn -main [& _args]
  (let [icons (requested-icons)]
    (clean-out-dir!)
    (doseq [icon icons]
      (write-icon! icon))
    (println "exported" (count icons) "icons to" out-dir)
    (doseq [icon icons]
      (println " -" (name icon)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
