(ns app.probeplan.actions-test
  (:require
   [app.gigs.domain :as gig.domain]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [clojure.walk :as walk]
   [datomic.api :as d]
   [tick.core :as t]))

(defn action-fn [sym]
  (requiring-resolve sym))

(defn open-edit-action [state signals]
  ((action-fn 'app.probeplan.actions/open-edit-action) state signals))

(defn cancel-edit-action [state signals]
  ((action-fn 'app.probeplan.actions/cancel-edit-action) state signals))

(defn save-probeplans-action [state signals]
  ((action-fn 'app.probeplan.actions/save-probeplans-action) state signals))

(defn seed-gig! [conn gig-id days-from-today]
  @(d/transact conn [(gig.domain/gig->db {:gig/gig-id    gig-id
                                          :gig/title     (str "Probe " days-from-today)
                                          :gig/status    :gig.status/confirmed
                                          :gig/gig-type  :gig.type/probe
                                          :gig/date      (t/>> (t/date) (t/new-period days-from-today :days))
                                          :gig/location  "Room"
                                          :gig/call-time (t/time "18:00")})]))

(defn seed-song!
  ([conn song-id title]
   (seed-song! conn song-id title true))
  ([conn song-id title active?]
   @(d/transact conn [{:song/song-id song-id
                       :song/title   title
                       :song/active? active?}])))

(defn seed-probeplan! [conn gig-id songs]
  @(d/transact conn [(into {:probeplan/gig     [:gig/gig-id gig-id]
                            :probeplan/version :probeplan.version/classic}
                           {:probeplan.classic/ordered-songs
                            (mapv (fn [[song-id position emphasis]]
                                    [[:song/song-id song-id] position emphasis])
                                  songs)})]))

(defn state [conn]
  {:db (d/db conn)})

(defn row-signal [gig-id songs]
  {:gig-id (str gig-id)
   :songs  (into {}
                 (map-indexed
                  (fn [idx [song-id position emphasis]]
                    [(str "s" idx)
                     {:song-id  (str song-id)
                      :position position
                      :emphasis emphasis}]))
                 songs)})

(defn normalize-tmpids [tx-data]
  (let [tmpids (into #{} (keep #(when (map? %) (:db/id %))) tx-data)]
    (mapv (fn [tx]
            (walk/postwalk #(if (tmpids %) "probeplan" %) tx))
          tx-data)))

(defn normalize-effects [effects]
  (mapv (fn [[fx tx-data opts :as effect]]
          (if (= :db/transact fx)
            [fx (normalize-tmpids tx-data) opts]
            effect))
        effects))

(deftest open-edit-action-enters-edit-mode-with-fresh-row-signals
  (is (= [[:app.datastar/remove-signals ["probeplan.rows"]]
          [:app.datastar/assoc-state [:probeplan :editing] true]]
         (open-edit-action {} {}))))

(deftest cancel-edit-action-leaves-edit-mode-and-clears-row-signals
  (is (= [[:app.datastar/remove-signals ["probeplan.rows"]]
          [:app.datastar/assoc-state [:probeplan :editing] false]]
         (cancel-edit-action {} {}))))

(deftest save-probeplans-action-reconciles-submitted-fixed-rows
  (let [{:keys [conn]} (tc/new-system "probeplan-save")
        gig-id         (random-uuid)
        song-a         (random-uuid)
        song-b         (random-uuid)
        song-c         (random-uuid)
        song-d         (random-uuid)
        song-e         (random-uuid)
        song-f         (random-uuid)
        replacement    (random-uuid)]
    (seed-gig! conn gig-id 7)
    (doseq [[id title] [[song-a "Alpha"]
                        [song-b "Bravo"]
                        [song-c "Charlie"]
                        [song-d "Delta"]
                        [song-e "Echo"]
                        [song-f "Foxtrot"]
                        [replacement "Replacement"]]]
      (seed-song! conn id title))
    (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/intensive]
                                  [song-b 1 :probeplan.emphasis/intensive]
                                  [song-c 2 :probeplan.emphasis/none]
                                  [song-d 3 :probeplan.emphasis/none]
                                  [song-e 4 :probeplan.emphasis/none]
                                  [song-f 5 :probeplan.emphasis/none]])
    (is (= [[:db/transact
             [{:probeplan/gig     [:gig/gig-id gig-id]
               :db/id             "probeplan"
               :probeplan/version :probeplan.version/classic}
              [:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id replacement] 1 :probeplan.emphasis/intensive]]
              [:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-b] 1 :probeplan.emphasis/intensive]]]
             {}]
            [:app.datastar/remove-signals ["probeplan.rows"]]
            [:app.datastar/assoc-state [:probeplan :editing] false]]
           (normalize-effects
            (save-probeplans-action
             (state conn)
             {:probeplan {:rows {"r0" (row-signal gig-id [[song-a 0 "intensive"]
                                                          [replacement 1 "intensive"]
                                                          [song-c 2 "none"]
                                                          [song-d 3 "none"]
                                                          [song-e 4 "none"]
                                                          [song-f 5 "none"]])}}}))))))

(deftest save-probeplans-action-reconciles-bound-only-row-signals
  (let [{:keys [conn]} (tc/new-system "probeplan-save-bound-only")
        gig-id         (random-uuid)
        song-a         (random-uuid)
        song-b         (random-uuid)
        song-c         (random-uuid)
        song-d         (random-uuid)
        song-e         (random-uuid)
        replacement    (random-uuid)]
    (seed-gig! conn gig-id 7)
    (doseq [[id title] [[song-a "Alpha"]
                        [song-b "Bravo"]
                        [song-c "Charlie"]
                        [song-d "Delta"]
                        [song-e "Echo"]
                        [replacement "Replacement"]]]
      (seed-song! conn id title))
    (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/intensive]
                                  [song-b 1 :probeplan.emphasis/intensive]
                                  [song-c 2 :probeplan.emphasis/none]
                                  [song-d 3 :probeplan.emphasis/none]
                                  [song-e 4 :probeplan.emphasis/none]])
    (is (= [[:db/transact
             [{:probeplan/gig     [:gig/gig-id gig-id]
               :db/id             "probeplan"
               :probeplan/version :probeplan.version/classic}
              [:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id replacement] 1 :probeplan.emphasis/intensive]]
              [:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-b] 1 :probeplan.emphasis/intensive]]]
             {}]
            [:app.datastar/remove-signals ["probeplan.rows"]]
            [:app.datastar/assoc-state [:probeplan :editing] false]]
           (normalize-effects
            (save-probeplans-action
             (state conn)
             {:probeplan {:rows {"r0" {:songs {"s1" {:song-id (str replacement)}}}}}}))))))

(deftest save-probeplans-action-closes-edit-mode-without-valid-rows
  (let [{:keys [conn]} (tc/new-system "probeplan-save-invalid-rows")]
    (is (= [[:app.datastar/remove-signals ["probeplan.rows"]]
            [:app.datastar/assoc-state [:probeplan :editing] false]]
           (save-probeplans-action
            (state conn)
            {:probeplan {:rows {"not-a-row" "ignored"
                                "missing-gig" {:songs {"s0" {:position 0}}}}}})))))
