(ns app.gigs.archive.actions)

(defn normalize-search [search]
  (or search ""))

(defn set-search-phrase-action
  [_state {:keys [query-params]}]
  [[:app.datastar/assoc-state [:gigs-archive :search]
    (normalize-search (get query-params "q"))]])

(def actions
  {::set-search-phrase #'set-search-phrase-action})
