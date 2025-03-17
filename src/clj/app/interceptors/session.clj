(ns app.interceptors.session
  (:require
   [app.interceptors.options :as options]
   [ring.middleware.session :as session]
   [ring.middleware.session.memory :as session.memory]))

(defn- response-fn->leave
  [response-fn & args]
  (fn [context]
    (let [{:keys [request response]} context]
      (cond-> context
        response (assoc :response (apply response-fn response request args))))))

;; Explicitly configure a session store, defaulting to the memory store.
;; Without this, then reitit will mount one instance of the session store per route,
;; which is definitely not what the developer expects.
;; ref: https://github.com/metosin/reitit/issues/205
;; ref: https://github.com/ferdinand-beyer/reitit-ring-defaults#warning-on-session-middleware
(def default-session-store (session.memory/memory-store))

(defn ->session-interceptor-opts [v]
  (options/valid! "session-interceptor" options/SessionInterceptorOptions
                  (let [opts (options/coerce options/SessionInterceptorOptions (or v {}))]
                    (if (nil? (:store opts))
                      (assoc opts :store default-session-store)
                      opts))))

(defn session-interceptor
  "Interceptor for managing client sessions. A session is a simple store of data, associated with a single client,
  that persists between requests. A cookie (by default `ring-session`) is used to connect requests and responses
  to a session. A store (the default is an in-memory Atom) stores the data between requests.

  When using `session` also consider using `csrf-protection` to avoid Cross Site Request Forgery attacks.

  The request key `:session` is a map storing the session data, and `:session/key` stores the key uniquely
  identifying the client session.

  It is the application's responsibility to copy the `:session` and `:session/key` to the response. When this does
  not occur, the session will be removed from the store.

  Options:
  - `:store` - Implementation of SessionStore protocol for session storage. Defaults to in-memory storage.
  - `:root` - Root path of the session. Any path above this will not see the session. Sets cookie's path
    attribute. Default: `/`
  - `:cookie-name` - Name of the cookie holding the session key. Default: `ring-session`
  - `:cookie-attrs` - Map of attributes for the session cookie. Default: `{:same-site :lax :http-only true}`
  - `:set-cookies?` - If true, automatically includes cookie handling. Default: `true`

  On `:enter`, adds a `:session` key to the request.

  On `:leave`, uses the `:session` and `:session/key` response keys to update the store and, if necessary, create
  a new cookie with the new session key."

  ([] (session-interceptor {}))
  ([options]
   (let [options (->session-interceptor-opts options)]
     {:name           ::session
      :options-schema options/SessionInterceptorOptions
      :enter          (fn [context]
                        (update context :request session/session-request options))
      :leave          (response-fn->leave session/session-response options)})))
