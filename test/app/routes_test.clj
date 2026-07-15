(ns app.routes-test
  (:require
   [app.datastar :as datastar]
   [app.i18n :as i18n]
   [app.routes :as routes]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [reitit.core :as r]
   [reitit.http :as http]))

(def csrf-interceptor-name
  :app.interceptors.csrf/fetch-metadata)

(def unsafe-methods
  [:post :put :patch :delete])

(defn- test-system []
  (let [{:keys [conn]} (tc/new-system "csrf-routes")]
    {:env {:ig/system {:app.ig/profile :test}
           :session-config {:session-ttl-s 3600
                            :cookie-attrs {}}}
     :i18n-langs (i18n/read-langs)
     :oauth2 {}
     :datomic {:conn conn}
     :webdav {}
     :redis {}
     :filestore {}
     :nexus {:nexus/actions {}}
     :datastar-refresh-mult
     {::datastar/refresh-mult ::refresh-mult}
     :icon-sprites {}}))

(defn- app-handler []
  (routes/default-handler (test-system)))

(defn- app-router []
  (http/get-router (app-handler)))

(defn- unsafe-handler-summaries [router]
  (vec
   (for [[path data] (r/compiled-routes router)
         method unsafe-methods
         :when (get data method)]
     {:path path
      :method method
      :interceptor-names (mapv :name (:interceptors data))})))

(deftest static-resource-symlink-policy
  (let [options-fn (ns-resolve 'app.routes 'resource-handler-options)]
    (is (some? options-fn) "Static resource handler options should be explicit")
    (when options-fn
      (is (= [{:path "/" :allow-symlinks? true}
              {:path "/" :allow-symlinks? false}]
             (mapv options-fn
                   [{:env {:ig/system {:app.ig/profile :dev}}}
                    {:env {:ig/system {:app.ig/profile :prod}}}]))))))

(deftest act-route-checks-fetch-metadata-before-request-processing
  (let [router (app-router)
        names  (mapv :name
                     (get-in (r/match-by-path router "/act")
                             [:data :interceptors]))
        relevant-names
        (filterv
         #{:app.interceptors/log-request
           csrf-interceptor-name
           :reitit.http.interceptors.parameters/parameters
           :reitit.http.interceptors.muuntaja/format-request
           :reitit.http.interceptors.multipart/multipart
           :app.interceptors.session/session}
         names)]
    (is (= [:app.interceptors/log-request
            csrf-interceptor-name
            :reitit.http.interceptors.parameters/parameters
            :reitit.http.interceptors.muuntaja/format-request
            :reitit.http.interceptors.multipart/multipart
            :app.interceptors.session/session]
           relevant-names))))

(deftest every-unsafe-route-inherits-fetch-metadata-protection
  (let [unsafe-handlers (unsafe-handler-summaries (app-router))]
    (is (= {:unsafe-handler-count 59
            :unprotected []}
           {:unsafe-handler-count (count unsafe-handlers)
            :unprotected
            (into []
                  (remove #(some #{csrf-interceptor-name}
                                 (:interceptor-names %)))
                  unsafe-handlers)}))))

(deftest unsafe-fallback-requests-require-same-origin-fetch-metadata
  (let [handler  (app-handler)
        requests [{:case :unknown-path
                   :request-method :post
                   :uri "/definitely-missing"}
                  {:case :method-mismatch
                   :request-method :put
                   :uri "/login/restart"}
                  {:case :trailing-slash-redirect
                   :request-method :post
                   :uri "/login/"}
                  {:case :static-resource
                   :request-method :post
                   :uri "/js/confetti.js"}
                  {:case :trace
                   :request-method :trace
                   :uri "/definitely-missing"}
                  {:case :connect
                   :request-method :connect
                   :uri "/js/confetti.js"}
                  {:case :generic-handler
                   :request-method :trace
                   :uri "/login"}]
        responses
        (mapv (fn [{:keys [case] :as request}]
                {:case case
                 :response
                 (select-keys (handler (dissoc request :case))
                              [:status :headers :body])})
              requests)]
    (is (= (mapv (fn [{:keys [case]}]
                   {:case case
                    :response
                    {:status 403
                     :headers {"Cache-Control" "no-store"
                               "Vary" "Sec-Fetch-Site"}
                     :body ""}})
                 requests)
           responses))))

(deftest same-origin-fetch-metadata-continues-to-fallback-handlers
  (let [handler  (app-handler)
        requests [{:case :unknown-path
                   :request-method :post
                   :uri "/definitely-missing"}
                  {:case :method-mismatch
                   :request-method :put
                   :uri "/login/restart"}
                  {:case :trailing-slash-redirect
                   :request-method :post
                   :uri "/login/"}
                  {:case :static-resource
                   :request-method :post
                   :uri "/js/confetti.js"}
                  {:case :trace
                   :request-method :trace
                   :uri "/definitely-missing"}
                  {:case :connect
                   :request-method :connect
                   :uri "/js/confetti.js"}]
        statuses
        (into {}
              (map (fn [{:keys [case] :as request}]
                     [case
                      (:status
                       (handler
                        (-> request
                            (dissoc :case)
                            (assoc :headers
                                   {"sec-fetch-site" "same-origin"}))))]))
              requests)]
    (is (= {:unknown-path 404
            :method-mismatch 405
            :trailing-slash-redirect 308
            :static-resource 200
            :trace 404
            :connect 200}
           statuses))))
