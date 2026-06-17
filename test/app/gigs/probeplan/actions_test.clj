(ns app.gigs.probeplan.actions-test
  (:require
   [app.gigs.domain :as gig.domain]
   [app.gigs.probeplan.actions :as actions]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr [path]
  (name (last path)))

(defn seed-gig! [conn gig-id]
  @(d/transact conn [(gig.domain/gig->db {:gig/gig-id    gig-id
                                          :gig/title     "Probe"
                                          :gig/status    :gig.status/confirmed
                                          :gig/gig-type  :gig.type/probe
                                          :gig/date      (t/date "2026-05-01")
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
  {:db (d/db conn)
   :tr tr})

(defn params [m]
  {:gig-probeplan m})

(defn edited-effect [gig-id]
  {:on-success [[:app.gigs/trigger-gig-edited gig-id :probeplan]]})

(defn tx-effect [gig-id tx-data]
  [:db/transact tx-data (edited-effect gig-id)])

(deftest toggle-probeplan-song-action-test
  (testing "persists an added song immediately"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-add")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (is (= [(tx-effect
               gig-id
               [{:probeplan/gig     [:gig/gig-id gig-id]
                 :db/id             "probeplan"
                 :probeplan/version :probeplan.version/classic}
                [:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-id] 0 :probeplan.emphasis/none]]])]
             (actions/toggle-probeplan-song-action
              (state conn)
              (params {:gig-id (str gig-id) :song-id (str song-id) :selected true}))))))

  (testing "persists a removed song immediately"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-remove")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (seed-probeplan! conn gig-id [[song-id 0 :probeplan.emphasis/none]])
      (is (= [(tx-effect
               gig-id
               [{:probeplan/gig     [:gig/gig-id gig-id]
                 :db/id             "probeplan"
                 :probeplan/version :probeplan.version/classic}
                [:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-id] 0 :probeplan.emphasis/none]]])]
             (actions/toggle-probeplan-song-action
              (state conn)
              (params {:gig-id (str gig-id) :song-id (str song-id) :selected false}))))))

  (testing "uses stored songs instead of a filtered or stale client signal"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-stale-signal")
          gig-id         (random-uuid)
          existing-song  (random-uuid)
          added-song     (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn existing-song "Existing")
      (seed-song! conn added-song "Added")
      (seed-probeplan! conn gig-id [[existing-song 0 :probeplan.emphasis/none]])
      (let [[effect] (actions/toggle-probeplan-song-action
                      (state conn)
                      (params {:gig-id   (str gig-id)
                               :song-id  (str added-song)
                               :selected true
                               :songs    []}))]
        (is (= :db/transact (first effect)))
        (is (some #{[:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id added-song] 1 :probeplan.emphasis/none]]}
                  (second effect)))
        (is (not-any? #{[:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id existing-song] 0 :probeplan.emphasis/none]]}
                      (second effect)))))))

(deftest toggle-probeplan-intensive-action-test
  (testing "persists intensive toggle immediately"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-intensive")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (seed-probeplan! conn gig-id [[song-id 0 :probeplan.emphasis/none]])
      (is (= [(tx-effect
               gig-id
               [{:probeplan/gig     [:gig/gig-id gig-id]
                 :db/id             "probeplan"
                 :probeplan/version :probeplan.version/classic}
                [:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-id] 0 :probeplan.emphasis/intensive]]
                [:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-id] 0 :probeplan.emphasis/none]]])]
             (actions/toggle-probeplan-intensive-action
              (state conn)
              (params {:gig-id (str gig-id) :song-id (str song-id)}))))))

  (testing "allows more than two intensive songs"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-intensive-unlimited")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)
          song-c         (random-uuid)]
      (seed-gig! conn gig-id)
      (doseq [[id title] [[song-a "Alpha"] [song-b "Beta"] [song-c "Gamma"]]]
        (seed-song! conn id title))
      (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/intensive]
                                    [song-b 1 :probeplan.emphasis/intensive]
                                    [song-c 2 :probeplan.emphasis/none]])
      (let [[effect] (actions/toggle-probeplan-intensive-action
                      (state conn)
                      (params {:gig-id (str gig-id) :song-id (str song-c)}))]
        (is (= :db/transact (first effect)))
        (is (some #{[:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-c] 2 :probeplan.emphasis/intensive]]}
                  (second effect))))))

  (testing "uses stored songs instead of a filtered or stale client signal"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-intensive-stale-signal")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (seed-probeplan! conn gig-id [[song-id 0 :probeplan.emphasis/none]])
      (let [[effect] (actions/toggle-probeplan-intensive-action
                      (state conn)
                      (params {:gig-id  (str gig-id)
                               :song-id (str song-id)
                               :songs   []}))]
        (is (= :db/transact (first effect)))
        (is (some #{[:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-id] 0 :probeplan.emphasis/intensive]]}
                  (second effect)))))))

