(ns app.auth
  (:require
   [app.config :as config]
   [app.errors :as errors]
   [app.html :as html]
   [app.interceptors.session :as session]
   [app.interceptors.util :as int]
   [app.secret-box :as secret-box]
   [app.session :refer [redis-store]]
   [app.ui2 :as ui2]
   [app.util :as util]
   [buddy.core.codecs :as codecs]
   [buddy.core.keys :as buddy-keys]
   [buddy.sign.jwt :as jwt]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [jsonista.core :as j]
   [medley.core :as m]
   [org.httpkit.client :as http]))

(def logout-form-id
  "Identifies the application shell's shared native logout form."
  "logout-form")

(defn throw-unauthorized
  ([msg data]
   (throw (ex-info msg
                   (merge {:app/error-type :app.error.type/authentication-failure} data))))
  ([msg cause data]
   (throw
    (ex-info msg
             (merge {:app/error-type :app.error.type/authentication-failure} data)
             cause))))

(defn- load-openid-config [well-known-uri]
  (some->
   @(http/get well-known-uri)
   :body
   (j/read-value j/keyword-keys-object-mapper)))

(defn- validate-openid-config [{:keys [authorization_endpoint] :as c}]
  (assert (not (str/blank? authorization_endpoint)) "Valid openid configuration required. authorization_endpoint is blank.")
  c)

(defn build-oauth2-config [env]
  (let [{:keys [callback-path well-known-uri client-id client-secret]} (:oauth2 env)
        config (validate-openid-config (load-openid-config well-known-uri))]
    {:callback-uri (str (config/app-base-url env) callback-path)
     :client-id client-id
     :client-secret client-secret
     :openid-config config}))

