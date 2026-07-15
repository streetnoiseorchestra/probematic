(ns app.interceptors.options
  (:require
   [malli.transform :as mt]
   [ring.middleware.cookies :as ring-cookies]
   [malli.core :as m]
   [clojure.string :as str]
   [malli.error :as me]
   [malli.util :as mu]
   [ring.middleware.session.store])
  (:import  ring.middleware.session.store.SessionStore))

(defn coerce [schema v]
  (m/decode schema v (mt/default-value-transformer {::mt/add-optional-keys true})))

(defn valid! [int-name schema v]
  (if (m/validate schema v)
    v
    (throw  (ex-info  (str "Invalid options passed to interceptor " int-name)
                      {:schema  schema
                       :value   v
                       :explain (me/humanize (m/explain schema v))}))))

(def uri-path?
  "Predicate for validating URI paths.
   Valid: `/`, `/foo`, `/foo/bar`
   Invalid: ``, `foo`, `foo/bar`"
  (fn [x]
    (and (string? x) (str/starts-with? x "/"))))

(def NonBlankString
  [:and
   [:string {:min 1}]
   [:fn {:error/message "non-blank string"}
    (complement str/blank?)]])

(def UriPath
  (m/-simple-schema
   {:type            :uri-path
    :pred            uri-path?
    :type-properties {:error/message "Must be a valid URI path starting with /"
                      :decode/string str
                      :encode/string str}}))
(assert (m/into-schema? UriPath))

(def CookieInterval
  [:or
   [:fn
    #(satisfies? ring-cookies/CookieInterval %)]
   [:int]])

(def CookieDateTime
  [:or
   [:fn
    #(satisfies? ring-cookies/CookieDateTime %)]
   [:string]])

(def CookieAttrs
  (m/schema
   [:map
    [:value NonBlankString]
    [:path {:optional true} UriPath]
    [:domain {:optional true} NonBlankString]
    [:max-age {:optional true} pos-int?]
    [:expires {:optional true} NonBlankString] ;; RFC 7231 date format
    [:secure {:optional true} boolean?]
    [:http-only {:optional true} boolean?]
    [:same-site {:optional true} [:enum :strict :lax]]]))

(def CookieAttrsOption (mu/dissoc CookieAttrs :value))

(def PrettyExceptionsPageOptions
  (m/schema
   [:map
    [:app-namespaces
     {:optional true
      :doc      "Controls which namespaces show up on the pretty errors page as \"application\" frames. All frames from namespaces prefixed with the names in the list will be marked as application frames."}
     [:sequential {:error/message "should be a sequence of app namespaces symbols"} :symbol]]
    [:skip? {:doc      "Allows for skipping the pretty exceptions interceptor for a request. Should be a predicate function that takes the request as its argument."
             :optional true}
     [:fn {:error/message "should be a predicate function"} fn?]]]))

(def ErrorInterceptorOptions
  (m/schema
   [:map
    [:debug-errors? {:doc      "When true uses pink.interceptors.errors functionality for debugging application failures."
                     :optional true
                     :default  false} :boolean]
    [:error-handlers {:doc      "Map of pattern -> handler for exception matching. Kept broad because the keys are heterogeneous matcher data."
                      :optional true} :map]
    [:pretty-exceptions-opts {:doc     "Options for the pretty exceptiosn page handler"
                              :default {}} PrettyExceptionsPageOptions]]))

(def ExceptionBackstopInterceptorOptions
  (m/schema
   [:map
    [:report {:optional true
              :doc      "A side-effecting function that takes [exception request] for logging/reporting. Defaults to a function that uses tap> to report the error. The return value is discarded."}
     [:fn fn?]]]))

(def HTMLViewInterceptorOptions
  (m/schema
   [:map [:render-fn {:doc "An arity 1 function free of side-effects that produces an HTML string"}
          [:function [:=> [:cat :any] :string]]]]))

(def SessionStoreInstance
  [:fn {:error/message "implementation of ring.middleware.session.store.SesionStore"}
   #(instance? SessionStore %)])

(def SessionInterceptorOptions
  (m/schema
   [:map
    [:store {:optional true :doc "Implementation of SessionStore protocol for session storage. Defaults to in-memory storage."}
     SessionStoreInstance]
    [:root {:default "/" :doc "Root path of the session. Any path above this will not see the session. Sets cookie's path attribute."} UriPath]
    [:cookie-name {:default "ring-session" :doc "Name of the cookie holding the session key."} NonBlankString]
    [:cookie-attrs {:default {:same-site :lax :http-only true}
                    :doc     "Map of attributes for the session cookie."} CookieAttrsOption]
    [:set-cookies? {:default true :doc "If true, automatically includes cookie handling"} :boolean]]))
