(ns app.gigs.detail.views-test
  (:require
   [app.gigs.attendance.ui :as attendance.ui]
   [app.gigs.detail.views :as views]
   [app.gigs.view-test-support :as support]
   [app.ui2.button :as button]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.util :as util]
   [clojure.test :refer [deftest is testing]]
   [dev.onionpancakes.chassis.core :as c]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

(def gig-id
  #uuid "00000000-0000-0000-0000-000000000123")

(def member-id
  #uuid "00000000-0000-0000-0000-000000000456")

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(defn- translation-key [node]
  (some-> (l/select-one :i18n/tr node)
          l/first-child))

(defn- toolbar-view
  ([date]
   (toolbar-view date {}))
  ([date page-state]
   (let [gig-toolbar (ns-resolve 'app.gigs.detail.views 'gig-toolbar)]
     (gig-toolbar {:tr             tr
                   :page-state     page-state
                   :current-locale :en}
                  {:gig/gig-id   gig-id
                   :gig/title    "Summer Concert"
                   :gig/gig-type :gig.type/gig
                   :gig/date     date}))))

(defn- toolbar-action-summary [view]
  (let [toolbar        (l/select-one page-toolbar/PageToolbar view)
        toolbar-attrs  (l/attrs toolbar)
        actions        (::page-toolbar/actions toolbar-attrs)
        overflow-items (::page-toolbar/overflow-items toolbar-attrs)]
    {:visible
     (mapv (fn [action]
             {:label (translation-key action)
              :href  (:href (l/attrs action))})
           (l/select :app.ui2.button/button actions))
     :overflow
     (mapv (fn [item]
             (merge {:label (translation-key item)}
                    (select-keys (l/attrs item) [:value :data-dialog])))
           (l/select 'wa-dropdown-item overflow-items))}))

(deftest current-and-past-gig-toolbar-actions
  (testing "Today and past gigs expose play logging as the primary action."
    (let [today (t/date (util/local-time-austria!))]
      (is (= [{:visible [{:label :gigs/log-plays
                          :href  (str "/gig/" gig-id "/log-plays")}]
               :overflow [{:label :action/edit
                           :value (str "/gig/" gig-id "/edit")}
                          {:label       :gigs/remind-all
                           :data-dialog "open gig-detail-remind-all-dialog"}]}
              {:visible [{:label :gigs/log-plays
                          :href  (str "/gig/" gig-id "/log-plays")}]
               :overflow [{:label :action/edit
                           :value (str "/gig/" gig-id "/edit")}
                          {:label       :gigs/remind-all
                           :data-dialog "open gig-detail-remind-all-dialog"}]}]
             (mapv (comp toolbar-action-summary toolbar-view)
                   [today (t/<< today (t/new-period 1 :days))]))))))

(deftest future-gig-toolbar-actions
  (testing "A future gig exposes editing as the primary action."
    (let [today (t/date (util/local-time-austria!))]
      (is (= {:visible [{:label :action/edit
                         :href  (str "/gig/" gig-id "/edit")}]
              :overflow [{:label :gigs/log-plays
                          :value (str "/gig/" gig-id "/log-plays")}
                         {:label       :gigs/remind-all
                          :data-dialog "open gig-detail-remind-all-dialog"}]}
             (-> today
                 (t/>> (t/new-period 1 :days))
                 toolbar-view
                 toolbar-action-summary))))))

(deftest archived-gig-toolbar-actions
  (testing "An archived gig omits the reminder action."
    (is (= {:visible [{:label :gigs/log-plays
                       :href  (str "/gig/" gig-id "/log-plays")}]
            :overflow [{:label :action/edit
                        :value (str "/gig/" gig-id "/edit")}]}
           (-> (t/date (util/local-time-austria!))
               (t/<< (t/new-period 2 :months))
               toolbar-view
               toolbar-action-summary)))))

(deftest recently-sent-reminder-status
  (testing "A recent reminder identifies its send time in the overflow item."
    (let [view     (toolbar-view
                    (t/>> (t/date (util/local-time-austria!))
                          (t/new-period 1 :days))
                    {:gig-detail
                     {:attendance
                      {:remind-all-sent-at (t/inst)}}})
          toolbar  (l/select-one page-toolbar/PageToolbar view)
          overflow (::page-toolbar/overflow-items (l/attrs toolbar))]
      (is (= [:gigs/log-plays :gigs/remind-all :gigs/reminded-all-at]
             (mapv l/first-child (l/select :i18n/tr overflow)))))))

(deftest gig-date-uses-compact-date-range
  (testing "A multi-day gig shows compact start and end dates."
    (let [view (#'views/gig-date
                {:current-locale :en}
                {:gig/date     (t/date "2026-06-04")
                 :gig/end-date (t/date "2026-06-07")})]
      (is (= [{:datetime "2026-06-04" :text "Thu 04"}
              {:datetime "2026-06-07" :text "Sun 07 Jun 2026"}]
             (mapv (fn [time]
                     {:datetime (:datetime (l/attrs time))
                      :text     (l/text time)})
                   (l/select 'time view)))))))

(deftest attendance-plan-trigger-uses-native-caret-button
  (testing "The attendance trigger uses the native SVG caret button contract."
    (let [view     (attendance.ui/plan-dropdown
                    {::r/router support/router :tr tr}
                    gig-id
                    member-id
                    :plan/definitely)
          trigger  (l/select-one button/Button view)
          resolved (c/resolve-alias button/Button
                                    (l/attrs trigger)
                                    (l/raw-children trigger))]
      (is (= {:tag             :button
              :caret-count     1
              :plan-icon-count 1}
             {:tag             (first resolved)
              :caret-count     (count (l/select ".caret" resolved))
              :plan-icon-count (count (l/select ".gigs-attendance-plan-icon"
                                                resolved))})))))
