(ns app.gigs.setlist.actions-test
  (:require
   [app.gigs.domain :as gig.domain]
   [app.gigs.setlist.actions :as actions]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr [path]
  (name (last path)))

(defn seed-gig! [conn gig-id]
  @(d/transact conn [(gig.domain/gig->db {:gig/gig-id    gig-id
                                          :gig/title     "Gig"
                                          :gig/status    :gig.status/confirmed
                                          :gig/gig-type  :gig.type/gig
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

(defn seed-setlist! [conn gig-id songs]
  @(d/transact conn [(into {:setlist/gig     [:gig/gig-id gig-id]
                            :setlist/version :setlist.version/v1}
                           {:setlist.v1/ordered-songs
                            (mapv (fn [[song-id position]]
                                    [[:song/song-id song-id] position])
                                  songs)})]))

(defn state [conn]
  {:db (d/db conn)
   :tr tr})

(defn params [m]
  {:gig-setlist m})

(defn edited-effect [gig-id]
  {:on-success [[:app.gigs/trigger-gig-edited gig-id :setlist]]})

(defn tx-effect [gig-id tx-data]
  [:db/transact tx-data (edited-effect gig-id)])

(deftest toggle-setlist-song-action-test
  (testing "persists an added song immediately"
    (let [{:keys [conn]} (tc/new-system "setlist-toggle-add")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (is (= [(tx-effect
               gig-id
               [{:setlist/gig     [:gig/gig-id gig-id]
                 :db/id           "setlist"
                 :setlist/version :setlist.version/v1}
                [:db/add "setlist" :setlist.v1/ordered-songs [[:song/song-id song-id] 0]]])]
             (actions/toggle-setlist-song-action
              (state conn)
              (params {:gig-id (str gig-id) :song-id (str song-id) :selected true}))))))

  (testing "persists a removed song immediately"
    (let [{:keys [conn]} (tc/new-system "setlist-toggle-remove")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (seed-setlist! conn gig-id [[song-id 0]])
      (is (= [(tx-effect
               gig-id
               [{:setlist/gig     [:gig/gig-id gig-id]
                 :db/id           "setlist"
                 :setlist/version :setlist.version/v1}
                [:db/retract "setlist" :setlist.v1/ordered-songs [[:song/song-id song-id] 0]]])]
             (actions/toggle-setlist-song-action
              (state conn)
              (params {:gig-id (str gig-id) :song-id (str song-id) :selected false}))))))

  (testing "uses stored songs instead of a filtered or stale client signal"
    (let [{:keys [conn]} (tc/new-system "setlist-toggle-stale-signal")
          gig-id         (random-uuid)
          existing-song  (random-uuid)
          added-song     (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn existing-song "Existing")
      (seed-song! conn added-song "Added")
      (seed-setlist! conn gig-id [[existing-song 0]])
      (let [[effect] (actions/toggle-setlist-song-action
                      (state conn)
                      (params {:gig-id   (str gig-id)
                               :song-id  (str added-song)
                               :selected true
                               :songs    []}))]
        (is (= :db/transact (first effect)))
        (is (some #{[:db/add "setlist" :setlist.v1/ordered-songs [[:song/song-id added-song] 1]]}
                  (second effect)))
        (is (not-any? #{[:db/retract "setlist" :setlist.v1/ordered-songs [[:song/song-id existing-song] 0]]}
                      (second effect)))))))

(deftest set-repertoire-filter-action-test
  (is (= [[:app.datastar/assoc-state [:gig-setlist :repertoire-filter] "old"]]
         (actions/set-repertoire-filter-action
          {}
          (params {:repertoire-filter "old"}))))
  (is (= [[:app.datastar/assoc-state [:gig-setlist :repertoire-filter] "current"]]
         (actions/set-repertoire-filter-action
          {}
          (params {:repertoire-filter "nonsense"})))))

(deftest reorder-setlist-songs-action-test
  (let [{:keys [conn]} (tc/new-system "setlist-reorder")
        gig-id         (random-uuid)
        song-a         (random-uuid)
        song-b         (random-uuid)
        song-c         (random-uuid)]
    (seed-gig! conn gig-id)
    (doseq [[id title] [[song-a "Alpha"] [song-b "Beta"] [song-c "Gamma"]]]
      (seed-song! conn id title))
    (seed-setlist! conn gig-id [[song-a 0]
                                [song-b 1]
                                [song-c 2]])
    (let [[effect] (actions/reorder-setlist-songs-action
                    (state conn)
                    (params {:gig-id (str gig-id)
                             :order  [(str song-c) (str song-a) (str song-b)]}))]
      (is (= :db/transact (first effect)))
      (is (some #{[:db/add "setlist" :setlist.v1/ordered-songs [[:song/song-id song-c] 0]]}
                (second effect))))))

(deftest selected-songs-for-page-test
  (testing "uses stored setlist songs as the source of truth"
    (let [{:keys [conn]} (tc/new-system "setlist-page-songs")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-a "Alpha")
      (seed-song! conn song-b "Beta")
      (seed-setlist! conn gig-id [[song-a 0]])
      (is (= [{:song/song-id song-a
               :song/title   "Alpha"
               :song/active? true
               :position     0}]
             (mapv #(select-keys % [:song/song-id :song/title :song/active? :position])
                   (actions/selected-songs-for-page
                    (d/db conn)
                    gig-id)))))))
