(ns app.gigs.setlist.actions
  (:require
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]))

(def setlist-path [:gig-setlist])
(def error-path (conj setlist-path :_error))
(def repertoire-filter-path (conj setlist-path :repertoire-filter))
(def default-repertoire-filter "current")
(def repertoire-filters #{"current" "old" "all"})

(defn normalize-repertoire-filter [filter]
  (let [filter (str filter)]
    (if (repertoire-filters filter)
      filter
      default-repertoire-filter)))

(defn- normalize-bool [v]
  (cond
    (true? v) true
    (false? v) false
    (string? v) (= "true" (str/lower-case v))
    :else (boolean v)))

(defn- normalize-song [idx song]
  {:song-id  (str (or (:song-id song) (:song/song-id song)))
   :position idx})

(defn- renumber [songs]
  (mapv normalize-song (range) songs))

(defn- db-songs [db gig-id]
  (renumber (q/setlist-songs-for-gig db gig-id)))

(defn selected-songs-for-page [db gig-id]
  (let [songs        (db-songs db gig-id)
        song-details (into {}
                           (map (juxt :song/song-id identity))
                           (q/retrieve-songs db (map (comp util/ensure-uuid! :song-id) songs)))]
    (->> songs
         (map (fn [{:keys [song-id position]}]
                (let [song-id (util/ensure-uuid! song-id)]
                  (assoc (get song-details song-id)
                         :position position))))
         (filter some?)
         (sort-by :position)
         (vec))))

(defn- error-effect [message]
  [[:app.datastar/assoc-state error-path {:error message}]])

(defn- setlist-song-tuple [{:keys [song-id position]}]
  [[:song/song-id (util/ensure-uuid! song-id)]
   position])

(defn- reconcile-setlist [eid new-song-tuples current-song-tuples]
  (let [new-set     (set new-song-tuples)
        current-set (set current-song-tuples)
        add-tx      (for [song-tuple new-song-tuples
                          :when (not (current-set song-tuple))]
                      [:db/add eid :setlist.v1/ordered-songs song-tuple])
        remove-tx   (for [song-tuple current-song-tuples
                          :when (not (new-set song-tuple))]
                      [:db/retract eid :setlist.v1/ordered-songs song-tuple])]
    (vec (concat add-tx remove-tx))))

(defn setlist-tx-data [db gig-id songs]
  (let [song-tuples (mapv setlist-song-tuple (renumber songs))
        current     (sort-by second (q/setlist-song-tuples-for-gig db gig-id))]
    (into [{:setlist/gig     [:gig/gig-id gig-id]
            :db/id           "setlist"
            :setlist/version :setlist.version/v1}]
          (reconcile-setlist "setlist" song-tuples current))))

(defn persist-setlist-effect [db gig-id songs]
  [:db/transact
   (setlist-tx-data db gig-id songs)
   {:on-success [[:app.gigs/trigger-gig-edited gig-id :setlist]]}])

(defn toggle-setlist-song-action [{:keys [db]} {:keys [gig-setlist]}]
  (let [{:keys [gig-id song-id selected]} gig-setlist
        gig-id         (util/ensure-uuid! gig-id)
        song-id        (str (util/ensure-uuid! song-id))
        selected?      (normalize-bool selected)
        songs          (db-songs db gig-id)
        selected-song? (some #(= song-id (:song-id %)) songs)]
    (cond
      (and selected? selected-song?)
      [(persist-setlist-effect db gig-id songs)]

      selected?
      (let [song (q/retrieve-song db (util/ensure-uuid! song-id))]
        (if song
          [(persist-setlist-effect db gig-id (conj songs {:song-id  song-id
                                                          :position (count songs)}))]
          (error-effect "Song not found.")))

      :else
      [(persist-setlist-effect db gig-id (remove #(= song-id (:song-id %)) songs))])))

(defn set-repertoire-filter-action [_state {:keys [gig-setlist]}]
  (let [{:keys [repertoire-filter]} gig-setlist]
    [[:app.datastar/assoc-state repertoire-filter-path (normalize-repertoire-filter repertoire-filter)]]))

(defn reorder-setlist-songs-action [{:keys [db]} {:keys [gig-setlist]}]
  (let [{:keys [gig-id order]} gig-setlist
        gig-id      (util/ensure-uuid! gig-id)
        songs       (db-songs db gig-id)
        by-id       (into {} (map (juxt :song-id identity)) songs)
        ordered     (->> order
                         (map str)
                         (keep by-id))
        ordered-ids (set (map :song-id ordered))
        missing     (remove #(ordered-ids (:song-id %)) songs)]
    [(persist-setlist-effect db gig-id (concat ordered missing))]))

(def actions
  {::set-repertoire-filter   #'set-repertoire-filter-action
   ::toggle-setlist-song     #'toggle-setlist-song-action
   ::reorder-setlist-songs   #'reorder-setlist-songs-action})
