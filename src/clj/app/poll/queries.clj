(ns app.poll.queries
  (:require
   [app.datomic :as d]
   [app.poll.domain :as domain]
   [app.util :as util]
   [tick.core :as t]))

(def poll-pattern
  [:poll/poll-id :poll/title :poll/description
   :poll/min-choice :poll/max-choice
   :poll/poll-type :poll/poll-status
   :poll/autoremind?
   :poll/closes-at :poll/created-at])

(def vote-detail-pattern
  [:poll.vote/poll-vote-id
   {:poll.vote/author [:member/member-id :member/nick]}
   :poll.vote/created-at
   {:poll.vote/poll-option [:poll.option/poll-option-id :poll.option/value]}])

(def poll-detail-pattern
  [:poll/poll-id :poll/title :poll/description
   :poll/min-choice :poll/max-choice
   :poll/poll-type :poll/poll-status :poll/chart-type
   :poll/author :poll/autoremind?
   :poll/closes-at :poll/created-at
   {:poll/votes vote-detail-pattern}
   {:poll/options [:poll.option/poll-option-id :poll.option/value :poll.option/position]}])

(def ^:private utc-zone (t/zone "UTC"))
(def ^:private vienna-zone (t/zone "Europe/Vienna"))

(defn- utc-local->vienna-local [value]
  (cond
    (nil? value)
    nil

    (inst? value)
    (t/date-time (t/in value vienna-zone))

    :else
    (let [utc-instant (t/instant (t/in (t/date-time value) utc-zone))]
      (t/date-time (t/in utc-instant vienna-zone)))))

(defn- normalize-poll-time [poll]
  (cond-> poll
    (contains? poll :poll/closes-at)
    (update :poll/closes-at utc-local->vienna-local)))

(defn retrieve-poll [db poll-id]
  (when-let [poll (d/find-by db :poll/poll-id (util/ensure-uuid poll-id) poll-detail-pattern)]
    (normalize-poll-time (domain/db->poll poll))))

(defn votes-for-poll [db poll-id]
  (->>
   (d/q '[:find (pull ?vote pat)
          :in $ ?poll-id pat
          :where
          [?poll :poll/poll-id ?poll-id]
          [?poll :poll/votes ?vote]]
        db (util/ensure-uuid poll-id) vote-detail-pattern)
   (mapv first)))

(defn poll-counts [db poll]
  (letfn [(count-attr [kw]
            (or
             (ffirst
              (d/q '[:find (count ?child)
                     :in $ ?poll-id ?kw
                     :where
                     [?e :poll/poll-id ?poll-id]
                     [?e ?kw ?child]]
                   db (:poll/poll-id poll) kw))
             0))]
    {:poll/options-count (count-attr :poll/options)
     :poll/votes-count   (count-attr :poll/votes)
     :poll/voter-count   (count (distinct (map #(get-in % [:poll.vote/author :member/member-id])
                                               (votes-for-poll db (:poll/poll-id poll)))))}))

(defn- member-map [member-or-id]
  (if (map? member-or-id)
    member-or-id
    {:member/member-id (util/ensure-uuid member-or-id)}))

(defn member-has-voted?
  "Return true if the member has voted in the given poll"
  [db poll-id member-or-id]
  (some some?
        (d/q '[:find (pull ?vote [:poll.vote/poll-vote-id :poll.vote/poll-option])
               :in $ ?poll-id ?member-id
               :where
               [?poll :poll/poll-id ?poll-id]
               [?poll :poll/votes ?vote]
               [?vote :poll.vote/author ?member-id]]
             db
             (util/ensure-uuid poll-id)
             (d/ref (member-map member-or-id) :member/member-id))))

(defn votes-for-poll-by [db poll-id member-or-id]
  (->>
   (d/q '[:find (pull ?vote [:poll.vote/poll-vote-id
                             {:poll.vote/poll-option [:poll.option/poll-option-id]}
                             :poll.vote/created-at])
          :in $ ?poll-id ?member
          :where
          [?poll :poll/poll-id ?poll-id]
          [?poll :poll/votes ?vote]
          [?vote :poll.vote/author ?member]]
        db
        (util/ensure-uuid poll-id)
        (d/ref (member-map member-or-id) :member/member-id))
   (mapv first)))

(defn- load-polls [q-result]
  (->> q-result
       (mapv first)
       (mapv domain/db->poll)
       (map normalize-poll-time)
       (sort-by :poll/created-at)
       vec))

(defn find-open-polls
  ([db]
   (find-open-polls db poll-pattern))
  ([db pattern]
   (->>
    (d/find-all-by db :poll/poll-status :poll.status/open pattern)
    (load-polls))))

(defn unanswered-open-polls
  [db member]
  (->> (find-open-polls db)
       (remove #(member-has-voted? db (:poll/poll-id %) member))
       (sort-by :poll/closes-at)
       vec))

(defn find-draft-polls
  ([db]
   (find-draft-polls db poll-pattern))
  ([db pattern]
   (->>
    (d/find-all-by db :poll/poll-status :poll.status/draft pattern)
    (load-polls))))

(defn find-closed-polls
  ([db]
   (find-closed-polls db poll-pattern))
  ([db pattern]
   (->>
    (d/find-all-by db :poll/poll-status :poll.status/closed pattern)
    (load-polls))))

(defn with-counts [db poll]
  (merge poll (poll-counts db poll)))

(defn index-page-data [db]
  {:running-polls (vec (concat (map #(with-counts db %) (find-open-polls db))
                               (map #(with-counts db %) (find-draft-polls db))))
   :past-polls    (mapv #(with-counts db %) (find-closed-polls db))})

(defn detail-page-data [db poll-id current-member-id]
  (when-let [poll (retrieve-poll db poll-id)]
    (let [poll-id (:poll/poll-id poll)
          member  (when current-member-id {:member/member-id current-member-id})]
      {:poll         (with-counts db poll)
       :votes        (votes-for-poll db poll-id)
       :member-votes (when member
                       (votes-for-poll-by db poll-id member))
       :has-voted?   (when member
                       (member-has-voted? db poll-id member))})))
