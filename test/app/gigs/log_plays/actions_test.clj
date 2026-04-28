(ns app.gigs.log-plays.actions-test
  (:require
   [app.gigs.domain :as gig.domain]
   [app.gigs.log-plays.actions :as actions]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn seed-gig! [conn gig-id]
  @(d/transact conn [(gig.domain/gig->db {:gig/gig-id    gig-id
                                          :gig/title     "Probe"
                                          :gig/status    :gig.status/confirmed
                                          :gig/gig-type  :gig.type/probe
                                          :gig/date      (t/date "2026-05-01")
                                          :gig/location  "Room"
                                          :gig/call-time (t/time "18:00")})]))

(defn seed-song! [conn song-id title]
  @(d/transact conn [{:song/song-id song-id
                      :song/title   title
                      :song/active? true}]))

(defn seed-play! [conn gig-id song-id play-id rating emphasis]
  @(d/transact conn [{:played/gig      [:gig/gig-id gig-id]
                      :played/song     [:song/song-id song-id]
                      :played/rating   rating
                      :played/gig+song (pr-str [gig-id song-id])
                      :played/play-id  play-id
                      :played/emphasis emphasis}]))

(defn state [conn]
  {:db (d/db conn)})

(defn params [m]
  {:gig-log-plays m})

(defn edited-effect [gig-id]
  {:on-success [[:app.gigs/recalc-play-stats]
                [:app.gigs/trigger-gig-edited gig-id :plays]]})

(defn tx-effect [gig-id tx-data]
  [:db/transact tx-data (edited-effect gig-id)])

(deftest update-rating-action-test
  (testing "creates a play immediately"
    (let [{:keys [conn]} (tc/new-system "log-plays-create")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (is (= [(tx-effect
               gig-id
               [{:played/gig      [:gig/gig-id gig-id]
                 :played/song     [:song/song-id song-id]
                 :played/rating   :play-rating/good
                 :played/gig+song (pr-str [gig-id song-id])
                 :played/emphasis :play-emphasis/durch
                 :played/play-id  :db/gen-uuid}])]
             (actions/update-rating-action
              (state conn)
              (params {:gig-id   (str gig-id)
                       :song-id  (str song-id)
                       :rating   "play-rating/good"
                       :emphasis "play-emphasis/durch"}))))))

  (testing "updates an existing play by preserving its play id"
    (let [{:keys [conn]} (tc/new-system "log-plays-update")
          gig-id         (random-uuid)
          song-id        (random-uuid)
          play-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (seed-play! conn gig-id song-id play-id :play-rating/ok :play-emphasis/durch)
      (is (= [(tx-effect
               gig-id
               [{:played/gig      [:gig/gig-id gig-id]
                 :played/song     [:song/song-id song-id]
                 :played/rating   :play-rating/bad
                 :played/gig+song (pr-str [gig-id song-id])
                 :played/emphasis :play-emphasis/intensiv
                 :played/play-id  play-id}])]
             (actions/update-rating-action
              (state conn)
              (params {:gig-id   (str gig-id)
                       :song-id  (str song-id)
                       :rating   "play-rating/bad"
                       :emphasis "play-emphasis/intensiv"}))))))

  (testing "not played clears intensive emphasis"
    (let [{:keys [conn]} (tc/new-system "log-plays-not-played")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (is (= [(tx-effect
               gig-id
               [{:played/gig      [:gig/gig-id gig-id]
                 :played/song     [:song/song-id song-id]
                 :played/rating   :play-rating/not-played
                 :played/gig+song (pr-str [gig-id song-id])
                 :played/emphasis :play-emphasis/durch
                 :played/play-id  :db/gen-uuid}])]
             (actions/update-rating-action
              (state conn)
              (params {:gig-id   (str gig-id)
                       :song-id  (str song-id)
                       :rating   "play-rating/not-played"
                       :emphasis "play-emphasis/intensiv"})))))))

(deftest toggle-intensive-action-test
  (let [{:keys [conn]} (tc/new-system "log-plays-intensive")
        gig-id         (random-uuid)
        song-id        (random-uuid)]
    (seed-gig! conn gig-id)
    (seed-song! conn song-id "Alpha")
    (is (= [(tx-effect
             gig-id
             [{:played/gig      [:gig/gig-id gig-id]
               :played/song     [:song/song-id song-id]
               :played/rating   :play-rating/good
               :played/gig+song (pr-str [gig-id song-id])
               :played/emphasis :play-emphasis/intensiv
               :played/play-id  :db/gen-uuid}])]
           (actions/toggle-intensive-action
            (state conn)
            (params {:gig-id   (str gig-id)
                     :song-id  (str song-id)
                     :rating   "play-rating/good"
                     :emphasis "play-emphasis/durch"}))))))

(deftest set-repertoire-filter-action-test
  (is (= [[:app.datastar/assoc-state [:gig-log-plays :repertoire-filter] "old"]]
         (actions/set-repertoire-filter-action
          {}
          (params {:repertoire-filter "old"}))))
  (is (= [[:app.datastar/assoc-state [:gig-log-plays :repertoire-filter] "current"]]
         (actions/set-repertoire-filter-action
          {}
          (params {:repertoire-filter "wat"})))))
