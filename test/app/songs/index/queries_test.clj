(ns app.songs.index.queries-test
  (:require
   [app.songs.index.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn seed-song!
  ([conn song-id title active?]
   (seed-song! conn song-id title active? nil nil))
  ([conn song-id title active? last-played total-plays]
   @(d/transact conn [(cond-> {:song/song-id song-id
                               :song/title   title
                               :song/active? active?}
                        last-played (assoc :song/last-played-on last-played)
                        total-plays (assoc :song/total-plays total-plays))])))

(deftest songs-filter-by-repertoire-state-test
  (let [{:keys [conn]} (tc/new-system "songs-index-repertoire-filter")
        active-id      (random-uuid)
        old-id         (random-uuid)]
    (seed-song! conn active-id "Watermelon Man" true)
    (seed-song! conn old-id "Old Folks" false)
    (let [db (d/db conn)]
      (testing "current shows active songs"
        (is (= ["Watermelon Man"]
               (mapv :song/title
                     (queries/songs db {:repertoire-filter "current"})))))

      (testing "old shows inactive songs"
        (is (= ["Old Folks"]
               (mapv :song/title
                     (queries/songs db {:repertoire-filter "old"})))))

      (testing "all shows both active and old songs sorted by title"
        (is (= ["Old Folks" "Watermelon Man"]
               (mapv :song/title
                     (queries/songs db {:repertoire-filter "all"}))))))))

(deftest songs-search-test
  (let [{:keys [conn]} (tc/new-system "songs-index-search")]
    (seed-song! conn (random-uuid) "Watermelon Man" true)
    (seed-song! conn (random-uuid) "Bella Ciao" true)
    (seed-song! conn (random-uuid) "Old Water" false)
    (let [db (d/db conn)]
      (testing "search is case-insensitive and trimmed"
        (is (= ["Watermelon Man"]
               (mapv :song/title
                     (queries/songs db {:repertoire-filter "current"
                                        :search            " waterMELON "})))))

      (testing "search combines with repertoire filter"
        (is (= ["Old Water"]
               (mapv :song/title
                     (queries/songs db {:repertoire-filter "old"
                                        :search            "water"}))))))))

(deftest normalize-page-state-test
  (is (= {:search ""
          :repertoire-filter "current"}
         (select-keys (queries/normalize-page-state nil)
                      [:search :repertoire-filter])))

  (is (= {:search "Water"
          :repertoire-filter "all"}
         (select-keys (queries/normalize-page-state {:search "Water"
                                                     :repertoire-filter "all"})
                      [:search :repertoire-filter])))

  (is (= "current"
         (:repertoire-filter
          (queries/normalize-page-state {:repertoire-filter "broken"})))))
