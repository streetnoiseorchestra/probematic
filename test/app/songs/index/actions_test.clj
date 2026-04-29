(ns app.songs.index.actions-test
  (:require
   [app.songs.index.actions :as actions]
   [clojure.test :refer [deftest is]]))

(deftest set-search-phrase-action-test
  (is (= [[:app.datastar/assoc-state [:songs-index :search] "Watermelon"]]
         (actions/set-search-phrase-action
          {}
          {:songs-index {:search "Watermelon"}})))

  (is (= [[:app.datastar/assoc-state [:songs-index :search] ""]]
         (actions/set-search-phrase-action
          {}
          {:songs-index {:search nil}}))))

(deftest set-repertoire-filter-action-test
  (is (= [[:app.datastar/assoc-state [:songs-index :repertoire-filter] "old"]]
         (actions/set-repertoire-filter-action
          {}
          {:songs-index {:repertoire-filter "old"}})))

  (is (= [[:app.datastar/assoc-state [:songs-index :repertoire-filter] "current"]]
         (actions/set-repertoire-filter-action
          {}
          {:songs-index {:repertoire-filter "nonsense"}}))))
