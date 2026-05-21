(ns app.gigs.detail.views-test
  (:require
   [app.gigs.detail.views :as views]
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [tick.core :as t]))

(deftest gig-date-uses-compact-date-range
  (let [html (html/->str
              (#'views/gig-date
               {:current-locale :en}
               {:gig/date     (t/date "2026-06-04")
                :gig/end-date (t/date "2026-06-07")}))]
    (is (str/includes? html "Thu 04"))
    (is (str/includes? html "Sun 07"))
    (is (str/includes? html "Jun 2026"))
    (is (not (str/includes? html "Thursday, June 4, 2026")))))
