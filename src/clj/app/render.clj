(ns app.render
  (:require
   [app.config :as config]
   [app.secret-box :as secret-box]
   [app.util :as util]
   [clojure.java.io :as io]
   [ctmx.render :as ctmx.render]
   [hiccup.page :as hiccup.page]
   [hiccup.util :as hiccup.util]
   [hiccup2.core :refer [html]]))

(defmacro html5-safe
  "Create a HTML5 document with the supplied contents. Using hiccup2.core/html to auto escape strings"
  [options & contents]
  (if-not (map? options)
    `(html5-safe {} ~options ~@contents)
    (if (options :xml?)
      `(let [options# (dissoc ~options :xml?)]
         (str (html {:mode :xml}
                    (hiccup.page/xml-declaration (options# :encoding "UTF-8"))
                    (hiccup.page/doctype :html5)
                    (hiccup.page/xhtml-tag options# (options# :lang) ~@contents))))
      `(let [options# (dissoc ~options :xml?)]
         (str (html {:mode :html}
                    (hiccup.page/doctype :html5)
                    [:html options# ~@contents]))))))

(defn html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html"}
   ;:body (pretty-print-html body)
   :body  body})

(defn html-status-response
  ([status-code body]
   (html-status-response status-code {} body))
  ([status-code extra-headers body]
   {:status status-code
    :headers (merge  {"Content-Type" "text/html"} extra-headers)
                                        ;:body (pretty-print-html body)
    :body  body}))

(def memoed-sha384-resource (memoize secret-box/sha384-resource))

(defn sha384-resource [{:keys [system]} v]
  (util/url-encode
   (if (config/prod-mode? (:env system))
     (memoed-sha384-resource v)
     (secret-box/sha384-resource v))))

(defn script [req relative-prefix path & extra]
  (let [sri-hash (sha384-resource req (str "public/js/" path))
        cache-buster (subs sri-hash  (- 71 8))]
    [:script (merge {:src (str relative-prefix "/js/" path "?v=" cache-buster)
                     :integrity  sri-hash}
                    (apply hash-map extra))]))

;; hashed-path (str dir "/hash-" cache-buster path)
(defn stylesheet [req relative-prefix dir path & extra]
  (let [full-path (str "public/" dir "/" path)
        sri-hash (sha384-resource req full-path)
        cache-buster (subs sri-hash (- 71 8))]
    [:link (merge {:rel "stylesheet"
                   :href (str relative-prefix "/" dir "/" path "?v=" cache-buster)
                   :integrity sri-hash}
                  (apply hash-map extra))]))

(defn head [req title relative-prefix]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name    "viewport"
           :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
   [:link {:rel "shortcut icon" :href "/img/megaphone-icon.png"}]
   [:title (or title "SNOrga")]
   (stylesheet req relative-prefix "css" "easymde.min@2.18.0.css")
   (stylesheet req relative-prefix "font/inter" "inter.css")
   (stylesheet req relative-prefix "css" "fa.css")
   (stylesheet req relative-prefix "css/compiled" "main.css")])

(defn body-end [req relative-prefix]
  (list
   #_[:script {:type :importmap}
      (hiccup.util/raw-string
       (j/write-value-as-string
        :imports {"wa-dialog" "https://early.webawesome.com/webawesome@3.0.0-alpha.11/dist/components/dialog/dialog.js"}))]

   [:svg {:style "display: none"}
    [:symbol {:id "svg-sprite-spinner" :fill "none", :viewbox "0 0 24 24"}
     [:circle {:class "opacity-25", :cx "12", :cy "12", :r "10", :stroke "currentColor", :stroke-width "4"}]
     [:path {:class "opacity-75", :fill "currentColor", :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]]
   [:link {:rel "stylesheet", :href "https://early.webawesome.com/webawesome@3.0.0-alpha.11/dist/styles/utilities/fouce.css"}]
   [:link {:rel "stylesheet" :href "https://early.webawesome.com/webawesome@3.0.0-alpha.11/dist/styles/themes/default.css"}]
   [:script {:type "module" :src "https://early.webawesome.com/webawesome@3.0.0-alpha.11/dist/components/dialog/dialog.js"}]
   ;; [:link {:rel "stylesheet" :href "https://early.webawesome.com/webawesome@3.0.0-alpha.11/dist/styles/webawesome.css"}]
   ;; [:script {:type "module" :src "https://early.webawesome.com/webawesome@3.0.0-alpha.11/dist/webawesome.loader.js"}]
   (script req relative-prefix "datastar@1.0.0-beta.11.js" :type :module)
   (script req relative-prefix "hyperscript.org@0.9.12.js")
   (script req relative-prefix "htmx.org@1.9.12.js")
   (script req relative-prefix "class-tools@1.9.12.js")
   (script req relative-prefix "nprogress.js")
   (script req relative-prefix "popperjs@2-dev.js")
   (script req relative-prefix "tippy@6-dev.js")
   (script req relative-prefix "confetti.js" :type :module)
   (script req relative-prefix "sweetalert2.all@11.7.5.js")
   (script req relative-prefix "dropzone@6.0.0-beta.2.min.js")
   (script req relative-prefix "easymde.min@2.18.0.js")

   (script req nil "widgets/sortable.js")
   (script req nil "sortable@1.14.0.js")
   (script req relative-prefix "app.js" :type :module)))

(defn chart-poll-scripts [req]
  (list
   (script req nil "chart@4.4.0.js")
   (script req nil "chartjs-plugin-datalabels.min.js")
   (script req nil "widgets/poll-chart.js")))

(defn chart-stat-scripts [req]
  (list
   (script req nil "chart@4.4.0.js")
   (script req nil "chartjs-plugin-datalabels.min.js")
   (script req nil "widgets/stats-chart.js")))

(defn sortable-scripts [req]
  (list
   (script req nil "widgets/sortable.js")
   (script req nil "sortable@1.14.0.js")))

(defn html5-response
  ([req body] (html5-response req nil body))
  ([req {:keys [js extra-scripts title]} body]
   (html-response
    (html5-safe
        (head req title nil)
      [:body
       (ctmx.render/walk-attrs body)
       (conj
        (body-end req nil)
        (when extra-scripts
          (map #(apply script %) extra-scripts))
        (when js (map (partial script nil) js)))]))))

(defn html5-response-absolute
  ([req {:keys [js title
                uri-prefix]} body]
   (html-response
    (html5-safe
        (head req title uri-prefix)
      [:body (ctmx.render/walk-attrs body)
       (body-end req uri-prefix)]
      (when js [:script {:src (str uri-prefix "/js" js)}])))))

(defn snippet-response [body]
  (ctmx.render/snippet-response body))

(defn post-login-client-side-redirect
  "When using a SameSite=strict session cookie, after the OAUTH2 login the session cookie will not be sent. We need to interrupt the server-side redirect
  with this client side redirect to trigger the SameSite=strict allow policy so the session cookie will be sent."
  [session cookies relative-uri]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :session session
   :cookies cookies
   :body  (html5-safe
           [:head
            [:title "Probematic"]
            [:style
             (hiccup.util/raw-string (-> (io/resource "public/css/login-interstitial.css") slurp))]
            [:meta {:http-equiv "refresh" :content (str "0;URL='" relative-uri "'")}]]
           [:body
            [:div.container
             [:div.content
              [:noscript
               [:p [:a {:href relative-uri} "Continue"]]]
              [:div.spinner
               [:div]
               [:div]
               [:div]]
              [:p "Logging in..."]]]])})
