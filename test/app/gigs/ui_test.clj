(ns app.gigs.ui-test
  (:require
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(defn- render-gig-row [gig]
  (html/->str
   (gigs.ui/gig-row {:current-locale :en}
                    (merge {:gig/gig-id "gig-1"
                            :gig/title "Skappanabanda, Graz"
                            :gig/status :gig.status/confirmed
                            :gig/date (t/date "2026-06-04")
                            :gig/end-date (t/date "2026-06-07")}
                           gig))))

(deftest gig-row-uses-compact-date-range
  (let [html (render-gig-row {:gig/location "Proberaum"})]
    (is (str/includes? html "Thu 04"))
    (is (str/includes? html "Sun 07"))
    (is (str/includes? html "Jun 2026"))
    (is (not (str/includes? html "Thursday, June 4, 2026")))))

(deftest gig-row-hides-location-icon-when-location-is-blank
  (testing "blank location"
    (let [html (render-gig-row {:gig/location "   "})]
      (is (not (str/includes? html "location-dot")))
      (is (not (str/includes? html "gigs-row-location")))))
  (testing "missing location"
    (let [html (render-gig-row {})]
      (is (not (str/includes? html "location-dot")))
      (is (not (str/includes? html "gigs-row-location")))))
  (testing "present location"
    (let [html (render-gig-row {:gig/location "Proberaum"})]
      (is (str/includes? html "location-dot"))
      (is (str/includes? html "Proberaum")))))
