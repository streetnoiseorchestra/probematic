(ns app.html
  (:import (java.io OutputStream))
  (:require
   [squint.compiler :as squint]
   [backtick         :refer [template]]
   [dev.onionpancakes.chassis.core :as chassis]
   [ring.util.response :as ring-response]
   [buddy.core.codecs :as codecs]
   [buddy.core.hash :as digest]
   [clojure.java.io :as io]))

(def ->str
  "Returns an HTML string given a hiccup datstructure"
  chassis/html)

(defn raw
  "Wraps value as an unescaped string that will be rendered directly to HTML.
  WARNING: This bypasses HTML escaping and can lead to XSS vulnerabilities if used with untrusted input.
  Examples:
  (raw \"<br>\")   ;; safe: known markup
  (raw user-input) ;; dangerous: could contain malicious scripts
  Arguments:
  `value` - String or value to be rendered without escaping
  `more` - Additional values to be concatenated"
  ([] (chassis/raw ""))
  ([value]
   (chassis/raw value))
  ([value & more]
   (apply chassis/raw value more)))

(def doctype-html5 chassis/doctype-html5)

(defn -sri-integrity
  "Generates an integrity hash for an asset resource"
  [resource _cache-break]
  (when-let [hash (some-> resource
                          io/input-stream
                          digest/sha512
                          (codecs/bytes->b64-str true))]
    (str "sha512-" hash)))

(def -sri-integrity-memo (memoize -sri-integrity))

(defn sri-integrity
  "Generates an integrity hash for an asset `resource`.

  The 2-arity version is memoized.

  Example:
  (sri-integrity (io/resource \"js/app.js\")

  (let [res (io/resource \"js/app.js\")]
    (sri-integrity res (resource-last-modified res)))
  "
  ([resource]
   (-sri-integrity resource nil))
  ([resource cache-break]
   (-sri-integrity-memo resource cache-break)))

(defn asset-path [path]
  (str "public/" path))

(defn resource-last-modified [resource]
  (some-> resource
          (ring-response/resource-data)
          ^java.util.Date (:last-modified)
          (.getTime)))

(defn asset-meta [path]
  (let [resource      (io/resource (asset-path path))
        last-modified (resource-last-modified resource)
        sri-hash      (sri-integrity resource last-modified)]
    {:src                   path
     :src-with-cache-buster (str path "?v=" last-modified)
     :cache-buster          last-modified
     :sri-hash              sri-hash}))

(defn script [path & extra]
  (let [{:keys [src-with-cache-buster sri-hash src]} (asset-meta path)]
    [:script (merge {:src         (or src-with-cache-buster src)
                     :crossorigin (when sri-hash "anonymous")
                     :integrity   sri-hash}
                    (apply hash-map extra))]))

(defn stylesheet [path & extra]
  (let [{:keys [src-with-cache-buster sri-hash src]} (asset-meta path)]
    [:link (merge {:rel         "stylesheet"
                   :href        (or src-with-cache-buster src)
                   :crossorigin (when sri-hash "anonymous")
                   :integrity   sri-hash}
                  (apply hash-map extra))]))

(defn csrf-token-input
  [req]
  [:input {:type "hidden" :name "__anti-forgery-token" :value (:pink.interceptors.csrf/token req)}])

(defn html-document [{:keys [class lang title description image favicon svg-icon apple-touch-icon url canonical head body-attrs]} & body]
  [chassis/doctype-html5
   [:html {:lang (or lang "en") :class class}
    [:head
     [:title title]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
     [:meta {:name "description" :content description}]
     [:meta {:content title :property "og:title"}]
     [:meta {:content description :property "og:description"}]
     (when url
       [:meta {:content url :property "og:url"}])
     (when canonical
       [:link {:ref "canonical" :href canonical}])
     (when image
       (list
        [:meta {:content "summary_large_image" :name "twitter:card"}]
        [:meta {:content image :property "og:image"}]))
     (when favicon
       ;; 32x32 is there to prevent a chrome bug where it choose a .ico over an svg
       [:link {:rel "icon" :href favicon :sizes "32x32"}])
     (when svg-icon
       [:link {:rel "icon" :href svg-icon :type "image/svg+xml"}])
     (when apple-touch-icon
       ;;  should be "180×180", at least in 2024
       [:link {:rel "apple-touch-icon", :href apple-touch-icon}])
     head]
    [:body
     body-attrs
     body]]])

(comment
  (script "js/main.js")
  ;; => [:script {:src "js/main.js?v=1730982192000", :crossorigin "anonymous", :integrity "sha512--1OrtO2Bmj12hgoAGiSklMwKLK0ZYu6PV0qr0IRB2YRYARd-aB_8B6BS9tRuKUCCDW_igy0wAQzQwugT50cC0A"}]

  (stylesheet "js/main.js")
  ;; => [:link {:rel "stylesheet", :href "js/main.js?v=1730982192000", :crossorigin "anonymous", :integrity "sha512--1OrtO2Bmj12hgoAGiSklMwKLK0ZYu6PV0qr0IRB2YRYARd-aB_8B6BS9tRuKUCCDW_igy0wAQzQwugT50cC0A"}]

  ;; this is fun? someday?
  (defmethod chassis/resolve-alias :pink.html/stylesheet
    [_ {:pink.html/keys [path]} _]
    (let [{:keys [src-with-cache-buster sri-hash src]} (asset-meta path)]
      [:link {:rel         "stylesheet"
              :href        (or src-with-cache-buster src)
              :crossorigin (when sri-hash "anonymous")
              :integrity   sri-hash}]))
  (chassis/html [:pink.html/stylesheet {:pink.html/path "js/main.js"}]))

(def nbsp [:span chassis/nbsp])
(def emdash [:span (chassis/raw "&mdash;")])
(def endash [:span (chassis/raw "&ndash;")])
(def ellipsis [:span (chassis/raw "&hellip;")])
(def euro [:span (chassis/raw "&euro;")])
(def cent [:span (chassis/raw "&cent;")])
(def copyright [:span (chassis/raw "&copy;")])
(def trademark [:span (chassis/raw "&reg;")])
(def dagger [:span (chassis/raw "&#8224;")])
(def Dagger [:span (chassis/raw "&#8225;")])
(def prime [:span (chassis/raw "&#8242;")])
(def Prime [:span (chassis/raw "&#8243;")])
(def almost [:span (chassis/raw "&asymp;")])
(def half [:span (chassis/raw "&frac12;")])
(def degree [:span (chassis/raw "&deg;")])
(def plusminus [:span (chassis/raw "&plusmn;")])

(defn js* [form]
  (squint.compiler/compile-string (str form) {}))

(defmacro ->js
  [& forms]
  `(js* (template ~forms)))

(defn script-inline [s]
  [:script {:type :module}
   (raw s)])

(defmacro squint-inline
  [& forms]
  `[:script {:type :module} (raw (js* (template ~forms)))])
