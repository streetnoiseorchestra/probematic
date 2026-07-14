(ns app.dashboard.views-test
  (:require
   [app.dashboard.index.views :as views]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(def request
  {::r/router       (r/router ["/act" {:name :app.routes.datastar/act}])
   :current-locale :en
   :page-state     {}
   :system         {:env {}}
   :tr             tr})

(def member
  {:member/name "Ada Lovelace"
   :member/nick "Ada"})

(defn home-content [data]
  ((ns-resolve 'app.dashboard.index.views 'home-content)
   request
   member
   data))

(deftest home-content-renders-the-approved-dashboard-regions
  (let [view (home-content {:insurance-survey  nil
                            :insurance-todos   []
                            :ledger            nil
                            :unanswered        []
                            :unanswered-polls  []
                            :upcoming          []})
        root (l/select-one ".dashboard-home" view)
        quick-actions (l/select-one ".quick-actions" root)
        activity (l/select-one ".activity" root)]
    (is (= #{"dashboard-home" "wa-grid" "wa-gap-l"}
           (:class (l/attrs root))))
    (is (= #{"personal" "wa-stack" "wa-gap-l" "wa-text-end"}
           (-> (l/select-one ".personal" root) l/attrs :class)))
    (is (= #{"quick-actions" "wa-cluster" "wa-gap-xs" "wa-align-items-end"}
           (:class (l/attrs quick-actions))))
    (is (some? (l/select-one ".focus" root)))
    (is (some? activity))
    (is (= ["/gigs/create" "/polls/new"]
           (mapv (comp :href l/attrs)
                 (l/select button/Button quick-actions))))
    (is (= 3 (count (l/select ".activity-entry" activity))))
    (is (some :disabled (map l/attrs (l/select button/Button activity))))
    (is (nil? (l/select-one page-surface/PageSurface view)))
    (is (nil? (l/select-one page-toolbar/PageToolbar view)))))

(deftest my-responses-includes-one-rich-insurance-review-and-polls
  (let [policy-id (random-uuid)
        survey-id (random-uuid)
        poll-id   (random-uuid)
        closes-at (t/date-time "2026-08-10T20:00")
        view      (home-content
                   {:insurance-survey
                    {:closes-at   closes-at
                     :name        "Coverage check"
                     :policy-id   policy-id
                     :policy-name "Insurance 2026"
                     :survey-id   survey-id
                     :todo-count  2
                     :total-count 3}
                    :insurance-todos  []
                    :ledger           nil
                    :unanswered       []
                    :unanswered-polls [{:poll/poll-id poll-id
                                        :poll/title "Choose a rehearsal date"
                                        :poll/closes-at (t/date-time "2026-08-12T20:00")}]
                    :upcoming         []})
        responses (some #(when (str/includes? (:class (l/attrs %)) "responses") %)
                        (l/select card/Card view))
        survey-task (l/select-one ".dashboard-insurance-survey" responses)
        picture      (l/select-one :picture survey-task)
        relative-time (l/select-one 'wa-relative-time survey-task)
        progress      (some #(when (and (vector? %)
                                        (= :i18n/tr (first %))
                                        (= :insurance/review-dashboard-progress
                                           (second %)))
                               %)
                            (tree-seq coll? seq survey-task))
        summary       (some #(when (and (vector? %)
                                        (= :i18n/tr (first %))
                                        (= :insurance/review-dashboard-summary
                                           (second %)))
                               %)
                            (tree-seq coll? seq survey-task))
        hrefs        (set (keep (comp :href l/attrs)
                                (concat (l/select :a responses)
                                        (l/select button/Button responses))))
        section-keys (set (keep (fn [section]
                                  (some #(when (and (vector? %)
                                                    (= :i18n/tr (first %)))
                                           (second %))
                                        (tree-seq coll? seq section)))
                                (l/select ".dashboard-response-section"
                                          responses)))]
    (is (= "2" (l/text (l/select-one :wa-badge responses))))
    (is (= {:animated       "/img/peanut_butter_jelly_time.gif"
            :frame-classes  #{"mascot" "wa-frame:square" "wa-gap-0"}
            :progress       [:i18n/tr :insurance/review-dashboard-progress
                             {:count 2 :total 3}]
            :relative-time  {:date    "2026-08-10T20:00"
                             :format  "long"
                             :numeric "auto"
                             :sync    true
                             :title   (ui2/format-date-time request
                                                            :medium
                                                            closes-at)}
            :still          "/img/peanut_butter_jelly_time_still.gif"
            :summary        nil}
           {:animated       (-> (l/select-one :img survey-task) l/attrs :src)
            :frame-classes  (:class (l/attrs picture))
            :progress       progress
            :relative-time  (select-keys (l/attrs relative-time)
                                         [:date :format :numeric :sync :title])
            :still          (-> (l/select-one :source survey-task) l/attrs :srcset)
            :summary        summary}))
    (is (= 1
           (count (filter #(= (str "/insurance-survey/" policy-id "/")
                              (:href (l/attrs %)))
                          (concat (l/select :a responses)
                                  (l/select button/Button responses))))))
    (is (= #{(str "/insurance-survey/" policy-id "/")
             (str "/poll/" poll-id)}
           hrefs))
    (is (= #{:insurance/instrument-insurance
             :polls/response-needed}
           section-keys))))

