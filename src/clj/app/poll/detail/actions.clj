(ns app.poll.detail.actions
  (:require
   [app.nexus.actions :as support]
   [app.poll.queries :as queries]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]))

(defn- poll-id-from [signals signal-key]
  (let [params (or (get signals signal-key) signals)]
    (util/ensure-uuid! (or (:poll-id params) (:targetid signals) (:targetid params)))))

(defn- poll-ref [poll-id]
  [:poll/poll-id poll-id])

(defn- vote-ref [vote-id]
  [:poll.vote/poll-vote-id vote-id])

(defn- option-ref [option-id]
  [:poll.option/poll-option-id option-id])

(defn- invalid [path message]
  [support/clear-loading
   [:app.datastar/assoc-state path {:_error {:_top {:error message}}}]])

(defn open-poll-action [{:keys [db tr]} signals]
  (let [poll-id (poll-id-from signals :poll-detail)
        poll    (queries/retrieve-poll db poll-id)]
    (cond
      (nil? poll)
      (invalid [:poll-detail] (tr [:error/poll-not-found]))

      (not= :poll.status/draft (:poll/poll-status poll))
      (invalid [:poll-detail] (tr [:poll/open-hint]))

      :else
      [[:db/transact
        [[:db/add (poll-ref poll-id) :poll/poll-status :poll.status/open]]
        {}]
       [:app.poll/send-poll-opened poll-id]
       support/clear-loading])))

(defn close-poll-action [{:keys [now]} signals]
  (let [poll-id (poll-id-from signals :poll-detail)]
    [[:db/transact
      [[:db/add (poll-ref poll-id) :poll/poll-status :poll.status/closed]
       [:db/add (poll-ref poll-id) :poll/closes-at now]]
      {}]
     support/clear-loading]))

(defn- selected-values [selected]
  (cond
    (map? selected)
    (keep (fn [[option-id selected?]]
            (when (and selected? (not= "false" selected?))
              (name option-id)))
          selected)

    :else
    (util/ensure-coll selected)))

(defn- selected-option-ids [{:keys [selected-options selected-option]}]
  (let [selected (or selected-options selected-option)]
    (->> (selected-values selected)
         (remove #(str/blank? (str %)))
         (mapv util/ensure-uuid!)
         distinct
         vec)))

(defn- poll-option-ids [poll]
  (into #{} (map :poll.option/poll-option-id) (:poll/options poll)))

(defn- vote-count-valid? [{:poll/keys [poll-type min-choice max-choice]} vote-count]
  (case poll-type
    :poll.type/single (= 1 vote-count)
    :poll.type/multiple (and (<= (or min-choice 0) vote-count)
                             (<= vote-count (or max-choice ##Inf)))
    false))

(defn- vote-error [{:keys [tr]} poll selected-options]
  (cond
    (nil? poll)
    (tr [:error/poll-not-found])

    (not= :poll.status/open (:poll/poll-status poll))
    (tr [:poll/error-not-open])

    (not-every? (poll-option-ids poll) selected-options)
    (tr [:poll/error-invalid-option])

    (and (= :poll.type/single (:poll/poll-type poll))
         (not (vote-count-valid? poll (count selected-options))))
    (tr [:poll/error-only-one])

    (and (= :poll.type/multiple (:poll/poll-type poll))
         (not (vote-count-valid? poll (count selected-options))))
    (tr [:poll/error-between] [(:poll/min-choice poll) (:poll/max-choice poll)])))

(defn- vote-tx [member-id idx option-id]
  {:db/id                  (str "poll-vote-" idx)
   :poll.vote/poll-vote-id (sq/generate-squuid)
   :poll.vote/poll-option  (option-ref option-id)
   :poll.vote/author       [:member/member-id member-id]
   :poll.vote/created-at   :db/now})

(defn- vote-add-txs [poll-id vote-txs]
  (mapv (fn [vote-tx]
          [:db/add (poll-ref poll-id) :poll/votes (:db/id vote-tx)])
        vote-txs))

(defn- retract-existing-votes [existing-votes]
  (mapv (fn [{:poll.vote/keys [poll-vote-id]}]
          [:db/retractEntity (vote-ref poll-vote-id)])
        existing-votes))

(defn cast-vote-action [{:keys [db current-member-id] :as state} signals]
  (let [params              (or (:poll-vote signals) signals)
        poll-id             (util/ensure-uuid! (:poll-id params))
        selected-options    (selected-option-ids params)
        poll                (queries/retrieve-poll db poll-id)
        maybe-error-message (vote-error state poll selected-options)]
    (if maybe-error-message
      (invalid [:poll-vote]
               maybe-error-message)
      (let [existing-votes (queries/votes-for-poll-by db poll-id current-member-id)
            vote-txs       (map-indexed (partial vote-tx current-member-id) selected-options)
            tx-data        (vec (concat (vote-add-txs poll-id vote-txs)
                                        vote-txs
                                        (retract-existing-votes existing-votes)))]
        [[:db/transact tx-data {}]
         [:app.datastar/redirect (urls/link-poll poll-id)]]))))

(def actions
  {::open-poll  #'open-poll-action
   ::close-poll #'close-poll-action
   ::cast-vote  #'cast-vote-action})
