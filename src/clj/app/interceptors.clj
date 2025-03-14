(ns app.interceptors
   ;; [clojure.data :as diff]
   ;; [io.pedestal.interceptor.chain :as chain]
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.i18n :as i18n]
   [app.queries :as q]
   [app.rand-human-id :as human-id]
   [app.routes.errors :as errors]
   [app.routes.pedestal-prone :as pedestal-prone]
   [app.schemas :as schemas]
   [clojure.string :as str]
   [co.deps.ring-etag-middleware :as etag]
   [com.brunobonacci.mulog :as μ]
   [app.datomic.shim :as d]
   [io.pedestal.http :as http]
   [io.pedestal.http.ring-middlewares :as middlewares]
   [io.pedestal.interceptor :as interceptor]
   [io.pedestal.interceptor.error :as error-int]
   [luminus-transit.time :as time]
   [muuntaja.core :as m]
   [reitit.coercion.malli :as rcm]
   [reitit.http.coercion :as coercion]
   [reitit.http.interceptors.multipart :as multipart]
   [reitit.http.interceptors.muuntaja :as muuntaja]
   [reitit.http.interceptors.parameters :as parameters]
   [reitit.pedestal :as pedestal]
   [ring.middleware.keyword-params :as keyword-params])
  (:import
   (org.eclipse.jetty.server HttpConfiguration)))

(defn current-user-interceptor
  "Fetches the current user from the request (see app.auth/auth-interceptor),
   looks up the member and attaches the member info to the request under :session :session/member.
  If there is no authed member, then does nothing."
  [system]
  (assert system)
  {:name ::current-user-interceptor
   :enter (fn [ctx]
            (if-let [member-email (-> ctx :request :session :session/email)]
              (if-let [member (q/member-by-email (d/db (-> system :datomic :conn)) member-email)]
                (assoc-in ctx  [:request :session :session/member] member)
                ctx)
              ctx))})

(def keyword-params-interceptor
  "Keywordizes request parameter keys. CTMX expects this."
  {:name ::keyword-params
   :enter (fn [ctx]
            (let [request (:request ctx)]
              (assoc ctx :request
                     (keyword-params/keyword-params-request request))))})

(defn datomic-interceptor
  "Attaches the datomic db and connection to the request map"
  [system]
  (assert system)
  (assert (-> system :datomic :conn))
  {:name ::datomic--interceptor
   :enter (fn [ctx]
            (let [conn (-> system :datomic :conn)]
              (-> ctx
                  (assoc-in  [:request :db] (d/db conn))
                  (assoc-in  [:request :datomic-conn] conn))))})

(defn filestore-interceptor
  "Attaches the filestore to the request map"
  [system]
  (assert system)
  (assert (:filestore system) "Filestore not available")
  {:name ::filestore--interceptor
   :enter (fn [ctx]
            (let [filestore (:filestore system)]
              (-> ctx
                  (assoc-in  [:request :filestore] filestore))))})

(def service-error-handler
  (error-int/error-dispatch [ctx ex]

                            [{:exception-type :java.lang.ArithmeticException :interceptor ::another-bad-one}]
                            (assoc ctx :response {:status 400 :body "Another bad one"})

                            [{:exception-type :java.lang.ArithmeticException}]
                            (assoc ctx :response {:status 400 :body "A bad one"})

                            [{:exception-type :clojure.lang.ExceptionInfo :cognitect.anomalies/category :cognitect.anomalies/incorrect}]
                            (assoc ctx :response (errors/not-found-error (:request ctx) ex))

                            [{:exception-type :clojure.lang.ExceptionInfo :app/error-type :app.error.type/not-found}]
                            (assoc ctx :response (errors/not-found-error (:request ctx) ex))

                            [{:exception-type :clojure.lang.ExceptionInfo :app/error-type :app.error.type/validation}]
                            (assoc ctx :response (errors/validation-error (:request ctx) ex))

                            [{:exception-type :clojure.lang.ExceptionInfo :app/error-type :app.error.type/authentication-failure}]
                            (assoc ctx :response (errors/unauthorized-error (:request ctx) ex))

                            :else
                            (assoc ctx :response (errors/unknown-error (:request ctx) ex))))

(def htmx-interceptor
  "Sets :htmx? to true if the request originates from htmx"
  {:name ::htmx
   :enter (fn [ctx]
            (let [request (:request ctx)
                  headers (:headers request)]
              (if (some? (get headers "hx-request"))
                (-> ctx
                    (assoc-in [:request :htmx]
                              (->> headers
                                   (filter (fn [[key _]] (.startsWith key "hx-")))
                                   (map (fn [[key val]] [(keyword key) val]))
                                   (into {})))
                    (assoc-in [:request :htmx?] true))
                (assoc-in ctx [:request :htmx?] false))))})

