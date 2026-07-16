(ns app.dashboard.calendar.views-test
  (:require
   [app.dashboard.calendar.views :as views]
   [app.html :as html]
   [app.i18n :as i18n]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn tr [path]
  (name (last path)))

(deftest calendar-page-renders-iframe-in-native-card-body
  (let [rendered (html/->str (i18n/resolve-translations tr (views/page {})))]
    (is (str/includes? rendered "class=\"sno-card\""))
    (is (str/includes? rendered
                       "<div class=\"body\"><iframe class=\"dashboard-calendar-frame\""))
    (is (str/includes? rendered
                       "src=\"https://data.streetnoise.at/apps/calendar/embed/yRFYYPnQkasfa8nk/listMonth/now\""))
    (is (str/includes? rendered "width=\"100%\""))
    (is (str/includes? rendered "height=\"1000\""))))
