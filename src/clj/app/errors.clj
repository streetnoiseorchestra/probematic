(ns app.errors
  (:require
   [app.util.error :as u.error]
   [app.config :as config]
   [app.util :as util]
   [com.brunobonacci.mulog :as μ]
   [medley.core :as m]))

(def dangerous-keys
  #{:datomic-conn
    :conn
    :db
    :db-before
    :db-after
    :system
    :env
    :claims
    :cookies
    :cookie-secret
    :session-config
    :app-secret-key
    :client-id
    :client-secret
    :oauth2
    :secrets
    :session
    :access_token
    :id_token
    :refresh_token
    :keycloak
    :password
    :password-confirm
    :reitit.core/match
    :muuntaja/response
    :reitit.core/router})

(def redact-keys #{:password :pass "x-forwarded-access-token" "cookie"})

(defn sanitize [v]
  (let [user-email (get-in v [:session :session/email])
        member-id (get-in v [:session :session/member :member/member-id])
        v (->> v
               (util/remove-deep dangerous-keys)
               (util/replace-deep redact-keys "<REDACTED>"))]
    (if (map? v)
      (-> v
          (m/assoc-some :user-email user-email)
          (m/assoc-some :member-id (str member-id)))
      v)))

(defn unwrap-ex [ex]
  (-> (if-let [data (ex-data ex)]
        (if-let [nested-ex (:exception data)]
          nested-ex
          ex)
        ex)
      (sanitize)
      (u.error/clean-throwable)))

(defn prepare-req
  "Given a request map, returns a smaller sanitized map designed for event logging consumption"
  [req]
  (-> req
      (update :params (fn [params]
                        (-> {:params params}
                            (assoc :htmx? (:htmx? req))
                            (assoc :current-locale (:current-locale req))
                            (assoc :will-change-lang (:will-change-lang req))
                            (assoc :form-params (:form-params req))
                            (assoc :path-params (:path-params req)))))
      ;; (update :request-method name)
      (select-keys   [:uri :query-string :request-method :headers :params :member-id :user-email])
      ;; (set/rename-keys  {:request-method :method :params :data :uri :url})
      sanitize))

(defn send-event!
  "Sends a telemetry event asynchronously"
  ([req ex]
   (send-event! (or (ex-message ex) "no message") req ex))
  ([msg req ex]
   (let [event-data {:msg msg
                     :extra {:human-id (:human-id req)}
                     :request (prepare-req req)}]
     (μ/with-context event-data
       (μ/log ::error :ex (unwrap-ex ex))))))

(defn log-error! [req ex]
  (when (config/prod-mode? (-> req :system :env))
    (μ/log ::error
      :ex (unwrap-ex ex)
      :request (prepare-req req))))

(defn report-error!
  "Report an exception outside the normal request/response lifecycle"
  ([ex]
   (report-error! ex nil))
  ([ex extra]
   (μ/with-context {:msg       (ex-message ex)
                    :extra     extra
                    :reported? true}
     (μ/log ::error :ex (unwrap-ex ex)))))

(defn redact-mulog-events [events]
  (->> events
       (map sanitize)))

(defmacro try-log [data & body]
  `(try
     ~@body
     (catch Throwable ~'t
       (report-error! ~'t ~data)
       ;; Return nil when there is an error
       nil)))