(deftest my-responses-orders-survey-todos-and-attendance-by-priority
  (let [policy-id (random-uuid)
        member-id (random-uuid)
        view      (home-content
                   {:insurance-survey
                    {:closes-at   (t/date-time "2026-08-10T20:00")
                     :policy-id   policy-id
                     :todo-count  6
                     :total-count 6}
                    :insurance-todos
                    [{:insurance.policy/name "Insurance 2026"
                      :insurance.policy/policy-id policy-id
                      :total-needs-review 1
                      :total-changed      0
                      :total-new          0
                      :total-removed      0}]
                    :ledger           nil
                    :unanswered
                    [{:gig/gig-id    (random-uuid)
                      :gig/title     "Summer concert"
                      :gig/status    :gig.status/confirmed
                      :gig/date      (t/date "2026-08-12")
                      :gig/call-time (t/time "18:00")
                      :attendance    {:attendance/member
                                      {:member/member-id member-id}
                                      :attendance/plan :plan/no-response}}]
                    :unanswered-polls []
                    :upcoming         []})
        responses (some #(when (str/includes? (:class (l/attrs %)) "responses") %)
                        (l/select card/Card view))]
    (is (= [:insurance-survey :insurance-todos :attendance]
           (mapv (fn [section]
                   (let [classes (:class (l/attrs section))]
                     (cond
                       (contains? classes "dashboard-insurance-todo-section")
                       :insurance-todos

                       (contains? classes "dashboard-gig-section")
                       :attendance

                       :else
                       :insurance-survey)))
                 (l/select :section responses))))))

(deftest response-queues-share-one-list-presentation
  (let [policy-id (random-uuid)
        member-id (random-uuid)
        poll-id   (random-uuid)
        poll-closes-at (t/date-time "2026-08-13T20:00")
        view      (home-content
                   {:insurance-survey
                    {:closes-at   (t/date-time "2026-08-10T20:00")
                     :policy-id   policy-id
                     :todo-count  2
                     :total-count 3}
                    :insurance-todos
                    [{:insurance.policy/name "Insurance 2026"
                      :insurance.policy/policy-id policy-id
                      :total-needs-review 1
                      :total-changed      1
                      :total-new          20
                      :total-removed      10}]
                    :ledger           nil
                    :unanswered
                    [{:gig/gig-id    (random-uuid)
                      :gig/title     "Summer concert"
                      :gig/status    :gig.status/confirmed
                      :gig/date      (t/date "2026-08-12")
                      :gig/call-time (t/time "18:00")
                      :attendance    {:attendance/member
                                      {:member/member-id member-id}
                                      :attendance/plan :plan/no-response}}]
                    :unanswered-polls
                    [{:poll/poll-id   poll-id
                      :poll/title     "Dashboard verification poll"
                      :poll/closes-at poll-closes-at}]
                    :upcoming []})
        responses (some #(when (str/includes? (:class (l/attrs %))
                                              "responses")
                           %)
                        (l/select card/Card view))
        [todo-row gig-row poll-row] (l/select ".dashboard-row" responses)
        poll-detail (some #(when (and (vector? %)
                                      (= :i18n/tr (first %))
                                      (= :polls/response-dashboard-detail
                                         (second %)))
                             %)
                          (tree-seq coll? seq poll-row))]
    (is (= [#{"dashboard-list"}
            #{"dashboard-list" "dashboard-row-list"}
            #{"dashboard-list" "dashboard-row-list"}
            #{"dashboard-list" "dashboard-row-list"}]
           (mapv (comp :class l/attrs)
                 (l/select ".dashboard-list" responses))))
    (is (= [#{"dashboard-insurance-todo-row" "dashboard-row" "sno-no-visited"}
            #{"dashboard-gig-row" "dashboard-row" "sno-no-visited"}
            #{"dashboard-row" "sno-no-visited" "wa-flank:end" "wa-gap-m"}]
           (mapv (comp :class l/attrs) [todo-row gig-row poll-row])))
    (is (= "Insurance 2026"
           (l/text (l/select-one ".dashboard-insurance-todo-name" todo-row))))
    (is (nil? (l/select-one ".dashboard-insurance-todo-status-cell" todo-row)))
    (is (= ["1" "1" "20" "10"]
           (mapv l/text
                 (l/select ".dashboard-insurance-todo-count" todo-row))))
    (is (= 4 (count (l/select ico/Icon todo-row))))
    (is (= "Summer concert"
           (l/text (l/select-one ".dashboard-gig-title" gig-row))))
    (is (= "Dashboard verification poll"
           (l/text (l/select-one :strong poll-row))))
    (is (= [:i18n/tr :polls/response-dashboard-detail
            {:date (ui2/format-date-time request :medium poll-closes-at)}]
           poll-detail))
    (is (= #{(str "/poll/" poll-id)}
           (set (keep (comp :href l/attrs)
                      (concat (l/select :a poll-row)
                              (l/select button/Button poll-row))))))))

(deftest ledger-widget-renders-native-card-body
  (let [widget (#'views/ledger-widget
                request
                {:ledger/balance 1234
                 :ledger/entries []
                 :ledger/owner   {:member/member-id
                                  #uuid "00000000-0000-0000-0000-000000000123"}})
        card   (l/select-one card/Card widget)]
    (testing "the ledger content remains in the Card body rather than a named slot"
      (is (= #{"dashboard-ledger-card"} (:class (l/attrs card))))
      (is (some? (l/select-one ".dashboard-ledger-grid" card))))))
