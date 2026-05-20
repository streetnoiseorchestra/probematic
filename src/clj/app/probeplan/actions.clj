(ns app.probeplan.actions
  (:require
   [app.probeplan.domain :as domain]
   [app.queries :as q]
   [app.util :as util]))

(def edit-path [:probeplan :editing])
(def row-signals-path "probeplan.rows")

(defn- clear-row-signals-effect []
  [:app.datastar/remove-signals [row-signals-path]])

(defn open-edit-action [_state _signals]
  [(clear-row-signals-effect)
   [:app.datastar/assoc-state edit-path true]])

(defn cancel-edit-action [_state _signals]
  [(clear-row-signals-effect)
   [:app.datastar/assoc-state edit-path false]])

(defn- close-edit-effect []
  [:app.datastar/assoc-state edit-path false])

(defn- str->emphasis [emphasis]
  (cond
    (= :probeplan.emphasis/intensive emphasis) :probeplan.emphasis/intensive
    (= :probeplan.emphasis/none emphasis) :probeplan.emphasis/none
    (= "intensive" (str emphasis)) :probeplan.emphasis/intensive
    (= "probeplan.emphasis/intensive" (str emphasis)) :probeplan.emphasis/intensive
    :else domain/probeplan-classic-default-emphasis))

(defn- ->int [v]
  (cond
    (integer? v) v
    (number? v) (int v)
    (string? v) (Integer/parseInt v)
    :else (throw (ex-info "Expected an integer value" {:value v}))))

(defn- signal-index [prefix k]
  (try
    (cond
      (integer? k) k
      (number? k) (int k)

      :else
      (let [s (name k)]
        (when (.startsWith s prefix)
          (->int (subs s (count prefix))))))
    (catch RuntimeException _
      nil)))

(defn- signal-entries [prefix v]
  (cond
    (map? v) (keep (fn [[k value]]
                     (when-let [idx (signal-index prefix k)]
                       [idx value]))
                   v)
    (sequential? v) (map-indexed vector v)
    :else nil))

(defn- row-signals [signals]
  (signal-entries "r" (get-in signals [:probeplan :rows])))

(defn- fixed-probeplan-rows [db]
  (->> (q/next-probes-with-plan db domain/emphasis-comparator)
       (take 4)
       (vec)))

(defn- row-gig-id [fixed-rows row-idx row]
  (or (:gig-id row)
      (:gig/gig-id (get fixed-rows row-idx))))

(defn- default-emphasis [position]
  (if (< position 2)
    :probeplan.emphasis/intensive
    domain/probeplan-classic-default-emphasis))

(defn- tuple-song-id [song-tuple]
  (second (first song-tuple)))

(defn- tuple-emphasis [song-tuple]
  (nth song-tuple 2 nil))

(defn- song-tuple [current-by-position slot-position song]
  (when (map? song)
    (let [position      (or (some-> (:position song) ->int) slot-position)
          current-tuple (get current-by-position position)
          song-id       (or (:song-id song) (some-> current-tuple tuple-song-id))
          emphasis      (if (contains? song :emphasis)
                          (str->emphasis (:emphasis song))
                          (or (some-> current-tuple tuple-emphasis)
                              (default-emphasis position)))]
      (when song-id
        [[:song/song-id (util/ensure-uuid! song-id)]
         position
         emphasis]))))

(declare reconcile-probeplan)

(defn- parse-song-tuples [row current-song-tuples]
  (when-let [song-entries (seq (signal-entries "s" (:songs row)))]
    (try
      (let [current-by-position (into {} (map (juxt second identity)) current-song-tuples)
            updates             (reduce (fn [acc [slot-position song]]
                                          (if-let [tuple (song-tuple current-by-position slot-position song)]
                                            (assoc acc (second tuple) tuple)
                                            (reduced nil)))
                                        {}
                                        song-entries)]
        (when updates
          (->> (merge current-by-position updates)
               (vals)
               (sort-by second)
               (vec))))
      (catch RuntimeException _
        nil))))

(defn- parse-row [db fixed-rows [row-idx row]]
  (when (map? row)
    (try
      (when-let [gig-id (some->> (row-gig-id fixed-rows row-idx row)
                                 (util/ensure-uuid!))]
        (let [current-song-tuples (sort-by second (q/probeplan-song-tuples-for-gig db gig-id))]
          (when-let [song-tuples (parse-song-tuples row current-song-tuples)]
            {:gig-id      gig-id
             :song-tuples song-tuples})))
      (catch RuntimeException _
        nil))))

(defn- row-tx-data [db fixed-rows idx row-entry]
  (when-let [{:keys [gig-id song-tuples]} (parse-row db fixed-rows row-entry)]
    (let [tmpid   (str "probeplan-" idx)
          current (sort-by second (q/probeplan-song-tuples-for-gig db gig-id))
          txes    (reconcile-probeplan tmpid song-tuples current)]
      (when (seq txes)
        (into [{:probeplan/gig     [:gig/gig-id gig-id]
                :db/id             tmpid
                :probeplan/version :probeplan.version/classic}]
              txes)))))

(defn reconcile-probeplan [eid new-song-tuples current-song-tuples]
  (let [new-set     (set new-song-tuples)
        current-set (set current-song-tuples)
        add-tx      (for [song-tuple new-song-tuples
                          :when (not (current-set song-tuple))]
                      [:db/add eid :probeplan.classic/ordered-songs song-tuple])
        remove-tx   (for [song-tuple current-song-tuples
                          :when (not (new-set song-tuple))]
                      [:db/retract eid :probeplan.classic/ordered-songs song-tuple])]
    (vec (concat add-tx remove-tx))))

(defn save-probeplans-action [{:keys [db]} signals]
  (let [fixed-rows (fixed-probeplan-rows db)
        tx-data    (->> (or (row-signals signals) [])
                        (map-indexed (partial row-tx-data db fixed-rows))
                        (apply concat)
                        (vec))]
    (cond-> []
      (seq tx-data) (conj [:db/transact tx-data {}])
      true (conj (clear-row-signals-effect))
      true (conj (close-edit-effect)))))

(def actions
  {::open-edit       #'open-edit-action
   ::cancel-edit     #'cancel-edit-action
   ::save-probeplans #'save-probeplans-action})
