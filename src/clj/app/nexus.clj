(ns app.nexus
  (:require
   [app.account.actions]
   [app.account.effects :as account.effects]
   [app.datastar :as datastar]
   [app.file-browser.actions]
   [app.gigs.actions]
   [app.gigs.effects :as gigs.effects]
   [app.insurance.actions]
   [app.insurance.effects :as insurance.effects]
   [app.members.actions]
   [app.members.effects :as members.effects]
   [app.probeplan.actions]
   [app.poll.actions]
   [app.poll.effects :as poll.effects]
   [app.settings.actions]
   [app.songs.actions]
   [app.songs.effects :as songs.effects]
   [clojure.walk :as walk]
   [com.yetanalytics.squuid :as sq]
   [datomic.api :as d]
   [medley.core :as m]
   [nexus.core :as nexus]
   [nexus.strategies :as strategies]))

(def unique-attrs
  #{:user-account/id
    :user-account/email
    :user-account/username
    :gig/gig-id
    :song/song-id
    :team/team-id
    :team/name
    :section/name
    :travel.discount.type/discount-type-id
    :travel.discount.type/discount-type-name
    :poll/poll-id
    :poll.option/poll-option-id
    :poll.vote/poll-vote-id})

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
  (when-let [tab-id (datastar/request-tab-id request)]
    (get @datastar/!page-state tab-id {})))

(defn system->state
  [{:keys [system request]}]
  (cond-> {:now                (java.util.Date.)
           :tr                 (:tr request)
           :db                 (d/db (-> system :datomic :conn))
           :page-state         (request-page-state request)
           :current-user-roles (current-user-roles request)}
    (:env system) (assoc :env (:env system))
    (current-member-id request) (assoc :current-member-id (current-member-id request))))

(defn- on-success-actions [transact-actions]
  (mapcat (comp :on-success second) transact-actions))

(defn response? [x]
  (and (map? x) (contains? x :status)))

(defn- response-result? [x]
  (or (response? x) (datastar/sse-response-plan? x)))

(defn- result-responses [dispatch-result]
  (letfn [(collect-response [result]
            (cond
              (response-result? result) [result]
              (and (map? result) (sequential? (:results result)))
              (mapcat (comp collect-response :res) (:results result))
              :else []))]
    (mapcat (comp collect-response :res) (:results dispatch-result))))

(defn- single-response [responses]
  (when (> (count responses) 1)
    (throw (ex-info
            "One Nexus dispatch may contain at most one response owner"
            {:response-count (count responses)})))
  (first responses))

(defn result-response [dispatch-result]
  (some-> dispatch-result result-responses single-response))

(defn ^:nexus/batch db-transact-fx
  [{:keys [dispatch]} {:keys [system]} transact-actions]
  (let [conn (-> system :datomic :conn)
        _    (assert conn "Nexus :db/transact requires a Datomic connection")]
    (try
      (let [result          @(d/transact conn (batch-transactions transact-actions))
            actions         (vec (on-success-actions transact-actions))
            dispatch-result (when (seq actions)
                              (dispatch actions {:tx-result result}))]
        (or (result-response dispatch-result) result))
      (catch Exception e
        (let [causes     (take-while some? (iterate ex-cause e))
              tx-error   (or (some #(when (:app/error-code (ex-data %)) %) causes)
                             e)
              error-code (:app/error-code (ex-data tx-error))
              actions    (->> transact-actions
                              (mapcat #(get-in (second %)
                                               [:on-error error-code]))
                              vec)]
          (if (seq actions)
            (result-response (dispatch actions {:tx-error tx-error}))
            (throw e)))))))

