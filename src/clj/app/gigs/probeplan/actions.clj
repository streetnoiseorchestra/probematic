(ns app.gigs.probeplan.actions
  (:require
   [app.form :as form]
   [app.queries :as q]
   [app.util :as util]))

(def probeplan-path [:gig-probeplan])
(def error-path (conj probeplan-path :_error))
(def repertoire-filter-path (conj probeplan-path :repertoire-filter))
(def default-repertoire-filter "current")
(def repertoire-filters #{"current" "old" "all"})

(defn normalize-repertoire-filter [filter]
  (let [filter (str filter)]
    (if (repertoire-filters filter)
      filter
      default-repertoire-filter)))

(defn- str->emphasis [emphasis]
  (cond
    (= :probeplan.emphasis/intensive emphasis) :probeplan.emphasis/intensive
    (= :probeplan.emphasis/none emphasis) :probeplan.emphasis/none
    (= "intensive" (str emphasis)) :probeplan.emphasis/intensive
    (= "probeplan.emphasis/intensive" (str emphasis)) :probeplan.emphasis/intensive
    :else :probeplan.emphasis/none))

(defn- emphasis->signal [emphasis]
  (if (= :probeplan.emphasis/intensive (str->emphasis emphasis))
    "intensive"
    "none"))

(defn- normalize-song [idx song]
  (let [song-id (or (:song-id song) (:song/song-id song))]
    {:song-id  (str song-id)
     :position idx
     :emphasis (emphasis->signal (:emphasis song))}))

(defn- renumber [songs]
  (mapv normalize-song (range) songs))

(defn- db-songs [db gig-id]
  (renumber (q/probeplan-songs-for-gig db gig-id)))

(defn selected-songs-for-page [db gig-id]
  (let [songs        (db-songs db gig-id)
        song-details (into {}
                           (map (juxt :song/song-id identity))
                           (q/retrieve-songs db (map (comp util/ensure-uuid! :song-id) songs)))]
    (->> songs
         (map (fn [{:keys [song-id position emphasis]}]
                (let [song-id (util/ensure-uuid! song-id)]
                  (assoc (get song-details song-id)
                         :position position
                         :emphasis (str->emphasis emphasis)))))
         (filter some?)
         (sort-by :position)
         (vec))))

(defn- error-effect [message]
  [[:app.datastar/assoc-state error-path {:error message}]])

(defn- probeplan-song-tuple [{:keys [song-id position emphasis]}]
  [[:song/song-id (util/ensure-uuid! song-id)]
   position
   (str->emphasis emphasis)])

(defn- reconcile-probeplan [eid new-song-tuples current-song-tuples]
  (let [new-set     (set new-song-tuples)
        current-set (set current-song-tuples)
        add-tx      (for [song-tuple new-song-tuples
                          :when (not (current-set song-tuple))]
                      [:db/add eid :probeplan.classic/ordered-songs song-tuple])
        remove-tx   (for [song-tuple current-song-tuples
                          :when (not (new-set song-tuple))]
                      [:db/retract eid :probeplan.classic/ordered-songs song-tuple])]
    (vec (concat add-tx remove-tx))))

(defn probeplan-tx-data [db gig-id songs]
  (let [song-tuples (mapv probeplan-song-tuple (renumber songs))
        current     (sort-by second (q/probeplan-song-tuples-for-gig db gig-id))]
    (into [{:probeplan/gig     [:gig/gig-id gig-id]
            :db/id             "probeplan"
            :probeplan/version :probeplan.version/classic}]
          (reconcile-probeplan "probeplan" song-tuples current))))

(defn persist-probeplan-effect [db gig-id songs]
  [:db/transact
   (probeplan-tx-data db gig-id songs)
   {:on-success [[:app.gigs/trigger-gig-edited gig-id :probeplan]]}])

(defn toggle-probeplan-song-action [{:keys [db]} {:keys [gig-probeplan]}]
  (let [{:keys [gig-id song-id selected]} gig-probeplan
        gig-id         (util/ensure-uuid! gig-id)
        song-id        (str (util/ensure-uuid! song-id))
        selected?      (form/normalize-bool selected)
        songs          (db-songs db gig-id)
        selected-song? (some #(= song-id (:song-id %)) songs)]
    (cond
      (and selected? selected-song?)
      [(persist-probeplan-effect db gig-id songs)]

      selected?
      (let [song (q/retrieve-song db (util/ensure-uuid! song-id))]
        (if song
          [(persist-probeplan-effect db gig-id (conj songs {:song-id  song-id
                                                            :position (count songs)
                                                            :emphasis "none"}))]
          (error-effect "Song not found.")))

      :else
      [(persist-probeplan-effect db gig-id (remove #(= song-id (:song-id %)) songs))])))

(defn toggle-probeplan-intensive-action [{:keys [db]} {:keys [gig-probeplan]}]
  (let [{:keys [gig-id song-id]} gig-probeplan
        gig-id     (util/ensure-uuid! gig-id)
        song-id    (str (util/ensure-uuid! song-id))
        songs      (db-songs db gig-id)
        target     (some #(when (= song-id (:song-id %)) %) songs)
        intensive? (= "intensive" (:emphasis target))]
    (if-not target
      [(persist-probeplan-effect db gig-id songs)]
      [(persist-probeplan-effect
        db
        gig-id
        (mapv (fn [song]
                (if (= song-id (:song-id song))
                  (assoc song :emphasis (if intensive? "none" "intensive"))
                  song))
              songs))])))

(defn set-repertoire-filter-action [_state {:keys [gig-probeplan]}]
  (let [{:keys [repertoire-filter]} gig-probeplan]
    [[:app.datastar/assoc-state repertoire-filter-path (normalize-repertoire-filter repertoire-filter)]]))

(defn reorder-probeplan-songs-action [{:keys [db]} {:keys [gig-probeplan]}]
  (let [{:keys [gig-id order]} gig-probeplan
        gig-id      (util/ensure-uuid! gig-id)
        songs       (db-songs db gig-id)
        by-id       (into {} (map (juxt :song-id identity)) songs)
        ordered     (->> order
                         (map str)
                         (keep by-id))
        ordered-ids (set (map :song-id ordered))
        missing     (remove #(ordered-ids (:song-id %)) songs)]
    [(persist-probeplan-effect db gig-id (concat ordered missing))]))

(def actions
  {::set-repertoire-filter      #'set-repertoire-filter-action
   ::toggle-probeplan-song      #'toggle-probeplan-song-action
   ::toggle-probeplan-intensive #'toggle-probeplan-intensive-action
   ::reorder-probeplan-songs    #'reorder-probeplan-songs-action})
