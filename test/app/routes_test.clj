(ns app.routes-test
  (:require
   [app.datastar :as datastar]
   [app.i18n :as i18n]
   [app.job-queue :as job-queue]
   [app.routes :as routes]
   [app.session :as session]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [reitit.core :as r]
   [reitit.http :as http]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-sqlite-db)

(def csrf-interceptor-name
  :app.interceptors.csrf/fetch-metadata)

(def unsafe-methods
  [:post :put :patch :delete])

(defn- test-system []
  (let [{:keys [conn]} (tc/new-system "csrf-routes")]
    {:env          {:ig/system      {:app.ig/profile :test}
                    :session-config {:session-ttl-s 3600
                                     :cookie-attrs  {}}}
     :i18n-langs   (i18n/read-langs)
     :oauth2       {}
     :datomic      {:conn conn}
     :webdav       {}
     :auxiliary    tc/*sqlite-db*
     :filestore    {}
     :nexus        {:nexus/actions {}}
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
         method      unsafe-methods
         :when       (get data method)]
     {:path              path
      :method            method
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
           :app.interceptors.session/session-cookie
           :app.interceptors.session/session-data}
         names)]
    (is (= [:app.interceptors/log-request
            csrf-interceptor-name
            :reitit.http.interceptors.parameters/parameters
            :reitit.http.interceptors.muuntaja/format-request
            :reitit.http.interceptors.multipart/multipart
            :app.interceptors.session/session-cookie
            :app.interceptors.session/session-data]
           relevant-names))))

(deftest every-unsafe-route-inherits-fetch-metadata-protection
  (let [unsafe-handlers (unsafe-handler-summaries (app-router))]
    (is (= {:unsafe-handler-count 59
            :unprotected          []}
           {:unsafe-handler-count (count unsafe-handlers)
            :unprotected
            (into []
                  (remove #(some #{csrf-interceptor-name}
                                 (:interceptor-names %)))
                  unsafe-handlers)}))))

(deftest unsafe-fallback-requests-require-same-origin-fetch-metadata
  (let [handler  (app-handler)
        requests [{:case           :unknown-path
                   :request-method :post
                   :uri            "/definitely-missing"}
                  {:case           :method-mismatch
                   :request-method :put
                   :uri            "/login/restart"}
                  {:case           :trailing-slash-redirect
                   :request-method :post
                   :uri            "/login/"}
                  {:case           :static-resource
                   :request-method :post
                   :uri            "/js/confetti.js"}
                  {:case           :trace
                   :request-method :trace
                   :uri            "/definitely-missing"}
                  {:case           :connect
                   :request-method :connect
                   :uri            "/js/confetti.js"}
                  {:case           :generic-handler
                   :request-method :trace
                   :uri            "/login"}]
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
                    {:status  403
                     :headers {"Cache-Control" "no-store"
                               "Vary"          "Sec-Fetch-Site"}
                     :body    ""}})
                 requests)
           responses))))

(deftest same-origin-fetch-metadata-continues-to-fallback-handlers
  (let [handler  (app-handler)
        requests [{:case           :unknown-path
                   :request-method :post
                   :uri            "/definitely-missing"}
                  {:case           :method-mismatch
                   :request-method :put
                   :uri            "/login/restart"}
                  {:case           :trailing-slash-redirect
                   :request-method :post
                   :uri            "/login/"}
                  {:case           :static-resource
                   :request-method :post
                   :uri            "/js/confetti.js"}
                  {:case           :trace
                   :request-method :trace
                   :uri            "/definitely-missing"}
                  {:case           :connect
                   :request-method :connect
                   :uri            "/js/confetti.js"}]
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
    (is (= {:unknown-path            404
            :method-mismatch         405
            :trailing-slash-redirect 308
            :static-resource         200
            :trace                   404
            :connect                 200}
           statuses))))

(deftest jobs-dashboard-requires-admin-and-protects-mutations
  (let [queue    (job-queue/start! {:filename ":memory:"})
        system   (assoc (test-system) :job-queue queue)
        handler  (routes/default-handler system)
        sessions (session/init! tc/*sqlite-db* {:expire-secs 3600})
        request  (fn [sid method uri site]
                   (handler {:uri     uri                        :request-method method
                             :headers {"cookie"         (str "sid=" sid)
                                       "sec-fetch-site" site}}))]
    (try
      @(d/transact (get-in system [:datomic :conn])
                   [{:member/member-id (random-uuid) :member/email "jobs-test@example.com"}])
      (doseq [[sid roles] [["admin" #{:admin}] ["member" #{:Mitglieder}]]]
        (session/write-session! sessions sid {:session/email "jobs-test@example.com"
                                              :session/roles roles}))
      (is (= [[303 303 200 200 200 404]
              [401 401 401 401 401 401]
              [302 302 302 302 302 302]]
             (mapv (fn [sid]
                     (mapv #(:status (request sid :get % "same-origin"))
                           ["/admin/jobs" "/admin/jobs/" "/admin/jobs/queues"
                            "/admin/jobs/jobs" "/admin/jobs/public/style.css"
                            "/admin/jobs/missing"]))
                   ["admin" "member" "anonymous"])))
      (is (= "/admin/jobs/queues"
             (get-in (request "admin" :get "/admin/jobs" "same-origin")
                     [:headers "location"])))
      (drip/upsert-queue (:client queue) "test" {})
      (doseq [[sid site expected-status] [["admin" "cross-site" 403]
                                          ["admin" nil 403]
                                          ["member" "same-origin" 401]
                                          ["anonymous" "same-origin" 302]]]
        (is (= {:status expected-status :paused? false}
               {:status  (:status (request sid :post "/admin/jobs/queues/test/pause" site))
                :paused? (some? (:paused-at (first (drip/list-queues (:client queue)))))})))
      (is (= {:status 303 :paused? true}
             {:status  (:status (request "admin" :post "/admin/jobs/queues/test/pause" "same-origin"))
              :paused? (some? (:paused-at (first (drip/list-queues (:client queue)))))}))
      (is (= 404 (:status (request "admin" :get "/admin/jobs-other" "same-origin"))))
      (finally (job-queue/stop! queue)))))
