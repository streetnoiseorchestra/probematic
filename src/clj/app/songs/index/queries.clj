(ns app.songs.index.queries
  (:require
   [app.queries :as q]
   [app.songs.index.actions :as actions]
   [clojure.string :as str]))

(def default-page-state
  {:search            ""
   :repertoire-filter actions/default-repertoire-filter})

(defn normalize-page-state [page-state]
  (-> default-page-state
      (merge page-state)
      (update :search actions/normalize-search)
      (update :repertoire-filter actions/normalize-repertoire-filter)))

(defn- matches-repertoire-filter? [repertoire-filter song]
  (case repertoire-filter
    "current" (:song/active? song)
    "old"     (not (:song/active? song))
    "all"     true))

(defn- normalize-search-term [search]
  (some-> search str/trim str/lower-case not-empty))

(defn- matches-search? [search song]
  (if-let [search (normalize-search-term search)]
    (str/includes? (str/lower-case (or (:song/title song) "")) search)
    true))

(defn songs [db page-state]
  (let [{:keys [repertoire-filter search]} (normalize-page-state page-state)]
    (->> (q/retrieve-all-songs db)
         (filter #(matches-repertoire-filter? repertoire-filter %))
         (filter #(matches-search? search %))
         vec)))