(defn system-interceptor
  "Install the integrant system map into the request under the :system key"
  [system]
  {:name ::system-interceptor
   :enter (fn [ctx]
            (assoc-in ctx [:request :system] system))})

(defn dev-mode-interceptor
  "Tell the request if we are in dev mode or not"
  [system]
  {:name ::dev-mode-interceptor
   :enter (fn [ctx]
            (assoc-in ctx [:request :dev?] (config/dev-mode? (-> system :env))))})

(def human-id-interceptor
  "Add a human readable id for the request"
  {:name ::human-id-interceptor
   :enter (fn [ctx]
            (assoc-in ctx [:request :human-id] (human-id/human-id)))})
(defn webdav-interceptor
  "Add webdav service to request map"
  [system]
  {:name ::webdav-interceptor
   :enter (fn [ctx]
            (assoc-in ctx [:request :webdav] (:webdav system)))})

(def tap-interceptor
  {:name :tap-interceptor
   :enter (fn [ctx]
            ;; (tap> (-> req :request))
            (tap> {:tap-inter (-> ctx :request :headers)})
            ctx)
   :leave (fn [ctx]
            (tap> {:leaving true})
            ctx)})

(def log-request-interceptor
  "Logs all http requests with response time."
  {:name ::log-request
   :enter (fn [ctx]
            (assoc-in ctx [:request :start-time] (System/currentTimeMillis)))
   :leave (fn [ctx]
            (let [{:keys [uri start-time request-method query-string human-id] :as req} (:request ctx)
                  user-email (auth/get-current-email req)
                  finish (System/currentTimeMillis)
                  total (- finish start-time)]
              (μ/log :http/request :msg "request completed"
                     :request-method request-method
                     :uri uri
                     :human-id human-id
                     :user-email user-email
                     :query-string  query-string
                     :status (:status (:response ctx))
                     :response-time total)
              ctx))})

