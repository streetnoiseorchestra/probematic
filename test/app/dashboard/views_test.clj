(ns app.dashboard.views-test
  (:require
   [app.dashboard.index.views :as views]
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(deftest ledger-widget-renders-native-card-body
  (let [rendered (html/->str
                  (#'views/ledger-widget
                   {:tr tr :system {:env {}}}
                   {:ledger/balance 1234
                    :ledger/entries []
                    :ledger/owner   {:member/member-id
                                     #uuid "00000000-0000-0000-0000-000000000123"}}))]
    (is (str/includes? rendered "class=\"sno-card dashboard-ledger-card\""))
    (is (str/includes? rendered
                       "<div class=\"body\"><div class=\"dashboard-ledger-grid\">"))))
