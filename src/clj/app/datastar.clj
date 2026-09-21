;; Portions of this file are based on hyperlith code from @Anders
;; MIT License
;; Copyright (c) 2025 Anders Murphy
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:

;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.

;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.
(ns app.datastar
  (:require
   [app.brotli :as br]
   [app.game-loop :as game]
   [app.urls :as urls]
   [buddy.core.codecs :as codecs]
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [jsonista.core :as j]
   [starfederation.datastar.clojure.adapter.common :as d*com]
   [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.expressions :refer [->js ->js-str]]
   [tick.core :as t])
  (:import
   [java.util.concurrent ConcurrentHashMap]))

(defn ->signals [m]
  (j/write-value-as-string m))

;; Pending interactions: submission, admission, and committed-frame acknowledgment.

(defn interaction-key
  "Returns a stable key from a policy's scope and UUID target fields."
  [{:keys [scope targets]} params]
  (str (name scope) ":"
       (str/join ":" (map (fn [k]
                            (or (some-> (get params k) str parse-uuid)
                                (throw (ex-info "Missing interaction target" {:status 400 :target k}))))
                          targets))))

(defn request-interaction
  "Validates optional interaction metadata against the registered policy and connection."
  [policies action signals token]
  (when-let [{:keys [key revision conn-id]} (:interaction signals)]
    (let [policy (get policies action)]
      (when-not (and policy
                     (integer? revision) (<= 1 revision 9007199254740991)
                     (= key (interaction-key policy (get signals (:signals policy)))))
        (throw (ex-info "Invalid interaction" {:status 400})))
      (when-not (= conn-id (str token))
        (throw (ex-info "Replaced interaction connection" {:status 409})))
      {:key key :revision revision :replace? (:replace? policy)})))

(defn interaction-decision
  "Returns :execute or a terminal outcome for a connection-scoped submission.

  Retain only the latest revision/outcome per interaction key. Older setters
  are superseded. Older non-setters are explicitly rejected because their
  history is unknown; they must not execute again or silently succeed.
  Call and record the execution outcome on the single writer."
  [ledger {:keys [key revision replace?]}]
  (let [latest (get ledger key)]
    (cond
      (or (nil? latest) (> revision (:revision latest))) :execute
      (= revision (:revision latest)) (:outcome latest)
      replace? :superseded
      :else :rejected)))

(defn pending-expr [key]
  ;; `in` tracks sparse key changes through Datastar's Proxy; Object.hasOwn does not.
  (->js (js* "(~{} in ~{})" ~key $_pending)))

(defn blocked-expr [{:keys [block group]} key]
  (let [busy? (case block
                :self (pending-expr key)
                :group (->js ($_busy ~(name group)))
                false)]
    (->js (or $_interrupted (not $_conn-id) ~busy?))))

