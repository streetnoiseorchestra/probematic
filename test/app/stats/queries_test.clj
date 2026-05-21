(ns app.stats.queries-test
  (:require
   [app.stats.queries :as stats]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def from #inst "2099-01-01T00:00:00.000Z")
(def through-all #inst "2099-02-01T00:00:00.000Z")
(def through-first-gig #inst "2099-01-12T00:00:00.000Z")

(defn- member [member-id name active?]
  {:member/member-id member-id
   :member/name      name
   :member/nick      (str name "Nick")
   :member/active?   active?})

(defn- gig [gig-id title date status gig-type]
  {:gig/gig-id   gig-id
   :gig/title    title
   :gig/date     date
   :gig/status   status
   :gig/gig-type gig-type})

(defn- attendance [gig-id member-id plan]
  {:attendance/gig    [:gig/gig-id gig-id]
   :attendance/member [:member/member-id member-id]
   :attendance/plan   plan})

(defn- play [play-id gig-id song-id rating]
  {:played/play-id  play-id
   :played/gig      [:gig/gig-id gig-id]
   :played/song     [:song/song-id song-id]
   :played/rating   rating
   :played/gig+song (str gig-id ":" song-id ":" play-id)})

(defn- seed-stats-data! [conn]
  (let [alice-id     (random-uuid)
        bob-id       (random-uuid)
        charlie-id   (random-uuid)
        dana-id      (random-uuid)
        gig-one-id   (random-uuid)
        probe-one-id (random-uuid)
        gig-two-id   (random-uuid)
        draft-gig-id (random-uuid)
        song-id      (random-uuid)]
    @(d/transact
      conn
      [(member alice-id "Alice" true)
       (member bob-id "Bob" true)
       (member charlie-id "Charlie" true)
       (member dana-id "Dana" false)
       {:song/song-id song-id
        :song/title   "Stats Song"
        :song/active? true}
       (gig gig-one-id "Gig One" #inst "2099-01-10T00:00:00.000Z" :gig.status/confirmed :gig.type/gig)
       (gig probe-one-id "Probe One" #inst "2099-01-15T00:00:00.000Z" :gig.status/confirmed :gig.type/probe)
       (gig gig-two-id "Gig Two" #inst "2099-01-20T00:00:00.000Z" :gig.status/confirmed :gig.type/gig)
       (gig draft-gig-id "Draft Gig" #inst "2099-01-25T00:00:00.000Z" :gig.status/unconfirmed :gig.type/gig)])
    @(d/transact
      conn
      [(attendance gig-one-id alice-id :plan/definitely)
       (attendance gig-one-id bob-id :plan/definitely)
       (attendance probe-one-id alice-id :plan/definitely)
       (attendance probe-one-id bob-id :plan/definitely)
       (attendance probe-one-id charlie-id :plan/definitely)
       (attendance gig-two-id alice-id :plan/definitely)
       (attendance gig-two-id charlie-id :plan/definitely-not)
       (attendance draft-gig-id alice-id :plan/definitely)
       (attendance draft-gig-id bob-id :plan/definitely)
       (play (random-uuid) gig-one-id song-id :play-rating/good)
       (play (random-uuid) gig-one-id song-id :play-rating/not-played)
       (play (random-uuid) probe-one-id song-id :play-rating/ok)])
    {:alice-id alice-id
     :bob-id bob-id
     :charlie-id charlie-id
     :dana-id dana-id
     :gig-one-id gig-one-id
     :probe-one-id probe-one-id
     :gig-two-id gig-two-id
     :draft-gig-id draft-gig-id
     :song-id song-id}))

(defn- histogram-map [histogram]
  (into (sorted-map) (map (juxt :x :y)) histogram))

(defn- member-summary [{:keys [member gigs-attended probes-attended gig-rate probe-rate last-seen gig-title]}]
  {:name            (:member/name member)
   :gigs-attended   gigs-attended
   :probes-attended probes-attended
   :gig-rate        gig-rate
   :probe-rate      probe-rate
   :last-seen       (str last-seen)
   :gig-title       gig-title})

(deftest stats-for-calculates-attendance-play-and-histogram-data
  (let [{:keys [conn]} (tc/new-system "stats-query")
        _             (seed-stats-data! conn)
        result        (stats/stats-for (d/db conn) from through-all nil)]
    (is (= {:summary         {:probe-count                   1
                              :gig-count                     2
                              :total-plays                   2
                              :attendance-rate-gigs          1/2
                              :attendance-rate-probes        1
                              :mean-attendance-gig           3/2
                              :mean-attendance-probe         3
                              :active-members-count          3
                              :most-active-gig-count         2
                              :least-active-gig-count        1
                              :most-active-probe-count       3
                              :least-active-probe-count      3}
            :members         [{:name            "Alice"
                               :gigs-attended   2
                               :probes-attended 1
                               :gig-rate        1
                               :probe-rate      1
                               :last-seen       "2099-01-20"
                               :gig-title       "Gig Two"}
                              {:name            "Bob"
                               :gigs-attended   1
                               :probes-attended 1
                               :gig-rate        1/2
                               :probe-rate      1
                               :last-seen       "2099-01-15"
                               :gig-title       "Probe One"}
                              {:name            "Charlie"
                               :gigs-attended   0
                               :probes-attended 1
                               :gig-rate        0
                               :probe-rate      1
                               :last-seen       "2099-01-15"
                               :gig-title       "Probe One"}]
            :gig-histogram   {0   1
                              10  0
                              20  0
                              30  0
                              40  0
                              50  1
                              60  0
                              70  0
                              80  0
                              90  0
                              100 1}
            :probe-histogram {0   0
                              10  0
                              20  0
                              30  0
                              40  0
                              50  0
                              60  0
                              70  0
                              80  0
                              90  0
                              100 3}}
           {:summary         (select-keys result [:probe-count
                                                  :gig-count
                                                  :total-plays
                                                  :attendance-rate-gigs
                                                  :attendance-rate-probes
                                                  :mean-attendance-gig
                                                  :mean-attendance-probe
                                                  :active-members-count
                                                  :most-active-gig-count
                                                  :least-active-gig-count
                                                  :most-active-probe-count
                                                  :least-active-probe-count])
            :members         (mapv member-summary (:per-member-stats result))
            :gig-histogram   (histogram-map (:gig-histogram result))
            :probe-histogram (histogram-map (:probe-histogram result))}))))

(deftest stats-for-sorts-member-stats-by-requested-field
  (let [{:keys [conn]} (tc/new-system "stats-query-sort")
        _             (seed-stats-data! conn)
        result        (stats/stats-for (d/db conn) from through-all [{:field :gigs-attended :order :asc}])]
    (is (= ["Charlie" "Bob" "Alice"]
           (mapv (comp :member/name :member) (:per-member-stats result))))))

(deftest stats-for-handles-ranges-without-probes
  (let [{:keys [conn]} (tc/new-system "stats-query-no-probes")
        _             (seed-stats-data! conn)
        result        (stats/stats-for (d/db conn) from through-first-gig nil)]
    (is (= {:summary {:probe-count             0
                      :gig-count               1
                      :attendance-rate-gigs    2/3
                      :attendance-rate-probes  0
                      :mean-attendance-gig     2
                      :mean-attendance-probe   nil
                      :most-active-probe-count nil
                      :least-active-probe-count nil}
            :members [{:name            "Alice"
                       :gigs-attended   1
                       :probes-attended 0
                       :gig-rate        1
                       :probe-rate      0
                       :last-seen       "2099-01-10"
                       :gig-title       "Gig One"}
                      {:name            "Bob"
                       :gigs-attended   1
                       :probes-attended 0
                       :gig-rate        1
                       :probe-rate      0
                       :last-seen       "2099-01-10"
                       :gig-title       "Gig One"}]}
           {:summary (select-keys result [:probe-count
                                          :gig-count
                                          :attendance-rate-gigs
                                          :attendance-rate-probes
                                          :mean-attendance-gig
                                          :mean-attendance-probe
                                          :most-active-probe-count
                                          :least-active-probe-count])
            :members (mapv member-summary (:per-member-stats result))}))))

(deftest stats-for-handles-empty-ranges
  (let [{:keys [conn]} (tc/new-system "stats-query-empty")
        _             (seed-stats-data! conn)
        result        (stats/stats-for (d/db conn) #inst "2099-03-01T00:00:00.000Z" #inst "2099-03-31T00:00:00.000Z" nil)]
    (is (= {:summary       {:probe-count              0
                            :gig-count                0
                            :total-plays              0
                            :attendance-rate-gigs     0
                            :attendance-rate-probes   0
                            :mean-attendance-gig      nil
                            :mean-attendance-probe    nil
                            :active-members-count     nil
                            :most-active-gig-count    nil
                            :least-active-gig-count   nil
                            :most-active-probe-count  nil
                            :least-active-probe-count nil}
            :members       []
            :gig-histogram {0   0
                            10  0
                            20  0
                            30  0
                            40  0
                            50  0
                            60  0
                            70  0
                            80  0
                            90  0
                            100 0}}
           {:summary       (select-keys result [:probe-count
                                                :gig-count
                                                :total-plays
                                                :attendance-rate-gigs
                                                :attendance-rate-probes
                                                :mean-attendance-gig
                                                :mean-attendance-probe
                                                :active-members-count
                                                :most-active-gig-count
                                                :least-active-gig-count
                                                :most-active-probe-count
                                                :least-active-probe-count])
            :members       (mapv member-summary (:per-member-stats result))
            :gig-histogram (histogram-map (:gig-histogram result))}))))
