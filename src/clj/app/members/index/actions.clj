(ns app.members.index.actions
  (:require
   [app.util :as util]))

(def valid-filter-presets
  #{"all" "active" "inactive"})

(def valid-sort-fields
  #{"name" "travel-discount" "email" "phone" "section" "active"})

(def default-filter-preset "active")
(def default-sort-field "name")
(def default-sort-order "asc")

(defn- normalize-search [search]
  (or search ""))

(defn- normalize-filter-preset [filter-preset]
  (if (valid-filter-presets filter-preset)
    filter-preset
    default-filter-preset))

(defn- normalize-sort-field [sort-field]
  (if (valid-sort-fields sort-field)
    sort-field
    default-sort-field))

(defn- normalize-sort-order [sort-order]
  (if (= sort-order "desc")
    "desc"
    default-sort-order))

(defn set-search-phrase-action
  [_state {:keys [members-index]}]
  [[:app.datastar/assoc-state [:members-index :search]
    (normalize-search (:search members-index))]])

(defn set-filter-preset-action
  [_state {:keys [members-index]}]
  [[:app.datastar/assoc-state [:members-index :filter-preset]
    (normalize-filter-preset (:filter-preset members-index))]])

(defn set-sort-action
  [_state {:keys [members-index]}]
  (let [sort-field         (normalize-sort-field (:sort-field members-index))
        sort-order         (normalize-sort-order (:sort-order members-index))
        sort-request-field (normalize-sort-field (:sort-request-field members-index))
        next-sort-order    (if (= sort-field sort-request-field)
                             (if (= sort-order "asc")
                               "desc"
                               "asc")
                             "asc")]
    [[:app.datastar/assoc-state [:members-index :sort-field] sort-request-field]
     [:app.datastar/assoc-state [:members-index :sort-order] next-sort-order]]))

(def clear-invite-signals
  [:app.datastar/respond-sse
   [[:app.datastar.sse/merge-signals
     {:invite {:action nil
               :code nil
               :member-id nil
               :generation nil
               :inflight false}}]]])

(defn resend-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/resend-invitation (:code invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(defn reissue-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/reissue-invitation (:code invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(defn reissue-revoked-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/reissue-revoked-invitation
    (util/ensure-uuid! (:member-id invite))
    (:generation invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(defn delete-invitation-action
  [{:keys [now]} {:keys [invite]}]
  [[:app.members.index/delete-invitation (:code invite)]
   [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   clear-invite-signals])

(def actions
  {::set-search-phrase   #'set-search-phrase-action
   ::set-filter-preset   #'set-filter-preset-action
   ::set-sort            #'set-sort-action
   ::resend-invitation   #'resend-invitation-action
   ::reissue-invitation  #'reissue-invitation-action
   ::reissue-revoked-invitation #'reissue-revoked-invitation-action
   ::delete-invitation   #'delete-invitation-action})
