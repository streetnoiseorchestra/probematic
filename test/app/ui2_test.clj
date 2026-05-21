(ns app.ui2-test
  (:require
   [app.html :as html]
   [app.ui2 :as ui2]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(deftest format-compact-date-uses-request-locale
  (let [date (t/date "2026-05-20")]
    (testing "German request locale"
      (is (= "Mi. 20 Mai 2026"
             (ui2/format-date {:current-locale :de} :compact-with-weekday date))))
    (testing "English request locale"
      (is (= "Wed 20 May 2026"
             (ui2/format-date {:current-locale :en} :compact-with-weekday date))))
    (testing "nil request locale defaults to English"
      (is (= "Wed 20 May 2026"
             (ui2/format-date {:current-locale nil} :compact-with-weekday date))))))

(deftest format-date-supports-shared-display-styles
  (let [date (t/date "2026-05-20")]
    (is (= "20.05"
           (ui2/format-date {:current-locale :de} :month-day date)))
    (is (= "05.20"
           (ui2/format-date {:current-locale :en} :month-day date)))
    (is (= "20.05"
           (ui2/format-date {:current-locale "en-GB"} :month-day date)))
    (is (= "20.05.26"
           (ui2/format-date {:current-locale :de} :short date)))
    (is (= "5/20/26"
           (ui2/format-date {:current-locale :en} :short date)))
    (is (= "20. Mai 2026"
           (ui2/format-date {:current-locale :de} :long date)))
    (is (= "May 20, 2026"
           (ui2/format-date {:current-locale :en} :long date)))
    (is (nil? (ui2/format-date {:current-locale :en} :long nil)))))

(deftest format-date-range-supports-compact-weekday-style
  (let [start (t/date "2026-06-04")
        end   (t/date "2026-06-07")]
    (is (= "Thu 04 Jun 2026"
           (ui2/format-date {:current-locale :en} :compact-with-weekday start)))
    (is (= "Do. 04 Juni 2026"
           (ui2/format-date {:current-locale :de} :compact-with-weekday start)))
    (is (= "Thu 04–Sun 07 Jun 2026"
           (ui2/format-date-range {:current-locale :en} :compact-with-weekday start end)))
    (is (= "Do. 04–So. 07 Juni 2026"
           (ui2/format-date-range {:current-locale :de} :compact-with-weekday start end)))))

(deftest format-date-time-uses-request-locale
  (let [date-time       (t/date-time "2026-05-20T09:30:00")
        english-default (ui2/format-date-time {:current-locale nil} :medium date-time)]
    (is (= "20.05.2026, 09:30"
           (ui2/format-date-time {:current-locale :de} :medium date-time)))
    (is (str/includes? english-default "May 20, 2026"))
    (is (str/includes? english-default "9:30"))))

(deftest date-range-display-renders-localized-time-elements
  (let [html (html/->str
              (ui2/date-range-display
               {:current-locale :en}
               :month-day
               (t/date "2026-05-20")
               (t/date "2026-05-21")))]
    (is (str/includes? html "<time datetime=\"2026-05-20\">05.20</time>"))
    (is (str/includes? html " – "))
    (is (str/includes? html "<time datetime=\"2026-05-21\">05.21</time>"))))
