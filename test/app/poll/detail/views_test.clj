(ns app.poll.detail.views-test
  (:require
   [app.poll.detail.views :as views]
   [app.poll.test-support :as pts]
   [app.poll.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

(defn- page-contract [status]
  (let [system             (support/new-system (str "poll-detail-" (name status)))
        {:keys [poll-id]} (support/seed-poll! system status)
        view               (-> system
                               (support/request {:path-params {:poll/poll-id poll-id}})
                               views/page)]
    {:poll-id   poll-id
     :structure (page-shell/page-structure view)}))

(deftest draft-poll-page-surface
  (testing "A draft poll makes opening primary and keeps editing in overflow."
    (let [{:keys [poll-id structure]} (page-contract :poll.status/draft)]
      (is (= {:contract {:width       :standard
                         :breadcrumbs [:polls/title "Existing Poll"]
                         :mobile      {:label :polls/title :href "/polls"}
                         :actions     [{:label       :polls/open-poll
                                        :appearance  "filled"
                                        :variant     "brand"
                                        :data-dialog (str "open poll-open-" poll-id)}]
                         :overflow    [{:label :action/edit
                                        :value (str "/poll/" poll-id "/edit")}]}
              :heading  "Existing Poll draft"
              :subtitle "single"
              :form-id  nil
              :last-tag :wa-dialog
              :last-id  (str "poll-close-" poll-id)}
             structure)))))

(deftest open-poll-page-surface
  (testing "An open poll makes editing visible and moves early closure into overflow."
    (let [{:keys [poll-id structure]} (page-contract :poll.status/open)]
      (is (= {:contract {:width       :standard
                         :breadcrumbs [:polls/title "Existing Poll"]
                         :mobile      {:label :polls/title :href "/polls"}
                         :actions     [{:label      :action/edit
                                        :href       (str "/poll/" poll-id "/edit")
                                        :appearance "filled"}]
                         :overflow    [{:label       :polls/close-early
                                        :data-dialog (str "open poll-close-" poll-id)
                                        :variant     "danger"}]}
              :heading  "Existing Poll open"
              :subtitle "single"
              :form-id  nil
              :last-tag :wa-dialog
              :last-id  (str "poll-close-" poll-id)}
             structure)))))

(deftest closed-poll-page-surface
  (testing "A closed poll has context but no lifecycle actions."
    (let [{:keys [poll-id structure]} (page-contract :poll.status/closed)]
      (is (= {:contract {:width       :standard
                         :breadcrumbs [:polls/title "Existing Poll"]
                         :mobile      {:label :polls/title :href "/polls"}
                         :actions     []
                         :overflow    []}
              :heading  "Existing Poll closed"
              :subtitle "single"
              :form-id  nil
              :last-tag :wa-dialog
              :last-id  (str "poll-close-" poll-id)}
             structure)))))

(deftest poll-results
  (testing "Three members cast two Yes votes and one No vote in a poll with an unselected Maybe option."
    (let [yes-id     (random-uuid)
          no-id      (random-uuid)
          maybe-id   (random-uuid)
          member-ids (repeatedly 3 random-uuid)
          poll       {:poll/closes-at pts/default-closes-at
                      :poll/options   [{:poll.option/poll-option-id yes-id
                                        :poll.option/position       0
                                        :poll.option/value          "Yes"}
                                       {:poll.option/poll-option-id no-id
                                        :poll.option/position       1
                                        :poll.option/value          "No"}
                                       {:poll.option/poll-option-id maybe-id
                                        :poll.option/position       2
                                        :poll.option/value          "Maybe"}]
                      :poll/votes     [{:poll.vote/author      {:member/member-id (first member-ids)}
                                        :poll.vote/poll-option {:poll.option/poll-option-id yes-id}}
                                       {:poll.vote/author      {:member/member-id (second member-ids)}
                                        :poll.vote/poll-option {:poll.option/poll-option-id yes-id}}
                                       {:poll.vote/author      {:member/member-id (last member-ids)}
                                        :poll.vote/poll-option {:poll.option/poll-option-id no-id}}]}
          view       (views/results-section {:current-locale :en
                                             :tr             pts/tr}
                                            poll)]
      (testing "Each option shows its vote share and count through an accessible progress bar."
        (is (= [{:label    "Yes"
                 :summary  "66.7% (2)"
                 :progress {:label "Yes 66.7% (2)"
                            :style {"--indicator-color" "var(--wa-color-success-fill-loud)"
                                    "--poll-result-progress-value" "66.7%"}
                            :value "66.7"}}
                {:label    "No"
                 :summary  "33.3% (1)"
                 :progress {:label "No 33.3% (1)"
                            :style {"--indicator-color" "var(--wa-color-warning-fill-loud)"
                                    "--poll-result-progress-value" "33.3%"}
                            :value "33.3"}}
                {:label    "Maybe"
                 :summary  "0% (0)"
                 :progress {:label "Maybe 0% (0)"
                            :style {"--indicator-color" "var(--wa-color-purple-60)"
                                    "--poll-result-progress-value" "0%"}
                            :value "0"}}]
               (mapv (fn [row]
                       {:label    (-> (l/select-one '.poll-result-label row) l/text)
                        :summary  (-> (l/select-one '.poll-result-value row) l/text)
                        :progress (select-keys
                                   (l/attrs (l/select-one 'wa-progress-bar row))
                                   [:label :style :value])})
                     (l/select '[ol > li] view))))))))
