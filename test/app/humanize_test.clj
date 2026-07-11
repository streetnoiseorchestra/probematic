(ns app.humanize-test
  (:require
   [app.humanize :as sut]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time LocalDateTime]))

(deftest one-hour-ago-uses-correct-article
  (testing "A timestamp exactly one hour in the past is described naturally."
    (let [now (LocalDateTime/of 2026 7 10 12 0)]
      (is (= "an hour ago"
             (sut/from (.minusHours now 1) :now-t now))))))
