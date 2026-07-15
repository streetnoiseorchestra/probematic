(ns app.interceptors.csrf-test
  (:require
   [app.interceptors.csrf]
   [clojure.test :refer [deftest is testing]]
   [reitit.http :as http]
   [reitit.interceptor.sieppari :as sieppari]
   [reitit.ring :as ring]))

(def missing-api ::missing-api)

(defn- resolved-value [symbol]
  (some-> (ns-resolve 'app.interceptors.csrf symbol) deref))

(defn- request-policy [request]
  (if-let [policy (resolved-value 'request-policy)]
    (policy request)
    missing-api))

(defn- security-event? [request]
  (if-let [classify (resolved-value 'security-event?)]
    (classify request)
    missing-api))

(defn- csrf-handler [downstream]
  (when-let [interceptor (resolved-value 'fetch-metadata-interceptor)]
    (http/ring-handler
     (http/router
      [["/" {:interceptors [interceptor]
             :handler downstream}]])
     (ring/create-default-handler)
     {:executor sieppari/executor})))

(deftest safe-methods-do-not-require-fetch-metadata
  (doseq [method [:get :head :options]]
    (testing (name method)
      (is (= {:allowed? true
              :security-event? false}
             (request-policy {:request-method method}))))))

(deftest unsafe-methods-require-exact-same-origin-metadata
  (doseq [method [:post :put :patch :delete]]
    (testing (name method)
      (is (= {:allowed? true
              :security-event? false}
             (request-policy
              {:request-method method
               :headers {"sec-fetch-site" "same-origin"}}))))))

(deftest unsafe-methods-reject-untrusted-fetch-metadata
  (doseq [method [:post :put :patch :delete]
          [label fetch-site security-event?]
          [["same-site" "same-site" true]
           ["cross-site" "cross-site" true]
           ["none" "none" false]
           ["missing" nil false]
           ["empty" "" false]
           ["unknown" "future-value" false]
           ["malformed" " same-origin" false]
           ["wrong case" "SAME-ORIGIN" false]]]
    (testing (str (name method) " with " label)
      (is (= {:allowed? false
              :security-event? security-event?}
             (request-policy
              {:request-method method
               :headers (cond-> {}
                          fetch-site
                          (assoc "sec-fetch-site" fetch-site))}))))))

(deftest methods-outside-the-safe-set-follow-the-unsafe-policy
  (doseq [method [:trace :connect :custom]]
    (testing (name method)
      (is (= [{:allowed? false
               :security-event? false}
              {:allowed? true
               :security-event? false}]
             [(request-policy {:request-method method})
              (request-policy
               {:request-method method
                :headers {"sec-fetch-site" "same-origin"}})])))))

(deftest security-event-classification-is-limited-to-explicit-site-blocks
  (is (= {"same-site" true
          "cross-site" true
          "none" false
          "missing" false
          "empty" false
          "unknown" false
          "safe-method" false}
         {"same-site" (security-event?
                       {:request-method :post
                        :headers {"sec-fetch-site" "same-site"}})
          "cross-site" (security-event?
                        {:request-method :post
                         :headers {"sec-fetch-site" "cross-site"}})
          "none" (security-event?
                  {:request-method :post
                   :headers {"sec-fetch-site" "none"}})
          "missing" (security-event? {:request-method :post})
          "empty" (security-event?
                   {:request-method :post
                    :headers {"sec-fetch-site" ""}})
          "unknown" (security-event?
                     {:request-method :post
                      :headers {"sec-fetch-site" "future-value"}})
          "safe-method" (security-event?
                         {:request-method :get
                          :headers {"sec-fetch-site" "cross-site"}})})))

(deftest same-origin-request-continues-through-the-interceptor
  (let [handled? (atom false)
        handler  (csrf-handler
                  (fn [_request]
                    (reset! handled? true)
                    {:status 200
                     :headers {}
                     :body "handled"}))]
    (is (some? handler) "Fetch Metadata interceptor should exist")
    (when handler
      (is (= {:response {:status 200
                         :headers {"Vary" "Sec-Fetch-Site"}
                         :body "handled"}
              :handled? true}
             {:response (handler
                         {:request-method :post
                          :uri "/"
                          :headers {"sec-fetch-site" "same-origin"}})
              :handled? @handled?})))))

(deftest untrusted-request-terminates-before-downstream-behavior
  (let [handled? (atom false)
        handler  (csrf-handler
                  (fn [_request]
                    (reset! handled? true)
                    {:status 200
                     :headers {}
                     :body "handled"}))]
    (is (some? handler) "Fetch Metadata interceptor should exist")
    (when handler
      (is (= {:response {:status 403
                         :headers {"Cache-Control" "no-store"
                                   "Vary" "Sec-Fetch-Site"}
                         :body ""}
              :handled? false}
             {:response (handler
                         {:request-method :post
                          :uri "/"
                          :headers {"sec-fetch-site" "cross-site"}})
              :handled? @handled?})))))

(deftest allowed-response-preserves-and-deduplicates-vary-fields
  (let [handler (csrf-handler
                 (fn [_request]
                   {:status 204
                    :headers {"vary" "Accept-Encoding, sec-fetch-site"}
                    :body ""}))]
    (is (some? handler) "Fetch Metadata interceptor should exist")
    (when handler
      (is (= {:status 204
              :headers {"Vary" "Accept-Encoding, sec-fetch-site"}
              :body ""}
             (handler
              {:request-method :post
               :uri "/"
               :headers {"sec-fetch-site" "same-origin"}}))))))
