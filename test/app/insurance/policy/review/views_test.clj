(ns app.insurance.policy.review.views-test
  (:require
   [app.insurance.policy.review.views :as sut]
   [app.ui2.card :as card]
   [app.test-common :as tu]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(def translations
  {[:action/previous]                            "Previous"
   [:insurance.review/filter-needs-review]       "Todo"
   [:insurance.review/filter-missing-insurer-id] "Missing ID"
   [:insurance.review/skip]                      "Skip"
   [:insurance.review/approve-and-next]          "Approve and next"
   [:insurance.review/save-and-continue]         "Save and continue"
   [:insurance.review/items-left]                "%1 items left."
   [:insurance.review/item-left]                 "%1 item left."
   [:insurance.dashboard/policy-cost]            "Policy cost"
   [:insurance.review/see-all-in-workbench]      "See all in the workbench"
   [:insurance.review/comments]                  "Comments"
   [:insurance.review/comment-placeholder]       "Add a note about this item."
   [:insurance.review/add-comment]               "Add comment"
   [:insurance.review/commented]                 "commented"
   [:insurance.review/leave-reply]               "Leave a reply"
   [:instrument.coverage/insurer-id]             "Harmonia ID"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce-kv (fn [s idx value]
                (str/replace s (str "%" (inc idx)) (str value)))
              (tr path)
              (vec args))))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def policy-id
  #uuid "00000000-0000-0000-0000-000000001001")

(def selected-coverage-id
  #uuid "00000000-0000-0000-0000-000000001002")

(def previous-coverage-id
  #uuid "00000000-0000-0000-0000-000000001003")

(def next-coverage-id
  #uuid "00000000-0000-0000-0000-000000001004")

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/status    :insurance.policy.status/draft
   :insurance.policy/currency  :EUR})

(defn select-attrs
  [selector hiccup]
  (some-> (l/select-one selector hiccup)
          l/attrs))

(defn navigation-url
  [script]
  (when-let [[_ url] (re-find #"window\.location\.href = '([^']+)'" script)]
    url))

(defn action-keyword
  [script]
  (when-let [[_ value] (some->> script
                                (re-find #"[?&]kw=([^&')]+)"))]
    (keyword value)))

(defn action-keywords
  [hiccup]
  (into #{}
        (keep action-keyword)
        (tu/select-attribute '* [:data-on:click] hiccup)))

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

(defn workflow-actions-view
  [filter coverage]
  (let [{:keys [conn member-id]} (tu/new-system
                                  (str "insurance-review-actions-" (name filter)))]
    (seed-insurance-team! conn member-id)
    (sut/review-action-row
     (review-req conn member-id)
     {:filter            filter
      :selected-coverage (merge {:instrument.coverage/coverage-id selected-coverage-id}
                                coverage)
      :previous-coverage {:instrument.coverage/coverage-id previous-coverage-id}
      :next-coverage     {:instrument.coverage/coverage-id next-coverage-id}
      :policy            policy})))

(defn action-buttons
  [view]
  (l/select :app.ui2.button/button view))

(deftest review-filters
  (testing "The review queue supports the Todo and Missing ID workflows."
    (let [view (sut/filter-bar
                {:tr tr}
                {:policy       policy
                 :filter       :needs-review
                 :filter-order [:needs-review :missing-insurer-id]})
          tabs (l/select 'wa-tab view)]
      (testing "Todo is the active workflow."
        (is (= {:group-active "needs-review"
                :active-tabs  [true nil]}
               {:group-active (:active (select-attrs 'wa-tab-group view))
                :active-tabs  (mapv #(get (l/attrs %) :active) tabs)})))
      (testing "Each tab is labelled and navigates to its workflow."
        (is (= [{:label "Todo"
                 :panel "needs-review"
                 :url   (str "/insurance-policy/" policy-id
                             "/review?filter=needs-review")}
                {:label "Missing ID"
                 :panel "missing-insurer-id"
                 :url   (str "/insurance-policy/" policy-id
                             "/review?filter=missing-insurer-id")}]
               (mapv (fn [tab]
                       (let [attrs (l/attrs tab)]
                         {:label (l/text tab)
                          :panel (:panel attrs)
                          :url   (navigation-url (:data-on:click attrs))}))
                     tabs))))
      (testing "Each workflow has a matching tab panel."
        (is (= ["needs-review" "missing-insurer-id"]
               (mapv #(get (l/attrs %) :name)
                     (l/select 'wa-tab-panel view))))))))

(deftest workbench-summary
  (testing "The review queue summarizes remaining work and known policy costs."
    (let [singular-view (sut/workbench-summary
                         {:tr tr}
                         {:policy      policy
                          :filter      :needs-review
                          :queue-count 1
                          :totals      {:total-cost 0M}})
          plural-view   (sut/workbench-summary
                         {:tr tr}
                         {:policy      policy
                          :filter      :missing-insurer-id
                          :queue-count 23
                          :totals      {:total-cost 12.34M}})
          link          (l/select-one 'a plural-view)]
      (testing "Remaining item labels use the correct singular or plural form."
        (is (= ["1 item left." "23 items left."]
               (mapv #(-> (l/select-one "[data-review-items-left]" %) l/text)
                     [singular-view plural-view]))))
      (testing "The known-cost policy aggregate remains visible."
        (is (= ["Policy cost 0,00 €" "Policy cost 12,34 €"]
               (mapv #(-> (l/select-one "[data-review-policy-total]" %) l/text)
                     [singular-view plural-view]))))
      (testing "The workbench link preserves the active review filter."
        (is (= {:text "See all in the workbench"
                :href (str "/insurance-policy/" policy-id
                           "/workbench?review-filter=missing-id")}
               {:text (l/text link)
                :href (:href (l/attrs link))}))))))

(deftest review-cards-use-native-card-chassis
  (let [review {:filter            :needs-review
                :policy            policy
                :queue             []
                :queue-count       0
                :selected-coverage nil
                :totals            {:total-cost 0M}}
        cards  [(sut/workbench-summary {:tr tr} review)
                (second (sut/review-aside {:tr tr} review))
                (#'sut/queue-card {:tr tr} review)]]
    (is (= [card/Card card/Card card/Card]
           (mapv first cards)))))

(deftest todo-review
  (testing "An insurance-team member is reviewing a Todo item on a draft policy."
    (let [view    (workflow-actions-view
                   :needs-review
                   {:instrument.coverage/status
                    :instrument.coverage.status/needs-review})
          buttons (action-buttons view)]
      (testing "Previous and Skip navigate to the adjacent queue items."
        (is (= [{:text "Previous"
                 :href (str "/insurance-policy/" policy-id
                            "/review?filter=needs-review&coverage-id="
                            previous-coverage-id)}
                {:text "Skip"
                 :href (str "/insurance-policy/" policy-id
                            "/review?filter=needs-review&coverage-id="
                            next-coverage-id)}]
               (mapv (fn [button]
                       {:text (l/text button)
                        :href (:href (l/attrs button))})
                     (take 2 buttons)))))
      (testing "The primary action approves the item and advances the queue."
        (is (= {:label   "Approve and next"
                :actions #{:mark-coverage-reviewed}}
               {:label   (-> buttons last l/text)
                :actions (action-keywords view)}))))))

(deftest missing-id-review
  (testing "An insurance-team member is reviewing an item without a Harmonia ID."
    (let [view  (workflow-actions-view
                 :missing-insurer-id
                 {:instrument.coverage/status     :instrument.coverage.status/coverage-active
                  :instrument.coverage/insurer-id nil})
          input (l/select-one 'wa-input view)]
      (testing "The missing Harmonia ID can be entered."
        (is (= {:label     "Harmonia ID"
                :value     ""
                :data-bind "insuranceReview.insurerId"}
               (select-keys (l/attrs input) [:label :value :data-bind]))))
      (testing "Saving the ID continues to the next queue item."
        (is (= {:labels  ["Previous" "Skip" "Save and continue"]
                :actions #{:update-insurer-id}}
               {:labels  (mapv l/text (action-buttons view))
                :actions (action-keywords view)}))))))

(deftest comments
  (testing "The review aside displays the temporary comment thread."
    (let [view     (sut/review-aside {:tr tr} {})
          textarea (l/select-one 'wa-textarea view)]
      (testing "A reviewer can compose a comment."
        (is (= {:heading     ["Comments"]
                :placeholder "Add a note about this item."
                :buttons     ["Add comment"]}
               {:heading     (mapv l/text (l/select '[aside h2] view))
                :placeholder (:placeholder (l/attrs textarea))
                :buttons     (mapv l/text (action-buttons view))})))
      (testing "The dummy thread contains three attributed comments."
        (is (= {:authors         ["Robert Fox" "Virginia Woolf" "Clarissa Vaughan"]
                :relative-times 3
                :reply-link     "Leave a reply"}
               {:authors         (mapv #(get (l/attrs %) :app.ui2.avatar/name)
                                       (l/select :app.ui2.avatar/avatar view))
                :relative-times (count (l/select 'wa-relative-time view))
                :reply-link     (-> (l/select-one 'a view) l/text)}))))))
