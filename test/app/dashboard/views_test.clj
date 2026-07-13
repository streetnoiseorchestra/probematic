(ns app.dashboard.views-test
  (:require
   [app.dashboard.index.views :as views]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [tick.core :as t]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(def request
  {:current-locale :en
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
  (let [view (home-content {:insurance-surveys []
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

(deftest my-responses-includes-personal-coverage-reviews-and-polls
  (let [policy-id (random-uuid)
        survey-id (random-uuid)
        poll-id   (random-uuid)
        view      (home-content
                   {:insurance-surveys
                    [{:closes-at  (t/date-time "2026-08-10T20:00")
                      :name       "Coverage check"
                      :policy-id  policy-id
                      :policy-name "Insurance 2026"
                      :survey-id  survey-id
                      :todo-count 2
                      :total-count 3}]
                    :insurance-todos  []
                    :ledger           nil
                    :unanswered       []
                    :unanswered-polls [{:poll/poll-id poll-id
                                        :poll/title "Choose a rehearsal date"
                                        :poll/closes-at (t/date-time "2026-08-12T20:00")}]
                    :upcoming         []})
        responses (some #(when (str/includes? (:class (l/attrs %)) "responses") %)
                        (l/select card/Card view))
        hrefs     (set (keep (comp :href l/attrs)
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
    (is (= #{(str "/insurance-survey/" policy-id "/")
             (str "/poll/" poll-id)}
           hrefs))
    (is (= #{:insurance/pending-coverage-reviews
             :polls/response-needed}
           section-keys))))

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
