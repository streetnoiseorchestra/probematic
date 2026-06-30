(ns app.insurance.policy.review.views-test
  (:require
   [app.html :as html]
   [app.insurance.policy.review.views :as views]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def translations
  {[:action/save]                              "Save"
   [:action/previous]                          "Previous"
   [:insurance.review/filters]                 "Review filters"
   [:insurance.review/filter-needs-review]     "Todo"
   [:insurance.review/filter-missing-insurer-id] "Missing ID"
   [:insurance.review/mark-reviewed]           "Mark reviewed"
   [:insurance.review/skip]                    "Skip"
   [:insurance.review/approve-and-next]        "Approve and next"
   [:insurance.review/save-and-continue]       "Save and continue"
   [:insurance.review/items-left]              "%1 items left."
   [:insurance.review/see-all-in-workbench]    "See all in the workbench"
   [:insurance.review/comments]                "Comments"
   [:insurance.review/comment-placeholder]     "Add a note about this item."
   [:insurance.review/add-comment]             "Add comment"
   [:insurance.review/commented]               "commented"
   [:insurance.review/leave-reply]             "Leave a reply"
   [:instrument.coverage/insurer-id]           "Harmonia ID"
   [:instrument.coverage.status/needs-review]  "Todo"
   [:instrument.coverage.status/reviewed]      "Reviewed"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce-kv (fn [s idx value]
                (str/replace s (str "%" (inc idx)) (str value)))
              (tr path)
              (vec args))))

(deftest review-filter-renders-tab-group-with-supported-review-workflows
  (let [policy-id (random-uuid)
        html      (html/->str
                   (#'views/filter-bar
                    {:tr tr}
                    {:policy        {:insurance.policy/policy-id policy-id}
                     :filter        :needs-review
                     :filter-counts {:needs-review       1
                                     :missing-insurer-id 2}
                     :filter-order  [:needs-review
                                     :missing-insurer-id]}))]
    (is (str/includes? html "<wa-tab-group"))
    (is (str/includes? html "active=\"needs-review\""))
    (is (= 2 (count (re-seq #"<wa-tab " html))))
    (is (= 2 (count (re-seq #"<wa-tab-panel" html))))
    (is (str/includes? html "panel=\"needs-review\""))
    (is (str/includes? html "panel=\"missing-insurer-id\""))
    (is (str/includes? html ">Todo<"))
    (is (str/includes? html ">Missing ID<"))
    (is (str/includes? html "window.location.href"))
    (is (not (str/includes? html "<wa-select")))
    (is (not (str/includes? html "<wa-option")))
    (is (not (str/includes? html "Missing photo")))
    (is (not (str/includes? html "Modified")))
    (is (not (str/includes? html "Added")))
    (is (not (str/includes? html "Removed")))
    (is (not (str/includes? html "<wa-button")))))

(deftest workbench-summary-replaces-progress-visual
  (let [policy-id (random-uuid)
        html      (html/->str
                   (#'views/workbench-summary
                    {:tr tr}
                    {:policy      {:insurance.policy/policy-id policy-id}
                     :filter      :missing-insurer-id
                     :queue-count 23}))]
    (is (str/includes? html "23 items left."))
    (is (str/includes? html "See all in the workbench"))
    (is (str/includes? html (str "/insurance-policy/" policy-id "/workbench?review-filter=missing-id")))
    (is (str/includes? html "wa-split"))
    (is (not (str/includes? html "<wa-progress-bar")))
    (is (not (str/includes? html "Progress")))))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:db/id            "member"
                      :member/member-id member-id
                      :member/name      "Reviewer"}
                     {:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   ["member"]}]))

(defn review-req
  [conn member-id]
  {::r/router  router
   :db         (d/db conn)
   :tr         tr
   :session    {:session/member {:member/member-id member-id}}
   :page-state {}})

(defn workflow-actions-html
  [filter coverage]
  (let [{:keys [conn member-id]} (tc/new-system (str "insurance-review-actions-" (name filter)))
        policy-id                (random-uuid)
        previous-id              (random-uuid)
        next-id                  (random-uuid)]
    (seed-insurance-team! conn member-id)
    (html/->str
     (#'views/review-action-row
      (review-req conn member-id)
      {:filter            filter
       :selected-coverage (merge {:instrument.coverage/coverage-id (random-uuid)} coverage)
       :previous-coverage {:instrument.coverage/coverage-id previous-id}
       :next-coverage     {:instrument.coverage/coverage-id next-id}
       :policy            {:insurance.policy/policy-id policy-id
                           :insurance.policy/status    :insurance.policy.status/draft}}))))

(deftest todo-review-renders-combined-navigation-and-approve-action-row
  (let [html (workflow-actions-html
              :needs-review
              {:instrument.coverage/status :instrument.coverage.status/needs-review})]
    (is (str/includes? html "Previous"))
    (is (str/includes? html "Skip"))
    (is (str/includes? html "Approve and next"))
    (is (str/includes? html "wa-outlined"))
    (is (str/includes? html "flex-wrap: nowrap"))
    (is (str/includes? html "overflow-x: auto"))
    (is (str/includes? html "mark-coverage-reviewed"))
    (is (str/includes? html "#snoico-circle-check-outline"))
    (is (not (str/includes? html "Mark reviewed")))
    (is (not (str/includes? html "<wa-button-group")))
    (is (not (str/includes? html "Change status")))
    (is (not (str/includes? html "update-insurer-id")))
    (is (not (str/includes? html "wa-split")))
    (is (not (str/includes? html "Harmonia ID")))))

(deftest missing-id-review-renders-combined-navigation-and-harmonia-id-action-row
  (let [html (workflow-actions-html
              :missing-insurer-id
              {:instrument.coverage/status     :instrument.coverage.status/coverage-active
               :instrument.coverage/insurer-id nil})]
    (is (str/includes? html "Previous"))
    (is (str/includes? html "Skip"))
    (is (str/includes? html "<wa-input"))
    (is (str/includes? html "Harmonia ID"))
    (is (str/includes? html "flex-wrap: wrap"))
    (is (str/includes? html "min-inline-size: min(100%, 24rem)"))
    (is (str/includes? html "data-bind=\"insuranceReview.insurerId\""))
    (is (str/includes? html "$insuranceReview ="))
    (is (str/includes? html "document.getElementById"))
    (is (str/includes? html "update-insurer-id"))
    (is (str/includes? html "Save and continue"))
    (is (not (str/includes? html ">Save<")))
    (is (not (str/includes? html "Mark reviewed")))
    (is (not (str/includes? html "mark-coverage-reviewed")))
    (is (not (str/includes? html "<wa-button-group")))
    (is (not (str/includes? html "Change status")))))

(deftest comments-aside-renders-dummy-comment-thread
  (let [html (html/->str (#'views/review-aside {:tr tr} {}))]
    (is (str/includes? html "<aside"))
    (is (str/includes? html "Comments"))
    (is (str/includes? html "<wa-textarea"))
    (is (str/includes? html "Add comment"))
    (is (str/includes? html "<wa-avatar"))
    (is (str/includes? html "<wa-relative-time"))
    (is (str/includes? html "commented"))
    (is (str/includes? html "Leave a reply"))
    (is (not (str/includes? html ">Queue<")))))
