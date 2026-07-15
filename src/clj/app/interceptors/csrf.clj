(ns app.interceptors.csrf
  "Fail-closed Fetch Metadata CSRF protection for browser requests."
  (:require
   [app.interceptors.util :as int]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as μ]))

(def ^:private safe-methods
  #{:get :head :options})

(def ^:private security-event-sites
  #{"same-site" "cross-site"})

(defn- fetch-site [request]
  (get-in request [:headers "sec-fetch-site"]))

(defn- safe-request? [request]
  (contains? safe-methods (:request-method request)))

(defn security-event?
  "Returns true when an unsafe `request` explicitly reports a sibling-site or
  cross-site initiator.

  Missing, malformed, unknown, and `none` values still fail closed, but rely on
  the ordinary HTTP request log instead of producing a second security event."
  [request]
  (and (not (safe-request? request))
       (contains? security-event-sites (fetch-site request))))

(defn request-policy
  "Classifies a Ring `request` against the browser Fetch Metadata boundary.

  `GET`, `HEAD`, and `OPTIONS` are always allowed. Every other method requires
  an exact `Sec-Fetch-Site: same-origin` header; missing or unrecognized values
  fail closed."
  [request]
  (let [allowed? (or (safe-request? request)
                     (= "same-origin" (fetch-site request)))]
    {:allowed? allowed?
     :security-event? (and (not allowed?)
                           (security-event? request))}))

(defn- header-name [header]
  (if (keyword? header)
    (name header)
    (str header)))

(defn- header-key? [header expected]
  (= (str/lower-case (header-name header))
     (str/lower-case expected)))

(defn- response-header [headers expected]
  (some (fn [[header value]]
          (when (header-key? header expected)
            value))
        headers))

(defn- without-header [headers expected]
  (into {}
        (remove (fn [[header _value]]
                  (header-key? header expected)))
        headers))

(defn- distinct-header-fields [fields]
  (reduce (fn [result field]
            (if (some #(= (str/lower-case %)
                          (str/lower-case field))
                      result)
              result
              (conj result field)))
          []
          fields))

(defn- add-fetch-site-vary [headers]
  (let [headers (or headers {})
        vary    (response-header headers "Vary")
        fields  (distinct-header-fields
                 (if (str/blank? (str vary))
                   []
                   (mapv str/trim (str/split (str vary) #","))))
        fields  (cond-> fields
                  (not-any? #(header-key? % "Sec-Fetch-Site") fields)
                  (conj "Sec-Fetch-Site"))]
    (assoc (without-header headers "Vary")
           "Vary" (str/join ", " fields))))

(defn- vary-fetch-site [response]
  (update response :headers add-fetch-site-vary))

(defn- rejection-response []
  (vary-fetch-site
   {:status 403
    :headers {"Cache-Control" "no-store"}
    :body ""}))

(defn- log-security-event [{:keys [human-id request-method uri] :as request}]
  (μ/log ::blocked-request
         :level :info
         :request-method request-method
         :uri uri
         :sec-fetch-site (fetch-site request)
         :human-id human-id))

(def fetch-metadata-interceptor
  "Rejects unsafe requests unless the browser reports an exact same-origin
  initiator, before request parsing or session loading can occur."
  {:name ::fetch-metadata
   :enter
   (fn [{:keys [request] :as context}]
     (let [{:keys [allowed? security-event?]} (request-policy request)]
       (if allowed?
         context
         (do
           (when security-event?
             (log-security-event request))
           (int/terminate context (rejection-response))))))
   :leave
   (fn [{:keys [request response] :as context}]
     (if (and response (not (safe-request? request)))
       (assoc context :response (vary-fetch-site response))
       context))})
