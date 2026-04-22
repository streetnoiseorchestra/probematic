(ns app.poll.controller
  (:require
   [app.auth :as auth]
   [app.datomic :as d]
   [app.email :as email]
   [app.poll.domain :as domain]
   [app.queries :as q]
   [app.schemas :as s]
   [app.util :as util]
   [app.util.http :as common]
   [com.yetanalytics.squuid :as sq]
   [malli.util :as mu]
   [tick.core :as t]))

(def UpdatePoll
  "This schema describes the http post we receive when updating an instrument"
  [:map {:name ::UpdatePoll}
   [:poll-title :string]
   [:poll-description :string]
   [:poll-type :string]
   [:min-choice {:optional true} :int]
   [:max-choice {:optional true} :int]
   [:author-id :string]
   [:poll-status :string]
   [:closes-at ::s/date-time]
   [:autoremind? ::s/checkbox-boolean]
   [:options [:map
              [:value [:vector :string]]]]])

(defn update-poll-schema [open? multiple?]
  (cond-> UpdatePoll
    multiple? (mu/required-keys)
    open? (->
           (mu/dissoc :options))))

(defn update-poll-tx [{:keys [created-at poll-title poll-description poll-type min-choice max-choice author-id poll-status closes-at autoremind?] :as decoded} poll-id]
  (let [poll {:poll/poll-id poll-id
              :poll/title poll-title
              :poll/description poll-description
              :poll/poll-type (domain/str->poll-type poll-type)
              :poll/chart-type :poll.chart.type/bar
              :poll/min-choice min-choice
              :poll/max-choice max-choice
              :poll/author [:member/member-id (util/ensure-uuid! author-id)]
              :poll/poll-status (domain/str->status poll-status)
              :poll/closes-at (t/date-time closes-at)
              :poll/autoremind? autoremind?
              :poll/created-at (if (or (nil? created-at) (= "" created-at))  (t/inst)
                                   (t/inst created-at))}
        maybe-remove-min-max (fn [{:keys [poll-type] :as poll}]
                               (if (= poll-type :poll.type/single)
                                 (-> poll
                                     (dissoc :poll/min-choice)
                                     (dissoc :poll/max-choice))
                                 poll))]

    (-> poll
        (maybe-remove-min-max)
        (util/remove-nils)
        (domain/poll->db))))

