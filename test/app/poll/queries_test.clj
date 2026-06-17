(ns app.poll.queries-test
  (:require
   [app.poll.queries :as queries]
   [app.poll.test-support :as pts]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(deftest index-page-data-test
  (testing "groups running and past polls and enriches them with counts"
    (let [{:keys [conn member-id]} (tc/new-system "poll-index-query")
          open                    (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})
          draft                   (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/draft})
          closed                  (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/closed})]
      (pts/seed-vote! conn (:poll-id open) member-id (first (:option-ids open)))
      (let [{:keys [running-polls past-polls]} (queries/index-page-data (d/db conn))]
        (is (= {:running-statuses [:poll.status/open :poll.status/draft]
                :past-statuses    [:poll.status/closed]
                :running-ids      [(:poll-id open) (:poll-id draft)]
                :past-ids         [(:poll-id closed)]
                :open-counts      {:poll/options-count 2
                                   :poll/votes-count   1
                                   :poll/voter-count   1}}
               {:running-statuses (mapv :poll/poll-status running-polls)
                :past-statuses    (mapv :poll/poll-status past-polls)
                :running-ids      (mapv :poll/poll-id running-polls)
                :past-ids         (mapv :poll/poll-id past-polls)
                :open-counts      (select-keys (first running-polls)
                                               [:poll/options-count
                                                :poll/votes-count
                                                :poll/voter-count])}))))))

(deftest retrieve-poll-time-zone-test
  (testing "returns close times as Europe/Vienna local datetimes"
    (let [{:keys [conn member-id]} (tc/new-system "poll-retrieve-query")
          seeded                  (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/draft})
          poll                    (queries/retrieve-poll (d/db conn) (:poll-id seeded))]
      (is (= pts/default-closes-at
             (:poll/closes-at poll))))))
