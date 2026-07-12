(ns app.gigs.detail.views-test
  (:require
   [app.gigs.detail.views :as views]
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [tick.core :as t]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(deftest gig-toolbar-keeps-log-plays-visible-and-puts-edit-and-remind-in-overflow
  (let [gig-toolbar (ns-resolve 'app.gigs.detail.views 'gig-toolbar)]
    (is (some? gig-toolbar) "Gig detail toolbar should exist")
    (when gig-toolbar
      (let [gig      {:gig/gig-id (random-uuid)
                      :gig/title "Summer Concert"
                      :gig/gig-type :gig.type/gig
                      :gig/date (t/>> (t/date) (t/new-period 2 :months))}
            rendered (html/->str tr (gig-toolbar {:tr tr :page-state {}} gig))
            dropdown (second (re-find #"(?s)(<wa-dropdown.*</wa-dropdown>)" rendered))]
        (is (str/includes? rendered "class=\"sno-page-toolbar\""))
        (is (str/includes? rendered (str "href=\"/gig/" (:gig/gig-id gig) "/log-plays\"")))
        (is (str/includes? dropdown (str "value=\"/gig/" (:gig/gig-id gig) "/edit\"")))
        (is (str/includes? dropdown "data-dialog=\"open gig-detail-remind-all-dialog\""))
        (is (str/includes? dropdown ">edit</wa-dropdown-item>"))
        (is (str/includes? dropdown ">remind-all</wa-dropdown-item>"))))))

(deftest archived-gig-toolbar-omits-remind-all
  (let [gig-toolbar (ns-resolve 'app.gigs.detail.views 'gig-toolbar)]
    (is (some? gig-toolbar) "Gig detail toolbar should exist")
    (when gig-toolbar
      (let [rendered (html/->str tr
                                 (gig-toolbar
                                  {:tr tr :page-state {}}
                                  {:gig/gig-id (random-uuid)
                                   :gig/title "Archived Concert"
                                   :gig/gig-type :gig.type/gig
                                   :gig/date (t/<< (t/date) (t/new-period 2 :months))}))]
        (is (not (str/includes? rendered "gig-detail-remind-all-dialog")))))))

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
