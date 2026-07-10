(ns app.routes.datastar
  (:require
   [app.brotli :as br]
   [app.datastar :as d*]
   [app.html :as html]
   [app.interceptors.compression :as compression]
   [app.layout2 :as layout2]
   [app.nexus :as nexus])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.nio.charset StandardCharsets)
   (java.util.zip GZIPOutputStream)))

;; Toggle this to false to render full page bodies on initial GET requests while
;; keeping the long-lived SSE POST active.
(def ^:dynamic *use-page-shim?* false)

(defn- shim-html [req]
  (layout2/shim-html req nil))

(defn- shim-cache-key [req]
  {:current-locale (:current-locale req)})

(defn- gzip-bytes [^bytes body]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gzip (GZIPOutputStream. out)]
      (.write gzip body))
    (.toByteArray out)))

(defn- brotli-bytes [^bytes body]
  (br/compress body))

(defn- precompressed-shim [html]
  (let [body (.getBytes ^String html StandardCharsets/UTF_8)]
    {:identity body
     :gzip     (gzip-bytes body)
     :br       (brotli-bytes body)}))

(def ^:private shim-cache_ (atom {}))

(defn- cached-precompressed-shim [req]
  (let [cache-key (shim-cache-key req)]
    (if-let [cached (get @shim-cache_ cache-key)]
      (do
        (tap> [:shim:hit cache-key])
        cached)
      (let [created (precompressed-shim (shim-html req))]
        (tap> [:shim:miss cache-key])
        (get (swap! shim-cache_
                    #(if (contains? % cache-key)
                       %
                       (assoc % cache-key created)))
             cache-key)))))

(defn- accepted-shim-encoding [req]
  (cond
    (compression/accepts-brotli? req) :br
    (compression/accepts-gzip? req)   :gzip
    :else                             :identity))

(defn- precompressed-shim-response [req precompressed]
  (let [encoding (accepted-shim-encoding req)
        body     (get precompressed encoding)
        headers  (cond-> {"Content-Type"   "text/html"
                          "Content-Length" (str (alength ^bytes body))
                          "Vary"           "Accept-Encoding"}
                   (= :br encoding)   (assoc "content-encoding" "br")
                   (= :gzip encoding) (assoc "content-encoding" "gzip"))]
    {:status 200
     :headers headers
     :body    (ByteArrayInputStream. body)}))

(defn shim [req]
  #_(layout/app-shell req nil)
  (precompressed-shim-response req (cached-precompressed-shim req)))

(defn- full-page-response [render-fn opts req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body    (layout2/datastar-page-html req opts (render-fn req))})

(defn- initial-get-handler [render-fn opts]
  (fn [req]
    (if *use-page-shim?*
      (shim req)
      (full-page-response render-fn opts req))))

(defn- action-query-params [req]
  (or (get-in req [:parameters :query])
      (:query-params req)
      (:params req)))

(defn- action-body [req]
  (let [body         (or (:body-params req)
                         (get-in req [:parameters :body])
                         {})
        query-params (not-empty (apply dissoc
                                       (action-query-params req)
                                       [:ns :kw "ns" "kw"]))]
    (cond-> body
      query-params (assoc :query-params query-params))))

(defn act-handler [req]
  (let [action-key   (d*/action-key (action-query-params req))
        nexus-config (get-in req [:system :nexus])
        action       (get-in nexus-config [:nexus/actions action-key])]
    (cond
      (nil? action-key)
      {:status 400
       :headers {}
       :body "Missing or invalid act query params: ns and kw"}

      (nil? action)
      {:status 404
       :headers {}
       :body (str "No such action registered for " action-key)}

      :else
      [[action-key (action-body req)]])))

(defn act-route [system]
  ["/act" {:name       ::act
           :interceptors [(nexus/nexus-interceptor (:nexus system) system)]
           :post       {:handler act-handler}}])

(defn- wrap-render-fn [render-fn]
  (fn [req]
    (html/->str (:tr req) (layout2/app-shell-body req (render-fn req)))))

(defn page-routes
  [{:keys [extra-head page path page-name route-data]}]
  (assert path "path is required")
  (assert page (str ":page render function is required for " page-name))
  (let [wrapped-render (wrap-render-fn page)
        route-data     (cond-> (merge {:name page-name} route-data)
                         extra-head (assoc :extra-head extra-head))
        child-routes   (into [["" {:get  (initial-get-handler page route-data)
                                   :post (d*/render-handler wrapped-render)}]])]
    (into [path route-data] child-routes)))
