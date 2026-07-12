(ns app.ui2.page-toolbar-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn page-toolbar-alias []
  (try
    (some-> (requiring-resolve 'app.ui2.page-toolbar/PageToolbar) deref)
    (catch Throwable _
      nil)))

(defn page-toolbar-html [attrs]
  (when-let [page-toolbar (page-toolbar-alias)]
    (html/->str [page-toolbar attrs])))

(deftest page-toolbar-renders-context-actions-and-accessible-overflow
  (let [page-toolbar (page-toolbar-alias)]
    (is (some? page-toolbar) "PageToolbar alias should exist")
    (when page-toolbar
      (let [rendered
            (page-toolbar-html
             {:id "gig-toolbar"
              :aria-label "Gig controls"
              :app.ui2.page-toolbar/breadcrumb
              [:nav {:aria-label "Breadcrumb"} [:a {:href "/gigs"} "Gigs"]]
              :app.ui2.page-toolbar/mobile-back
              [:a {:href "/gigs"} "Back to gigs"]
              :app.ui2.page-toolbar/actions
              [[:a {:href "/gig/1/log-plays"} "Log Plays"]]
              :app.ui2.page-toolbar/overflow-label "More gig actions"
              :app.ui2.page-toolbar/overflow-items
              [[:wa-dropdown-item {:value "/gig/1/edit"} "Edit"]]})]
        (is (str/starts-with? rendered "<header"))
        (is (str/includes? rendered "class=\"sno-page-toolbar\""))
        (is (str/includes? rendered "role=\"toolbar\""))
        (is (str/includes? rendered "aria-label=\"Gig controls\""))
        (is (str/includes? rendered "class=\"desktop\""))
        (is (str/includes? rendered "class=\"mobile\""))
        (is (str/includes? rendered "href=\"/gig/1/log-plays\""))
        (is (str/includes? rendered "<wa-dropdown placement=\"bottom-end\""))
        (is (str/includes? rendered "aria-label=\"More gig actions\""))
        (is (str/includes? rendered "<wa-dropdown-item value=\"/gig/1/edit\">Edit</wa-dropdown-item>"))))))

(deftest page-toolbar-omits-empty-action-and-overflow-regions
  (let [page-toolbar (page-toolbar-alias)]
    (is (some? page-toolbar) "PageToolbar alias should exist")
    (when page-toolbar
      (let [rendered (page-toolbar-html
                      {:app.ui2.page-toolbar/breadcrumb [:span "Gigs"]
                       :app.ui2.page-toolbar/mobile-back [:a {:href "/gigs"} "Gigs"]})]
        (is (not (str/includes? rendered "<menu")))
        (is (not (str/includes? rendered "<wa-dropdown")))))))
