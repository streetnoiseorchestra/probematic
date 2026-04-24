(ns app.nexus
  (:require
   [medley.core :as m]
   [app.datastar :as datastar]
   [app.settings.actions]
   [clojure.walk :as walk]
   [com.yetanalytics.squuid :as sq]
   [datomic.api :as d]
   [nexus.core :as nexus]
   [nexus.strategies :as strategies]))

(def unique-attrs
  #{:user-account/id
    :user-account/email
    :user-account/username
    :team/team-id
    :team/name
    :section/name
    :travel.discount.type/discount-type-id
    :travel.discount.type/discount-type-name})

(defn system->conn [system]
  (or (:conn system)
      (:datomic-conn system)
      (get-in system [:datomic :conn])))

(defn prepare-tx-with-retractions
  "Transform transactions by connverting nil values to retractions.
   Returns a sequence of transactions ready for submission.
   Throws an exception if a retraction is needed but no entity identity is available."
  ([txes] (prepare-tx-with-retractions txes unique-attrs))
  ([txes unique-attrs]
   (mapcat
    (fn [tx]
      (if (map? tx)
        (let [nil-ks (map key (filter (comp nil? val) tx))
              db-id (:db/id tx)
              unique-attr (when-let [attr (some #(when (unique-attrs %) %)
                                                (keys tx))]
                            [attr (attr tx)])
              identity (or db-id unique-attr)]
          (if (seq nil-ks)
            (if identity
              (if db-id
                 ;; Has :db/id: cleaned map first, then retractions
                (concat [(apply dissoc tx nil-ks)]
                        (for [k nil-ks] [:db/retract identity k]))
                 ;; Has unique attribute: retractions first, then cleaned
                 ;; map
                (concat (for [k nil-ks] [:db/retract identity k])
                        [(apply dissoc tx nil-ks)]))
               ;; No identity available for retractions
              (throw
               (ex-info
                "Cannot create retractions for entity without :db/id or unique attribute"
                {:entity tx, :nil-keys nil-ks, :unique-attrs unique-attrs})))
            [tx]))
        [tx]))
    txes)))

(defn generated-value-replacer []
  (let [now             (java.time.Instant/now)
        named-squuid->v (atom {})]
    (fn [x]
      (cond
        (= :db/now x)
        now

        (= :db/gen-uuid x)
        (sq/generate-squuid)

        (and (vector? x)
             (= :db/gen-uuid (first x))
             (= 2 (count x)))
        (or (get @named-squuid->v x)
            (let [squuid (sq/generate-squuid)]
              (swap! named-squuid->v assoc x squuid)
              squuid))

        :else
        x))))

(defn batch-transactions
  "Given a list of transaction actions, batch them into a single transaction,
  adding retractions if a transaction action has the property
  :transact-w-nils?. Replaces generated value markers before preparing
  retractions:

  - :db/now becomes one Instant shared by the batch
  - :db/gen-uuid becomes a fresh squuid at each occurrence
  - [:db/gen-uuid k] becomes one stable squuid per k within the batch"
  ([transact-actions] (batch-transactions transact-actions unique-attrs))
  ([transact-actions unique-attrs]
   (let [generated-value-replacer (generated-value-replacer)]
     (->> (reduce (fn [acc [txs opts]]
                    (let [txs (walk/prewalk generated-value-replacer txs)
                          txs (if (:transact-w-nils? opts)
                                (prepare-tx-with-retractions txs unique-attrs)
                                txs)]
                      (into acc txs)))
                  []
                  transact-actions)
          (distinct)
          (vec)))))

(defn current-member-id [request]
  (get-in request [:session :session/member :member/member-id]))

(defn system->state
  ([system] (system->state system nil))
  ([system request]
   (cond-> {:now (java.util.Date.)}
     (system->conn system) (assoc :db (d/db (system->conn system)))
     (current-member-id request) (assoc :current-member-id (current-member-id request)))))

(defn ^:nexus/batch db-transact-fx
  [_ system transact-actions]
  (let [conn (system->conn system)]
    (assert conn "Nexus :db/transact requires a Datomic connection")
    @(d/transact conn (batch-transactions transact-actions))))

(defn request [ctx]
  (get-in ctx [:dispatch-data :request]))

(defn merge-signals-fx [ctx _system merge-signals]
  (datastar/respond-signals (request ctx) :merge merge-signals))

(defn remove-signals-fx [ctx _system remove-signals]
  (datastar/respond-signals (request ctx) :remove remove-signals))

(defn open-form-fx [ctx _system form-name form-id-key form-id-value]
  (datastar/open-form (request ctx) form-name form-id-key form-id-value))

(defn close-form-fx [ctx _system form-name form-id-key]
  (datastar/close-form (request ctx) form-name form-id-key))

