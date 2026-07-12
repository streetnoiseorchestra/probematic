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

(deftest home-content-renders-the-approved-dashboard-regions
  (let [home-content (ns-resolve 'app.dashboard.index.views 'home-content)]
    (is (some? home-content) "Dashboard home content should exist")
    (when home-content
      (let [rendered (html/->str tr
                                 (home-content
                                  {:tr tr :system {:env {}} :current-locale :en}
                                  {:member/name "Ada Lovelace" :member/nick "Ada"}
                                  {:insurance-todos []
                                   :ledger nil
                                   :unanswered []
                                   :upcoming []}))]
        (is (str/includes? rendered "class=\"dashboard-home\""))
        (is (str/includes? rendered "class=\"personal\""))
        (is (str/includes? rendered "class=\"focus\""))
        (is (str/includes? rendered "class=\"activity\""))
        (is (= ["/gigs/create" "/polls/new"]
               (mapv second
                     (re-seq #"class=\"[^\"]*dashboard-home-quick-action[^\"]*\" href=\"([^\"]+)\""
                             rendered))))
        (is (= 3 (count (re-seq #"class=\"activity-entry\"" rendered))))
        (is (str/includes? rendered "disabled"))
        (is (not (str/includes? rendered "sno-page-surface")))
        (is (not (str/includes? rendered "sno-page-toolbar")))))))

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
