;; Copyright 2024 Casey Link
;; Copyright 2024 Nubank NA
;; Copyright 2014-2022 Cognitect, Inc.
;; Copyright 2013 Relevance, Inc.

;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;;
;; You must not remove this notice, or any other, from this software.
(ns app.interceptors.csrf
  "CSRF protection interceptor support"
  (:require
   [app.interceptors.options :as options]
   [crypto.random :as random]
   [crypto.equality :as crypto]
   [app.interceptors.util :as int]
   [malli.core :as m]))

;; This is a port of the Pedestal CSRF implementation which is itself a port of the ring-anti-forgery implementation.
;; https://github.com/pedestal/pedestal/blob/0.7.0/service/src/io/pedestal/http/csrf.clj
;;
;; The differences from the pedestal implementation are:
;;  * Use reitit style interceptors
;;
;; The differences from the ring implementation are:
;;  * Optionally include (and handle) a double-submit cookie
;;  * A function to get the current token (to embed in a head/meta tag, html form)
;;    given the context
;;  * CSRF token is in the `request` not in a dynamic var
;;  * Structured for Pedestal interceptor
;;  * No third-party dependency on an HTML templating library

(assert (m/schema? options/CSRFProtectionInterceptorOptions))

;; this is both the marker and the function (to use with the context)
(def anti-forgery-token :pink.interceptors.csrf/token)
(def anti-forgery-rotate-token :pink.interceptors.csrf/rotate-token?)
(def anti-forgery-token-str "__anti-forgery-token")

(defn existing-token [request]
  (get-in request [:session anti-forgery-token-str]))

(defn new-token []
  (random/base64 60))

(defn- session-token [request]
  (or (existing-token request)
      (new-token)))

(defn- assoc-session-token [response request token]
  (let [old-token (existing-token request)]
    (if (= old-token token)
      response
      (-> response
          (assoc :session (:session response (:session request)))
          (assoc-in [:session anti-forgery-token-str] token)))))

;; This must be run after the session token setting
(defn- assoc-double-submit-cookie [response token cookie-attrs]
  ;; (tap> [:assoc-cookie token cookie-attrs response])
  (if token
    (assoc-in response
              [:cookies anti-forgery-token-str]
              (merge cookie-attrs {:value token}))
    response))

(defn- form-params [request]
  (merge (:form-params request)
         (:multipart-params request)))

(defn- default-request-token [request]
  (let [params (form-params request)]
    ;; Or is used here to prevent multiple lookups from occuring
    ;; `get` is used to be nil-safe
    (or
     (get-in request [:cookies anti-forgery-token-str :value])
     (get params :__anti-forgery-token)
     (get params anti-forgery-token-str)
     (get-in request [:headers "x-csrf-token"]))))

(defn rotate-token
  "Rotate the csrf token, e.g. after login succeeds."
  [response]
  (assoc response anti-forgery-rotate-token true))

(defn- get-or-recreate-token [request response]
  (if (anti-forgery-rotate-token response)
    (new-token)
    (anti-forgery-token request)))

(defn- valid-request? [request read-token]
  (let [user-token   (read-token request)
        stored-token (session-token request)]
    (and user-token
         stored-token
         (crypto/eq? user-token stored-token))))

(defn- get-request? [{method :request-method}]
  (or (= :head method)
      (= :get method)))

(def denied-msg "<h1>Invalid CSRF token</h1>")

(defn ->csrf-protection-interceptor-opts [v]
  (options/valid! "csrf-protection-interceptor"
                  options/CSRFProtectionInterceptorOptions
                  (let [{:keys [error-handler error-response] :as opts} (options/coerce options/CSRFProtectionInterceptorOptions (or v {}))]
                    (if (and (nil? error-handler) (nil? error-response))
                      (assoc opts :error-response {:status  403
                                                   :headers {"Content-Type" "text/html"}
                                                   :body    denied-msg})
                      opts))))

(defn csrf-protection-interceptor
  "Interceptor that prevents Cross-Site Request Forgery (CSRF) attacks. Any `POST`/`PUT`/`PATCH`/`DELETE` request
  must contain a valid anti-forgery token, or an access-denied response is returned.

  Intended to be used with the `session` interceptor.

  The anti-forgery token can be accessed via `:pink.interceptors.csrf/token` within the request, which is bound to a random
  key unique to the current session. By default, the token is expected in either:
  - A form field named \"__anti-forgery-token\" or `:__anti-forgery-token`
  - The `X-CSRF-Token` header

  Options:
  - `:read-token` - Function that takes a request and returns an anti-forgery token, or `nil` if token doesn't
    exist. Default: checks form fields (including multipart) and headers mentioned above
  - `:error-response` - Response to return if token is incorrect or missing. Default: 403 response with HTML message
  - `:error-handler` - Handler function (passed the request) called if token is incorrect or missing. Should
    return a valid response
  - `:body-params` - Body-params parser map to use. Default: standard body-params parsers
  - `:cookie-attrs` - Map of attributes for the CSRF cookie. Default: `{:path \"/\" :same-site :lax :http-only true}`
  - `:cookie-token?` - If true (the default), the csrf token is also added as a cookie.


  Note: Only one of `:error-response` or `:error-handler` may be specified.

  Additional Features:
  - Token rotation can be triggered by adding `:pink.interceptors.csrf/rotate-token? true` to the response map. This is
    useful after events like successful login where you want to ensure a fresh token.


  On `:enter`:
  - Validates the presence and correctness of the anti-forgery token for unsafe methods
  - Sets the token in the request if not present
  - Optionally sets a double-submit cookie


  On `:leave`:
  - Updates session with token
  - Handles token rotation if requested
  - Sets double-submit cookie if enabled"
  ([] (csrf-protection-interceptor {}))
  ([opts]
   {:pre [(not (and (:error-response opts)
                    (:error-handler opts)))]}

   (let [opts           (->csrf-protection-interceptor-opts opts)
         token-reader   (:read-token opts default-request-token)
         cookie-token?  (:cookie-token? opts)
         cookie-attrs   (:cookie-attrs opts)
         error-response (:error-response opts)
         error-handler  (:error-handler opts (fn [context]
                                               (int/terminate context error-response)))]
     {:name           ::anti-forgery
      :options-schema options/CSRFProtectionInterceptorOptions
      :enter          (fn [context]
                        (let [{:keys [request]} context
                              token             (session-token request)]
                          #_(when (not (get-request? request))
                              (tap> [:enter-token request token :get? (get-request? request) :valid? (valid-request? request token-reader)]))
                          (if (and (not (get-request? request))
                                   (not (valid-request? request token-reader)))
                            (error-handler context)
                            (assoc-in context [:request anti-forgery-token] token))))
      :leave          (fn [context]
                        (let [{:keys [request response]} context
                              token                      (get-or-recreate-token request response)]
                          ;; (tap> [:cookie-okten? cookie-token? token cookie-attrs])
                          (assoc context
                                 :response (cond-> (assoc-session-token response request token)
                                             cookie-token? (assoc-double-submit-cookie token cookie-attrs)))))})))
