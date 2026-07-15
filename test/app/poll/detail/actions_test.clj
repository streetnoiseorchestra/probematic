(ns app.poll.detail.actions-test
  (:require
   [app.nexus.actions :as support]
   [app.poll.detail.actions :as actions]
   [app.poll.test-support :as pts]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]))

(defn error-summary [effects]
  [(first effects)
   (first (second effects))
   (second (second effects))
   (get-in (second effects) [2 :_error])])

(defn vote-top-error [effects]
  (get-in (second effects) [2 :_error :_top :error]))

(deftest open-poll-action-test
  (testing "opens a draft poll and returns the poll-opened email effect"
    (let [{:keys [conn member-id]} (tc/new-system "poll-open-action")
          {:keys [poll-id]}       (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/draft})]
      (is (= [[:db/transact
               [[:db/add (pts/poll-ref poll-id) :poll/poll-status :poll.status/open]]
               {}]
              [:app.poll/send-poll-opened poll-id]
              support/clear-loading]
             (actions/open-poll-action
              (pts/action-state conn member-id)
              (pts/poll-detail-signals poll-id)))))))

(deftest close-poll-action-test
  (testing "closes an open poll and stores the close time"
    (let [{:keys [conn member-id]} (tc/new-system "poll-close-action")
          {:keys [poll-id]}       (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})
          now                    (:now (pts/action-state conn member-id))]
      (is (= [[:db/transact
               [[:db/add (pts/poll-ref poll-id) :poll/poll-status :poll.status/closed]
                [:db/add (pts/poll-ref poll-id) :poll/closes-at now]]
               {}]
              support/clear-loading]
             (actions/close-poll-action
              (pts/action-state conn member-id)
              (pts/poll-detail-signals poll-id)))))))

(deftest cast-single-choice-vote-action-test
  (testing "casts a single-choice vote and redirects to detail"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-single-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})
          selected-option-id     (first option-ids)
          effects                (actions/cast-vote-action
                                  (pts/action-state conn member-id)
                                  (pts/vote-signals poll-id [selected-option-id]))
          [transact redirect]    effects
          [_ tx-data opts]       transact
          vote-map               (first (filter :poll.vote/poll-vote-id tx-data))]
      (is (= {:poll.vote/poll-option (pts/option-ref selected-option-id)
              :poll.vote/author      [:member/member-id member-id]
              :poll.vote/created-at  :db/now}
             (select-keys vote-map [:poll.vote/poll-option
                                    :poll.vote/author
                                    :poll.vote/created-at])))
      (is (uuid? (:poll.vote/poll-vote-id vote-map)))
      (is (= [[:db/add (pts/poll-ref poll-id) :poll/votes (:db/id vote-map)]]
             (filterv #(and (vector? %)
                            (= :db/add (first %))
                            (= :poll/votes (nth % 2)))
                      tx-data)))
      (is (= {} opts))
      (is (= [:app.datastar/respond-sse
              [[:app.datastar.sse/redirect (urls/link-poll poll-id)]]]
             redirect))))

  (testing "changes an existing vote by retracting the old vote"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-change-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})
          old-vote-id            (pts/seed-vote! conn poll-id member-id (first option-ids))
          tx-data                (-> (actions/cast-vote-action
                                      (pts/action-state conn member-id)
                                      (pts/vote-signals poll-id [(second option-ids)]))
                                     first
                                     second)]
      (is (= [[:db/retractEntity (pts/vote-ref old-vote-id)]]
             (filterv #(and (vector? %)
                            (= :db/retractEntity (first %)))
                      tx-data)))))

  (testing "rejects selecting more than one option for a single-choice poll"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-single-too-many-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})]
      (is (= "You can only vote for one option"
             (vote-top-error
              (actions/cast-vote-action
               (pts/action-state conn member-id)
               (pts/vote-signals poll-id option-ids))))))))

(deftest cast-multiple-choice-vote-action-test
  (testing "casts a valid multiple-choice vote"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-multiple-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn
                                                       member-id
                                                       {:poll/poll-status :poll.status/open
                                                        :poll/poll-type   :poll.type/multiple
                                                        :poll/min-choice   2
                                                        :poll/max-choice   3}
                                                       ["A" "B" "C" "D"])
          tx-data                (-> (actions/cast-vote-action
                                      (pts/action-state conn member-id)
                                      (pts/vote-signals poll-id (take 2 option-ids)))
                                     first
                                     second)
          vote-maps              (filter :poll.vote/poll-vote-id tx-data)]
      (is (= #{(pts/option-ref (first option-ids))
               (pts/option-ref (second option-ids))}
             (into #{} (map :poll.vote/poll-option) vote-maps)))))

  (testing "rejects too few multiple-choice selections"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-multiple-too-few-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn
                                                       member-id
                                                       {:poll/poll-status :poll.status/open
                                                        :poll/poll-type   :poll.type/multiple
                                                        :poll/min-choice   2
                                                        :poll/max-choice   3}
                                                       ["A" "B" "C"])]
      (is (= "You can only vote for between 2 and 3 options"
             (vote-top-error
              (actions/cast-vote-action
               (pts/action-state conn member-id)
               (pts/vote-signals poll-id [(first option-ids)])))))))

  (testing "rejects too many multiple-choice selections"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-multiple-too-many-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn
                                                       member-id
                                                       {:poll/poll-status :poll.status/open
                                                        :poll/poll-type   :poll.type/multiple
                                                        :poll/min-choice   1
                                                        :poll/max-choice   2}
                                                       ["A" "B" "C"])]
      (is (= "You can only vote for between 1 and 2 options"
             (vote-top-error
              (actions/cast-vote-action
               (pts/action-state conn member-id)
               (pts/vote-signals poll-id option-ids))))))))

(deftest cast-vote-rejection-test
  (testing "rejects voting for draft and closed polls"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-status-action")
          draft                  (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/draft})
          closed                 (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/closed})]
      (is (= [support/clear-loading
              :app.datastar/assoc-state
              [:poll-vote]
              {:_top {:error "You cannot vote for a poll that is not open"}}]
             (error-summary
              (actions/cast-vote-action
               (pts/action-state conn member-id)
               (pts/vote-signals (:poll-id draft) [(first (:option-ids draft))])))))
      (is (= "You cannot vote for a poll that is not open"
             (vote-top-error
              (actions/cast-vote-action
               (pts/action-state conn member-id)
               (pts/vote-signals (:poll-id closed) [(first (:option-ids closed))])))))))

  (testing "rejects option ids that do not belong to the poll"
    (let [{:keys [conn member-id]} (tc/new-system "poll-vote-foreign-option-action")
          poll                    (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})
          other                   (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})]
      (is (= "That option does not belong to this poll"
             (vote-top-error
              (actions/cast-vote-action
               (pts/action-state conn member-id)
               (pts/vote-signals (:poll-id poll) [(first (:option-ids other))]))))))))
