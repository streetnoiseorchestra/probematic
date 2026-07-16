(ns app.humanize-test
  (:require
   [app.humanize :as sut]
   [app.i18n :as i18n]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(deftest one-hour-ago-uses-correct-article
  (testing "A timestamp exactly one hour in the past is described naturally."
    (let [now (t/date-time "2026-07-10T12:00")
          tr  (i18n/tr-with (i18n/read-langs) [:en])]
      (is (= "an hour ago"
             (i18n/resolve-translations
              tr
              (sut/from (t/<< now (t/new-duration 1 :hours))
                        :now-t now)))))))
