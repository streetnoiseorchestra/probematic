(ns app.interceptors.errors-test
  (:require
   [app.interceptors.errors :as sut]
   [clojure.test :refer [deftest is]]))

(deftest match-handler-allows-reusing-one-handler-for-multiple-patterns
  (let [same-handler    (fn [_e _req]
                          {:status 418
                           :body   "same handler"})
        default-handler (fn [_e _req]
                          {:status 500
                           :body   "default"})
        prepared        (sut/prepare-handlers
                         {{:app/error-type [:= :app.error.type/not-found]}
                          same-handler

                          {:cognitect.anomalies/category [:= :cognitect.anomalies/incorrect]}
                          same-handler

                          ::sut/default
                          default-handler})]
    (is (= [{:status 418
             :body   "same handler"}
            {:status 418
             :body   "same handler"}
            {:status 500
             :body   "default"}]
           [((sut/match-handler prepared
                                (ex-info "not found" {:app/error-type :app.error.type/not-found}))
             nil nil)
            ((sut/match-handler prepared
                                (ex-info "incorrect" {:cognitect.anomalies/category :cognitect.anomalies/incorrect}))
             nil nil)
            ((sut/match-handler prepared
                                (ex-info "unknown" {:app/error-type :app.error.type/unknown}))
             nil nil)]))))

(deftest match-handler-supports-class-keyword-schema-and-vector-patterns
  (let [prepared (sut/prepare-handlers
                  {ArithmeticException
                   (fn [_e _req] {:matched :class})

                   :keyword
                   (fn [_e _req] {:matched :keyword-schema})

                   [:map [:app/error-type [:= :app.error.type/vector-pattern]]]
                   (fn [_e _req] {:matched :vector})

                   ::sut/default
                   (fn [_e _req] {:matched :default})})]
    (is (= [{:matched :class}
            {:matched :keyword-schema}
            {:matched :vector}
            {:matched :default}]
           [((sut/match-handler prepared (ArithmeticException. "math")) nil nil)
            ((sut/match-handler prepared :app.error.type/keyword-pattern) nil nil)
            ((sut/match-handler prepared
                                (ex-info "vector" {:app/error-type :app.error.type/vector-pattern}))
             nil nil)
            ((sut/match-handler prepared
                                (ex-info "unknown" {:app/error-type :app.error.type/unknown}))
             nil nil)]))))

(deftest handle-exceptions-int-returns-interceptor-context-with-response
  (let [error    (ex-info "validation failed" {:app/error-type :app.error.type/validation})
        prepared (sut/prepare-handlers
                  {{:app/error-type [:= :app.error.type/validation]}
                   (fn [e req]
                     {:status 400
                      :body   {:message (ex-message e)
                               :uri     (:uri req)}})

                   ::sut/default
                   (fn [_e _req]
                     {:status 500})})]
    (is (= {:request  {:uri "/members"}
            :response {:status 400
                       :body   {:message "validation failed"
                                :uri     "/members"}}}
           (sut/handle-exceptions-int prepared
                                      {:request  {:uri "/members"}
                                       :response {:status 200}
                                       :error    error})))))

(deftest handle-exceptions-int-keeps-replacement-exceptions-for-downstream-handlers
  (let [replacement-error (ex-info "handler declined" {:app/error-type :app.error.type/unknown})
        prepared          (sut/prepare-handlers
                           {{:app/error-type [:= :app.error.type/validation]}
                            (fn [_e _req]
                              replacement-error)

                            ::sut/default
                            (fn [_e _req]
                              {:status 500})})]
    (is (= {:request {:uri "/members"}
            :error   replacement-error}
           (sut/handle-exceptions-int prepared
                                      {:request  {:uri "/members"}
                                       :response {:status 200}
                                       :error    (ex-info "validation failed"
                                                          {:app/error-type :app.error.type/validation})})))))

(deftest exception-interceptor-error-stage-returns-context-not-a-raw-response
  (let [interceptor (sut/exception-interceptor
                     {:error-handlers
                      {{:app/error-type [:= :app.error.type/authentication-failure]}
                       (fn [e req]
                         {:status 401
                          :body   {:message (ex-message e)
                                   :uri     (:uri req)}})

                       ::sut/default
                       (fn [_e _req]
                         {:status 500})}})]
    (is (= {:request  {:uri "/admin"}
            :response {:status 401
                       :body   {:message "not allowed"
                                :uri     "/admin"}}}
           ((:error interceptor)
            {:request {:uri "/admin"}
             :error   (ex-info "not allowed"
                               {:app/error-type :app.error.type/authentication-failure})})))))

(deftest wrapper-can-decorate-matched-handler-response
  (let [prepared (sut/prepare-handlers
                  {{:app/error-type [:= :app.error.type/validation]}
                   (fn [_e _req]
                     {:status 400})

                   ::sut/wrap
                   (fn [handler e req]
                     (assoc (handler e req)
                            :wrapped? true
                            :uri (:uri req)))

                   ::sut/default
                   (fn [_e _req]
                     {:status 500})})]
    (is (= {:request  {:uri "/wrapped"}
            :response {:status   400
                       :wrapped? true
                       :uri      "/wrapped"}}
           (sut/handle-exceptions-int prepared
                                      {:request {:uri "/wrapped"}
                                       :error   (ex-info "validation failed"
                                                         {:app/error-type :app.error.type/validation})})))))
