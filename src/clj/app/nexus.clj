(ns app.nexus
  (:require
   [app.datastar :as datastar]
   [app.gigs.actions]
   [app.gigs.effects :as gigs.effects]
   [app.insurance.actions]
   [app.members.actions]
   [app.members.effects :as members.effects]
   [app.settings.actions]
   [clojure.walk :as walk]
   [com.yetanalytics.squuid :as sq]
   [datomic.api :as d]
   [medley.core :as m]
   [nexus.core :as nexus]))

(def unique-attrs
  #{:user-account/id
    :user-account/email
    :user-account/username
    :gig/gig-id
    :team/team-id
    :team/name
    :section/name
    :travel.discount.type/discount-type-id
    :travel.discount.type/discount-type-name})

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
  (let [now             (java.util.Date.)
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

(defn current-user-roles [request]
  (get-in request [:session :session/roles] #{}))

(defn- request-page-state [request]
  (when-let [tab-id (or (-> request :body-params :tab-id)
                        (get-in request [:parameters :body :tab-id]))]
    (get @datastar/!page-state tab-id {})))

(defn system->state
  [{:keys [system request]}]
  (cond-> {:now                (java.util.Date.)
           :tr                 (:tr request)
           :db                 (d/db (-> system :datomic :conn))
           :page-state         (request-page-state request)
           :current-user-roles (current-user-roles request)}
    (current-member-id request) (assoc :current-member-id (current-member-id request))))

(defn- on-success-actions [transact-actions]
  (mapcat (comp :on-success second) transact-actions))

(defn ^:nexus/batch db-transact-fx
  [{:keys [dispatch]} {:keys [system]} transact-actions]
  (let [conn    (-> system :datomic :conn)
        _       (assert conn "Nexus :db/transact requires a Datomic connection")
        result  @(d/transact conn (batch-transactions transact-actions))
        actions (vec (on-success-actions transact-actions))]
    (when (seq actions)
      (dispatch actions {:tx-result result}))
    result))

(defn merge-signals-fx [_ {req :request} merge-signals]
  (datastar/respond-signals req :merge merge-signals))

(defn remove-signals-fx [_ {req :request} remove-signals]
  (datastar/respond-signals req :remove remove-signals))

(defn open-form-fx [_ {req :request} form-name form-id-key form-id-value]
  (datastar/open-form req form-name form-id-key form-id-value))

(defn close-form-fx [_ {req :request} form-name form-id-key]
  (datastar/close-form req form-name form-id-key))

(defn assoc-page-state-fx [_ {req :request} path value]
  (datastar/state-transact! req #(assoc-in % path value)))

(defn merge-page-state-fx [_ {req :request} path value]
  (datastar/state-transact! req
                            (fn [s]
                              (update-in s path (fnil m/deep-merge {}) value))))

(defn redirect-fx [_ {req :request} url]
  (datastar/redirect req url))

(defn send-user-invitation-fx [_ {req :request} member-id]
  (members.effects/send-user-invitation! req member-id))

(defn resend-invitation-fx [_ {req :request} invite-code]
  (members.effects/resend-invitation! req invite-code))

(defn delete-invitation-fx [_ {req :request} invite-code]
  (members.effects/delete-invitation! req invite-code))

(defn update-keycloak-meta-fx [_ {req :request} member-id]
  (members.effects/update-keycloak-meta! req member-id))

(defn set-keycloak-account-enabled-fx [_ {req :request} member-id enabled?]
  (members.effects/set-keycloak-account-enabled! req member-id enabled?))

(defn response? [x]
  (and (map? x) (contains? x :status)))

(defn result-response [dispatch-result]
  (some->> (:results dispatch-result)
           (keep :res)
           (filter response?)
           last))

(defn dispatch-actions
  [nexus system {:keys [request response]} on-error]
  (let [result (nexus/dispatch nexus {:system system :request request} {:request request} response)]
    (when-let [error (->> (:errors result) (keep :err) first)]
      (on-error error))
    (or
     (result-response result)
     {:status  204
      :headers {}
      :body    ""})))

(defn nexus-interceptor
  "Dispatch Nexus action vectors returned by a Reitit route handler.
  attach a Nexus state snapshot to the request, dispatch action vectors, and
  pass normal Ring responses through unchanged."
  ([nexus system]
   (nexus-interceptor nexus system nil))
  ([nexus system {:keys [on-error] :or {on-error #(throw %)}}]
   {:name  ::nexus-interceptor
    :enter (fn [ctx]
             ctx
             #_(update ctx :request assoc :nexus/state
                       (system->state system (:request ctx))))
    :leave (fn [{:keys [response] :as ctx}]
             (if (vector? response)
               (assoc ctx :response
                      (dispatch-actions nexus system ctx on-error))
               ctx))}))

(defn nexus []
  {:nexus/system->state system->state
   :nexus/effects       {:db/transact                         (with-meta db-transact-fx {:nexus/batch true})
                         :app.datastar/merge-signals          merge-signals-fx
                         :app.datastar/remove-signals         remove-signals-fx
                         :app.datastar/open-form              open-form-fx
                         :app.datastar/close-form             close-form-fx
                         :app.datastar/assoc-state            assoc-page-state-fx
                         :app.datastar/merge-state            merge-page-state-fx
                         :app.datastar/redirect               redirect-fx
                         :app.gigs/trigger-gig-details-edited gigs.effects/trigger-gig-details-edited-fx
                         :app.gigs/trigger-gig-created        gigs.effects/trigger-gig-created-fx
                         :app.gigs/trigger-gig-deleted        gigs.effects/trigger-gig-deleted-fx
                         :app.gigs/trigger-gig-edited         gigs.effects/trigger-gig-edited-fx
                         :app.gigs/recalc-play-stats          gigs.effects/recalc-play-stats-fx
                         :app.gigs/send-reminder-to-all       gigs.effects/send-reminder-to-all-fx
                         :app.members/send-user-invitation          send-user-invitation-fx
                         :app.members/update-keycloak-meta          update-keycloak-meta-fx
                         :app.members/set-keycloak-account-enabled set-keycloak-account-enabled-fx
                         :app.members.index/resend-invitation       resend-invitation-fx
                         :app.members.index/delete-invitation delete-invitation-fx}
   :nexus/actions       (merge app.settings.actions/actions
                               app.members.actions/actions
                               app.gigs.actions/actions
                               app.insurance.actions/actions)})
