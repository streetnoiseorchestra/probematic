;; Cookie lifecycle adapted from hifi.web.middleware.session.
;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns app.interceptors.session
  (:require
   [app.util.crypto :as crypto]
   [app.interceptors.options :as options]
   [app.interceptors.util :as int]
   [app.session :as session]
   [ring.middleware.cookies :as cookies]))

(defn- cookie-response [response request cookie-sid {:keys [cookie-name cookie-attrs] :as opts}]
  (let [sid      (if (contains? response :app/sid) (:app/sid response) (:app/sid request))
        attrs    (:app/session-cookie-attrs response)
        response (cond-> response
                   (or (not= sid cookie-sid)
                       (and attrs sid)
                       (and (contains? response :app/sid) (nil? sid)))
                   (assoc-in [:cookies cookie-name]
                             (cond-> (merge cookie-attrs attrs {:value (or sid "")})
                               (nil? sid) (assoc :max-age 0))))]
    (cookies/cookies-response
     (dissoc response :app/sid :app/session-cookie-attrs) opts)))

(defn session-cookie-interceptor
  "Manages the opaque `sid` cookie through `:app/sid`.

  A cookieless GET gets a new ID. Other cookieless methods return 403.
  A response `:app/sid` rotates the ID; explicit nil expires the cookie.
  Inner response cookies are serialized together with the session cookie.
  Install after Fetch Metadata CSRF protection and before [[session-data-interceptor]].

  | Option | Description |
  |--------|-------------|
  | `:cookie-name` | Cookie name, default `sid`. |
  | `:cookie-attrs` | Ring cookie attributes, default path `/`, HttpOnly, SameSite=Lax. |

  Responses can override attributes through `:app/session-cookie-attrs`."
  ([] (session-cookie-interceptor {}))
  ([opts]
   (let [opts (options/valid! "session-cookie-interceptor" options/SessionCookieInterceptorOptions
                              (options/coerce options/SessionCookieInterceptorOptions opts))]
     {:name  ::session-cookie
      :enter (fn [{:keys [request] :as context}]
               (let [request (cookies/cookies-request request opts)
                     sid     (not-empty (get-in request [:cookies (:cookie-name opts) :value]))
                     context (assoc context ::cookie-sid sid :request request)]
                 (cond
                   sid (assoc-in context [:request :app/sid] sid)
                   (= :get (:request-method request))
                   (assoc-in context [:request :app/sid] (crypto/new-uid))
                   :else (int/terminate context {:status 403}))))
      :leave (fn [{:keys [request response] :as context}]
               (cond-> context
                 response (assoc :response (cookie-response response request (::cookie-sid context) opts))))})))

(defn- persist-response! [db sid stored-session response]
  (cond
    (or (and (contains? response :app/session) (nil? (:app/session response)))
        (and (contains? response :app/sid) (nil? (:app/sid response))))
    (do (session/delete-session! db sid)
        (assoc response :app/sid nil))

    (or (contains? response :app/session)
        (and stored-session (:app/sid response) (not= sid (:app/sid response))))
    (let [data    (if (contains? response :app/session) (:app/session response) stored-session)
          new-sid (or (:app/sid response) (when stored-session sid) (crypto/new-uid))]
      (assert (map? data) "Response :app/session must be a map or nil")
      (session/write-session! db sid new-sid data)
      (assoc response :app/sid new-sid))

    :else response))

(defn session-data-interceptor
  "Loads SQLite data into request `:app/session`, defaulting to an empty map.

  Install after [[session-cookie-interceptor]]. A response `:app/session` map
  saves data; nil deletes it and expires the cookie; omission leaves it unchanged.
  ID rotation moves existing data and deletes the old row atomically. Saving a
  session with no live stored row generates a fresh ID, not a client-chosen ID.

  | Option | Description |
  |--------|-------------|
  | `:expire-secs` | Required positive lifetime; writes refresh it, reads do not. |

  The caller owns `db` and its lifecycle."
  [db opts]
  (let [db (session/init! db opts)]
    {:name  ::session-data
     :enter (fn [{:keys [request] :as context}]
              (let [data (session/read-session db (:app/sid request))]
                (-> context
                    (assoc ::stored-session data)
                    (assoc-in [:request :app/session] (or data {})))))
     :leave (fn [{:keys [request response] :as context}]
              (cond-> context
                response (assoc :response
                                (dissoc (persist-response! db (:app/sid request) (::stored-session context) response)
                                        :app/session))))}))
