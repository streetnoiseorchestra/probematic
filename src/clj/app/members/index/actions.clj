(ns app.members.index.actions)

(def default-filter-preset "active")
(def default-sort-field "name")
(def default-sort-order "asc")

(def valid-filter-presets
  #{"all" "active" "inactive"})

(def valid-sort-fields
  #{"name" "travel-discount" "email" "phone" "section" "active"})

(def valid-sort-orders
  #{"asc" "desc"})

(defn- normalize-search-phrase [phrase]
  (or phrase ""))

(defn- normalize-filter-preset [preset]
  (if (contains? valid-filter-presets preset)
    preset
    default-filter-preset))

(defn- normalize-sort-field [field]
  (if (contains? valid-sort-fields field)
    field
    default-sort-field))

(defn- normalize-sort-order [order]
  (if (contains? valid-sort-orders order)
    order
    default-sort-order))

(defn set-search-phrase-action
  [_state {:keys [members-index]}]
  [[:app.datastar/assoc-state [:members-index :search]
    (normalize-search-phrase (:search members-index))]])

(defn set-filter-preset-action
  [_state {:keys [members-index]}]
  [[:app.datastar/assoc-state [:members-index :filter-preset]
    (normalize-filter-preset (:filter-preset members-index))]])

(defn set-sort-action
  [_state {:keys [members-index]}]
  (let [current-field (normalize-sort-field (:sort-field members-index))
        current-order (normalize-sort-order (:sort-order members-index))
        requested     (normalize-sort-field (:sort-request-field members-index))
        next-order    (if (= requested current-field)
                        (if (= current-order "asc") "desc" "asc")
                        "asc")]
    [[:app.datastar/assoc-state [:members-index :sort-field] requested]
     [:app.datastar/assoc-state [:members-index :sort-order] next-order]]))

(defn- invitation-cleanup-effects [now]
  [[:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
   [:app.datastar/merge-signals {:invite {:action nil
                                          :code nil
                                          :inflight false}}]])

(defn resend-invitation-action
  [{:keys [now]} {:keys [invite]}]
  (into [[:app.members.index/resend-invitation (:code invite)]]
        (invitation-cleanup-effects now)))

(defn delete-invitation-action
  [{:keys [now]} {:keys [invite]}]
  (into [[:app.members.index/delete-invitation (:code invite)]]
        (invitation-cleanup-effects now)))

(def actions
  {::set-search-phrase  #'set-search-phrase-action
   ::set-filter-preset  #'set-filter-preset-action
   ::set-sort           #'set-sort-action
   ::resend-invitation  #'resend-invitation-action
   ::delete-invitation  #'delete-invitation-action})