(defn submit-js
  "Builds a Datastar submission with frozen arguments and sparse pending state.

  `values` maps argument keys to trusted JavaScript expressions, not user input."
  [url {:keys [signals group] :as policy} params values]
  (let [key       (interaction-key policy params)
        arguments (merge (update-vals params #(if (uuid? %) (str %) %))
                         (update-vals values #(->js (expr/raw ~%))))]
    (->js-str
     (when (not ~(blocked-expr policy key))
       (set! $_failed false)
       (set! $_next (+ $_next 1))
       (let [revision $_next
             args     ~arguments
             payload  {"tab-id" $tab-id :interaction {:key ~key :revision revision :conn-id $_conn-id}}]
         (aset $_pending ~key {:revision revision :group ~(name group) :args args})
         (aset payload ~(name signals) args)
         (@post ~url {:requestCancellation "disabled" :retry "never" :payload payload}))))))

(defn page-attrs
  "Initializes private bookkeeping once and computes each exclusion group once."
  [policies]
  (merge
   {:data-signals__ifmissing (->signals
                              {:_next          0
                               :_pending       {}
                               :_conn-id       ""
                               :_frame-conn-id ""
                               :_stream-ended  false
                               :_acks          []
                               :_interrupted   false
                               :_failed        false})
    :data-effect
    (->js-str
     (when (and $_conn-id (!== $_conn-id $_frame-conn-id) (> (.-length (Object.keys $_pending)) 0))
       (set! $_interrupted true)
       (set! $_pending {}))
     (set! $_conn-id $_frame-conn-id)
     (when $_conn-id (set! $_stream-ended false))
     (.forEach $_acks
               (fn [a]
                 (when (and (Object.hasOwn $_pending a.key)
                            (=== (.-revision (aget $_pending a.key)) a.revision))
                   (js-delete $_pending a.key)
                   (when (.includes ["failed" "rejected"] a.outcome)
                     (set! $_failed true))))))
    :data-on:datastar-fetch
    (->js-str
     (when (and (=== evt.detail.el.id "long-lived-sse")
                (.includes ["finished" "error" "retrying" "retries-failed"] evt.detail.type))
       (when (> (.-length (Object.keys $_pending)) 0)
         (set! $_interrupted true)
         (set! $_pending {}))
       (set! $_frame-conn-id "")
       (set! $_stream-ended true))
     (when (and (evt.detail.el.hasAttribute "data-interaction")
                (.includes ["error" "retrying" "retries-failed"] evt.detail.type)
                (> (.-length (Object.keys $_pending)) 0))
       (set! $_interrupted true)
       (set! $_pending {})))}
   (into {} (for [group (distinct (keep #(when (= :group (:block %)) (:group %)) (vals policies)))]
              [(keyword (str "data-computed:_busy" (name group)))
               ;; Keep the callback expression-only: Datastar's computed parser
               ;; splits on semicolons inside function bodies.
               (str "Object.values($_pending).some(p => p.group === "
                    (j/write-value-as-string (name group)) ")")]))))

#_(def patch-elements! d*/patch-elements!)
(def patch-signals! d*/patch-signals!)

(defn- remove-signals-patch [paths]
  (reduce (fn [acc path]
            (assoc-in acc (mapv keyword (str/split path #"\.")) nil))
          {}
          paths))

(defn digest
  "Digest function based on Clojure's hash."
  [data]
  (codecs/bytes->b64-str (.getBytes (str (hash data)))))

(def !page-state (atom {}))

(defn request-tab-id
  "Returns the browser tab signal from JSON or multipart request parameters."
  [req]
  (or (-> req :body-params :tab-id)
      (get-in req [:parameters :body :tab-id])
      (get-in req [:parameters :multipart :tab-id])))

(defn state-transact!
  "Updates the transient page state addressed by the request's tab signal."
  [req f]
  (if-let [tab-id (request-tab-id req)]
    (swap! !page-state
           (fn [pages]
             (let [state (get pages tab-id)]
               (if (and (::state-token req)
                        (not= (::state-token req) (::state-token state)))
                 pages
                 (assoc pages tab-id (-> state (f) (assoc ::modified (t/instant))))))))
    (throw (ex-info "No tab-id in request" {}))))

(defn remove-tab-state! [tab-id]
  (swap! !page-state dissoc tab-id))

(def brotli-write-profile
  {d*com/wrap-output-stream (fn [os] (-> os br/->brotli-os d*com/->os-writer))
   d*com/content-encoding   "br"
   d*com/write!             (d*com/->write-with-temp-buffer!)})

(def ^:private sse-event-kinds
  #{:app.datastar.sse/execute-script
    :app.datastar.sse/merge-signals
    :app.datastar.sse/patch-elements
    :app.datastar.sse/redirect
    :app.datastar.sse/remove-signals})

(defn- validate-sse-event! [event]
  (let [[kind _payload opts] event]
    (when-not (and (vector? event)
                   (<= 2 (count event) 3)
                   (contains? sse-event-kinds kind)
                   (or (nil? opts) (map? opts)))
      (throw (ex-info "Unknown Datastar SSE event" {:event event}))))
  event)

(defn- validate-sse-events! [events]
  (when-not (vector? events)
    (throw (ex-info "Datastar SSE events must be a vector" {:events events})))
  (doseq [event events]
    (validate-sse-event! event))
  events)

(defn- emit-sse-event! [sse-gen [kind payload opts]]
  (let [opts (or opts {})]
    (case kind
      :app.datastar.sse/merge-signals
      (d*/patch-signals! sse-gen (->signals payload) opts)

      :app.datastar.sse/remove-signals
      (d*/patch-signals! sse-gen (->signals (remove-signals-patch payload)) opts)

      :app.datastar.sse/patch-elements
      (d*/patch-elements! sse-gen payload opts)

      :app.datastar.sse/execute-script
      (d*/execute-script! sse-gen payload opts)

      :app.datastar.sse/redirect
      (d*/redirect! sse-gen payload opts))))

(defn sse-response-plan
  "Creates a validated ordered SSE response plan for the Nexus HTTP boundary."
  [events]
  (validate-sse-events! events)
  {::sse-events events})

(defn sse-response-plan?
  "Returns whether `x` is an ordered SSE response plan."
  [x]
  (and (map? x) (contains? x ::sse-events)))

(defn sse-response-events
  "Returns the ordered events from `response-plan`."
  [response-plan]
  (::sse-events response-plan))

(defn assoc-connection-token
  "Returns `request` with its connection token if the authenticated member owns
  the active connection. Otherwise returns nil."
  [runtime request]
  (let [client    (get @(:clients runtime) (request-tab-id request))
        member-id (get-in request [:app/session :session/member :member/member-id])]
    (when (and member-id client (= member-id (:member-id client)))
      (assoc request ::state-token (:token client) ::ledger (:interactions client)))))

(defn- update-frame-client! [runtime tab-id token f]
  (swap! (:clients runtime)
         (fn [clients]
           (if (= token (get-in clients [tab-id :token]))
             (update clients tab-id f)
             clients))))

(defn mark-action!
  "Records the current action on its original connection.

  Durable completion feedback can use this id to avoid redirecting a tab after
  a later action. Call on the writer before evaluating the action."
  [runtime request action-id]
  (update-frame-client! runtime (request-tab-id request) (::state-token request)
                        #(assoc % :action-id action-id)))

(defn queue-sse-events!
  "Validates `events` and appends them to the originating connection's pending SSE
  messages. A render worker sends them in order during a later render phase;
  this function does not send them or wait for delivery.

  Queued actions use this to return feedback through the existing SSE connection
  after `/act` has returned. The connection is identified by the tab ID and token
  in `request`. Discards events if that connection has closed or been replaced.
  Throws for invalid events, even if the connection no longer exists."
  [runtime request events]
  (validate-sse-events! events)
  (when (seq events)
    (update-frame-client!
     runtime (request-tab-id request) (::state-token request)
     (fn [client]
       (let [revision (inc (:revision client))]
         (-> client
             (assoc :revision revision)
             (update :events conj [revision events])))))))

(defn render-in-frame!
  "Runs `render!` on a render worker with a captured frame and returns its result.

  Initial page GET requests use this to produce full HTML from the committed
  frame database. The calling HTTP request thread waits up to five seconds.
  Action requests (`POST /act`) do not use this function or wait for execution.

  Returns a Ring 503 response if `runtime` is stopped or the wait times out.
  Rethrows exceptions from `render!` on the calling thread. Removes the temporary
  render callback on exit; a timeout does not cancel rendering already in progress."
  [runtime render!]
  (if (or (nil? runtime) @(:stopped? runtime))
    {:status 503 :headers {} :body ""}
    (let [id     (Object.)
          result (promise)
          conns  ^ConcurrentHashMap (::game/conns runtime)]
      (try
        (.put conns id (fn [frame]
                         (when-not (realized? result)
                           (deliver result (try (render! frame) (catch Exception e e))))))
        (let [value (deref result 5000 ::timeout)]
          (cond
            (= ::timeout value) {:status 503 :headers {} :body ""}
            (instance? Exception value) (throw value)
            :else value))
        (finally (.remove conns id))))))

(defn- frame-connection-render [runtime request tab-id token sse render-fn]
  (let [render-html!
        (game/render-callback
         (fn [frame]
           (render-fn (assoc request :request-method :get :db (:db frame)
                             :page-state (get (:page-state frame) tab-id {}))))
         #(d*/patch-elements! sse % {d*/id (digest %)}))]
    (fn [frame]
      (let [client (get (:clients frame) tab-id)]
        (when (= token (:token client))
          ;; HTML and compression run here; sending does not wait for delivery.
          (when (render-html! frame)
            (loop [[[revision events] & remaining] (:events client)]
              (when revision
                (when (every? #(emit-sse-event! sse %) events)
                  (update-frame-client!
                   runtime tab-id token
                   #(update % :events (fn [pending] (filterv (fn [[id]] (> id revision)) pending))))
                  (recur remaining))))))))))

(defn frame-render-handler [runtime render-fn]
  (fn [request]
    (if (or (nil? runtime) @(:stopped? runtime))
      {:status 503 :headers {} :body ""}
      (let [tab-id    (or (request-tab-id request) (str (random-uuid)))
            token     (random-uuid)
            member-id (get-in request [:app/session :session/member :member/member-id])
            clients   (:clients runtime)
            conns     ^ConcurrentHashMap (::game/conns runtime)]
        (hk-gen/->sse-response
         request
         {:headers             {"X-Accel-Buffering" "no" "Cache-Control" "no-cache"}
          hk-gen/write-profile brotli-write-profile
          hk-gen/on-open
          (fn [sse]
            (locking clients
              (let [old (get @clients tab-id)]
                (if (or @(:stopped? runtime) (and old (not= member-id (:member-id old))))
                  (d*/close-sse! sse)
                  (do
                    (when old ((:close! old)))
                    (swap! !page-state assoc tab-id {::created (t/instant) ::state-token token})
                    (swap! clients assoc tab-id
                           {:token        token                                                                                  :member-id member-id :revision 0
                            :interactions (atom {})
                            :events       [[0 [[:app.datastar.sse/merge-signals {:tab-id tab-id :_frame-conn-id (str token)}]]]]
                            :close!       #(d*/close-sse! sse)})
                    (.put conns tab-id (frame-connection-render runtime request tab-id token sse render-fn)))))))
          hk-gen/on-close
          (fn [_ _]
            (locking clients
              (when (= token (get-in @clients [tab-id :token]))
                (.remove conns tab-id)
                (swap! clients dissoc tab-id)
                (remove-tab-state! tab-id))))})))))

(def camelCaseMapper
  (j/object-mapper
   {:encode-key-fn csk/->camelCaseString
    :decode-key-fn csk/->kebab-case-keyword}))

(defn datastar-params-interceptor []
  {:name  ::datastar-params-interceptor
   :enter (fn [ctx]
            (if (= :get (-> ctx :request :request-method))
              (assoc-in ctx [:request :datastar-params]
                        (when-let [dps (get-in ctx [:request :params "datastar"])]
                          (j/read-value dps camelCaseMapper)))
              ctx))})

;; ------------------------------------------------------------
;; Helpers

(def ActionOptsSchema
  [:map {:closed true}
   [:content-type {:optional true :default :json}
    [:enum :json :form]]
   [:include-local {:optional true :default false}
    :boolean]
   [:selector {:optional true :default nil}
    [:maybe :string]]
   [:headers {:optional true}
    [:maybe [:map-of :string :any]]]
   [:open-when-hidden {:optional true :default false}
    :boolean]
   [:retry-interval {:optional true :default 1000}
    :int]
   [:retry-scaler {:optional true :default 2}
    :double]
   [:retry-max-wait-ms {:optional true :default 30000}
    :int]
   [:retry-max-count {:optional true :default 10}
    :int]
   [:abort {:optional true}
    [:maybe :any]]])

(def Actions [:enum :get :put :patch :post :delete])

(>defn action
  ([method url]
   [:keyword :string => :string]
   (action method url nil))
  ([method url opts]
   [:keyword :string [:maybe ActionOptsSchema] => :string]
   (if opts
     (str "@" (name method) "('" url "', " (j/write-value-as-string opts camelCaseMapper) ")")
     (str "@" (name method) "('" url "')"))))

#_(>defn expr_DEPRECATED [& stmts]
    [[:* [:maybe :string]] => :string]
    (str/join "; " (filter identity stmts)))

#_(>defn assign [signal-name value]
    [:string :any => :string]
    (format "$%s=%s" signal-name (j/write-value-as-string value)))

(defn dispatch
  ([req cmd]
   (action :post (urls/url-for req cmd)))
  ([req cmd opts]
   (action :post (urls/url-for req cmd) opts)))

(defn action-query-params [cmd]
  (assert (qualified-keyword? cmd) (str "Actions must be qualified keywords: " cmd))
  {"ns" (namespace cmd)
   "kw" (name cmd)})

(defn action-key [query-params]
  (let [action-ns (or (clojure.core/get query-params :ns) (clojure.core/get query-params "ns"))
        action-kw (or (clojure.core/get query-params :kw) (clojure.core/get query-params "kw"))]
    (when (and (seq action-ns) (seq action-kw))
      (keyword action-ns action-kw))))

(defn act
  ([req cmd]
   (act req cmd nil))
  ([req cmd _opts]
   (urls/url-for req :app.routes.datastar/act nil (action-query-params cmd))
   #_(action :post
             (urls/url-for req :app.routes.datastar/act nil (action-query-params cmd))
             opts)))
