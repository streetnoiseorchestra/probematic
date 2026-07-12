(ns app.gigs.detail.views-test
  (:require
   [app.gigs.detail.views :as views]
   [app.html :as html]
   [app.util :as util]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [tick.core :as t]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(defn- toolbar-parts [gig]
  (let [gig-toolbar (ns-resolve 'app.gigs.detail.views 'gig-toolbar)
        rendered    (html/->str tr (gig-toolbar {:tr tr :page-state {}} gig))
        [visible]   (str/split rendered #"<wa-dropdown" 2)
        overflow    (second (re-find #"(?s)(<wa-dropdown.*</wa-dropdown>)" rendered))]
    {:visible  visible
     :overflow overflow}))

(deftest current-and-past-gigs-keep-log-plays-visible
  (let [gig-id (random-uuid)
        today  (t/date (util/local-time-austria!))]
    (doseq [date [today
                  (t/<< today (t/new-period 1 :days))]]
      (let [{:keys [visible overflow]}
            (toolbar-parts {:gig/gig-id   gig-id
                            :gig/title    "Summer Concert"
                            :gig/gig-type :gig.type/gig
                            :gig/date     date})]
        (is (str/includes? visible (str "href=\"/gig/" gig-id "/log-plays\"")))
        (is (not (str/includes? visible (str "href=\"/gig/" gig-id "/edit\""))))
        (is (str/includes? overflow (str "value=\"/gig/" gig-id "/edit\"")))
        (is (not (str/includes? overflow (str "/gig/" gig-id "/log-plays"))))
        (is (str/includes? overflow "data-dialog=\"open gig-detail-remind-all-dialog\""))))))

(deftest future-gigs-keep-edit-visible
  (let [today              (t/date (util/local-time-austria!))
        gig-id             (random-uuid)
        {:keys [visible overflow]}
        (toolbar-parts {:gig/gig-id   gig-id
                        :gig/title    "Summer Concert"
                        :gig/gig-type :gig.type/gig
                        :gig/date     (t/>> today (t/new-period 1 :days))})]
    (is (str/includes? visible (str "href=\"/gig/" gig-id "/edit\"")))
    (is (not (str/includes? visible (str "href=\"/gig/" gig-id "/log-plays\""))))
    (is (str/includes? overflow (str "value=\"/gig/" gig-id "/log-plays\"")))
    (is (not (str/includes? overflow (str "/gig/" gig-id "/edit"))))
    (is (str/includes? overflow "data-dialog=\"open gig-detail-remind-all-dialog\""))))

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

(deftest recently-sent-reminder-identifies-its-time-in-the-overflow-menu
  (let [gig-toolbar (ns-resolve 'app.gigs.detail.views 'gig-toolbar)]
    (is (some? gig-toolbar) "Gig detail toolbar should exist")
    (when gig-toolbar
      (let [rendered (html/->str
                      tr
                      (gig-toolbar
                       {:tr tr
                        :page-state {:gig-detail
                                     {:attendance
                                      {:remind-all-sent-at (java.util.Date.)}}}}
                       {:gig/gig-id (random-uuid)
                        :gig/title "Summer Concert"
                        :gig/gig-type :gig.type/gig
                        :gig/date (t/>> (t/date) (t/new-period 2 :months))}))]
        (is (str/includes? rendered "reminded-all-at"))))))

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
