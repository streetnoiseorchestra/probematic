(ns app.songs.index.actions)

(def valid-repertoire-filters
  #{"all" "current" "old"})

(def default-repertoire-filter "current")

(defn normalize-search [search]
  (or search ""))

(defn normalize-repertoire-filter [repertoire-filter]
  (if (valid-repertoire-filters repertoire-filter)
    repertoire-filter
    default-repertoire-filter))

(defn set-search-phrase-action
  [_state {:keys [songs-index]}]
  [[:app.datastar/assoc-state [:songs-index :search]
    (normalize-search (:search songs-index))]])

(defn set-repertoire-filter-action
  [_state {:keys [songs-index]}]
  [[:app.datastar/assoc-state [:songs-index :repertoire-filter]
    (normalize-repertoire-filter (:repertoire-filter songs-index))]])

(def actions
  {::set-search-phrase     #'set-search-phrase-action
   ::set-repertoire-filter #'set-repertoire-filter-action})