(defn assoc-page-state-fx [ctx _system path value]
  (datastar/state-transact! (request ctx) #(assoc-in % path value)))

(defn merge-page-state-fx [ctx _system path value]
  (datastar/state-transact! (request ctx)
                            (fn [s]
                              (update-in s path (fnil m/deep-merge {}) value))))

(defn response? [x]
  (and (map? x) (contains? x :status)))

(defn result-response [dispatch-result]
  (some->> (:results dispatch-result)
           (keep :res)
           (filter response?)
           last))

(defn has-fail-fast-strategy? [nexus]
  (contains? (set (:nexus/interceptors nexus)) strategies/fail-fast))

(defn maybe-add-fail-fast [nexus]
  (if (has-fail-fast-strategy? nexus)
    nexus
    (update nexus :nexus/interceptors (fnil conj []) strategies/fail-fast)))

(defn add-response-actions [nexus]
  (-> nexus
      (assoc-in [:nexus/actions :http-response/ok]
                (fn [_ response-body]
                  [[:http/respond {:status 200
                                   :body   response-body}]]))
      (assoc-in [:nexus/actions :http-response/created]
                (fn ([_ response-body]
                     [[:http/respond {:status 201
                                      :body   response-body}]])
                  ([_ response-body location]
                   [[:http/respond (cond-> {:status 201
                                            :body   response-body}
                                     location (assoc-in [:headers "Location"] location))]])))
      (assoc-in [:nexus/actions :http-response/bad-request]
                (fn [_ response-body]
                  [[:http/respond {:status 400
                                   :body   response-body}]]))
      (assoc-in [:nexus/actions :http-response/unauthorized]
                (fn [_ response-body]
                  [[:http/respond {:status 401
                                   :body   response-body}]]))
      (assoc-in [:nexus/actions :http-response/forbidden]
                (fn [_ response-body]
                  [[:http/respond {:status 403
                                   :body   response-body}]]))
      (assoc-in [:nexus/actions :http-response/not-found]
                (fn [_ response-body]
                  [[:http/respond {:status 404
                                   :body   response-body}]]))
      (assoc-in [:nexus/actions :http-response/internal-server-error]
                (fn [_ response-body]
                  [[:http/respond {:status 500
                                   :body   response-body}]]))))

(defn prepare-nexus-template
  [nexus {:ring-nexus/keys [fail-fast? add-response-actions?]
          :or {fail-fast? true, add-response-actions? true}}]
  (cond-> nexus
    add-response-actions? add-response-actions
    fail-fast? maybe-add-fail-fast))

(defn add-respond-effect [nexus-template respond]
  (assoc-in nexus-template [:nexus/effects :http/respond]
            (fn [_ _ {:keys [body status headers] :as response-map}]
              (respond (cond-> response-map
                         (nil? body) (assoc :body "")
                         (nil? headers) (assoc :headers {})
                         (nil? status) (assoc :status 200))))))

(defn dispatch-actions
  [nexus-template system request actions on-error]
  (let [response_      (atom nil)
        prepared-nexus (add-respond-effect nexus-template #(reset! response_ %))
        result         (nexus/dispatch prepared-nexus system {:request request} actions)]
    (when-let [error (->> (:errors result) (keep :err) first)]
      (on-error error))
    (or @response_
        (result-response result)
        {:status 204
         :headers {}
         :body ""})))

(defn- request-state [system->state system request]
  (try
    (system->state system request)
    (catch clojure.lang.ArityException _
      (system->state system))))

(defn nexus-interceptor
  "Dispatch Nexus action vectors returned by a Reitit route handler.

  This is the small local subset of ring-nexus-middleware that Probematic needs:
  attach a Nexus state snapshot to the request, dispatch action vectors, and
  pass normal Ring responses through unchanged."
  ([nexus system]
   (nexus-interceptor nexus system nil))
  ([{:keys [nexus/system->state] :as nexus} system
    {:ring-nexus/keys [state-k on-error]
     :or {state-k :nexus/state, on-error #(throw %)}
     :as opts}]
   (let [nexus-template (prepare-nexus-template nexus opts)]
     {:name  ::nexus-interceptor
      :enter (fn [ctx]
               (update ctx :request assoc state-k
                       (request-state system->state system (:request ctx))))
      :leave (fn [{:keys [request response] :as ctx}]
               (if (vector? response)
                 (assoc ctx :response
                        (dispatch-actions nexus-template system request response on-error))
                 ctx))})))

(defn nexus []
  {:nexus/system->state system->state
   :nexus/effects       {:db/transact                         (with-meta db-transact-fx {:nexus/batch true})
                         :app.datastar/merge-signals          merge-signals-fx
                         :app.datastar/remove-signals         remove-signals-fx
                         :app.datastar/open-form              open-form-fx
                         :app.datastar/close-form             close-form-fx
                         :app.datastar/assoc-state            assoc-page-state-fx
                         :app.datastar/merge-state merge-page-state-fx}
   :nexus/actions app.settings.actions/actions})
