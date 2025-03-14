(ns app.gigs.service
  (:refer-clojure :exclude [comment])
  (:require
   [app.auth :as auth]
   [app.email :as email]
   [app.config :as config]
   [app.datomic :as d]
   [app.discourse :as discourse]
   [app.gigs.domain :as domain]
   [app.jobs.gig-events :as gig.events]
   [app.probeplan.domain :as probeplan.domain]
   [app.probeplan.stats :as stats]
   [app.queries :as q]
   [app.schemas :as s]
   [app.secret-box :as secret-box]
   [app.util :as util]
   [app.util.http :as common]
   [clojure.data :as clojure.data]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]
   [ctmx.rt :as rt]
   [app.datomic.shim :as datomic]
   [medley.core :as m]
   [tick.core :as t]))

(def str->plan (zipmap (map name domain/plans) domain/plans))
(def str->motivation (zipmap (map name domain/motivations) domain/motivations))
(def str->status (zipmap (map name domain/statuses) domain/statuses))
(def str->gig-type (zipmap (map name domain/gig-types) domain/gig-types))

(defn attach-attendance [db member {:gig/keys [gig-id] :as gig}]
  (assoc gig :attendance
         (q/attendance-for-gig db gig-id (:member/member-id member))))

(defn gigs-planned-for
  "Return the gigs that the member as supplied an attendance plan for"
  [db member]
  (assert member)
  (->>
   (q/results->gigs (d/q '[:find (pull ?gig pattern)
                           :in $ ?member ?reference-time pattern
                           :where
                           [?gig :gig/date ?date]
                           [(>= ?date ?reference-time)]
                           (not [?gig :gig/status :gig.status/cancelled])
                           [?a :attendance/gig ?gig]
                           [?a :attendance/member ?member]
                           [?a :attendance/plan ?plan]
                           [(!= ?plan :plan/no-response)]
                           [(!= ?plan :plan/unknown)]]
                         db (d/ref member) (q/date-midnight-today!) q/gig-pattern))
   (map (partial attach-attendance db member))))

(defn gigs-needing-plan
  "Return the gigs that the member needs to supply an attendance plan for"
  [db member]
  (let [gigs-with-no-attendance
        (->>
         (d/q '[:find (pull ?gig pattern)
                :in $ ?member ?reference-time pattern
                :where
                [?gig :gig/date ?date]
                [(>= ?date ?reference-time)]
                [?gig :gig/gig-id ?gig-id]
                (not [?gig :gig/status :gig.status/cancelled])
                (not-join [?gig ?member]
                          [?a :attendance/gig ?gig]
                          [?a :attendance/member ?member])]

              db (d/ref member) (q/date-midnight-today!) q/gig-detail-pattern)
         q/results->gigs
         (map (fn [gig]
                (assoc gig :attendance {:attendance/section (:member/section member)
                                        :attendance/member  member
                                        :attendance/plan :plan/no-response}))))
        gigs-with-unknown-attendance (->> (d/q '[:find (pull ?gig pattern)
                                                 :in $ ?member ?reference-time pattern
                                                 :where
                                                 [?gig :gig/date ?date]
                                                 (not [?gig :gig/status :gig.status/cancelled])
                                                 [(>= ?date ?reference-time)]
                                                 [?a :attendance/gig ?gig]
                                                 [?a :attendance/member ?member]
                                                 (or
                                                  [(missing? $ ?a :attendance/plan)]
                                                  [?a :attendance/plan :plan/no-response]
                                                  [?a :attendance/plan :plan/unknown])]

                                               db (d/ref member) (q/date-midnight-today!) q/gig-detail-pattern)
                                          q/results->gigs
                                          (map (partial attach-attendance db member)))]

    (->> (concat gigs-with-no-attendance gigs-with-unknown-attendance)
         (sort-by :gig/call-time)
         (sort-by :gig/date))))

(clojure.core/comment
  (q/attendance-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCQw"
                        "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA2NP7ggoM")

  (gigs-needing-plan db {:member/member-id "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA2NP7ggoM"}))

