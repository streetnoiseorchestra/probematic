(ns app.ui2-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(def missing-helper ::missing-helper)

(defn helper [sym]
  (or (requiring-resolve sym) missing-helper))

(defn call-helper [sym & args]
  (let [f (helper sym)]
    (is (not= missing-helper f) (str sym " should exist"))
    (when-not (= missing-helper f)
      (apply @f args))))

(deftest format-date-uses-request-locale
  (let [date (t/date "2026-05-20")]
    (testing "German request locale"
      (is (= "Mittwoch, 20. Mai 2026"
             (call-helper 'app.ui2/format-date {:current-locale :de} :with-weekday date))))
    (testing "English request locale"
      (is (= "Wednesday, May 20, 2026"
             (call-helper 'app.ui2/format-date {:current-locale :en} :with-weekday date))))
    (testing "nil request locale defaults to English"
      (is (= "Wednesday, May 20, 2026"
             (call-helper 'app.ui2/format-date {:current-locale nil} :with-weekday date))))))

(deftest format-date-supports-shared-display-styles
  (let [date (t/date "2026-05-20")]
    (is (= "20.05"
           (call-helper 'app.ui2/format-date {:current-locale :de} :month-day date)))
    (is (= "05.20"
           (call-helper 'app.ui2/format-date {:current-locale :en} :month-day date)))
    (is (= "20.05"
           (call-helper 'app.ui2/format-date {:current-locale "en-GB"} :month-day date)))
    (is (= "20.05.26"
           (call-helper 'app.ui2/format-date {:current-locale :de} :short date)))
    (is (= "5/20/26"
           (call-helper 'app.ui2/format-date {:current-locale :en} :short date)))
    (is (= "20. Mai 2026"
           (call-helper 'app.ui2/format-date {:current-locale :de} :long date)))
    (is (= "May 20, 2026"
           (call-helper 'app.ui2/format-date {:current-locale :en} :long date)))
    (is (nil? (call-helper 'app.ui2/format-date {:current-locale :en} :long nil)))))

(deftest format-date-time-uses-request-locale
  (let [date-time       (t/date-time "2026-05-20T09:30:00")
        english-default (call-helper 'app.ui2/format-date-time {:current-locale nil} :medium date-time)]
    (is (= "20.05.2026, 09:30"
           (call-helper 'app.ui2/format-date-time {:current-locale :de} :medium date-time)))
    (is (str/includes? english-default "May 20, 2026"))
    (is (str/includes? english-default "9:30"))))

(deftest date-range-display-renders-localized-time-elements
  (let [html (html/->str
              (call-helper 'app.ui2/date-range-display
                           {:current-locale :en}
                           :month-day
                           (t/date "2026-05-20")
                           (t/date "2026-05-21")))]
    (is (str/includes? html "<time datetime=\"2026-05-20\">05.20</time>"))
    (is (str/includes? html " – "))
    (is (str/includes? html "<time datetime=\"2026-05-21\">05.21</time>"))))
