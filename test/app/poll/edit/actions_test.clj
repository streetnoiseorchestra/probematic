(ns app.poll.edit.actions-test
  (:require
   [app.nexus.actions :as support]
   [app.poll.domain :as domain]
   [app.poll.edit.actions :as actions]
   [app.poll.test-support :as pts]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(def expected-closes-at
  (domain/closes-at-inst (t/date-time "2026-06-17T19:00")))

(defn field-error-summary [effects]
  [(first effects)
   (first (second effects))
   (second (second effects))
   (get-in (second effects) [2 :_error])])

(deftest create-poll-action-test
  (testing "returns a draft poll transaction with options and redirects to detail"
    (let [{:keys [conn member-id]} (tc/new-system "poll-create-action")
          effects                 (actions/create-poll-action
                                   (pts/action-state conn member-id)
                                   {:poll-edit (pts/valid-edit-form "")})
          [transact redirect]     effects
          [_ tx-data opts]        transact
          poll-tx                 (first tx-data)
          poll-id                 (:poll/poll-id poll-tx)
          option-maps             (filter :poll.option/poll-option-id tx-data)
          option-adds             (filter #(and (vector? %)
                                                (= :db/add (first %))
                                                (= :poll/options (nth % 2)))
                                          tx-data)]
      (is (= :db/transact (first transact)))
      (is (uuid? poll-id))
      (is (= {:poll/title       "Pizza Poll"
              :poll/description "Choose dinner"
              :poll/poll-type   :poll.type/single
              :poll/poll-status :poll.status/draft
              :poll/chart-type   :poll.chart.type/bar
              :poll/author       [:member/member-id member-id]
              :poll/autoremind?  false
              :poll/closes-at    expected-closes-at}
             (select-keys poll-tx [:poll/title
                                   :poll/description
                                   :poll/poll-type
                                   :poll/poll-status
                                   :poll/chart-type
                                   :poll/author
                                   :poll/autoremind?
                                   :poll/closes-at])))
      (is (= #{{:poll.option/position 0
                :poll.option/value    "Pizza"}
               {:poll.option/position 1
                :poll.option/value    "Tacos"}}
             (into #{} (map #(select-keys % [:poll.option/position
                                             :poll.option/value])) option-maps)))
      (is (= 2 (count option-adds)))
      (is (= {} opts))
      (is (= [:app.datastar/respond-sse
              [[:app.datastar.sse/redirect (urls/link-poll poll-id)]]]
             redirect))
      (is (= 2 (count effects))))))

