(ns app.interceptors.errors
  "Interceptor for gracefully handling exceptions both during development and in production."
  (:require [malli.core :as m]
            [malli.experimental.lite :as l]
            [app.interceptors.options :as options]
            [reitit.ring :as ring]))

;; --------------------------------------------------------------------------------------------
;;; Pattern matching impl

(defn ->pattern [k]
  (cond
    (keyword? k) k
    (class? k)   [:fn #(instance? k %)]
    (map? k)     (l/schema k)
    (vector? k)  k
    :else        (throw (ex-info "Invalid pattern" {:pattern k}))))

(defn ->compiled-pattern [pattern handler]
  (let [schema (->pattern pattern)]
    {:schema  schema
     :match?  (m/validator schema)
     :handler handler}))

(defn ->patterns [handlers]
  (mapv (fn [[pattern handler]]
          (->compiled-pattern pattern handler))
        handlers))

(defn prepare-handlers [handlers]
  (let [default  (::default handlers)
        wrap     (::wrap handlers)
        patterns (->patterns (dissoc handlers ::default ::wrap))]
    {:patterns patterns
     :default  default
     :wrap     wrap}))

(defn match-handler [{:keys [patterns default]} e]
  (let [match-value (or (ex-data e) e)]
    (or (some (fn [{:keys [match? handler]}]
                (when (match? match-value)
                  handler))
              patterns)
        default)))

;; --------------------------------------------------------------------------------------------
;;; Invoking handlers

(defn call-handler [matching-opts handler error request]
  (if-let [wrapping-handler (:wrap matching-opts)]
    (wrapping-handler handler error request)
    (handler error request)))

(defn handle-exceptions-int [matching-opts {:keys [error request] :as ctx}]
  (let [handler      (match-handler matching-opts error)
        new-response (call-handler matching-opts handler error request)]
    (if (instance? Exception new-response)
      (-> ctx (assoc :error new-response) (dissoc :response))
      (-> ctx (assoc :response new-response) (dissoc :error)))))

(defn handle-exceptions [matching-opts error request]
  (let [handler      (match-handler matching-opts error)
        new-response (call-handler matching-opts handler error request)]
    (if (instance? Exception new-response)
      (throw new-response)
      new-response)))

;; --------------------------------------------------------------------------------------------
;;; Default Handlers
;; An absolutely minimal set of default handlers
;; The goal is that by default there should be no information leakage to the client
;; Developers definitely should provide their own handlers

(defn default-exception-handler
  [^Exception _e _]
  {:status  500
   :headers {"Content-Type"           "text/plain"
             "App-Exception" "default-exception-handler"}
   :body    "Internal server error"})

(defn default-not-found-handler
  [^Exception _e _]
  {:status 404
   :body   "404 Page not found"})

(defn create-status-handler [status msg]
  (fn [_ _]
    {:status status
     :body   msg}))

(defn http-response-handler
  "Reads response from Exception ex-data :response"
  [e _]
  (-> e ex-data :response))

(defn request-parsing-handler [e _]
  {:status  400
   :headers {"Content-Type" "text/plain"
             "App-Exception" "request-parsing-handler"}
   :body    (str "Malformed " (-> e ex-data :format pr-str) " request")})

(def default-exception-handlers
  {::default                                       default-exception-handler
   ::wrap                                          (fn [handler e req]
                                                     (handler e req))
   {:type [:= ::ring/response]}                    http-response-handler
   {:type [:= :muuntaja/decode]}                   request-parsing-handler
   {:type [:= :reitit.coercion/request-coercion]}  (create-status-handler 400 "Request coercion failed")
   {:type [:= :reitit.coercion/response-coercion]} (create-status-handler 500 "Response coercion failed")})

;; --------------------------------------------------------------------------------------------
;;; Interceptor

(defn debug-error! [_request e]
  (tap> e))

(defn- on-exception [handlers debug-errors? {:keys [request error] :as ctx}]
  (tap> [:on-exception debug-errors? error])
  (when debug-errors?
    (debug-error! request error))
  (handle-exceptions-int handlers ctx))

(defn exception-interceptor
  ([] (exception-interceptor {}))
  ([opts]
   (let [{:keys [debug-errors? error-handlers]} (options/coerce options/ErrorInterceptorOptions (or opts {}))
         prepared-handlers                         (prepare-handlers (or error-handlers default-exception-handlers))]
     {:name           ::errors-interceptor
      :options-schema options/ErrorInterceptorOptions
      :enter          identity
      :error          (fn [ctx]
                        (try
                          (on-exception prepared-handlers debug-errors? ctx)
                          (catch Throwable t
                            (tap> [:error-handler-threw t :orig-error (:error ctx)])
                            (-> ctx
                                (assoc :response
                                       {:status  500
                                        :headers {"Content-Type" "text/plain"}
                                        :body    "Broken error handler"})
                                (dissoc :error)))))})))

(defn exception-backstop-interceptor
  "Creates an interceptor that serves as the final safety mechanism to prevent
   exceptions from propagating to the servlet container.

   When placed as the outermost interceptor in a chain, it ensures that any
   unhandled exceptions are converted to proper HTTP responses.

   Options:
     :report - A side-effecting function that takes [exception request] for logging/reporting.
               Defaults to a function that uses tap> to report the error. The return value is discarded."
  ([] (exception-backstop-interceptor {}))
  ([{:keys [report]
     :or   {report (fn [e req]
                     (prn e)
                     (tap> [:exception-backstop :error e :request req]))}}]
   {:name           ::exception-backstop-interceptor
    :options-schema options/ExceptionBackstopInterceptorOptions
    :error          (fn [ctx]
                      (when report
                        (try
                          (report (:error ctx) (:request ctx))
                          (catch Throwable _)))
                      (-> ctx
                          (assoc :response {:status  500
                                            :headers {"Content-Type" "text/plain"}
                                            :body    "Internal Server Error"})
                          (dissoc :error)))}))

(comment
  (match-handler
   (prepare-handlers
    {{:cognitect.anomalies/category [:= :cognitect.anomalies/incorrect]}
     (constantly :nf1)

     {:app/error-type [:= :app.error.type/not-found]}
     (constantly :nf2)

     {:app/error-type [:= :app.error.type/validation]}
     (constantly :valid)

     {:app/error-type [:= :app.error.type/authentication-failure]}
     (constantly :unath)

     :app.interceptors.errors/default (constantly :default!)})

   (ex-info "message" {:app/error-type  :app.error.type/authentication-failure,
                       :permitted-roles #{:Mitglieder}}))

  (def test-ex (ex-info "message" {:app/error-type  :app.error.type/authentication-failure,
                                   :permitted-roles #{:Mitglieder}}))
  (m/schema [2 :string])

  (m/parse [:altn
            [:keyword 1]
            [2 :string]]
           [:test])
  (m/parse [:altn
            [1 :keyword]
            [2 :string]]
           [:test])
  ;; => [1 test]

  (m/parse [:altn
            [1 :keyword]
            [2 :string]]
           ["wow"])
  ;; => [2 "wow"]
  (m/parse [:altn
            [0 [:altn [:thing :keyword]]]
            [1 [:altn [:thing :string]]]]
           ["wow"])

  ;; =>
  ;; clojure.lang.ExceptionInfo  :malli.core/duplicate-keys
  ;; {:type    :malli.core/duplicate-keys,
  ;;  :message :malli.core/duplicate-keys,
  ;;  :data    {:arr [1, {:order 0}, 1, {:order 1}]}}

  (def broken
    (let [nf (constantly :not-found)]
      [:altn
       [(constantly :incorrec)
        [:map [:cognitect.anomalies/category [:= :cognitect.anomalies/incorrect]]]]
       [nf
        [:map [:app/error-type [:= :app.error.type/not-found]]]]
       [nf
        [:map [:app/error-type [:= :app.error.type/validation]]]]
       [(constantly :authf)
        [:map [:app/error-type [:= :app.error.type/authentication-failure]]]]
       [:default :any]]))
  (def not-broken
    [:altn
     [(constantly :incorrectl)
      [:map [:cognitect.anomalies/category [:= :cognitect.anomalies/incorrect]]]]
     [(constantly :nf)
      [:map [:app/error-type [:= :app.error.type/not-found]]]]
     [(constantly :valida)
      [:map [:app/error-type [:= :app.error.type/validation]]]]
     [(constantly :authf)
      [:map [:app/error-type [:= :app.error.type/authentication-failure]]]]
     [:default :any]])
  ((parse broken (ex-data test-ex)))
  ((parse not-broken (ex-data test-ex)))

  ;;
  )
;; => nil
;; => nil
(comment
  ;; Thought: Pedestal's core.match macro based exception handling is nice and expressive,
  ;;          but it it uses a macro which prevents manipulating error handlers as data.
  ;;          Contrast that to reitit.http.interceptors.exception/exception-interceptor which treats handlers as data
  ;;          but has much less expressive matching.
  ;;          Why can't we have the best of both?
  ;;
  ;; Idea: Use malli.core/parse as an alternative to core.match!

  ;; Example usage
  (def handlers
    "handlers is a map of pattern -> error handler fn
  The keys can be:
    - class  - matches based on the class of the exception
    - map    - a malli lite schema
    - vector - a malli schema

  There are two special keys:
    - :pink.interceptors.errors/default - a default handler, if none of the patterns match
    - :pink.interceptors.errors/wrap    - a (fn [handler e req]) which will wrap the actual handler, useful for handling cross-cutting concerns
  "
    {;; match based on exception class  type
     java.lang.ArithmeticException              (constantly :math-error)
     ;; match based on the value of the :type key in the ex-data
     {:type [:= :error]}                        (constantly :error)
     {:type [:= :wut]}                          (constantly :wut)
     ;; match based on the value of the :category key in the ex-data
     {:category [:= :horror]}                   (constantly :horror)
     ;; match based on the presence of a :anomaly key, regardless of its value
     {:anomaly :any}                            (constantly :anomaly)
     ;; while the malli lite format is more concise, perhaps you want to use the normal malli format
     [:map [:a-number [:and :int [:fn even?]]]] (constantly :even)
     ;; a default handler
     ::default                                  (constantly :default)
     ;; a wrapping handler for cross cutting concerns
     ::wrap                                     (fn [handler e req]
                                                  (let [result (handler e req)]
                                                    (tap> [:from-wrap result])
                                                    result))})

  (defn handle-error [opts e req]
    (let [matching-opts (prepare-handlers opts)
          handler       (match-handler matching-opts e)]
      (if-let [wrapping-handler (:wrap matching-opts)]
        (wrapping-handler handler e req)
        (handler e req))))
  (handle-error handlers 1 {})
  (handle-error handlers {:different 1} {})
  (handle-error handlers (ex-info "message" {:type :error}) {})
  (handle-error handlers (ex-info "message" {:type :wut}) {})
  (handle-error handlers (ex-info "message" {:category :horror}) {})
  (handle-error handlers (ex-info "message" {:anomaly :anything}) {})
  (handle-error handlers (ex-info "message" {:anomaly ["really" "anything"]}) {})
  (handle-error handlers (ex-info "message" {:a-number 2}) {})
  (handle-error handlers (ex-info "message" {:a-number 3}) {})
  (handle-error handlers (ex-info "message" {}) {})
  (handle-error handlers (java.lang.ArithmeticException. "message") {})

  ;; this matches on the key in the map
  (m/parse [:altn
            [:has-type-key [:map [:type :keyword]]]
            [:invalid :any]]
           [{:type :error}])
  ;; => [:has-type-key {:type :error}]

  ;; but we want to match on the value of :type
  (m/parse [:altn
            [:potates [:map [:type [:= :potates]]]]
            [:beans [:map [:type [:= :beans]]]]
            [:invalid :any]]
           [{:type :beans :other :stuff}])

  (m/parse [:catn [:a [:= 1]] [:b :any] [:c [:= 3]] [:rest [:* :any]]] '[1 2 3 4 5 6])
  ;; => {:a 1, :b 2, :c 3, :rest [4 5 6]}
  )
