(ns app.gigs.probeplan.actions
  (:require
   [app.nexus.actions :as support]
   [app.probeplan.domain :as probeplan.domain]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]))

(def probeplan-path [:gig-probeplan])
(def error-path (conj probeplan-path :_error))

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
  (-> signals keywordize-keys :gig-probeplan))

(defn- normalize-bool [v]
  (cond
    (true? v) true
    (false? v) false
    (string? v) (= "true" (str/lower-case v))
    :else (boolean v)))

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

(defn- page-state-songs [page-state]
  (let [state (get-in page-state probeplan-path ::missing)]
    (when-not (= ::missing state)
      (:songs state))))

(defn- db-songs [db gig-id]
  (renumber (q/probeplan-songs-for-gig db gig-id)))

(defn current-songs [{:keys [db page-state]} gig-id]
  (if-let [songs (page-state-songs page-state)]
    (renumber songs)
    (db-songs db gig-id)))

(defn- signal-songs [params]
  (when (contains? params :songs)
    (renumber (:songs params))))

(defn selected-songs-for-page [db page-state gig-id]
  (let [songs        (if-let [songs (page-state-songs page-state)]
                       (renumber songs)
                       (db-songs db gig-id))
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

(defn- assoc-probeplan [songs]
  [[:app.datastar/assoc-state probeplan-path {:songs (renumber songs)}]])

(defn- error-effect [message]
  [[:app.datastar/assoc-state error-path {:error message}]])

(defn- too-many-songs [{:keys [tr]}]
  (error-effect (tr [:error/probeplan-too-many-songs])))

(defn- too-many-intensive [{:keys [tr]}]
  (error-effect (tr [:error/probeplan-too-many-intensive])))

(defn- empty-probeplan [{:keys [tr]}]
  [support/clear-loading
   [:app.datastar/assoc-state error-path {:error (tr [:error/probeplan-empty])}]])

(defn toggle-probeplan-song-action [{:keys [db] :as state} signals]
  (let [{:keys [gig-id song-id selected] :as params} (params signals)
        gig-id     (util/ensure-uuid! gig-id)
        song-id    (str (util/ensure-uuid! song-id))
        selected?  (normalize-bool selected)
        songs      (or (signal-songs params)
                       (current-songs state gig-id))
        selected-song? (some #(= song-id (:song-id %)) songs)]
    (cond
      (and selected? selected-song?)
      (assoc-probeplan songs)

      (and selected? (>= (count songs) probeplan.domain/MAX-SONGS))
      (too-many-songs state)

      selected?
      (let [song (q/retrieve-song db (util/ensure-uuid! song-id))]
        (if song
          (assoc-probeplan (conj songs {:song-id song-id
                                        :position (count songs)
                                        :emphasis "none"}))
          (error-effect "Song not found.")))

      :else
      (assoc-probeplan (remove #(= song-id (:song-id %)) songs)))))

(defn toggle-probeplan-intensive-action [state signals]
  (let [{:keys [gig-id song-id]} (params signals)
        gig-id          (util/ensure-uuid! gig-id)
        song-id         (str (util/ensure-uuid! song-id))
        songs           (or (signal-songs (params signals))
                            (current-songs state gig-id))
        intensive-count (count (filter #(= "intensive" (:emphasis %)) songs))
        target          (some #(when (= song-id (:song-id %)) %) songs)
        intensive?      (= "intensive" (:emphasis target))]
    (cond
      (nil? target)
      (assoc-probeplan songs)

      (and (not intensive?) (>= intensive-count probeplan.domain/MAX-INTENSIVE))
      (too-many-intensive state)

      :else
      (assoc-probeplan
       (mapv (fn [song]
               (if (= song-id (:song-id song))
                 (assoc song :emphasis (if intensive? "none" "intensive"))
                 song))
             songs)))))

(defn move-probeplan-song-action [state signals]
  (let [{:keys [direction gig-id song-id]} (params signals)
        gig-id  (util/ensure-uuid! gig-id)
        song-id (str (util/ensure-uuid! song-id))
        songs   (current-songs state gig-id)
        idx     (first (keep-indexed #(when (= song-id (:song-id %2)) %1) songs))
        swap-idx (case direction
                   "up" (some-> idx dec)
                   "down" (some-> idx inc)
                   nil)]
    (if (and idx swap-idx (<= 0 swap-idx) (< swap-idx (count songs)))
      (assoc-probeplan (assoc songs idx (nth songs swap-idx) swap-idx (nth songs idx)))
      (assoc-probeplan songs))))

(defn reorder-probeplan-songs-action [state signals]
  (let [{:keys [gig-id order]} (params signals)
        gig-id   (util/ensure-uuid! gig-id)
        songs    (current-songs state gig-id)
        by-id    (into {} (map (juxt :song-id identity)) songs)
        ordered  (->> order
                      (map str)
                      (keep by-id))
        ordered-ids (set (map :song-id ordered))
        missing  (remove #(ordered-ids (:song-id %)) songs)]
    (assoc-probeplan (concat ordered missing))))

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

(defn- validate-save [{:keys [tr]} songs]
  (cond
    (empty? songs)
    (tr [:error/probeplan-empty])

    (> (count songs) probeplan.domain/MAX-SONGS)
    (tr [:error/probeplan-too-many-songs])

    (> (count (filter #(= "intensive" (:emphasis %)) songs)) probeplan.domain/MAX-INTENSIVE)
    (tr [:error/probeplan-too-many-intensive])))

(defn save-probeplan-action [{:keys [db] :as state} signals]
  (let [{:keys [gig-id]} (params signals)
        gig-id (util/ensure-uuid! gig-id)
        songs  (or (signal-songs (params signals))
                   (current-songs state gig-id))]
    (if-let [error (validate-save state songs)]
      (if (empty? songs)
        (empty-probeplan state)
        [support/clear-loading
         [:app.datastar/assoc-state error-path {:error error}]])
      [[:db/transact
        (probeplan-tx-data db gig-id songs)
        {:on-success [[:app.gigs/trigger-gig-edited gig-id :probeplan]]}]
       [:app.datastar/redirect (urls/link-gig gig-id)]])))

(def actions
  {::toggle-probeplan-song      #'toggle-probeplan-song-action
   ::toggle-probeplan-intensive #'toggle-probeplan-intensive-action
   ::move-probeplan-song        #'move-probeplan-song-action
   ::reorder-probeplan-songs    #'reorder-probeplan-songs-action
   ::save-probeplan             #'save-probeplan-action})
