(ns app.gigs.archive-actions-test
  (:require
   [app.gigs.actions :as gigs.actions]
   [app.gigs.archive.actions :as actions]
   [clojure.test :refer [deftest is]]))

(deftest set-search-phrase-action-test
  (is (= [[:app.datastar/assoc-state [:gigs-archive :search] "Wedding"]]
         (actions/set-search-phrase-action
          {}
          {:query-params {"q" "Wedding"}})))

  (is (= [[:app.datastar/assoc-state [:gigs-archive :search] ""]]
         (actions/set-search-phrase-action
          {}
          {:query-params {}}))))

(deftest archive-action-registration-test
  (is (contains? gigs.actions/actions ::actions/set-search-phrase)))