(deftest update-poll-action-test
  (testing "updates draft poll fields and replaces options"
    (let [{:keys [conn member-id]} (tc/new-system "poll-update-draft-action")
          {:keys [poll-id option-ids]} (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/draft})
          form                    (pts/multiple-edit-form poll-id)
          effects                 (actions/update-poll-action
                                   (pts/action-state conn member-id)
                                   {:poll-edit form})
          [transact redirect]     effects
          [_ tx-data opts]        transact
          poll-tx                 (first tx-data)
          option-maps             (filter :poll.option/poll-option-id tx-data)
          option-retractions      (filter #(and (vector? %)
                                                (= :db/retractEntity (first %))
                                                (= :poll.option/poll-option-id (first (second %))))
                                          tx-data)
          option-adds             (filter #(and (vector? %)
                                                (= :db/add (first %))
                                                (= :poll/options (nth % 2)))
                                          tx-data)]
      (is (= {:poll/poll-id     poll-id
              :poll/title       "Pizza Poll"
              :poll/description "Choose dinner"
              :poll/poll-type   :poll.type/multiple
              :poll/poll-status :poll.status/draft
              :poll/chart-type   :poll.chart.type/bar
              :poll/min-choice   1
              :poll/max-choice   2
              :poll/closes-at    expected-closes-at}
             (select-keys poll-tx [:poll/poll-id
                                   :poll/title
                                   :poll/description
                                   :poll/poll-type
                                   :poll/poll-status
                                   :poll/chart-type
                                   :poll/min-choice
                                   :poll/max-choice
                                   :poll/closes-at])))
      (is (= (set option-ids)
             (into #{} (map (comp second second)) option-retractions)))
      (is (= #{{:poll.option/position 0 :poll.option/value "Pizza"}
               {:poll.option/position 1 :poll.option/value "Tacos"}
               {:poll.option/position 2 :poll.option/value "Salad"}}
             (into #{} (map #(select-keys % [:poll.option/position
                                             :poll.option/value])) option-maps)))
      (is (= 3 (count option-adds)))
      (is (= {} opts))
      (is (= [:app.datastar/respond-sse
              [[:app.datastar.sse/redirect (urls/link-poll poll-id)]]]
             redirect))))

  (testing "rejects changed type or options for an open poll"
    (let [{:keys [conn member-id]} (tc/new-system "poll-update-open-action")
          {:keys [poll-id]}       (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/open})
          form                    (assoc (pts/multiple-edit-form poll-id)
                                         :options [{:value "Changed"}
                                                   {:value "Tacos"}])
          effects                 (actions/update-poll-action
                                   (pts/action-state conn member-id)
                                   {:poll-edit form})]
      (is (= [support/clear-loading
              :app.datastar/assoc-state
              [:poll-edit]
              {:_top {:error "Open poll type and options cannot be changed."}}]
             (field-error-summary effects)))))

  (testing "rejects edits for a closed poll"
    (let [{:keys [conn member-id]} (tc/new-system "poll-update-closed-action")
          {:keys [poll-id]}       (pts/seed-poll! conn member-id {:poll/poll-status :poll.status/closed})
          effects                 (actions/update-poll-action
                                   (pts/action-state conn member-id)
                                   {:poll-edit (pts/valid-edit-form poll-id)})]
      (is (= [support/clear-loading
              :app.datastar/assoc-state
              [:poll-edit]
              {:_top {:error "Closed polls cannot be edited."}}]
             (field-error-summary effects))))))

(deftest delete-poll-action-test
  (testing "returns a retract transaction effect and redirects to the polls list"
    (let [{:keys [conn member-id]} (tc/new-system "poll-delete-action")
          {:keys [poll-id]}       (pts/seed-poll! conn member-id {})]
      (is (= [[:db/transact
               [[:db/retractEntity (pts/poll-ref poll-id)]]
               {}]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/redirect (urls/link-polls-home)]]]]
             (actions/delete-poll-action
              (pts/action-state conn member-id)
              {:poll-edit {:poll-id (str poll-id)}}))))))

(deftest validate-poll-field-action-test
  (testing "validates one field and stores only that field error"
    (let [form (assoc (pts/multiple-edit-form "")
                      :min-choice "3"
                      :max-choice "2"
                      :validate-field "max-choice")]
      (is (= [[:app.datastar/merge-state
               [:poll-edit]
               (dissoc form :validate-field)]
              [:app.datastar/assoc-state
               [:poll-edit :_error :max-choice]
               {:error "Min choices must be less than or equal to max choices."}]]
             (actions/validate-poll-field-action
              {:tr pts/tr}
              {:poll-edit form})))))

  (testing "validates a required field"
    (let [form (assoc (pts/valid-edit-form "")
                      :title ""
                      :validate-field "title")]
      (is (= {:error "Poll Title is required."}
             (-> (actions/validate-poll-field-action
                  {:tr pts/tr}
                  {:poll-edit form})
                 second
                 last))))))

(deftest option-page-state-actions-test
  (testing "add-option appends an empty option row"
    (is (= [[:app.datastar/assoc-state
             [:poll-edit :options]
             [{:value "Pizza"} {:value ""}]]
            support/clear-loading]
           (actions/add-option-action
            {}
            {:poll-edit {:options [{:value "Pizza"}]}}))))

  (testing "remove-option removes the targeted option row"
    (is (= [[:app.datastar/assoc-state
             [:poll-edit :options]
             [{:value "Pizza"} {:value "Salad"}]]
            support/clear-loading]
           (actions/remove-option-action
            {}
            {:poll-edit {:options [{:value "Pizza"}
                                   {:value "Tacos"}
                                   {:value "Salad"}]}
             :targetid "1"})))))