(defn assoc-page-state-fx [_ {req :request} path value]
  (datastar/state-transact! req #(assoc-in % path value)))

(defn merge-page-state-fx [_ {req :request} path value]
  (datastar/state-transact! req
                            (fn [s]
                              (update-in s path (fnil m/deep-merge {}) value))))

(defn- respond-sse-fx [_ _ responses]
  (let [response-count (count responses)]
    (when-not (= 1 response-count)
      (throw (ex-info
              "One Nexus dispatch may contain at most one response owner"
              {:response-count response-count})))
    (datastar/sse-response-plan (ffirst responses))))

(defn resend-invitation-fx [_ {req :request} invite-code]
  (members.effects/resend-invitation! req invite-code))

(defn reissue-invitation-fx [_ {req :request} invite-code]
  (members.effects/reissue-invitation! req invite-code))

(defn reissue-revoked-invitation-fx
  [_ {req :request} member-id observed-generation]
  (members.effects/reissue-revoked-invitation! req member-id observed-generation))

(defn delete-invitation-fx [_ {req :request} invite-code]
  (members.effects/delete-invitation! req invite-code))

(defn update-keycloak-meta-fx [_ {req :request} member-id]
  (members.effects/update-keycloak-meta! req member-id))

(defn set-keycloak-account-enabled-fx [_ {req :request} member-id enabled?]
  (members.effects/set-keycloak-account-enabled! req member-id enabled?))

(defn dispatch-actions
  [nexus system {:keys [request response]} on-error]
  (let [empty-response {:status 204 :headers {} :body ""}
        result         (nexus/dispatch nexus
                                       {:system system :request request}
                                       {:request request}
                                       response)]
    (if-let [error (->> (:errors result) (keep :err) first)]
      (do
        (on-error error)
        empty-response)
      (try
        (if-let [response (result-response result)]
          (if (datastar/sse-response-plan? response)
            (let [sse-response
                  (datastar/respond-sse request
                                        (datastar/sse-response-events response))]
              (if (response? sse-response) sse-response empty-response))
            response)
          empty-response)
        (catch Exception error
          (on-error error)
          empty-response)))))

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
   :nexus/interceptors  [strategies/fail-fast]
   :nexus/effects       {:db/transact                                  (with-meta db-transact-fx {:nexus/batch true})
                         :app.account/save-profile                     account.effects/save-profile-fx
                         :app.account/discard-upload                   account.effects/discard-upload-fx
                         :app.datastar/assoc-state                     assoc-page-state-fx
                         :app.datastar/merge-state                     merge-page-state-fx
                         :app.datastar/respond-sse                     (with-meta respond-sse-fx {:nexus/batch true})
                         :app.insurance/send-policy-changes            insurance.effects/send-policy-changes-fx
                         :app.insurance/send-payment-notifications     insurance.effects/send-payment-notifications-fx
                         :app.insurance/send-survey-notifications      insurance.effects/send-survey-notifications-fx
                         :app.gigs/trigger-gig-details-edited          gigs.effects/trigger-gig-details-edited-fx
                         :app.gigs/trigger-gig-created                 gigs.effects/trigger-gig-created-fx
                         :app.gigs/trigger-gig-deleted                 gigs.effects/trigger-gig-deleted-fx
                         :app.gigs/trigger-gig-edited                  gigs.effects/trigger-gig-edited-fx
                         :app.gigs/recalc-play-stats                   gigs.effects/recalc-play-stats-fx
                         :app.gigs/send-reminder-to-all                gigs.effects/send-reminder-to-all-fx
                         :app.songs/trigger-song-edited                songs.effects/trigger-song-edited-fx
                         :app.songs/trigger-sync-all-songs             songs.effects/trigger-sync-all-songs-fx
                         :app.songs/recalc-play-stats                  songs.effects/recalc-play-stats-fx
                         :app.members/invite-member                    members.effects/invite-member-fx
                         :app.members/update-keycloak-meta             update-keycloak-meta-fx
                         :app.members/set-keycloak-account-enabled     set-keycloak-account-enabled-fx
                         :app.members.index/resend-invitation          resend-invitation-fx
                         :app.members.index/reissue-invitation         reissue-invitation-fx
                         :app.members.index/reissue-revoked-invitation reissue-revoked-invitation-fx
                         :app.members.index/delete-invitation          delete-invitation-fx
                         :app.poll/send-poll-opened                    poll.effects/send-poll-opened-fx}
   :nexus/actions       (merge app.account.actions/actions
                               app.settings.actions/actions
                               app.members.actions/actions
                               app.gigs.actions/actions
                               app.probeplan.actions/actions
                               app.insurance.actions/actions
                               app.file-browser.actions/actions
                               app.songs.actions/actions
                               app.poll.actions/actions)})