(defn query-string-lang [query-string]
  (when query-string
    (last (re-find #"lang=([^&]+)" query-string))))

(defn cookie-lang [cookies]
  (get-in cookies ["lang" :value]))

(defn i18n-interceptor [system]
  (let [lang-dicts (:i18n-langs system)]
    (assert lang-dicts "Translations not available")
    {:name ::i18n-interceptor
     :enter (fn [ctx]

              (let [lang-dicts (if (config/dev-mode? (:env system))
                                 (i18n/read-langs)
                                 lang-dicts)
                    request (:request ctx)
                    query-string (:query-string request)
                    lang-qr (query-string-lang query-string)
                    lang-cookie (cookie-lang (:cookies request))
                    lang-browser (i18n/browser-lang (:headers request))
                    all-accepted-langs (filterv some? (concat  [lang-qr lang-cookie] lang-browser [(name i18n/default-locale)]))
                    both-set (and lang-qr lang-cookie)
                    change-lang (or
                                 (and both-set (not (= lang-qr lang-cookie)))
                                 (and (not both-set) lang-qr))
                    tempura-accepted (if (:tempura/accept-langs request)
                                       (:tempura/accept-langs request)
                                       [])
                    accepted (if all-accepted-langs (into [] (concat all-accepted-langs tempura-accepted)) tempura-accepted)
                    current-locale (i18n/supported-lang lang-dicts accepted)
                    tr (i18n/tr-with lang-dicts accepted)
                    req (if tr
                          (assoc request
                                 :will-change-lang change-lang
                                 :tr tr
                                 :tempura/accept-langs accepted
                                 :current-locale (keyword current-locale))
                          (assoc request
                                 :will-change-lang change-lang
                                 :current-locale (keyword current-locale)))
                    ;;
                    ]
                (assoc ctx :request req)))
     :leave (fn [ctx]
              (if (get-in ctx [:request :will-change-lang])
                (-> ctx

                    (assoc-in [:response :cookies "lang" :value] (name (get-in ctx [:request :current-locale])))
                    (assoc-in [:response :cookies "lang" :path] "/")
                    (assoc-in [:response :cookies "lang" :max-age] (* 3600 30)))
                ctx))}))

(def default-coercion
  (-> rcm/default-options
      (assoc-in [:options :registry] schemas/registry)
      rcm/create))

(def formats-instance
  (m/create
   (-> m/default-options
       (update-in
        [:formats "application/transit+json" :decoder-opts]
        (partial merge time/time-deserialization-handlers))
       (update-in
        [:formats "application/transit+json" :encoder-opts]
        (partial merge time/time-serialization-handlers))
       (assoc-in [:formats "application/json" :encoder-opts]
                 {:encode-key-fn name})
       (assoc-in [:formats "application/json" :decoder-opts]
                 {:decode-key-fn keyword}))))

(defn prone-exception-interceptor
  "Pretty prints exceptions in the browser"
  [service]
  (update-in service [:io.pedestal.http/interceptors] #(vec (cons (pedestal-prone/exceptions {:app-namespaces ["app"]}) %))))

(defn http-configuration
  [max-size]
  (doto (HttpConfiguration.)
    (.setRequestHeaderSize max-size)))

(defn default-reitit-interceptors [system]
  (into [] (remove nil?
                   [;; inject-debug-interceptor
                    human-id-interceptor
                    (i18n-interceptor system)
                    log-request-interceptor
                    #_(cond (config/demo-mode? (:env system))
                            auth/demo-auth-interceptor
                            ;; (config/dev-mode? (:env system))
                            ;; (auth/dev-auth-interceptor (-> system :env :secrets :dev-session))
                            :else (auth/auth-interceptor (-> system :env :authorization :cert-filename)
                                                         (-> system :env :authorization :known-roles)))

                    ;; dev-mode-interceptor
                    ;; query-params & form-params
                    (parameters/parameters-interceptor)
                    ;; content-negotiation
                    (muuntaja/format-negotiate-interceptor)
                    ;; encoding response body
                    (muuntaja/format-response-interceptor)
                    ;; exception handling
                    ;; exception-interceptor
                    ;; decoding request body
                    (muuntaja/format-request-interceptor)
                    ;; coercing response bodys
                    (coercion/coerce-response-interceptor)
                    ;; coercing request parameters
                    (coercion/coerce-request-interceptor)
                    htmx-interceptor
                    ;; htmx reequires all params (query, form etc) to be keywordized
                    keyword-params-interceptor
                    ;; multipart
                    (multipart/multipart-interceptor)])))

(def etag-interceptor
  (interceptor/interceptor
   {:name ::etag
    :leave (middlewares/response-fn-adapter
            (fn [request _opts]
              (etag/add-file-etag request false)))}))

(def hash-prefix-len (count "hash-"))
(def hash-len 8)

#_(defn unhash-path [path]
    (let [components (str/split path  #"/")
          root-dir (second components)
          file-name (last components)]
      (if (and
           root-dir
           file-name
           (contains? #{"js" "img" "font" "css"} root-dir)
           (str/starts-with? file-name "hash-"))
        (let [new-file-name (subs file-name (+ hash-prefix-len hash-len))
              new-full-path (str/join "/" (concat  (butlast components) [new-file-name]))]
        ;; (tap> [:asset-hash-rewrite-interceptor-enter components  path  root-dir remaining new-file-name new-full-path])
          new-full-path)
        path)))

#_(defn  asset-hash-rewrite-interceptor-enter [ctx]
    (let [old-path (get-in ctx [:request :path-info])
          new-path (-> old-path unhash-path)]
      (if (= old-path new-path)
        ctx
        (-> ctx
            (assoc-in  [:request :uri] new-path)
            (assoc-in  [:request :path-info] new-path)))))

#_(def asset-hash-rewrite-interceptor
    (interceptor/interceptor
     {:name  ::cache-control
      :enter (fn [ctx] (asset-hash-rewrite-interceptor-enter ctx))}))

(def cache-control-interceptor
  (interceptor/interceptor
   {:name  ::cache-control
    :leave (fn [ctx]
             (if-not (get-in ctx [:response :headers "Cache-Control"])
               (if-let [content-type (get-in ctx [:response :headers "Content-Type"])]
                 (let [cacheable-content-type? (fn [content-type]
                                                 (some
                                                  #(contains? #{"text/css" "text/javascript" "image/svg+xml"
                                                                "image/png" "image/x-icon" "text/xml"} %)
                                                  (str/split content-type #";")))]
                   (assoc-in ctx [:response :headers "Cache-Control"]
                             (if (cacheable-content-type? content-type) "max-age=31536000,immutable,public" "no-cache")))
                 ctx)
               ctx))}))

(def to-remove #{:io.pedestal.http.route/query-params
                 :io.pedestal.http.route/path-params-decoder
                 :io.pedestal.http/log-request
                 :io.pedestal.http.route/router})

(defn with-our-pedestal-interceptors [service system router handler]
  (-> service
      (update ::http/interceptors conj
              service-error-handler
              middlewares/cookies
              etag-interceptor
              cache-control-interceptor
              #_asset-hash-rewrite-interceptor
              ;; this should be last!
              (pedestal/routing-interceptor router handler))
      ;; remove the pedestal default handler, because now we use the reitit one
      (update ::http/interceptors
              (fn [interceptors]
                (into []
                      (remove #(contains? to-remove (:name %)) interceptors))))))