(defn zip-params [in]
  ;; turn a map like this
  ;; {:value ["1" "2" "3"], :id ["id1" "id2" "id3"]}
  ;; into this:
  ;; [{:value "1" :id "id1" } ...]
  (let [keys (keys in)
        values (vals in)]
    (mapv (fn [i]
            (zipmap keys (mapv #(nth % i) values)))
          (range (count (first values))))))

(defn update-options-txs [poll-ref idx {:keys [value]}]
  (let [id (sq/generate-squuid)]
    [{:poll.option/poll-option-id id
      :poll.option/position       idx
      :poll.option/value          value
      :db/id  (str id)}
     [:db/add poll-ref :poll/options (str id)]]))

(defn retract-options-txs [{:poll/keys [options]}]
  (map (fn [option]
         [:db/retractEntity (d/ref option)])
       options))

(defn validate-update-invariants [existing-poll updated-poll]
  (assert (= (:poll/poll-status updated-poll) (:poll/poll-status existing-poll))
          "Cannot change poll-status on update")
  (when (= :poll.status/open (:poll/poll-status existing-poll))
    (assert (= (:poll/poll-type existing-poll) (:poll/poll-type updated-poll))
            "Cannot change an open poll's type")))

(defn update-poll! [{:keys [db] :as req}]
  (let [params  (common/unwrap-params req)
        open?   (= (:poll-status params) "open")
        schema  (update-poll-schema open? (= (:poll-type params) "multiple"))
        params  (update params "autoremind?" #(if % % false))
        decoded (util/remove-nils (s/decode schema params))
        options (zip-params (:options decoded))]
    (tap> {:param   (common/unwrap-params req)
           :decoded decoded
           :options options
           :ok?     (s/valid? schema decoded)
           :explain (s/explain-human schema decoded)})
    (if (s/valid? schema decoded)
      (let [poll-id            (common/path-param-uuid! req :poll-id)
            existing-poll      (q/retrieve-poll db poll-id)
            poll-tx            (update-poll-tx decoded poll-id)
            _                  (validate-update-invariants existing-poll poll-tx)
            retract-opts-txs   (when-not open? (retract-options-txs existing-poll))
            new-opts-txs       (when-not open? (mapcat #(into [] %) (map-indexed (partial update-options-txs (d/ref existing-poll)) options)))
            txs                (concat [poll-tx] retract-opts-txs new-opts-txs)
            _                  (tap> {:poll-tx          poll-tx
                                      :retract-opts-txs retract-opts-txs
                                      :new-opts-txs     new-opts-txs
                                      :txs              txs})
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data txs})]
        {:poll (q/retrieve-poll db-after poll-id)})
      {:error (s/explain-human schema decoded)})))

(defn create-poll! [req]
  (let [params  (common/unwrap-params req)
        schema (update-poll-schema false (= (:poll-type params) "multiple"))
        params (update params "autoremind?" #(if % % false))
        decoded (util/remove-nils (s/decode schema params))
        options (zip-params (:options decoded))]
    (tap> {:param   (common/unwrap-params req)
           :decoded decoded
           :options options
           :ok?     (s/valid? schema decoded)
           :explain (s/explain-human schema decoded)})
    (if (s/valid? schema decoded)
      (let [poll-id     (sq/generate-squuid)
            poll-tx     (assoc (update-poll-tx decoded poll-id) :db/id "new-poll")
            options-txs (mapcat #(into [] %) (map-indexed (partial update-options-txs "new-poll") options))
            txs         (concat [poll-tx] options-txs)
            _           (tap> {:poll-tx     poll-tx
                               :poll-id     poll-id
                               :txs         txs
                               :options-txs options-txs})
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data txs})]
        {:poll (q/retrieve-poll db-after poll-id)})

      {:error (s/explain-human schema decoded)})))

(defn publish-poll! [{:keys [db] :as req} poll-id]
  (let [{:poll/keys [poll-status] :as poll} (q/retrieve-poll db poll-id)]
    (if (= poll-status :poll.status/draft)
      (let [result {:poll (-> (d/transact-wrapper! req {:tx-data [[:db/add (d/ref poll) :poll/poll-status :poll.status/open]]})
                              :db-after
                              (q/retrieve-poll poll-id))}]
        (email/send-poll-opened! req poll-id)
        result)
      {:error "Poll is not a draft"})))

(defn delete-poll! [{:keys [db] :as req} poll-id]
  (let [poll (q/retrieve-poll db poll-id)]
    (d/transact-wrapper! req {:tx-data [[:db/retractEntity (d/ref poll)]]})))

(defn close-poll! [{:keys [db] :as req} poll-id]
  (let [poll (q/retrieve-poll db poll-id)]
    {:poll (-> (d/transact-wrapper! req {:tx-data [[:db/add (d/ref poll) :poll/poll-status :poll.status/closed]
                                                   [:db/add (d/ref poll) :poll/closes-at (domain/closes-at-inst (t/date-time))]]})
               :db-after
               (q/retrieve-poll poll-id))}))

(defn draft-polls [db]
  (->> (q/find-draft-polls db)
       (mapv #(merge % (q/poll-counts db %)))))

(defn open-polls [db]
  (->> (q/find-open-polls db)
       (mapv #(merge % (q/poll-counts db %)))))

(defn past-polls [db]
  (->> (q/find-closed-polls db)
       (mapv #(merge % (q/poll-counts db %)))))

(defn retrieve-poll [db poll-id]
  (q/retrieve-poll db poll-id))

(defn retrieve-vote-for [db poll member]
  (q/votes-for-poll-by db (:poll/poll-id poll) member))

(defn member-has-voted? [db poll-id member]
  (q/member-has-voted? db (util/ensure-uuid! poll-id) member))

(defn votes->option-id-set
  "Given a list of votes, return a set of option ids that were voted for"
  [votes]
  (into #{} (map #(get-in % [:poll.vote/poll-option :poll.option/poll-option-id]) votes)))

(defn vote-tx [member idx option-id]
  {:db/id (str "vote_" idx)
   :poll.vote/poll-vote-id (sq/generate-squuid)
   :poll.vote/poll-option [:poll.option/poll-option-id option-id]
   :poll.vote/author [:member/member-id (:member/member-id member)]
   :poll.vote/created-at (t/inst)})

(defn retract-old-votes-txs [existing-votes]
  (map
   (fn [{:poll.vote/keys [poll-vote-id]}] [:db/retractEntity [:poll.vote/poll-vote-id poll-vote-id]])
   existing-votes))

(defn poll-add-votes-txs [poll-id vote-txs]
  (mapv
   (fn [vote-tx]
     [:db/add [:poll/poll-id poll-id] :poll/votes (:db/id vote-tx)])
   vote-txs))

(defn validate-vote [tr {:poll/keys [min-choice max-choice poll-status poll-type]} votes]

  (let [errors []
        errors (cond
                 (and (= :poll.type/single poll-type) (> (count votes) 1))
                 (conj errors {:message (tr [:poll/error-only-one])})

                 (and (= :poll.type/multiple poll-type)
                      (not (>= min-choice (count votes)))
                      (not (<= (count votes) max-choice)))
                 (conj errors {:message (tr [:poll/error-between] [min-choice max-choice])})

                 :else errors)
        errors (if (not= poll-status :poll.status/open)
                 (conj errors {:message (tr [:poll/error-not-open])})
                 errors)]
    (if (empty? errors)
      nil
      errors)))

(defn cast-vote! [{:keys [db tr] :as req}]
  (let [{:keys [poll-id vote]} (common/unwrap-params req)
        vote  (map util/ensure-uuid! (util/ensure-coll vote))
        poll-id (util/ensure-uuid! poll-id)
        poll (retrieve-poll db poll-id)
        member (auth/get-current-member req)
        existing-votes (q/votes-for-poll-by db poll-id member)
        errors (validate-vote tr poll vote)]
    (if errors
      {:errors errors}
      (let [retract-vote-txs (retract-old-votes-txs existing-votes)
            vote-txs (map-indexed (partial vote-tx member) vote)
            poll-txs (poll-add-votes-txs poll-id vote-txs)
            tx-data (concat poll-txs vote-txs retract-vote-txs)
            _ (tap> {:p (common/unwrap-params req)
                     :vote-tx vote-txs
                     :retract-vote-txs retract-vote-txs
                     :poll-tx poll-txs
                     :existing existing-votes
                     :tx-data tx-data})
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data tx-data})]

        {:poll (q/retrieve-poll db-after poll-id)}))))

(clojure.core/comment

  (do
    (require '[integrant.repl.state :as state])
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))) ;; rcf

  (open-polls db)
  (q/find-draft-polls db q/poll-detail-pattern)

                                        ;
  )