(defn oauth2-cookie [env value]
  {:http-only true
   :secure (not (config/dev-mode? env))
   :same-site :lax
   :max-age (* 10 #_minutes 60)
   :value (secret-box/encrypt value (config/app-secret-key env))})

(defn expire-oauth2-cookie [env]
  {:http-only true
   :secure (not (config/dev-mode? env))
   :same-site :lax
   :max-age 0
   :value "kill"})

(defn login-page-handler [env {:keys [openid-config client-id callback-uri]} request]
  (let [next          (get-in request [:params :next] false)
        login_hint    (get-in request [:params :login_hint] false)
        state         (codecs/bytes->b64-str (util/random-bytes 16) true)
        scope         (str/join " " ["openid" "email" "profile"])
        authorize-uri (str (:authorization_endpoint openid-config)
                           "?response_type=code"
                           "&client_id=" client-id
                           "&redirect_uri=" callback-uri
                           "&state=" state
                           "&scope=" scope
                           (when login_hint
                             (str "&login_hint=" (util/url-encode login_hint))))]
    {:status 302 :headers {"Location" authorize-uri} :body ""
     :cookies {"oauth2" (oauth2-cookie env
                                       {:oauth2/state state :oauth2/redirect-uri callback-uri :oauth2/post-login-uri next})}}))

(defn logout-page-handler
  "Handle single-sign-out. Clears the local session and redirects to the IDP to perform sign-out there too.
   Docs:
     * spec:  https://openid.net/specs/openid-connect-rpinitiated-1_0.html"
  [env {:keys [openid-config client-id _callback-uri]} request]
  (let [id-token (-> request :session :session/id-token)
        idp-logout-uri (str (:end_session_endpoint openid-config)
                            "?post_logout_redirect_uri=" (util/url-encode (str (config/app-base-url env)))
                            "&client_id=" client-id
                            "&id_token_hint=" id-token)]
    {:status 302 :headers {"Location" idp-logout-uri} :body "" :session nil}))

(defn code->token [{:keys [client-id client-secret openid-config]} code original-redirect-uri]
  (some->
   @(http/post  (:token_endpoint openid-config)
                {:form-params {:grant_type "authorization_code"
                               :code code
                               :redirect_uri original-redirect-uri
                               :client_id client-id
                               :client_secret client-secret}})
   :body
   (j/read-value j/keyword-keys-object-mapper)))

(defn build-oauth2-session
  "Given response from the IDP's token_endpoint, this function verified the token and returns a :session map containing:

    :session/username - the preferred username of the authenticated user
    :session/email - the email of the user
    :session/keycloak-id - the stable Keycloak subject claim
    :session/groups - a set of groups in the claims (if included)
    :session/roles - a set of roles that the user has, filtered to only include those in known-roles
    :session/access-token - the access token
    :session/refresh-token - the refresh token
    :session/id-token - the id token

  If the JWT is invalid then an exception will be thrown with the
  key value :app/error-type :app.error.type/authentication-failure.

  If the JWT is not present, then the interceptor does nothing.
  "
  [token certificate known-roles]
  (try
    (let [access-token-claims (jwt/unsign (:access_token token) certificate {:alg :rs256})]
      ;; also verify the id token
      (jwt/unsign (:id_token token) certificate {:alg :rs256})
      {:session/username (:preferred_username access-token-claims)
       :session/email (:email access-token-claims)
       :session/keycloak-id (:sub access-token-claims)
       :session/access-token (:access_token token)
       :session/refresh-token (:refresh_token token)
       :session/id-token (:id_token token)
       :session/groups (set (:groups access-token-claims))
       :session/roles (set (->> (get-in access-token-claims [:realm_access :roles])
                                (map keyword)
                                (filter #(contains? known-roles  %))))})

    (catch Exception e
      (throw-unauthorized "Authentication Token Validation Failed" e
                          {:token token
                           :buddy-cause (-> (ex-data e) :cause)}))))

(defn restart-login [env]
  {:status 302 :headers {"location" "/login"} :body "" :cookies {"oauth2" (expire-oauth2-cookie env)}})

(defn restart-login-handler [env]
  (assoc (restart-login env) :session nil))

(defn identity-mismatch-response [{:keys [tr] :as  req}]
  (ui2/standalone-page
   {:status      403
    :lang        (some-> req :current-locale name)
    :title       [:i18n/tr :identity-mismatch/page-title]
    :description [:i18n/tr :identity-mismatch/body]
    :translator  tr}
   [:header
    [:p [:i18n/tr :identity-mismatch/eyebrow]]
    [:h1 [:i18n/tr :identity-mismatch/title]]]
   [:p [:i18n/tr :identity-mismatch/body]]
   (into [:dl
          [:dt [:i18n/tr :identity-mismatch/signed-in-email]]
          [:dd [:code (or (get-in req [:session :session/email])
                          [:i18n/tr :unknown])]]]
         (when-let [keycloak-id (get-in req [:session :session/keycloak-id])]
           [[:dt [:i18n/tr :identity-mismatch/sno-id-subject]]
            [:dd [:code keycloak-id]]]))
   [:p [:i18n/tr :identity-mismatch/retry-guidance]]
   [:footer
    [:form {:method "post" :action "/login/restart"}
     [:button {:type "submit"}
      [:i18n/tr :identity-mismatch/restart-login]]]
    [:form {:method "post" :action "/logout"}
     [:button {:type "submit"}
      [:i18n/tr :identity-mismatch/log-out]]]]))

(defn oauth2-load-certificate [{:keys [openid-config]}]
  (->>
   (some-> @(http/get (:jwks_uri openid-config))
           :body
           (j/read-value j/keyword-keys-object-mapper)
           :keys)
   (m/find-first #(= "RS256" (:alg %)))
   (buddy-keys/jwk->public-key)))

(defn- post-login-client-side-redirect
  "Returns an interstitial response that stores the session and then redirects client-side.

  This avoids losing the `SameSite=strict` session cookie after OAuth2 login."
  [session cookies relative-uri]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :session session
   :cookies cookies
   :body    (html/->str
             [html/doctype-html5
              [:html
               [:head
                [:title "Probematic"]
                [:style
                 (html/raw (-> (io/resource "public/css/login-interstitial.css") slurp))]
                [:meta {:http-equiv "refresh"
                        :content    (str "0;URL='" relative-uri "'")}]]
               [:body
                [:div {:class "container"}
                 [:div {:class "content"}
                  [:noscript
                   [:p [:a {:href relative-uri} "Continue"]]]
                  [:div {:class "spinner"}
                   [:div]
                   [:div]
                   [:div]]
                  [:p "Logging in..."]]]]]])})

(defn identity-mismatch-preview-handler [env req]
  (if (config/dev-mode? env)
    (identity-mismatch-response req)
    {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"}))

(defn oauth2-callback-handler [env oauth2 {:keys [_session params] :as request}]
  (try
    (let [{:keys [state code]} params
          oauth2-cookie (secret-box/decrypt (get-in request [:cookies "oauth2" :value]) (config/app-secret-key env))
          expected-state (:oauth2/state oauth2-cookie)]
      (if-not (= expected-state state)
        (restart-login env)
        (let [original-redirect-uri (:oauth2/redirect-uri oauth2-cookie)
              post-login-uri (or  (:oauth2/post-login-uri oauth2-cookie) "/")
              token (code->token oauth2 code original-redirect-uri)]
          (if (or (nil? token) (:error token))
            (restart-login env)
            (post-login-client-side-redirect
             (build-oauth2-session token
                                   (oauth2-load-certificate oauth2)
                                   (config/oauth2-known-roles env))
             {"oauth2" (expire-oauth2-cookie env)} post-login-uri)))))
    (catch Throwable e
      (errors/send-event! request e)
      (restart-login env))))

(defn routes [system]
  (cond-> [""
           ["/login" {:handler (fn [req] (login-page-handler (:env system) (:oauth2 system) req))}]
           ["/login/restart" {:post {:handler (fn [_req] (restart-login-handler (:env system)))}}]
           ["/logout" {:post {:handler (fn [req] (logout-page-handler (:env system) (:oauth2 system) req))}}]
           ["/oauth2"
            ["/callback" {:handler (fn [req] (oauth2-callback-handler (:env system) (:oauth2 system) req))}]]]
    (config/dev-mode? (:env system))
    (conj ["/dev/identity-mismatch" {:handler (fn [req] (identity-mismatch-preview-handler (:env system) req))}])))

(defn session-interceptor
  [{:keys [env redis]}]
  (let [{:keys [session-ttl-s cookie-attrs]} (config/session-config env)]
    (session/session-interceptor {:cookie-attrs cookie-attrs
                                  :store        (redis-store redis {:expire-secs session-ttl-s})})))

(def roles-authorization-interceptor
  "Reitit route interceptor that mounts itself if route has `:app.auth/roles` data. Expects `:app.auth/roles`
  to be a set of keyword and the context to have `[:session :app.auth/identity :app.auth/roles]` with user roles.
  responds with HTTP 403 if user doesn't have the roles defined, otherwise no-op."
  {:name    ::auth
   :compile (fn [{::keys [roles]} _]
              (when (seq roles)
                {:description  (str "requires roles " roles)
                 :spec         {::roles #{keyword?}}
                 :context-spec {:user {::roles #{keyword}}}
                 :enter        (fn [{:keys [request] :as ctx}]
                                 (if (not (set/subset? roles
                                                       (get-in request [:session :session/roles])))
                                   (throw-unauthorized "Current user lacks required roles" {:permitted-roles roles})
                                   ctx)
                                 ctx)}))})
(defn has-roles?
  "Given a role set and a request, returns true if the current user has all the roles."
  [roles req]
  (set/subset? roles
               (get-in req [:session :session/roles])))

(defn get-session
  "Fetch the user's session info from the request map"
  [req]
  (:session req))

(defn get-current-member
  "Fetch the user's member record from the request map"
  [req]
  (-> req :session :session/member))

(defn get-current-email
  "Fetch the logged in user's email address from the request map"
  [req]
  (-> req :session :session/email))

(defn admin? [roles]
  (contains? roles :admin))

(defn current-user-admin?
  [req]
  (admin? (get-in req [:session :session/roles])))

(defn- login-location [{:keys [uri query-string]}]
  (str "/login?next=" (util/url-encode (str uri "?" query-string))))

(defn- datastar-request? [request]
  (= "true" (get-in request [:headers "datastar-request"])))

(defn- authentication-required-response [request]
  (if (datastar-request? request)
    {:status 401
     :headers {"Cache-Control" "no-store"}
     :body ""}
    {:status 302
     :headers {"location" (login-location request)}
     :body ""}))

(def require-authenticated-user
  "Requires an authenticated application user.

  Document requests redirect to login. Datastar requests receive a same-origin
  401 so the application shell can initiate a top-level login navigation."
  {:name  ::require-authenticated-user
   :enter (fn [ctx]
            (let [req (:request ctx)]
              (cond
                (not (get-current-email req))
                (int/terminate ctx (authentication-required-response req))

                (get-current-member req)
                ctx

                :else
                (int/terminate ctx (identity-mismatch-response req)))))})

(def demo-auth-interceptor
  {:name  ::demo-auth-interceptor
   :enter #(-> % (assoc-in [:request :session] {:session/username "admin"
                                                :session/email    "admin@example.com"
                                                :session/groups   #{"/Mitglieder" "/admin"}
                                                :session/roles    #{:Mitglieder :admin}}))})
(defn dev-auth-interceptor [dev-session]
  {:name ::dev-auth-interceptor
   :enter #(-> % (assoc-in [:request :session] dev-session))})

(defn is-password-pwned? [password]
  (let [sha1sum ^String (secret-box/sha1-str password)
        r ^String (:body @(http/get (str "https://api.pwnedpasswords.com/range/" (.substring sha1sum 0 5))
                                    {:keepalive -1
                                     :headers   {"user-agent" "probematic: https://github.com/Ramblurr/probematic"}}))
        lines (when r (.split r "(?m)\n"))]
    (some #(-> (.toLowerCase ^String %)
               (.split ":")
               (first)
               (= (.substring sha1sum 5)))
          lines)))

(defn validate-password
  "Returns :password/valid if the password is valid.
  Other return options are :password/does-not-match :password/too-short, :password/commonly-used"
  [password password-confirm]
  (cond
    (not= password password-confirm) :password/does-not-match
    (< (count password) 8)           :password/too-short
    (is-password-pwned? password)    :password/commonly-used
    :else                            :password/valid))
