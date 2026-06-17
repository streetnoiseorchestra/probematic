(ns app.poll.test-support
  (:require
   [app.poll.domain :as domain]
   [datomic.api :as d]
   [tick.core :as t]))

(defn tr [[k] & [args]]
  (case k
    :error/is-required (str (first args) " is required.")
    :error/form-has-errors "Please fix the errors in the form."
    :error/poll-min-choice-greater-than-max "Min choices must be less than or equal to max choices."
    :error/poll-too-few-options "The poll needs at least as many options as the max choice count."
    :error/poll-edit-closed "Closed polls cannot be edited."
    :error/poll-open-immutable "Open poll type and options cannot be changed."
    :error/poll-not-found "Poll not found."
    :poll/title "Poll Title"
    :poll/description "Poll Description"
    :poll/closes-at "Automatically Closes At"
    :poll/min-choice "Min Choices"
    :poll/max-choice "Max Choices"
    :poll/options "Options"
    :poll/error-only-one "You can only vote for one option"
    :poll/error-between "You can only vote for between %1 and %2 options"
    :poll/error-not-open "You cannot vote for a poll that is not open"
    :poll/error-invalid-option "That option does not belong to this poll"
    (name k)))

(def created-at #inst "2026-01-01T10:00:00.000-00:00")
(def default-closes-at (t/date-time "2026-06-17T19:00"))

(defn poll-ref [poll-id]
  [:poll/poll-id poll-id])

(defn option-ref [option-id]
  [:poll.option/poll-option-id option-id])

(defn vote-ref [vote-id]
  [:poll.vote/poll-vote-id vote-id])

(defn seed-poll!
  ([conn member-id attrs]
   (seed-poll! conn member-id attrs ["Yes" "No"]))
  ([conn member-id attrs option-values]
   (let [poll-id     (or (:poll/poll-id attrs) (random-uuid))
         option-ids  (mapv (fn [_] (random-uuid)) option-values)
         poll-status (or (:poll/poll-status attrs) :poll.status/draft)
         poll-type   (or (:poll/poll-type attrs) :poll.type/single)
         poll        (merge {:poll/poll-id     poll-id
                             :poll/title       "Existing Poll"
                             :poll/description "Existing description"
                             :poll/poll-type   poll-type
                             :poll/poll-status poll-status
                             :poll/chart-type   :poll.chart.type/bar
                             :poll/author       [:member/member-id member-id]
                             :poll/closes-at    default-closes-at
                             :poll/created-at   created-at
                             :poll/autoremind?  false}
                            (when (= :poll.type/multiple poll-type)
                              {:poll/min-choice 1
                               :poll/max-choice (min 2 (count option-values))})
                            attrs)
         poll-tx     (assoc (domain/poll->db poll) :db/id "seed-poll")
         option-txs  (mapcat (fn [idx option-id value]
                               (let [tempid (str "option-" option-id)]
                                 [{:db/id                      tempid
                                   :poll.option/poll-option-id option-id
                                   :poll.option/position       idx
                                   :poll.option/value          value}
                                  [:db/add "seed-poll" :poll/options tempid]]))
                             (range)
                             option-ids
                             option-values)]
     @(d/transact conn (vec (concat [poll-tx] option-txs)))
     {:poll-id poll-id
      :option-ids option-ids})))

(defn seed-vote! [conn poll-id member-id option-id]
  (let [vote-id (random-uuid)]
    @(d/transact conn [{:db/id                  "seed-vote"
                        :poll.vote/poll-vote-id vote-id
                        :poll.vote/poll-option  (option-ref option-id)
                        :poll.vote/author       [:member/member-id member-id]
                        :poll.vote/created-at   created-at}
                       [:db/add (poll-ref poll-id) :poll/votes "seed-vote"]])
    vote-id))

(defn action-state [conn member-id]
  {:tr tr
   :db (d/db conn)
   :current-member-id member-id
   :now #inst "2026-06-17T18:00:00.000-00:00"})

(defn valid-edit-form [poll-id]
  {:poll-id      (str poll-id)
   :title        "Pizza Poll"
   :description  "Choose dinner"
   :poll-type    "single"
   :min-choice   ""
   :max-choice   ""
   :closes-at    "2026-06-17T19:00"
   :autoremind?  false
   :options      [{:value "Pizza"}
                  {:value "Tacos"}]})

(defn multiple-edit-form [poll-id]
  (assoc (valid-edit-form poll-id)
         :poll-type "multiple"
         :min-choice "1"
         :max-choice "2"
         :options [{:value "Pizza"}
                   {:value "Tacos"}
                   {:value "Salad"}]))

(defn poll-detail-signals [poll-id]
  {:poll-detail {:poll-id (str poll-id)}})

(defn vote-signals [poll-id option-ids]
  {:poll-vote {:poll-id (str poll-id)
               :selected-options (mapv str option-ids)}})