(defn page
  ([offset limit coll]
   (if (= limit ##Inf)
     (drop offset coll)
     (take limit (drop offset coll))))
  ([limit coll]
   (page coll 0 limit)))

(defn gigs-past-page [db offset limit]
  (->>
   (d/q '[:find (pull ?e pattern)
          :in $ ?time pattern
          :where
          [?e :gig/gig-id _]
          [?e :gig/date ?date]
          [(< ?date ?time)]] db (q/date-midnight-today!) [:gig/gig-id :gig/date :db/id])
   (map first)
   (sort-by :gig/date t/>)
   (page offset limit)
   (map :db/id)
   (d/pull-many db q/gig-pattern)
   (sort-by :gig/date t/>)
   (mapv domain/db->gig)))

(defn gigs-past-two-weeks [db]
  (let [now (q/date-midnight-today!)
        then (t/<< now (t/new-duration 14 :days))]
    (q/gigs-between db then now)))

(defn songs-not-played [plays all-songs]
  (->> all-songs
       (remove (fn [song]
                 (->> plays
                      (map #(-> % :played/song :song/song-id))
                      (some (fn [p] (= p (:song/song-id song)))))))))

(defn make-reminder [gig-id member-id remind-in-days]
  (domain/reminder->db
   {:reminder/reminder-id (sq/generate-squuid)
    :reminder/gig [:gig/gig-id (common/ensure-uuid gig-id)]
    :reminder/member [:member/member-id (common/ensure-uuid member-id)]
    :reminder/reminder-status :reminder-status/pending
    :reminder/reminder-type :reminder-type/gig-attendance
    :reminder/remind-at  (t/>> (t/instant) (t/new-period remind-in-days :days))}))

(defn reset-reminder [reminder remind-in-days]
  (domain/reminder->db
   (-> reminder
       (update :reminder/gig (fn [{:gig/keys [gig-id]}] [:gig/gig-id gig-id]))
       (update :reminder/member (fn [{:member/keys [member-id]}] [:member/member-id member-id]))
       (assoc :reminder/reminder-status :reminder-status/pending)
       (assoc :reminder/remind-at (t/>> (t/instant) (t/new-period remind-in-days :days))))))

(defn get-reminder [{:keys [db]} gig-id member-id]
  (q/gig-reminder-for db gig-id member-id))

(defn set-reminder! [{:keys [datomic-conn db] :as req} gig-id member-id remind-in-days]
  (let [existing-reminder (q/gig-reminder-for db gig-id member-id)]
    (when remind-in-days
      (if existing-reminder
        (do
          (tap> {:replace existing-reminder})
          (datomic/transact datomic-conn {:tx-data [(reset-reminder existing-reminder remind-in-days)]}))
        (let [reminder (make-reminder gig-id member-id remind-in-days)]
          (tap> {:new-reminder reminder})
          (datomic/transact datomic-conn {:tx-data [reminder]}))))))

(defn send-reminder-to-all! [req gig-id]
  (email/send-gig-reminder-to-all! req gig-id))

(defn get-attendance
  ([db gig-id member-id]
   (get-attendance db (q/gig+member gig-id member-id)))
  ([db gig+member]
   (assert (string? gig+member))
   (d/find-by db :attendance/gig+member gig+member q/attendance-pattern)))

(defn transact-attendance! [{:keys [datomic-conn] :as req} attendance-txs gig-id member-id]
  (let [result (d/transact datomic-conn {:tx-data attendance-txs})
        gig+member (q/gig+member gig-id member-id)]
    (if (d/db-ok? result)
      (do
        (gig.events/trigger-gig-edited req gig-id :attendance)
        {:attendance (get-attendance (:db-after result) gig+member)})
      result)))

(defn update-attendance-plan-tx [attendance plan]
  [:db/add (d/ref attendance) :attendance/plan plan])

(defn touch-attendance-tx [attendance]
  [:db/add (d/ref attendance) :attendance/updated (t/inst)])

(defn create-attendance-tx [db gig-id member-id]
  {:attendance/gig+member (q/gig+member gig-id member-id)
   :attendance/gig        [:gig/gig-id gig-id]
   :attendance/member     [:member/member-id member-id]
   :attendance/updated    (t/inst)
   :attendance/section    [:section/name (q/section-for-member db member-id)]})

(defn create-attendance-plan-tx [db gig-id member-id plan]
  (assoc (create-attendance-tx db gig-id member-id) :attendance/plan plan))

(defn update-attendance-plan! [{:keys [datomic-conn db] :as req} gig-id]
  (let [{:keys [member-id plan] :as params} (common/unwrap-params req)
        member-id (util/ensure-uuid! member-id)
        _ (assert gig-id)
        _ (assert member-id)
        attendance (get-attendance db gig-id member-id)
        plan-kw (str->plan plan)
        attendance-txs (if attendance
                         [(update-attendance-plan-tx attendance plan-kw)
                          (touch-attendance-tx attendance)]
                         [(create-attendance-plan-tx db gig-id member-id plan-kw)])]

    (assert plan-kw (format  "unknown plan value: '%s'" plan))
    (transact-attendance! req attendance-txs gig-id member-id)))

(defn update-attendance-from-link! [{:keys [datomic-conn db system params] :as req}]
  (let [answer-enc (:answer params)
        answer (secret-box/decrypt answer-enc (config/app-secret-key (:env system)))
        member-id (util/ensure-uuid! (:member/member-id answer))
        gig-id (util/ensure-uuid! (:gig/gig-id answer))
        gig (q/retrieve-gig db gig-id)
        member (q/retrieve-member db member-id)
        reminder (:reminder answer)
        plan-kw ((set domain/plans)  (:attendance/plan answer))]
    (assert gig)
    (assert member)
    (when (not reminder) (assert plan-kw))
    (if reminder
      (do
        (set-reminder! req gig-id member-id 2)
        {:gig gig :member member})

      (when (domain/in-future? gig)
        (let [attendance (get-attendance db gig-id member-id)
              attendance-txs (if attendance
                               [(update-attendance-plan-tx attendance plan-kw)
                                (touch-attendance-tx attendance)]
                               [(create-attendance-plan-tx db  gig-id member-id plan-kw)])]
          (transact-attendance! req  attendance-txs gig-id member-id)
          {:gig gig :member member})))))

(defn create-attendance-comment-tx [db gig-id member-id comment]
  (assoc (create-attendance-tx db gig-id member-id) :attendance/comment comment))

(defn update-attendance-comment-tx [attendance comment]
  [:db/add (d/ref attendance) :attendance/comment comment])

(defn retract-attendance-comment-tx [attendance]
  [:db/retract (d/ref attendance) :attendance/comment])

(defn update-attendance-comment! [{:keys [datomic-conn db] :as req} gig-id]
  (let [{:keys [member-id comment] :as params} (common/unwrap-params req)
        member-id (util/ensure-uuid! member-id)
        _ (assert gig-id)
        _ (assert member-id)
        attendance (get-attendance db gig-id member-id)
        attendance-txs (if attendance
                         (if (str/blank? comment)
                           (if (:attendance/comment attendance)
                             [(retract-attendance-comment-tx attendance)
                              (touch-attendance-tx attendance)]
                             :nop)
                           [(update-attendance-comment-tx attendance comment)
                            (touch-attendance-tx attendance)])
                         (if (str/blank? comment)
                           :nop
                           [(create-attendance-comment-tx db gig-id member-id comment)]))]

    (if (= :nop attendance-txs)
      {:attendance attendance}
      (transact-attendance! req attendance-txs gig-id member-id))))

(defn create-attendance-motivation-tx [db gig-id member-id motivation]
  (assoc (create-attendance-tx db gig-id member-id) :attendance/motivation motivation))

(defn update-attendance-motivation-tx [attendance motivation]
  [:db/add (d/ref attendance) :attendance/motivation motivation])

(defn update-attendance-motivation! [{:keys [datomic-conn db] :as req} gig-id]
  (let [{:keys [member-id motivation] :as params} (common/unwrap-params req)
        member-id (util/ensure-uuid! member-id)
        _ (assert gig-id)
        _ (assert member-id)
        attendance (get-attendance db gig-id member-id)
        motivation-kw (str->motivation motivation)
        attendance-txs  (if attendance
                          [(update-attendance-motivation-tx attendance motivation-kw)
                           (touch-attendance-tx attendance)]
                          [(create-attendance-motivation-tx db gig-id member-id motivation-kw)])]
    (assert motivation-kw (format  "unknown motivation value: %s" motivation))
    (transact-attendance! req attendance-txs  gig-id member-id)))

(defn transact-gig! [datomic-conn gig-txs gig-id]
  (let [result (datomic/transact datomic-conn {:tx-data gig-txs})]
    {:gig (q/retrieve-gig (:db-after result) gig-id)
     :gig-before (q/retrieve-gig (:db-before result) gig-id)
     :db-after (:db-after result)}))

(defn post-comment! [{:keys [datomic-conn] :as req}]
  (let [{:keys [body]} (common/unwrap-params req)
        author (d/ref (auth/get-current-member req))
        gig-id (common/path-param-uuid! req :gig/gig-id)
        gig-txs [{:db/id "new_comment"
                  :comment/comment-id (sq/generate-squuid)
                  :comment/body body
                  :comment/author author
                  :comment/created-at (t/inst)}
                 [:db/add [:gig/gig-id gig-id] :gig/comments "new_comment"]]]
    (:gig
     (transact-gig! datomic-conn gig-txs gig-id))))

(def UpdateGig
  "This schema describes the http post we receive when updating a gig's info"
  (s/schema
   [:map {:name ::UpdateGig}
    [:gig-id :uuid]
    [:title ::s/non-blank-string]
    [:date ::s/date]
    [:end-date {:optional true} ::s/date]
    [:location ::s/non-blank-string]
    [:contact {:optional true} :uuid]
    [:gig-type (s/enum-from (map name domain/gig-types))]
    [:status (s/enum-from (map name domain/statuses))]
    [:call-time ::s/time]
    [:set-time {:optional true} ::s/time]
    [:end-time {:optional true} ::s/time]
    [:leader {:optional true} :string]
    [:rehearsal-leader1 {:optional true} :string]
    [:rehearsal-leader2 {:optional true} :string]
    [:pay-deal {:optional true} :string]
    [:outfit {:optional true} :string]
    [:more-details {:optional true} :string]
    [:setlist {:optional true} :string]
    [:post-gig-plans {:optional true} :string]
    [:topic-id {:optional true} :string]]))

(defn maybe-remove-association-tx [eid attr v]
  (when-not v
    [:db/retract eid attr]))

(defn can-edit-gig? [req]
  (cond
    (auth/current-user-admin? req) true
    (not (domain/gig-archived? (:gig req))) true
    :else false))

(defn can-edit-gig?! [req]
  (when-not (can-edit-gig? req)
    (throw (ex-info "Not allowed to edit gig" {:gig (:gig req)}))))

(defn update-gig! [{:keys [datomic-conn] :as req}]
  (can-edit-gig?! req)
  (let [gig-id (common/path-param-uuid! req :gig/gig-id)
        params   (-> req common/unwrap-params util/remove-nils (assoc :gig-id gig-id)
                     (update :contact util/blank->nil)
                     (update :rehearsal-leader1 util/blank->nil)
                     (update :rehearsal-leader2 util/blank->nil)
                     (update :end-date util/blank->nil))
        gig-ref [:gig/gig-id gig-id]
        notify? (rt/parse-boolean (:notify? params))
        takeover-topic? (rt/parse-boolean (:takeover-topic? params))
        decoded  (util/remove-nils (s/decode UpdateGig params))]
    (if (s/valid? UpdateGig decoded)
      (let [resolve-member-ref  (fn [member-id] [:member/member-id (util/ensure-uuid! member-id)])
            tx (-> decoded
                   (common/ns-qualify-key :gig)
                   (set/rename-keys {:gig/topic-id :forum.topic/topic-id})
                   (update :gig/status str->status)
                   (update :gig/gig-type str->gig-type)
                   (update :forum.topic/topic-id discourse/parse-topic-id)
                   (m/update-existing :gig/contact resolve-member-ref)
                   (m/update-existing :gig/rehearsal-leader1 resolve-member-ref)
                   (m/update-existing :gig/rehearsal-leader2 resolve-member-ref)
                   (util/remove-nils)
                   (domain/gig->db))
            extra-txs (util/remove-nils [(maybe-remove-association-tx gig-ref :gig/contact (:contact params))
                                         (maybe-remove-association-tx gig-ref :gig/rehearsal-leader1 (:rehearsal-leader1 params))
                                         (maybe-remove-association-tx gig-ref :gig/rehearsal-leader2 (:rehearsal-leader2 params))])
            result (transact-gig! datomic-conn (conj extra-txs tx) gig-id)]
        (gig.events/trigger-gig-details-edited req notify? takeover-topic? result)
        result)
      (s/throw-error "Cannot update the gig. The gig data is invalid." nil  UpdateGig decoded))))

(defn create-gig! [{:keys [datomic-conn] :as req}]
  (let [gig-id (str (sq/generate-squuid))
        params (-> req util/unwrap-params util/remove-empty-strings util/remove-nils (assoc :gig-id gig-id))
        notify? (= "on" (:notify? params))
        thread? (= "on" (:thread? params))
        decoded (util/remove-nils (s/decode UpdateGig params))
        tx (-> decoded
               (common/ns-qualify-key :gig)
               (set/rename-keys {:topic-id :forum.topic/topic-id})
               (update :gig/status str->status)
               (update :gig/gig-type str->gig-type)
               (m/update-existing :gig/contact (fn [member-id] [:member/member-id member-id]))
               (domain/gig->db))
        result (transact-gig! datomic-conn [tx] gig-id)]
    (gig.events/trigger-gig-created req notify? thread? gig-id)
    result))

(defn -delete-gig! [{:keys [datomic-conn db] :as req} gig-id]
  (try
    (let [gig-ref     [:gig/gig-id gig-id]
          gig (q/retrieve-gig db gig-id)
          attendances (mapv (fn [{:attendance/keys [gig+member]}]
                              [:db/retractEntity [:attendance/gig+member gig+member]])  (q/attendances-for-gig db gig-id))
          played      (mapv (fn [{:played/keys [play-id]}]
                              [:db/retractEntity [:played/play-id play-id]]) (q/plays-by-gig db gig-id))
          txs         (concat attendances played
                              [[:db/retractEntity [:setlist/gig gig-ref]]
                               [:db/retractEntity [:probeplan/gig gig-ref]]
                               [:db/retractEntity gig-ref]])]

      (datomic/transact datomic-conn {:tx-data txs})
      (when (> (count played) 0)
        (stats/calc-play-stats-in-bg! datomic-conn))
      (gig.events/trigger-gig-deleted req gig-id)
      true)
    (catch Throwable t
      (if
       (re-find #".*Cannot resolve key.*" (ex-message t))
        true
        (throw t)))))

(defn delete-gig! [req]
  (-delete-gig! req (common/path-param-uuid! req :gig/gig-id)))

(defn reconcile-setlist [eid new-song-tuples current-song-tuples]
  (let [[added removed] (clojure.data/diff (set new-song-tuples)  (set current-song-tuples))
        add-tx          (map #(-> [:db/add eid :setlist.v1/ordered-songs %]) (filter some? added))
        remove-tx       (map #(-> [:db/retract eid :setlist.v1/ordered-songs %]) (filter some? removed))]
    (concat add-tx remove-tx)))

(defn update-setlist!
  "Updates the setlist for the current gig. song-ids are ordered. Returns the songs in the setlist."
  [{:keys [datomic-conn db] :as req} song-ids]
  (let [gig-id      (common/path-param-uuid! req :gig/gig-id)
        song-tuples (map-indexed (fn [idx sid] [[:song/song-id (parse-uuid sid)] idx]) song-ids)
        current     (q/setlist-song-tuples-for-gig db  gig-id)
        txs         (reconcile-setlist "setlist" song-tuples current)
        tx          {:setlist/gig     [:gig/gig-id gig-id]
                     :db/id           "setlist"
                     :setlist/version :setlist.version/v1}
        txs         (concat [tx] txs)
        result      (datomic/transact datomic-conn {:tx-data txs})]
    (gig.events/trigger-gig-edited req gig-id :setlist)

    (q/setlist-songs-for-gig (:db-after result) gig-id)))

(defn reconcile-probeplan [eid new-song-tuples current-song-tuples]
  (let [[added removed] (clojure.data/diff (set new-song-tuples)  (set current-song-tuples))
        add-tx          (map #(-> [:db/add eid :probeplan.classic/ordered-songs %]) (filter some? added))
        remove-tx       (map #(-> [:db/retract eid :probeplan.classic/ordered-songs %]) (filter some? removed))]
    (concat add-tx remove-tx)))

(defn probeplan-song-tx [{:keys [song-id emphasis position] :as s}]
  [[:song/song-id (util/ensure-uuid song-id)]
   (Integer/parseInt position)
   (if (rt/parse-boolean emphasis)
     :probeplan.emphasis/intensive
     probeplan.domain/probeplan-classic-default-emphasis)])

(defn update-probeplan!
  "Updates the probeplan for the current gig. song-ids are ordered. Returns the songs in the setlist."
  [{:keys [datomic-conn db] :as req} song-id-emphases]
  (let [gig-id (common/path-param-uuid! req :gig/gig-id)
        song-tuples (map probeplan-song-tx song-id-emphases)
        current (q/probeplan-song-tuples-for-gig db gig-id)
        txs (reconcile-probeplan "probeplan" song-tuples current)
        tx {:probeplan/gig [:gig/gig-id gig-id]
            :db/id "probeplan"
            :probeplan/version :probeplan.version/classic}
        txs (concat [tx] txs)
        result (datomic/transact datomic-conn {:tx-data txs})]
    (gig.events/trigger-gig-edited req gig-id :probeplan)
    (q/probeplan-songs-for-gig (:db-after result) gig-id)))

(clojure.core/comment
  (update-setlist! {:datomic-conn conn :db db :path-params {:gig/gig-id "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMDCiYe6CAw"}}
                   [#uuid "01844740-3eed-856d-84c1-c26f07068207"]) ;

  (q/setlist-song-tuples-for-gig db #uuid "01885050-f9e2-822f-8e0e-b78f390ac08e") ;
  (q/probeplan-song-tuples-for-gig db #uuid "01885050-f9e2-822f-8e0e-b78f390ac08e")

  (q/probeplan-songs-for-gig db #uuid "01885050-f9e2-822f-8e0e-b78f390ac08e")

  (reconcile-setlist "A" (set [[:song/song-id #uuid "01844740-3eed-856d-84c1-c26f07068207" 0]
                               [:song/song-id #uuid "01844740-3eed-856d-84c1-c26f0706820a" 1]])
                     (set [[:song/song-id #uuid "01844740-3eed-856d-84c1-c26f07068207" 1]
                           [:song/song-id #uuid "01844740-3eed-856d-84c1-c26f0706820a" 0]])) ;
  ;; p
  )

(defn upsert-log-play-tx [gig-id {:keys [song-id play-id feeling intensive]}]
  (let [song-id (common/ensure-uuid song-id)
        play-id (or (common/ensure-uuid play-id) (sq/generate-squuid))
        rating (keyword feeling)
        emphasis (keyword (if (string? intensive) intensive (second intensive)))]
    {:played/gig [:gig/gig-id gig-id]
     :played/song [:song/song-id song-id]
     :played/rating rating
     :played/gig+song (pr-str [gig-id song-id])
     :played/play-id play-id
     :played/emphasis emphasis}))

(defn log-play! [{:keys [datomic-conn] :as req} gig-id]
  (let [play-params (->> (common/unwrap-params req)
                         (map (fn [play]
                                ;; songs that are not played should not be marked as intensive
                                (if (= (:feeling play) "play-rating/not-played")
                                  (assoc play :intensive "play-emphasis/durch")
                                  play))))
        tx-data (map #(upsert-log-play-tx gig-id %) play-params)
        result (d/transact datomic-conn {:tx-data tx-data})]
    (if (d/db-ok? result)
      (do
        (stats/calc-play-stats-in-bg! datomic-conn)
        {:plays (q/plays-by-gig (:db-after result) gig-id)})
      result)))

(clojure.core/comment

  (do
    (require '[integrant.repl.state :as state])
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))) ;; rcf

  (def m0 (q/retrieve-member db "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICAu9bgmgoM"))
  (gigs-needing-plan db m0)

  (q/plays-by-gig db "0185a673-9f36-8f74-b737-1b53a510398c")

  (q/gigs-after db (q/date-midnight-today!) q/gig-detail-pattern)

  (q/attendances-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCQw")
  (q/attendances-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCAw")
  (tap> {:result
         (q/attendance-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCQw"
                               "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA2NP7ggoM")})

  (gigs-past-page db 0 100)
  (gigs-past-page db 0 100)

  (->>
   (d/find-all (datomic/db conn) :gig/gig-id q/gig-detail-pattern)
   (map first)
   (filter (fn [{:gig/keys [date]}]
             (t/< date (t/<< (q/date-midnight-today!) (t/new-duration 2 :days)))))
   (map :gig/gig-id)
   (map (partial -delete-gig! {:datomic-conn conn :db (datomic/db conn)}))
   (doall))

  (q/retrieve-gig (datomic/db conn) #uuid "01860c05-7101-8e35-a230-d7561dd146ca")
  (q/plays-by-gig (datomic/db conn) #uuid "01860c05-7101-8e35-a230-d7561dd146ca")

;;
  )
