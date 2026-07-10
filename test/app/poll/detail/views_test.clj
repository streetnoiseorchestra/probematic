(ns app.poll.detail.views-test
  (:require
   [app.poll.detail.views :as views]
   [app.poll.test-support :as pts]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

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
                            :value "66.7"}}
                {:label    "No"
                 :summary  "33.3% (1)"
                 :progress {:label "No 33.3% (1)"
                            :value "33.3"}}
                {:label    "Maybe"
                 :summary  "0% (0)"
                 :progress {:label "Maybe 0% (0)"
                            :value "0"}}]
               (mapv (fn [row]
                       {:label    (-> (l/select-one '.poll-result-label row) l/text)
                        :summary  (-> (l/select-one '.poll-result-value row) l/text)
                        :progress (select-keys
                                   (l/attrs (l/select-one 'wa-progress-bar row))
                                   [:label :value])})
                     (l/select '[ol > li] view))))))))