(deftest toggle-probeplan-song-no-limit-test
  (testing "allows more than five songs"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-song-unlimited")
          gig-id         (random-uuid)
          song-ids       (repeatedly 6 random-uuid)]
      (seed-gig! conn gig-id)
      (doseq [[idx song-id] (map-indexed vector song-ids)]
        (seed-song! conn song-id (str "Song " idx)))
      (seed-probeplan! conn gig-id (map-indexed (fn [idx song-id]
                                                  [song-id idx :probeplan.emphasis/none])
                                                (take 5 song-ids)))
      (let [[effect] (actions/toggle-probeplan-song-action
                      (state conn)
                      (params {:gig-id (str gig-id) :song-id (str (last song-ids)) :selected true}))]
        (is (= :db/transact (first effect)))
        (is (some #{[:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id (last song-ids)] 5 :probeplan.emphasis/none]]}
                  (second effect)))))))

(deftest set-repertoire-filter-action-test
  (is (= [[:app.datastar/assoc-state [:gig-probeplan :repertoire-filter] "old"]]
         (actions/set-repertoire-filter-action
          {}
          (params {:repertoire-filter "old"}))))
  (is (= [[:app.datastar/assoc-state [:gig-probeplan :repertoire-filter] "current"]]
         (actions/set-repertoire-filter-action
          {}
          (params {:repertoire-filter "nonsense"})))))

(deftest reorder-probeplan-songs-action-test
  (let [{:keys [conn]} (tc/new-system "probeplan-reorder")
        gig-id         (random-uuid)
        song-a         (random-uuid)
        song-b         (random-uuid)
        song-c         (random-uuid)]
    (seed-gig! conn gig-id)
    (doseq [[id title] [[song-a "Alpha"] [song-b "Beta"] [song-c "Gamma"]]]
      (seed-song! conn id title))
    (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/none]
                                  [song-b 1 :probeplan.emphasis/intensive]
                                  [song-c 2 :probeplan.emphasis/none]])
    (let [[effect] (actions/reorder-probeplan-songs-action
                    (state conn)
                    (params {:gig-id (str gig-id)
                             :order  [(str song-c) (str song-a) (str song-b)]}))]
      (is (= :db/transact (first effect)))
      (is (some #{[:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-c] 0 :probeplan.emphasis/none]]}
                (second effect))))))

(deftest selected-songs-for-page-test
  (testing "uses stored probeplan songs as the source of truth"
    (let [{:keys [conn]} (tc/new-system "probeplan-songs")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-a "Alpha")
      (seed-song! conn song-b "Beta")
      (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/none]])
      (is (= [{:song/song-id song-a
               :song/title   "Alpha"
               :song/active? true
               :position     0
               :emphasis     :probeplan.emphasis/none}]
             (mapv #(select-keys % [:song/song-id :song/title :song/active? :position :emphasis])
                   (actions/selected-songs-for-page
                    (d/db conn)
                    gig-id)))))))
