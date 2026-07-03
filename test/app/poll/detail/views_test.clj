(ns app.poll.detail.views-test
  (:require
   [app.poll.detail.views :as views]
   [app.poll.test-support :as pts]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [reitit.core :as r]))

(defn- occurrences [needle s]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) s)))

(defn- req [db member-id poll-id]
  {:current-locale    :en
   :current-member-id member-id
   :db                db
   :path-params       {:poll/poll-id poll-id}
   :tr                pts/tr
   ::r/router         (r/router [["/act" {:name :app.routes.datastar/act}]])})

(deftest poll-results-render-as-progress-bars
  (let [{:keys [conn member-id]} (tc/new-system "poll-detail-views")
        member-2                (random-uuid)
        member-3                (random-uuid)]
    @(d/transact conn [{:member/member-id member-2}
                       {:member/member-id member-3}])
    (let [{:keys [poll-id option-ids]}
          (pts/seed-poll! conn
                          member-id
                          {:poll/poll-status :poll.status/open}
                          ["Yes" "No" "Maybe"])
          [yes no _maybe] option-ids]
      (pts/seed-vote! conn poll-id member-id yes)
      (pts/seed-vote! conn poll-id member-2 yes)
      (pts/seed-vote! conn poll-id member-3 no)
      (let [html (views/page (req (d/db conn) member-id poll-id))]
        (is (= {:progress-bar-count  3
                :renders-labels      true
                :renders-results     true
                :renders-zero-option true
                :uses-wa-colors      true
                :no-canvas           true
                :no-poll-widget      true}
               {:progress-bar-count  (occurrences "<wa-progress-bar" html)
                :renders-labels      (and (str/includes? html ">Yes</span>")
                                          (str/includes? html ">No</span>")
                                          (str/includes? html ">Maybe</span>"))
                :renders-results     (and (str/includes? html ">66.7% (2)</span>")
                                          (str/includes? html ">33.3% (1)</span>"))
                :renders-zero-option (and (str/includes? html "value=\"0\"")
                                          (str/includes? html ">0% (0)</span>"))
                :uses-wa-colors      (and (str/includes? html "var(--wa-color-success-fill-loud)")
                                          (str/includes? html "var(--wa-color-warning-fill-loud)")
                                          (str/includes? html "var(--wa-color-purple-60)"))
                :no-canvas           (not (str/includes? html "<canvas"))
                :no-poll-widget      (not (str/includes? html "/js/widgets/poll-chart.js"))}))))))
