(ns app.gigs.probeplan.actions-test
  (:require
   [app.gigs.domain :as gig.domain]
   [app.gigs.probeplan.actions :as actions]
   [app.probeplan.domain :as probeplan.domain]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr [path]
  (case path
    [:error/probeplan-too-many-songs] "Choose at most 5 songs."
    [:error/probeplan-too-many-intensive] "Mark at most 2 songs as intensive."
    [:error/probeplan-empty] "Choose at least one song."
    (name (last path))))

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

(defn seed-probeplan! [conn gig-id songs]
  @(d/transact conn [(into {:probeplan/gig     [:gig/gig-id gig-id]
                            :probeplan/version :probeplan.version/classic}
                           {:probeplan.classic/ordered-songs
                            (mapv (fn [[song-id position emphasis]]
                                    [[:song/song-id song-id] position emphasis])
                                  songs)})]))

(defn state [conn page-state]
  {:db         (d/db conn)
   :tr         tr
   :page-state page-state})

(defn params [m]
  {:gig-probeplan m})

(defn selected-state [songs]
  {:gig-probeplan {:songs songs}})

(defn edited-effect [gig-id]
  {:on-success [[:app.gigs/trigger-gig-edited gig-id :probeplan]]})

(deftest toggle-probeplan-song-action-test
  (testing "adds a selected song at the end"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-add")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (is (= [[:app.datastar/assoc-state
               [:gig-probeplan]
               {:songs [{:song-id (str song-id)
                         :position 0
                         :emphasis "none"}]}]]
             (actions/toggle-probeplan-song-action
              (state conn {})
              (params {:gig-id (str gig-id) :song-id (str song-id) :selected true}))))))

  (testing "removes a song and renumbers remaining rows"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-remove")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-a "Alpha")
      (seed-song! conn song-b "Beta")
      (is (= [[:app.datastar/assoc-state
               [:gig-probeplan]
               {:songs [{:song-id (str song-b)
                         :position 0
                         :emphasis "intensive"}]}]]
             (actions/toggle-probeplan-song-action
              (state conn (selected-state [{:song-id (str song-a) :position 0 :emphasis "none"}
                                           {:song-id (str song-b) :position 1 :emphasis "intensive"}]))
              (params {:gig-id (str gig-id) :song-id (str song-a) :selected false})))))))

(deftest toggle-probeplan-intensive-action-test
  (testing "toggles a song intensive"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-intensive")
          gig-id         (random-uuid)
          song-id        (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-id "Alpha")
      (is (= [[:app.datastar/assoc-state
               [:gig-probeplan]
               {:songs [{:song-id (str song-id)
                         :position 0
                         :emphasis "intensive"}]}]]
             (actions/toggle-probeplan-intensive-action
              (state conn (selected-state [{:song-id (str song-id) :position 0 :emphasis "none"}]))
              (params {:gig-id (str gig-id) :song-id (str song-id)}))))))

  (testing "prevents more than the maximum intensive songs"
    (let [{:keys [conn]} (tc/new-system "probeplan-toggle-intensive-max")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)
          song-c         (random-uuid)]
      (seed-gig! conn gig-id)
      (doseq [[id title] [[song-a "Alpha"] [song-b "Beta"] [song-c "Gamma"]]]
        (seed-song! conn id title))
      (is (= [[:app.datastar/assoc-state
               [:gig-probeplan :_error]
               {:error "Choose at most 5 songs."}]]
             (actions/toggle-probeplan-song-action
              (state conn (selected-state (mapv (fn [idx]
                                                  {:song-id (str (random-uuid))
                                                   :position idx
                                                   :emphasis "none"})
                                                (range probeplan.domain/MAX-SONGS))))
              (params {:gig-id (str gig-id) :song-id (str song-c) :selected true}))))
      (is (= [[:app.datastar/assoc-state
               [:gig-probeplan :_error]
               {:error "Mark at most 2 songs as intensive."}]]
             (actions/toggle-probeplan-intensive-action
              (state conn (selected-state [{:song-id (str song-a) :position 0 :emphasis "intensive"}
                                           {:song-id (str song-b) :position 1 :emphasis "intensive"}
                                           {:song-id (str song-c) :position 2 :emphasis "none"}]))
              (params {:gig-id (str gig-id) :song-id (str song-c)})))))))

