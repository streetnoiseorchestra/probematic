(ns app.interceptors.session-test
  (:require
   [app.auth :as auth]
   [app.errors :as errors]
   [app.interceptors.csrf :as csrf]
   [app.interceptors.session :as sut]
   [app.session :as session]
   [app.sqlite :as sqlite]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [reitit.http :as http]
   [reitit.interceptor.sieppari :as sieppari]
   [reitit.ring :as ring]
   [sqlite4clj.core :as sql]
   [tick.core :as t]))

(defn- app [interceptors handler]
  (http/ring-handler
   (http/router [["/" {:interceptors interceptors :handler handler}]])
   (ring/create-default-handler)
   {:executor sieppari/executor}))

(defn- response-cookies [response]
  (into {}
        (map #(let [[name value] (str/split (first (str/split % #";" 2)) #"=" 2)]
                [name value]))
        (get-in response [:headers "Set-Cookie"])))

(defn- request [method sid]
  {:uri     "/"                                               :request-method method
   :headers (cond-> {} sid (assoc "cookie" (str "sid=" sid)))})

(defn- with-db [f]
  (let [db (sqlite/start {:filename ":memory:"})]
    (try (f db) (finally (sqlite/stop db)))))

(deftest cookie-bootstrap-and-inner-cookies
  (let [handler     (app [(sut/session-cookie-interceptor)]
                         (fn [req] {:status  200                                  :body (:app/sid req)
                                    :cookies {"other" {:value "value" :path "/"}}}))
        initial     (handler (request :get nil))
        sid         (get (response-cookies initial) "sid")
        established (handler (request :get sid))]
    (is (re-matches #"[A-Za-z0-9_-]{27}" sid))
    (is (= [{"sid" sid "other" "value"} {"other" "value"} sid sid false false]
           [(response-cookies initial) (response-cookies established)
            (:body initial) (:body established)
            (contains? initial :cookies) (contains? established :cookies)]))))

(deftest cookieless-non-get-requests-stop-before-handler
  (let [handler (app [(sut/session-cookie-interceptor)]
                     (fn [_] (throw (ex-info "Handler must not execute" {}))))]
    (doseq [method [:post :put :patch :delete :head :options]]
      (testing (name method)
        (is (= {:status 403} (handler (request method nil))))))))

(deftest empty-cookie-is-not-an-established-session
  (let [handler (app [(sut/session-cookie-interceptor)]
                     (fn [req] {:status 200 :body (:app/sid req)}))]
    (is (= 403 (:status (handler (request :post "")))))
    (is (re-matches #"[A-Za-z0-9_-]{27}" (:body (handler (request :get "")))))))

(deftest cookie-rotation-and-response-controls
  (doseq [incoming [nil "old"]]
    (let [handler  (app [(sut/session-cookie-interceptor)]
                        (constantly {:status                   200                        :app/sid "new"
                                     :app/session-cookie-attrs {:same-site :strict}
                                     :cookies                  {"other" {:value "value"}}}))
          response (handler (request :get incoming))]
      (is (= [{"sid" "new" "other" "value"} {}]
             [(response-cookies response)
              (select-keys response [:app/sid :app/session-cookie-attrs :cookies])]))
      (is (some #(str/includes? % "SameSite=Strict") (get-in response [:headers "Set-Cookie"]))))))

(deftest unchanged-id-does-not-reissue-cookie
  (let [handler (app [(sut/session-cookie-interceptor)]
                     (fn [req] {:status 200 :app/sid (:app/sid req)}))]
    (is (= {:status 200} (handler (request :get "existing"))))))

(deftest attribute-only-response-reissues-cookie
  (let [handler  (app [(sut/session-cookie-interceptor)]
                      (constantly {:status 200 :app/session-cookie-attrs {:max-age 42}}))
        response (handler (request :get "existing"))]
    (is (= {"sid" "existing"} (response-cookies response)))
    (is (str/includes? (first (get-in response [:headers "Set-Cookie"])) "Max-Age=42"))))

(deftest fetch-metadata-and-session-cookie-compose
  (let [handler (app [csrf/fetch-metadata-interceptor (sut/session-cookie-interceptor)]
                     (constantly {:status 200 :body "ok"}))]
    (is (= [#{"sid"} {:status 200 :body "ok"} 403]
           [(set (keys (response-cookies (handler (request :get nil)))))
            (handler (request :get "established"))
            (:status (handler (assoc-in (request :post "established") [:headers "sec-fetch-site"] "cross-site")))]))))

(deftest data-lifecycle-through-auth-interceptor-chain
  (with-db
    (fn [db]
      (let [interceptors (auth/session-interceptors
                          {:auxiliary db
                           :env       {:ig/system      {:app.ig/profile :prod}
                                       :session-config {:session-ttl-s 60
                                                        :cookie-attrs  {:same-site :strict :http-only true :path "/"}}}})
            data         {:session/email "member@example.com" :session/roles #{:admin}}
            save         (app interceptors (constantly {:status 200 :app/session data}))
            read         (app interceptors (fn [req] {:status 200 :body (:app/session req)}))
            delete       (app interceptors (constantly {:status 200 :app/session nil}))
            initial      (save (request :get nil))
            sid          (get (response-cookies initial) "sid")
            cookie       (first (get-in initial [:headers "Set-Cookie"]))
            loaded       (read (request :get sid))
            deleted      (delete (request :post sid))]
        (is (= [{:status 200 :body data} {} {"sid" ""} {} nil]
               [loaded (select-keys initial [:app/session :app/sid :cookies])
                (response-cookies deleted) (:body (read (request :get sid)))
                (session/read-session db sid)]))
        (is (every? #(str/includes? cookie %) ["Secure" "HttpOnly" "SameSite=Strict" "Max-Age=60"]))
        (is (str/includes? (first (get-in deleted [:headers "Set-Cookie"])) "Max-Age=0"))))))

(deftest bootstrap-does-not-write-data
  (with-db
    (fn [db]
      (let [handler (app [(sut/session-cookie-interceptor)
                          (sut/session-data-interceptor db {:expire-secs 60})]
                         (fn [req] {:status 200 :body (:app/session req)}))]
        (is (= [{} [0]] [(:body (handler (request :get nil)))
                         (sql/q (:reader db) ["SELECT count(*) FROM http_sessions"])]))))))

(deftest unknown-and-expired-ids-cannot-fixate-new-sessions
  (with-db
    (fn [db]
      (let [db      (session/init! db {:expire-secs 60})
            expired (t/with-clock (t/instant "2026-01-01T00:00:00Z")
                      (session/write-session! db nil {:old true}))
            handler (app [(sut/session-cookie-interceptor)
                          (sut/session-data-interceptor db {:expire-secs 60})]
                         (constantly {:status 200 :app/session {:authenticated true}}))]
        (doseq [old-sid ["attacker-chosen" expired]]
          (let [response (handler (request :get old-sid))
                sid      (get (response-cookies response) "sid")]
            (is (and (string? sid) (not= old-sid sid)))
            (is (= [nil {:authenticated true}]
                   [(session/read-session db old-sid) (session/read-session db sid)]))))))))

(deftest rotation-moves-data-and-revokes-old-id
  (doseq [replacement [::omitted {:updated true}]]
    (with-db
      (fn [db]
        (let [db       (session/init! db {:expire-secs 60})
              original {:original true}
              old-sid  (session/write-session! db nil original)
              handler  (app [(sut/session-cookie-interceptor)
                             (sut/session-data-interceptor db {:expire-secs 60})]
                            (constantly (cond-> {:status 200 :app/sid "rotated"}
                                          (not= ::omitted replacement) (assoc :app/session replacement))))
              response (handler (request :post old-sid))]
          (is (= [{"sid" "rotated"} nil (if (= ::omitted replacement) original replacement)]
                 [(response-cookies response) (session/read-session db old-sid)
                  (session/read-session db "rotated")])))))))

(deftest data-interceptor-works-without-cookie-layer
  (with-db
    (fn [db]
      (let [handler  (app [(sut/session-data-interceptor db {:expire-secs 60})]
                          (fn [req] {:status 200 :body (:app/session req) :app/session {}}))
            response (handler (assoc (request :get nil) :app/sid "untrusted"))]
        (is (= [{} {} false nil]
               [(:body response) (session/read-session db (:app/sid response))
                (contains? response :app/session) (session/read-session db "untrusted")]))))))

(deftest explicit-nil-id-deletes-data-and-cookie
  (with-db
    (fn [db]
      (let [db       (session/init! db {:expire-secs 60})
            sid      (session/write-session! db nil {:authenticated true})
            handler  (app [(sut/session-cookie-interceptor)
                           (sut/session-data-interceptor db {:expire-secs 60})]
                          (constantly {:status 200 :app/sid nil}))
            response (handler (request :post sid))]
        (is (= [{"sid" ""} nil]
               [(response-cookies response) (session/read-session db sid)]))
        (is (str/includes? (first (get-in response [:headers "Set-Cookie"])) "Max-Age=0"))))))

(deftest session-secrets-are-removed-from-error-reports
  (let [data {:app/sid                                 "secret"
              :app/session                             {:session/access-token "secret"}
              :app.interceptors.session/cookie-sid     "secret"
              :app.interceptors.session/stored-session {:session/access-token "secret"}
              :status                                  500}]
    (is (= {:status 500 :member-id ""} (errors/sanitize data)))))
