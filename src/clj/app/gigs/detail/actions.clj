(ns app.gigs.detail.actions
  (:require
   [app.gigs.domain :as domain]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]))

(def attendance-error-path [:gig-detail :attendance :_error])
(def comment-edit-path [:gig-detail :attendance :comment-edit])
(def show-committed-path [:gig-detail :attendance :show-committed?])

(defn- keywordize-keys [x]
  (cond
    (map? x) (into {}
                   (map (fn [[k v]]
                          [(if (keyword? k) k (keyword k))
                           (keywordize-keys v)]))
                   x)
    (vector? x) (mapv keywordize-keys x)
    :else x))

(defn- params [signals]
  (-> signals keywordize-keys :gig-attendance))

(defn- str->plan [plan]
  (when (seq (str plan))
    ((set domain/plans) (keyword "plan" (str plan)))))

(defn- str->motivation [motivation]
  (when (seq (str motivation))
    ((set domain/motivations) (keyword "motivation" (str motivation)))))

(defn- normalize-bool [v]
  (cond
    (true? v) true
    (false? v) false
    (string? v) (= "true" (str/lower-case v))
    :else (boolean v)))

(defn- ids [{:keys [gig-id member-id]}]
  {:gig-id    (some-> gig-id util/ensure-uuid!)
   :member-id (some-> member-id util/ensure-uuid!)})

(defn- invalid [message]
  [support/clear-loading
   [:app.datastar/assoc-state attendance-error-path {:error message}]])

(defn- invalid-plan [{:keys [tr]}]
  (invalid (tr [:error/gig-attendance-invalid-plan])))

(defn- invalid-motivation [{:keys [tr]}]
  (invalid (tr [:error/gig-attendance-invalid-motivation])))

(defn- attendance-ref [gig-id member-id]
  [:attendance/gig+member (q/gig+member gig-id member-id)])

(defn- attendance [db gig-id member-id]
  (q/attendance-for-gig db gig-id member-id))

(defn- create-attendance-tx [db gig-id member-id attrs]
  (merge {:attendance/gig+member (q/gig+member gig-id member-id)
          :attendance/gig        [:gig/gig-id gig-id]
          :attendance/member     [:member/member-id member-id]
          :attendance/updated    :db/now
          :attendance/section    [:section/name (q/section-for-member db member-id)]}
         attrs))

(defn- update-attendance-tx [gig-id member-id attr value]
  [[:db/add (attendance-ref gig-id member-id) attr value]
   [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]])

(defn- transact-attendance-effect [gig-id tx-data]
  [:db/transact tx-data {:on-success [[:app.gigs/trigger-gig-edited gig-id :attendance]]}])

(defn update-attendance-plan-action [{:keys [db] :as state} signals]
  (let [{:keys [plan] :as params} (params signals)
        {:keys [gig-id member-id]} (ids params)
        plan-kw (str->plan plan)]
    (if-not plan-kw
      (invalid-plan state)
      (let [tx-data (if (attendance db gig-id member-id)
                      (update-attendance-tx gig-id member-id :attendance/plan plan-kw)
                      [(create-attendance-tx db gig-id member-id {:attendance/plan plan-kw})])]
        [(transact-attendance-effect gig-id tx-data)]))))

(defn update-attendance-motivation-action [{:keys [db] :as state} signals]
  (let [{:keys [motivation] :as params} (params signals)
        {:keys [gig-id member-id]} (ids params)
        motivation-kw (str->motivation motivation)]
    (if-not motivation-kw
      (invalid-motivation state)
      (let [tx-data (if (attendance db gig-id member-id)
                      (update-attendance-tx gig-id member-id :attendance/motivation motivation-kw)
                      [(create-attendance-tx db gig-id member-id {:attendance/motivation motivation-kw})])]
        [(transact-attendance-effect gig-id tx-data)]))))

(defn open-attendance-comment-action [_state signals]
  (let [{:keys [comment] :as params} (params signals)
        {:keys [gig-id member-id]} (ids params)]
    [[:app.datastar/assoc-state
      comment-edit-path
      {:gig-id    (str gig-id)
       :member-id (str member-id)
       :comment   (or comment "")}]]))

(defn close-attendance-comment-action [_state _signals]
  [[:app.datastar/assoc-state comment-edit-path nil]])

(defn- comment-tx-data [db gig-id member-id comment]
  (let [comment  (str comment)
        existing (attendance db gig-id member-id)
        blank?   (str/blank? comment)]
    (cond
      (and blank? (not existing))
      nil

      blank?
      [[:db/retract (attendance-ref gig-id member-id) :attendance/comment]
       [:db/add (attendance-ref gig-id member-id) :attendance/updated :db/now]]

      existing
      (update-attendance-tx gig-id member-id :attendance/comment comment)

      :else
      [(create-attendance-tx db gig-id member-id {:attendance/comment comment})])))

(defn update-attendance-comment-action [{:keys [db]} signals]
  (let [{:keys [comment] :as params} (params signals)
        {:keys [gig-id member-id]} (ids params)
        tx-data    (comment-tx-data db gig-id member-id comment)
        close-edit [:app.datastar/assoc-state comment-edit-path nil]]
    (if tx-data
      [(transact-attendance-effect gig-id tx-data)
       close-edit]
      [support/clear-loading close-edit])))

(defn switch-attendance-comment-action [{:keys [db]} signals]
  (let [{:keys [comment comment-gig-id comment-member-id next-comment next-gig-id next-member-id]} (params signals)
        comment-gig-id    (util/ensure-uuid! comment-gig-id)
        comment-member-id (util/ensure-uuid! comment-member-id)
        next-gig-id       (util/ensure-uuid! next-gig-id)
        next-member-id    (util/ensure-uuid! next-member-id)
        tx-data           (comment-tx-data db comment-gig-id comment-member-id comment)
        open-next         [:app.datastar/assoc-state
                           comment-edit-path
                           {:gig-id    (str next-gig-id)
                            :member-id (str next-member-id)
                            :comment   (or next-comment "")}]
        clear-switching   [:app.datastar/merge-signals {:gig-attendance {:switching-comment false}}]]
    (cond-> []
      tx-data (conj (transact-attendance-effect comment-gig-id tx-data))
      true    (conj open-next clear-switching))))

(defn toggle-attendance-committed-action [_state signals]
  (let [{:keys [show-committed? show-committed]} (params signals)
        show-committed? (normalize-bool (if (some? show-committed?)
                                          show-committed?
                                          show-committed))]
    [[:app.datastar/assoc-state show-committed-path show-committed?]]))

(def actions
  {::update-attendance-plan       #'update-attendance-plan-action
   ::update-attendance-motivation #'update-attendance-motivation-action
   ::open-attendance-comment      #'open-attendance-comment-action
   ::close-attendance-comment     #'close-attendance-comment-action
   ::update-attendance-comment    #'update-attendance-comment-action
   ::switch-attendance-comment    #'switch-attendance-comment-action
   ::toggle-attendance-committed  #'toggle-attendance-committed-action})