(deftest reorder-probeplan-songs-action-test
  (let [{:keys [conn]} (tc/new-system "probeplan-reorder")
        gig-id         (random-uuid)
        song-a         (random-uuid)
        song-b         (random-uuid)
        song-c         (random-uuid)]
    (seed-gig! conn gig-id)
    (doseq [[id title] [[song-a "Alpha"] [song-b "Beta"] [song-c "Gamma"]]]
      (seed-song! conn id title))
    (is (= [[:app.datastar/assoc-state
             [:gig-probeplan]
             {:songs [{:song-id (str song-c) :position 0 :emphasis "none"}
                      {:song-id (str song-a) :position 1 :emphasis "none"}
                      {:song-id (str song-b) :position 2 :emphasis "intensive"}]}]]
           (actions/reorder-probeplan-songs-action
            (state conn (selected-state [{:song-id (str song-a) :position 0 :emphasis "none"}
                                         {:song-id (str song-b) :position 1 :emphasis "intensive"}
                                         {:song-id (str song-c) :position 2 :emphasis "none"}]))
            (params {:gig-id (str gig-id)
                     :order  [(str song-c) (str song-a) (str song-b)]}))))))

(deftest save-probeplan-action-test
  (testing "returns a reconciliation transaction and redirects to the gig"
    (let [{:keys [conn]} (tc/new-system "probeplan-save")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)
          song-c         (random-uuid)]
      (seed-gig! conn gig-id)
      (doseq [[id title] [[song-a "Alpha"] [song-b "Beta"] [song-c "Gamma"]]]
        (seed-song! conn id title))
      (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/none]
                                    [song-b 1 :probeplan.emphasis/intensive]])
      (is (= [[:db/transact
               [{:probeplan/gig [:gig/gig-id gig-id]
                 :db/id "probeplan"
                 :probeplan/version :probeplan.version/classic}
                [:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-b] 0 :probeplan.emphasis/intensive]]
                [:db/add "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-c] 1 :probeplan.emphasis/none]]
                [:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-a] 0 :probeplan.emphasis/none]]
                [:db/retract "probeplan" :probeplan.classic/ordered-songs [[:song/song-id song-b] 1 :probeplan.emphasis/intensive]]]
               (edited-effect gig-id)]
              [:app.datastar/redirect (urls/link-gig gig-id)]]
             (actions/save-probeplan-action
              (state conn (selected-state [{:song-id (str song-b) :position 0 :emphasis "intensive"}
                                           {:song-id (str song-c) :position 1 :emphasis "none"}]))
              (params {:gig-id (str gig-id)}))))))

  (testing "rejects an empty probeplan"
    (let [{:keys [conn]} (tc/new-system "probeplan-save-empty")
          gig-id         (random-uuid)]
      (seed-gig! conn gig-id)
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state
               [:gig-probeplan :_error]
               {:error "Choose at least one song."}]]
             (actions/save-probeplan-action
              (state conn (selected-state []))
              (params {:gig-id (str gig-id)})))))))

(deftest selected-songs-for-page-test
  (testing "uses page state before stored probeplan songs"
    (let [{:keys [conn]} (tc/new-system "probeplan-page-songs")
          gig-id         (random-uuid)
          song-a         (random-uuid)
          song-b         (random-uuid)]
      (seed-gig! conn gig-id)
      (seed-song! conn song-a "Alpha")
      (seed-song! conn song-b "Beta")
      (seed-probeplan! conn gig-id [[song-a 0 :probeplan.emphasis/none]])
      (is (= [{:song/song-id song-b
               :song/title "Beta"
               :song/active? true
               :position 0
               :emphasis :probeplan.emphasis/intensive}]
             (mapv #(select-keys % [:song/song-id :song/title :song/active? :position :emphasis])
                   (actions/selected-songs-for-page
                    (d/db conn)
                    {:gig-probeplan {:songs [{:song-id (str song-b) :position 0 :emphasis "intensive"}]}}
                    gig-id)))))))
