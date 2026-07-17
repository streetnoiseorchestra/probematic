(ns app.members.queries
  (:require
   [app.datomic :as d]
   [app.members.invite.domain :as invite.domain]
   [app.queries :as q]
   [clojure.string :as str]
   [tick.core :as t]))

(defn sections [db]
  (->> (d/find-all db :section/name [:section/name])
       (mapv first)
       (sort-by :section/name)))

(defn sort-by-spec [sorting coll]
  #_(tap> {:sorting sorting
           :fields  (mapv :field sorting)
           :dir     (if (= :asc (-> sorting first :order)) :asc :desc)})
  (let [asc? (= :asc (-> sorting first :order))

        r (sort-by (fn [v]
                     (mapv (fn [s]
                             (if (string? s)
                               (str/lower-case s)
                               s))
                           ((apply juxt (map :field sorting)) v))) coll)]
    (if asc? r (reverse r))))

(def filter-preset-pred
  {"all"      (constantly true)
   "active"   #(-> % :member/active?)
   "inactive" #(not (-> % :member/active?))})

(defn search-member [q member]
  (->> (select-keys member [:member/username :member/name :member/email :member/nick :member/phone])
       (map str/lower-case)
       (some #(str/includes? % q))))

(defn search-members [in coll]
  (filter (partial search-member  (str/lower-case in)) coll))

(defn filter-by-spec [{:keys [_fields preset search]} coll]
  ;; fields NYI
  (let [preset-pred (get filter-preset-pred preset)]
    (cond->> coll
      preset-pred (filter preset-pred)
      search      (search-members search))))

(def ^:private invitation-state-pattern
  [:member/member-id
   :member/invite-code
   :member/invite-expires-at
   {:member/invite-status [:db/ident]}
   :member/invite-generation])

(def ^:private acceptance-member-pattern
  [:member/member-id
   :member/name
   :member/email
   :member/username])

(def ^:private accepted-invitation-pattern
  [:member/member-id
   :member/keycloak-id
   {:member/invite-status [:db/ident]}])

(def ^:private pending-invitation-pattern
  [:member/member-id
   :member/name
   :member/email
   :member/invite-code
   :member/invite-expires-at])

(def ^:private revoked-invitation-pattern
  [:member/member-id
   :member/name
   :member/email
   :member/keycloak-id
   {:member/invite-status [:db/ident]}
   :member/invite-generation])

(def ^:private recoverable-invitation-statuses
  #{:member.invite.status/accepting
    :member.invite.status/creating
    :member.invite.status/activating
    :member.invite.status/compensating})

(defn- normalize-invitation-status [invitation]
  (update invitation :member/invite-status :db/ident))

(defn- after? [candidate boundary]
  (and candidate boundary (t/> candidate boundary)))

(defn invitation-state-by-code
  "Returns the narrow current member invitation state for `invite-code`.

  This lookup does not apply expiry or lifecycle validity. Callers must enforce
  the transition-specific policy before mutating the member."
  [db invite-code]
  (when-not (str/blank? invite-code)
    (some-> (d/find-by db
                       :member/invite-code
                       invite-code
                       invitation-state-pattern)
            normalize-invitation-status)))

(defn acceptance-invitation
  "Returns the HTTP-boundary invitation projection valid at `now`.

  Pending invitations require an expiry strictly after `now`. Claimed
  accepting, creating, activating, and compensating states remain recoverable
  after expiry. The returned `:invite-code` must be discarded before Mycelium
  runs."
  [db now invite-code]
  (when-let [{:member/keys [member-id
                            invite-expires-at
                            invite-status
                            invite-generation]}
             (invitation-state-by-code db invite-code)]
    (when (or (and (= :member.invite.status/pending invite-status)
                   (after? invite-expires-at now))
              (recoverable-invitation-statuses invite-status))
      {:member (d/find-by db
                          :member/member-id
                          member-id
                          acceptance-member-pattern)
       :member-id member-id
       :invite-code invite-code
       :invite-status invite-status
       :invite-generation invite-generation})))

(defn accepted-invitation-by-code
  "Returns a canonically completed invitation matching the opaque code receipt.

  The raw bearer is never retained after acceptance. This lookup hashes the
  submitted code and succeeds only for accepted state with a linked Keycloak
  user."
  [db invite-code]
  (when-not (str/blank? invite-code)
    (when-let [{:member/keys [member-id keycloak-id invite-status]}
               (some->
                (d/find-by
                 db
                 :member/invite-accepted-code-digest
                 (invite.domain/accepted-receipt-digest invite-code)
                 accepted-invitation-pattern)
                normalize-invitation-status)]
      (when (and (= :member.invite.status/accepted invite-status)
                 (not (str/blank? keycloak-id)))
        {:member (d/find-by db
                            :member/member-id
                            member-id
                            acceptance-member-pattern)
         :member-id member-id}))))

(defn revoked-invitation-by-member-id
  "Returns a revoked invitation for `member-id` without bearer fields."
  [db member-id]
  (when-let [invitation (some-> (d/find-by db
                                           :member/member-id
                                           member-id
                                           revoked-invitation-pattern)
                                normalize-invitation-status)]
    (when (and (= :member.invite.status/revoked
                  (:member/invite-status invitation))
               (str/blank? (:member/keycloak-id invitation)))
      invitation)))

(defn members-with-pending-invites
  "Returns pending member invitations and marks those expired at `now`."
  ([db]
   (members-with-pending-invites db (t/inst)))
  ([db now]
   (->> (d/find-all-by db
                       :member/invite-status
                       :member.invite.status/pending
                       pending-invitation-pattern)
        (map first)
        (map #(assoc % :invite-expired?
                     (not (after? (:member/invite-expires-at %) now))))
        (sort-by :member/name)
        vec)))

(defn members-with-revoked-invites
  "Returns revoked member invitations without bearer or expiry fields."
  [db]
  (->> (d/find-all-by db
                      :member/invite-status
                      :member.invite.status/revoked
                      revoked-invitation-pattern)
       (map first)
       (map normalize-invitation-status)
       (filter #(str/blank? (:member/keycloak-id %)))
       (sort-by :member/name)
       vec))

(def default-page-state
  {:search                    ""
   :filter-preset             "active"
   :sort-field                "name"
   :sort-order                "asc"
   :last-invitation-action-at nil})

(def valid-filter-presets
  #{"all" "active" "inactive"})

(defn travel-discount-sort-value [{:member/keys [travel-discounts]}]
  (->> travel-discounts
       (map (comp :travel.discount.type/discount-type-name :travel.discount/discount-type))
       (remove str/blank?)
       sort
       (str/join ", ")
       str/lower-case))

(def sort-field->fn
  {"name"            :member/name
   "travel-discount" travel-discount-sort-value
   "email"           :member/email
   "phone"           :member/phone
   "section"         (comp :section/name :member/section)
   "active"          :member/active?})

(defn normalize-page-state [page-state]
  (-> default-page-state
      (merge page-state)
      (update :search #(or % ""))
      (update :filter-preset #(if (valid-filter-presets %)
                                %
                                (:filter-preset default-page-state)))
      (update :sort-field #(if (contains? sort-field->fn %)
                             %
                             (:sort-field default-page-state)))
      (update :sort-order #(if (= % "desc") % "asc"))))

(defn filter-spec [page-state]
  (let [{:keys [search filter-preset]} (normalize-page-state page-state)
        search                         (some-> search str/trim not-empty)]
    {:fields []
     :preset filter-preset
     :search search}))

(defn sort-spec [page-state]
  (let [{:keys [sort-field sort-order]} (normalize-page-state page-state)]
    [{:field (get sort-field->fn sort-field (get sort-field->fn (:sort-field default-page-state)))
      :order (if (= sort-order "desc") :desc :asc)}]))

(defn members [db page-state]
  (let [sorting   (or (sort-spec page-state) [{:field :member/name :order :asc}])
        filtering (or (filter-spec page-state) {:fields [] :preset "active" :search nil})]
    (->> (d/find-all db :member/member-id q/member-detail-pattern)
         (mapv first)
         (filter-by-spec filtering)
         (sort-by-spec sorting))))
