(ns app.queries
  (:require
   [app.datomic :as d]
   [app.filestore.domain :as filestore.domain]
   [app.gigs.domain :as gig.domain]
   [app.insurance.domain :as insurance.domain]
   [app.ledger.domain :as ledger.domain]
   [app.poll.domain :as poll.domain]
   [app.settings.domain :as settings.domain]
   [app.util :as util]
   [clojure.java.io :as io]
   [app.datomic.shim :as datomic]
   [medley.core :as m]
   [tick.core :as t]))

(def file-pattern [:filestore.file/file-id
                   :filestore.file/atime
                   :filestore.file/mtime
                   :filestore.file/ctime
                   :filestore.file/hash
                   :filestore.file/size
                   :filestore.file/mime-type
                   :filestore.file/file-name])

(def image-pattern [:image/image-id
                    {:image/source-file file-pattern}
                    :image/width
                    :image/height
                    :image/filter-spec])

(def image-pattern-with-renditions
  (conj image-pattern {:image/renditions image-pattern}))

(def section-pattern [:section/name :section/default? :section/position :section/active?])

(def attendance-pattern [{:attendance/section [:section/name]}
                         {:attendance/member [:member/name :member/member-id :member/nick :member/email]}
                         :attendance/plan
                         :attendance/gig+member
                         {:attendance/gig [:gig/gig-id]}
                         :attendance/comment
                         :attendance/motivation
                         :attendance/updated])

(def travel-discount-type-pattern [:travel.discount.type/discount-type-name :travel.discount.type/discount-type-id :travel.discount.type/enabled?])
(def travel-discount-pattern
  [{:travel.discount/discount-type [:travel.discount.type/discount-type-name :travel.discount.type/discount-type-id]}
   :travel.discount/discount-id
   :travel.discount/expiry-date])

(def member-pattern [:member/member-id :member/username :member/name :member/nick :member/active? :member/phone :member/email :member/keycloak-id :member/avatar-template
                     {:member/section [:section/name]}])

(def member-detail-pattern [:member/member-id :member/name :member/nick :member/active? :member/phone :member/email
                            :member/username :member/keycloak-id
                            :member/discourse-id :member/avatar-template
                            {:member/travel-discounts [{:travel.discount/discount-type [:travel.discount.type/discount-type-name :travel.discount.type/discount-type-id]}
                                                       :travel.discount/discount-id
                                                       :travel.discount/expiry-date]}
                            {:member/section [:section/name]}
                            {:instrument/_owner [:instrument/name :instrument/instrument-id
                                                 {:instrument/category [:instrument.category/name]}]}])

(def song-pattern [:song/title :song/song-id :song/active? :song/solo-info
                   :song/last-played-on :song/total-plays :song/total-performances :song/total-rehearsals])
(def song-pattern-detail [:song/title :song/song-id
                          :song/total-plays :song/total-performances :song/total-rehearsals :song/last-played-on
                          :song/lyrics
                          :forum.topic/topic-id
                          {:song/last-rehearsal [:gig/gig-id :gig/title :gig/date]}
                          {:song/last-performance [:gig/gig-id :gig/title]}
                          :song/active? :song/composition-credits :song/arrangement-credits :song/arrangement-notes :song/origin  :song/solo-info])

(def gig-pattern [:gig/gig-id :gig/title :gig/status :gig/date :gig/end-date :gig/call-time :gig/set-time :gig/location :gig/gig-type :gig/gigo-id])
(def gig-detail-pattern [:gig/gig-id :gig/title :gig/status :gig/date :gig/location :gig/gigo-id
                         :gig/end-date  :gig/pay-deal :gig/call-time :gig/set-time
                         :gig/end-time :gig/description :gig/outfit :gig/setlist :gig/leader :gig/post-gig-plans
                         :forum.topic/topic-id :gig/more-details :gig/gig-type
                         {:gig/comments [{:comment/author [:member/name :member/nick :member/member-id :member/avatar-template]}
                                         :comment/body :comment/comment-id :comment/created-at]}
                         {:gig/contact [:member/name :member/member-id :member/nick]}
                         {:gig/rehearsal-leader1 [:member/name :member/member-id :member/nick :member/email]}
                         {:gig/rehearsal-leader2 [:member/name :member/member-id :member/nick :member/email]}])

(def reminder-pattern [:reminder/remind-at :reminder/reminder-id
                       {:reminder/member member-detail-pattern}
                       {:reminder/gig gig-detail-pattern}
                       :reminder/reminder-status
                       :reminder/reminder-type])

(def play-pattern [{:played/gig gig-pattern}
                   {:played/song [:song/song-id :song/title]}
                   :played/gig+song
                   :played/rating
                   :played/play-id
                   :played/emphasis])

(def instrument-coverage-detail-pattern
  [:instrument.coverage/coverage-id
   :instrument.coverage/private?
   :instrument.coverage/value
   :instrument.coverage/item-count
   :instrument.coverage/change
   :instrument.coverage/status
   :instrument.coverage/insurer-id
   {:insurance.policy/_covered-instruments
    [:insurance.policy/policy-id
     :insurance.policy/currency
     :insurance.policy/name
     :insurance.policy/status
     :insurance.policy/effective-at
     :insurance.policy/effective-until
     :insurance.policy/premium-factor
     {:insurance.policy/coverage-types
      [:insurance.coverage.type/name :insurance.coverage.type/description :insurance.coverage.type/premium-factor :insurance.coverage.type/type-id]}
     {:insurance.policy/category-factors
      [{:insurance.category.factor/category
        [:instrument.category/name
         :instrument.category/category-id]}
       :insurance.category.factor/factor
       :insurance.category.factor/category-factor-id]}]}
   {:instrument.coverage/instrument
    [:instrument/name
     :instrument/instrument-id
     :instrument/images-share-url
     :instrument/make
     :instrument/model
     :instrument/description
     :instrument/serial-number
     :instrument/build-year
     {:instrument/images image-pattern-with-renditions}
     {:instrument/owner [:member/name :member/member-id]}
     {:instrument/category [:instrument.category/category-id
                            :instrument.category/code
                            :instrument.category/name]}]}
   {:instrument.coverage/types
    [:insurance.coverage.type/name
     :insurance.coverage.type/description
     :insurance.coverage.type/type-id
     :insurance.coverage.type/premium-factor]}])

(def policy-lite-pattern [:insurance.policy/policy-id
                          :insurance.policy/currency
                          :insurance.policy/name
                          :insurance.policy/status
                          :insurance.policy/effective-at
                          :insurance.policy/effective-until
                          :insurance.policy/premium-factor
                          {:insurance.policy/coverage-types
                           [:insurance.coverage.type/description :insurance.coverage.type/name :insurance.coverage.type/premium-factor :insurance.coverage.type/type-id]}
                          {:insurance.policy/category-factors
                           [{:insurance.category.factor/category
                             [:instrument.category/name
                              :instrument.category/category-id]}
                            :insurance.category.factor/factor
                            :insurance.category.factor/category-factor-id]}])

(def policy-pattern [:insurance.policy/policy-id
                     :insurance.policy/currency
                     :insurance.policy/name
                     :insurance.policy/status
                     :insurance.policy/effective-at
                     :insurance.policy/effective-until
                     :insurance.policy/premium-factor
                     {:insurance.policy/covered-instruments
                      [:instrument.coverage/coverage-id
                       :instrument.coverage/private?
                       :instrument.coverage/value
                       :instrument.coverage/item-count
                       :instrument.coverage/status
                       :instrument.coverage/change
                       :instrument.coverage/insurer-id
                       {:instrument.coverage/instrument
                        [:instrument/name
                         :instrument/instrument-id
                         :instrument/images-share-url
                         :instrument/images
                         :instrument/make
                         :instrument/model
                         :instrument/description
                         :instrument/serial-number
                         :instrument/build-year
                         {:instrument/owner [:member/name :member/member-id :member/avatar-template :member/email]}
                         {:instrument/category [:instrument.category/category-id
                                                :instrument.category/code
                                                :instrument.category/name]}]}
                       {:instrument.coverage/types
                        [:insurance.coverage.type/name
                         :insurance.coverage.type/description
                         :insurance.coverage.type/type-id
                         :insurance.coverage.type/premium-factor]}]}
                     {:insurance.policy/coverage-types
                      [:insurance.coverage.type/description :insurance.coverage.type/name :insurance.coverage.type/premium-factor :insurance.coverage.type/type-id]}
                     {:insurance.policy/category-factors
                      [{:insurance.category.factor/category
                        [:instrument.category/name
                         :instrument.category/category-id]}
                       :insurance.category.factor/factor
                       :insurance.category.factor/category-factor-id]}])

(def instrument-pattern [:instrument/name
                         :instrument/instrument-id
                         :instrument/images-share-url
                         :instrument/images
                         {:instrument/owner [:member/name :member/member-id]}
                         {:instrument/category [:instrument.category/category-id
                                                :instrument.category/code
                                                :instrument.category/name]}])

(def instrument-detail-pattern [:instrument/name
                                :instrument/instrument-id
                                :instrument/images-share-url
                                :instrument/make
                                :instrument/model
                                {:instrument/images [:image/image-id]}
                                :instrument/description
                                :instrument/build-year
                                :instrument/serial-number
                                {:instrument/owner [:member/name :member/member-id]}
                                {:instrument/category [:instrument.category/category-id
                                                       :instrument.category/code
                                                       :instrument.category/name]}])

(def setlist-v1-pattern [{:setlist/gig [:gig/gig-id]}
                         :setlist/version
                         :setlist.v1/songs])

(def poll-pattern [:poll/poll-id :poll/title :poll/description
                   :poll/min-choice :poll/max-choice
                   :poll/poll-type :poll/poll-status
                   :poll/autoremind?
                   :poll/closes-at :poll/created-at])

(def vote-detail-pattern [:poll.vote/poll-vote-id {:poll.vote/author [:member/member-id :member/nick]} :poll.vote/created-at
                          {:poll.vote/poll-option [:poll.option/poll-option-id :poll.option/value]}])

(def poll-detail-pattern
  [:poll/poll-id :poll/title :poll/description
   :poll/min-choice :poll/max-choice
   :poll/poll-type :poll/poll-status :poll/chart-type
   :poll/author :poll/autoremind?
   :poll/closes-at :poll/created-at
   {:poll/votes vote-detail-pattern}
   {:poll/options [:poll.option/poll-option-id :poll.option/value :poll.option/position]}])

(defn retrieve-poll [db poll-id]
  (poll.domain/db->poll
   (d/find-by db :poll/poll-id poll-id poll-detail-pattern)))

(defn votes-for-poll [db poll-id]
  (->>
   (d/q '[:find (pull ?vote pat)
          :in $ ?poll-id pat
          :where
          [?poll :poll/poll-id ?poll-id]
          [?poll :poll/votes ?vote]]
        db poll-id vote-detail-pattern)
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
                   db (:poll/poll-id poll)  kw))
             0))]
    {:poll/options-count (or (count-attr :poll/options) 0)
     :poll/votes-count (or (count-attr :poll/votes) 0)
     :poll/voter-count (or (count (distinct (map #(get-in % [:poll.vote/author :member/member-id]) (votes-for-poll db (:poll/poll-id poll))))) 0)}))

(defn member-has-voted?
  "Return true if the member has voted in the given poll"
  [db poll-id member]
  (some some?
        (d/q '[:find (pull ?vote [:poll.vote/poll-vote-id :poll.vote/poll-option])
               :in $ ?poll-id ?member-id
               :where
               [?poll :poll/poll-id ?poll-id]
               [?poll :poll/votes ?vote]
               [?vote :poll.vote/author ?member-id]]
             db poll-id
             (d/ref member :member/member-id))))

(defn votes-for-poll-by [db poll-id member]
  (->>
   (d/q '[:find (pull ?vote [:poll.vote/poll-vote-id {:poll.vote/poll-option [:poll.option/poll-option-id]} :poll.vote/created-at])
          :in $ ?poll-id ?member
          :where
          [?poll :poll/poll-id ?poll-id]
          [?poll :poll/votes ?vote]
          [?vote :poll.vote/author ?member]]
        db poll-id (d/ref member :member/member-id))
   (mapv first)))

(defn- load-polls [q-result]
  (->> q-result
       (mapv first)
       (mapv poll.domain/db->poll)
       (sort-by :poll/created-at)))

(defn find-open-polls
  ([db]
   (find-open-polls db poll-pattern))
  ([db pat]
   (->>
    (d/find-all-by db :poll/poll-status :poll.status/open pat)
    (load-polls))))

(defn find-draft-polls
  ([db]
   (find-draft-polls db poll-pattern))
  ([db pat]
   (->>
    (d/find-all-by db :poll/poll-status :poll.status/draft pat)
    (load-polls))))

(defn find-closed-polls
  ([db]
   (find-closed-polls db poll-pattern))
  ([db pat]
   (->>
    (d/find-all-by db :poll/poll-status :poll.status/closed pat)
    (load-polls))))

(defn retrieve-song [db song-id]
  (d/find-by db :song/song-id song-id song-pattern-detail))

(defn retrieve-sections [db]
  (->>
   (d/find-all db :section/name section-pattern)
   (mapv first)
   (sort-by :section/position)))

(defn retrieve-active-sections [db]
  (->>
   (d/find-all-by db :section/active? true section-pattern)
   (mapv first)
   (sort-by :section/position)))

(defn retrieve-section-by-name [db name]
  (d/find-by db :section/name name section-pattern))

(defn results->gigs [r]
  (->> r
       (mapv first)
       (mapv gig.domain/db->gig)
       (sort-by :gig/call-time)
       (sort-by :gig/date)))

(defn find-all-gigs [db]
  (results->gigs (d/find-all db :gig/gig-id gig-pattern)))

(defn retrieve-gig [db gig-id]
  (gig.domain/db->gig
   (d/find-by db :gig/gig-id (util/ensure-uuid gig-id) gig-detail-pattern)))

(defn retrieve-gig-by-gigo [db gigo-id]
  (gig.domain/db->gig
   (d/find-by db :gig/gigo-id gigo-id gig-detail-pattern)))

(defn gigs-missing-id [db]
  (mapv first
        (d/q '[:find (pull ?e pattern)
               :in $ pattern
               :where [?e :gig/gigo-id _]
               [(missing? $ ?e :gig/gig-id)]]
             db gig-pattern)))

(defn members-missing-id [db]
  (mapv first
        (d/q '[:find (pull ?e pattern)
               :in $ pattern
               :where [?e :member/member-id _]
               [(missing? $ ?e :member/member-id)]]
             db member-pattern)))

(defn gigs-before
  ([db instant]
   (gigs-before db instant gig-pattern))
  ([db instant pattern]
   (results->gigs  (d/q '[:find (pull ?e pattern)
                          :in $ ?time pattern
                          :where
                          [?e :gig/gig-id _]
                          [?e :gig/date ?date]
                          [(< ?date ?time)]] db instant pattern))))

(defn gigs-after
  ([db instant]
   (gigs-after db instant gig-pattern))
  ([db instant pattern]
   (results->gigs (d/q '[:find (pull ?e pattern)
                         :in $ ?reference-time pattern
                         :where
                         [?e :gig/gig-id _]
                         [?e :gig/date ?date]
                         [(>= ?date ?reference-time)]] db instant pattern))))

(defn gigs-between [db instant-start instant-end]
  (results->gigs (d/q '[:find (pull ?e pattern)
                        :in $ ?ref-start ?ref-end pattern
                        :where
                        [?e :gig/gig-id _]
                        [?e :gig/date ?date]
                        [(>= ?date ?ref-start)]
                        [(<= ?date ?ref-end)]] db
                      instant-start  instant-end gig-pattern)))

(defn date-midnight-today! []
  (-> (t/date)
      (t/at (t/midnight))
      (t/in "UTC")
      (t/inst)))

(defn gigs-future [db]
  (gigs-after db (date-midnight-today!)))

(defn gigs-past
  ([db]
   (gigs-before db (date-midnight-today!)))
  ([db pattern]
   (gigs-before db (date-midnight-today!) pattern)))

(defn gig+member [gig-id member-id]
  (pr-str [(str gig-id) (str member-id)]))

(defn retrieve-member [db member-id]
  (d/find-by db :member/member-id member-id member-detail-pattern))

(defn member-travel-discounts [member]
  (->> (:member/travel-discounts member)
       (sort-by (juxt #(get-in % [:travel.discount/discount-type
                                  :travel.discount.type/discount-type-name])
                      :travel.discount/expiry-date))))

(defn members-for-select
  [db]
  (mapv first
        (d/find-all db :member/member-id  [:member/name :member/nick :member/member-id :member/active?])))

(defn members-for-select-active
  [db]
  (filter :member/active?
          (members-for-select db)))

(defn ->policy [policy]
  (-> policy
      (update :insurance.policy/effective-at t/zoned-date-time)
      (update :insurance.policy/effective-until t/zoned-date-time)))

(defn policies [db]
  (->> (d/find-all db :insurance.policy/policy-id policy-pattern)
       (mapv #(->policy (first %)))
       (sort-by (juxt :insurance.policy/effective-until :insurance.policy/name))
       (reverse)))

(defn retrieve-policy [db policy-id]
  (->policy (d/find-by db :insurance.policy/policy-id policy-id policy-pattern)))

(defn insurance-policy-effective-as-of [db inst pattern]
  (->>
   (datomic/q '[:find (pull ?e pattern)
                :in $ pattern ?now
                :where [?e :insurance.policy/effective-until ?date]
                [(>= ?date ?now)]]
              db pattern inst)
   (map first)
   (sort-by :insurance.policy/effective-until)
   reverse
   first))

(defn ->instrument [query-result]
  query-result)

(defn instruments [db]
  (->> (d/find-all db :instrument/instrument-id instrument-pattern)
       (mapv #(->instrument (first %)))
       (sort-by (juxt #(get-in % [:instrument/owner :member/name]) :instrument/name))))

(defn retrieve-instrument
  ([db instrument-id]
   (retrieve-instrument db instrument-id instrument-detail-pattern))
  ([db instrument-id pattern]
   (->instrument (d/find-by db :instrument/instrument-id instrument-id pattern))))

(defn retrieve-coverage [db coverage-id]
  (d/find-by db :instrument.coverage/coverage-id coverage-id instrument-coverage-detail-pattern))

(defn instruments-for-member-covered-by [db member policy pattern]
  (when policy
    (->>
     (datomic/q '[:find (pull ?coverage pattern)
                  :in $ pattern ?member ?policy-id
                  :where
                  [?policy-id :insurance.policy/covered-instruments ?coverage]
                  [?coverage :instrument.coverage/instrument ?instr]
                  [?instr :instrument/owner ?member]]
                db pattern
                (d/ref member :member/member-id)
                (d/ref policy :insurance.policy/policy-id))
     (map first))))

(defn coverages-for-instrument [db instrument-id pattern]
  (->>
   (datomic/q '[:find (pull ?coverages pattern)
                :in $ pattern ?instrument
                :where
                [?coverages :instrument.coverage/instrument ?instrument]]
              db pattern [:instrument/instrument-id instrument-id])
   (map first)))

(defn retrieve-all-songs
  ([db]
   (retrieve-all-songs db song-pattern))
  ([db song-pattern]
   (util/isort-by :song/title
                  (mapv first
                        (d/find-all db :song/song-id song-pattern)))))

(defn retrieve-active-songs [db]
  (->>
   (d/find-all db :song/song-id song-pattern)
   (mapv first)
   (filter #(:song/active? %))
   (util/isort-by :song/title)))

(defn retrieve-songs [db song-ids]
  (if (empty? song-ids)
    []
    (filter #((set song-ids) (:song/song-id %))
            (retrieve-all-songs db))))

(defn active-members-by-section [db]
  (->>
   (datomic/q '[:find (pull ?section pattern)
                :in $ pattern
                :where
                [?section :section/name ?v]]
              db [:section/name
                  {:member/_section [:member/name :member/member-id :member/nick]}])
   (map first)))

(defn active-members [db]
  (->>
   (datomic/q '[:find (pull ?member pattern)
                :in $ pattern
                :where
                [?member :member/active? true]]
              db [:member/name :member/member-id :member/nick {:member/section [:section/name]} :member/email])
   (map first)
   (map #(update % :member/section :section/name))))

(defn attendance-for-gig
  "Return the member's attendance for the gig"
  [db gig-id member-id]
  (m/find-first #(= member-id (-> % :attendance/member :member/member-id))

                (->
                 (datomic/q '[:find (pull ?gig pattern)
                              :in $ ?gig-id ?gig+member pattern
                              :where
                              [?gig :gig/gig-id ?gig-id]
                              [?a :attendance/gig+member ?gig+member]]
                            db gig-id (gig+member gig-id member-id)
                            [:gig/title {:attendance/_gig attendance-pattern}])
                 ffirst
                 :attendance/_gig)))

(defn attendances-for-gig
  "Return a list of attendances for the gig for all members"
  [db gig-id]
  (->>
   (d/find-by db :gig/gig-id gig-id
              [:gig/gig-type :gig/title {:attendance/_gig attendance-pattern}])
   :attendance/_gig
   (map #(update % :attendance/section :section/name))
   ;; (group-by #(-> % :attendance/section :section/name))
   ))

(defn attendance-for-gig-with-all-active-members
  "Returns a list of maps :attendance/ with attendance attributes for all active members.
  If some member has a concrete plan response for the gig, that will be included, if not then the plan is set to no-response."
  [db gig-id]
  (let [plans (attendances-for-gig db gig-id)
        members  (active-members db)
        no-plan (remove (fn [member]
                          (m/find-first (fn [p]
                                          (= (:member/member-id member) (get-in p [:attendance/member :member/member-id]))) plans))
                        members)]
    (concat plans
            (map (fn [member]
                   {:attendance/section (:member/section member)
                    :attendance/member  member
                    :attendance/plan :plan/no-response}) no-plan))))

(defn member-nick-or-name [member]
  (if (:member/nick member)
    (:member/nick member)
    (:member/name member)))

(defn attendance-plans-by-section-for-gig
  "Like attendance-for-gig-with-all-active-members, but returns a list of maps, one for each section with :members attendance plans"
  [db attendance-for-gig plan-filter]
  (->> attendance-for-gig
       (group-by :attendance/section)
       (reduce (fn [acc [k v]]
                 (when k
                   (conj acc {:section/name k
                              :members
                              (cond->> v
                                (= plan-filter :committed-only?) (filter gig.domain/committed?)
                                (= plan-filter :uncommitted-only?) (filter gig.domain/uncommitted?)
                                (= plan-filter :no-response?) (filter gig.domain/no-response?)
                                true (util/isort-by #(-> % :attendance/member member-nick-or-name)))})))
               [])
       (map (fn [as]
              (assoc as :section/position (-> (retrieve-section-by-name db (:section/name as))
                                              :section/position))))
       (sort-by :section/position)))

(defn section-for-member [db member-id]
  (->
   (datomic/q '[:find ?section-name
                :in $ ?member-id
                :where
                [?member :member/member-id ?member-id]
                [?member :member/section ?section]
                [?section :section/name ?section-name]]
              db member-id)
   ffirst))

(defn member-by-email [db email]
  (d/find-by db :member/email email member-pattern))

(defn member-by-keycloak-id [db keycloak-id]
  (d/find-by db :member/keycloak-id keycloak-id member-pattern))

(defn setlist-for-gig [db gig-id]
  (-> (d/find-by db :setlist/gig [:gig/gig-id gig-id] setlist-v1-pattern)
      (update  :setlist.v1/songs #(map (partial datomic/pull db song-pattern) %))))

(defn setlist-song-tuples-for-gig [db gig-id]
  (->>
   (-> (d/find-by db :setlist/gig [:gig/gig-id gig-id] [:setlist.v1/ordered-songs])
       :setlist.v1/ordered-songs)
   (sort-by second)
   (map #(update % 0 (fn [eid] (->> (datomic/pull db [:song/song-id] eid)
                                    (into [])
                                    first))))))
(defn setlist-song-ids-for-gig [db gig-id]
  (->> (-> (d/find-by db :setlist/gig [:gig/gig-id gig-id] [:setlist.v1/ordered-songs])
           :setlist.v1/ordered-songs)
       (sort-by second)
       (map first)
       (map (partial datomic/pull db [:song/song-id]))
       (map :song/song-id)))

(defn probeplan-song-tuples-for-gig
  [db gig-id]
  (->> (d/q '[:find ?result
              :in $ ?gig-id
              :where
              [?e :probeplan/gig ?gig-id]
              [?e :probeplan.classic/ordered-songs ?tup]
              [(untuple  ?tup) [?song-eid ?position ?emphasis]]
              [?song-eid :song/song-id ?song-id]
              [(tuple ?song-id ?position ?emphasis) ?result]]
            db [:gig/gig-id gig-id])
       (mapv first)
       (mapv (fn [song]
               (update song 0 #(vector :song/song-id %))))))

(defn probeplan-songs-for-gig
  "Returns a list of maps with the following keys:

     :song/title
     :song/song-id
     :song/last-played-on
     :emphasis
     :position
  "
  [db gig-id]
  (->> (d/q '[:find ?result
              :in $ ?gig-id
              :where
              [?e :probeplan/gig ?gig-id]
              [?e :probeplan.classic/ordered-songs ?tup]
              [(untuple  ?tup) [?song-eid ?position ?emphasis]]
              [?song-eid :song/song-id ?song-id]
              [(tuple ?song-id ?position ?emphasis) ?result]]
            db [:gig/gig-id gig-id])
       (mapv first)
       (mapv (fn [[song-id position emphasis]]
               (merge
                (retrieve-song db song-id)
                {:position position
                 :emphasis emphasis})))
       (sort-by :position)))

(defn setlist-songs-for-gig
  "Returns a list of maps with the following keys:
  :song/title
  :song/song-id
  :position
  "
  [db gig-id]
  (->> (setlist-song-tuples-for-gig db gig-id)
       (mapv (fn [[[_ song-id] position]]
               (let [{:song/keys [title solo-info]} (retrieve-song db song-id)]
                 {:song/title title
                  :song/song-id song-id
                  :song/solo-info solo-info
                  :position position})))
       (sort-by :position)))

(defn planned-songs-for-gig
  "If the gig is a probe, then returns probeplan-songs-for gig, otherwise returns"
  [db gig-id]
  (let [{:gig/keys [gig-type]} (retrieve-gig db gig-id)]
    (if (= :gig.type/gig gig-type)
      (setlist-songs-for-gig db gig-id)
      ;; (setlist-for-gig db gig-id)
      (probeplan-songs-for-gig db gig-id))))

(defn sheet-music-by-song [db song-id]
  (->> (d/find-all-by db :sheet-music/song  [:song/song-id song-id] play-pattern)
       (mapv first)))

(defn sheet-music-dir-for-song [db song-id]
  (let [first-sheet (->>
                     (d/q '[:find
                            (pull ?sm pattern)
                            :in $ ?song pattern
                            :where
                            [?sm :sheet-music/section ?section]
                            [?sm :sheet-music/song ?song]]
                          db
                          [:song/song-id song-id]
                          [:sheet-music/sheet-id :sheet-music/title :file/webdav-path {:sheet-music/section [:section/name  :section/position :section/default?]}])
                     (mapv first)
                     first)]
    (when first-sheet
      (str "/" (.getParent (io/file (:file/webdav-path first-sheet)))))))

(defn sheet-music-for-song
  "Returns a list of sections. If the section has sheet music, then the map will have a :sheet-music/_section key with the sheet music for that section."
  [db song-id]
  (let [sections-with-sheets (->>
                              (d/q '[:find
                                     (pull ?sm pattern)
                                     :in $ ?song pattern
                                     :where
                                     [?sm :sheet-music/section ?section]
                                     [?sm :sheet-music/song ?song]]
                                   db
                                   [:song/song-id song-id]
                                   [:sheet-music/sheet-id :sheet-music/title :file/webdav-path {:sheet-music/section [:section/name  :section/position :section/default?]}])
                              (mapv first)
                              (group-by :sheet-music/section)
                              (mapv (fn [[k v]] (assoc k :sheet-music/_section v))))
        sections-with-no-sheets (->> (d/q '[:find (pull ?section pattern)
                                            :in $ ?song pattern
                                            :where
                                            [?section :section/name _]
                                            [?section :section/active? true]
                                            (not-join [?song ?section]
                                                      [?sm :sheet-music/song ?song]
                                                      [?sm :sheet-music/section ?section]
                                                      ;;
                                                      )]
                                          db [:song/song-id song-id] [:section/name :section/default? :section/position])
                                     (mapv first))]

    (->> (concat sections-with-no-sheets sections-with-sheets)
         (sort-by :section/position))))

(defn plays-by-gig [db gig-id]
  (->> (d/find-all-by db :played/gig [:gig/gig-id gig-id] play-pattern)
       (mapv first)
       (sort-by #(-> % :played/song :song/title))))

(defn plays-by-song [db song-id]
  (->> (d/find-all-by db :played/song [:song/song-id song-id] play-pattern)
       (mapv first)))

(defn load-play-stats [db]
  (->>
   (d/find-all-by db :song/active? true [:song/song-id
                                         :song/title
                                         :song/total-plays
                                         :song/total-performances
                                         :song/total-rehearsals
                                         :song/total-rating-good
                                         :song/total-rating-bad
                                         :song/total-rating-ok
                                         :song/six-month-total-rating-good
                                         :song/six-month-total-rating-bad
                                         :song/six-month-total-rating-ok
                                         :song/days-since-performed
                                         :song/days-since-rehearsed
                                         :song/days-since-intensive
                                         :song/last-played-on
                                         {:song/last-performance [:gig/gig-id :gig/date]}
                                         {:song/last-rehearsal [:gig/gig-id :gig/date]}
                                         {:song/last-intensive [:gig/gig-id :gig/date]}
                                         {:song/first-performance [:gig/gig-id :gig/date]}
                                         {:song/first-rehearsal [:gig/gig-id :gig/date]}])
   (mapv first)))

(defn next-probes
  "Return the future confirmed probes"
  [db pattern]
  (->> (gigs-after db (date-midnight-today!) pattern)
       (filter gig.domain/normal-probe?)
       (filter gig.domain/confirmed?)))

(defn previous-probes
  "Returns the past confirmed normal probes (not extra-probes)"
  [db pattern]
  (->>
   (gigs-past db pattern)
   (filter gig.domain/normal-probe?)
   (filter gig.domain/confirmed?)))

(defn next-probes-with-plan
  "Return the future confirmed probes"
  [db comp]
  (->>
   (next-probes db gig-pattern)
   (map (fn [gig]
          (assoc gig :songs (->> (probeplan-songs-for-gig db (:gig/gig-id gig))
                                 (sort-by :emphasis comp)))))))

(defn next-probe
  "Returns the probe that will occur next including today!"
  [db]
  (first (next-probes db gig-detail-pattern)))

(defn previous-probe
  "Returns the last probe that occurred."
  [db]
  (last (previous-probes db gig-detail-pattern)))

(defn overdue-reminders-by-type
  ([db]
   (overdue-reminders-by-type db (t/inst)))
  ([db as-of]
   (->>
    (datomic/q '[:find (pull ?reminder pattern)
                 :in $ ?as-of pattern
                 :where
                 [?reminder :reminder/reminder-status :reminder-status/pending]
                 [?reminder :reminder/remind-at ?remind-at]
                 [(< ?remind-at ?as-of)]]
               db (t/inst as-of) reminder-pattern)

    (map first)
    (map gig.domain/db->reminder)
    (sort-by :reminder/remind-at)
    (group-by :reminder/reminder-type))))

(defn active-reminders-by-type [db]
  (->>
   (d/find-all-by db :reminder/reminder-status :reminder-status/pending reminder-pattern)
   (map first)
   (map gig.domain/db->reminder)
   (sort-by :reminder/remind-at)
   (group-by :reminder/reminder-type)))

(defn gig-reminder-for [db gig-id member-id]
  (->>
   (datomic/q '[:find (pull ?reminder pattern)
                :in $ ?gig-id ?member-id pattern
                :where
                [?reminder :reminder/member ?member-id]
                [?reminder :reminder/gig ?gig-id]
                [?reminder :reminder/reminder-type :reminder-type/gig-attendance]]
              db [:gig/gig-id gig-id] [:member/member-id member-id]
              reminder-pattern)
   (map first)
   (map gig.domain/db->reminder)
   (first)))

(defn retrieve-discount-type [db discount-type-id]
  (d/find-by db :travel.discount.type/discount-type-id discount-type-id travel-discount-type-pattern))

(defn retrieve-all-discount-types [db]
  (->>
   (d/find-all db :travel.discount.type/discount-type-id travel-discount-type-pattern)
   (map first)
   (map settings.domain/db->discount-type)
   (sort-by :travel.discount.type/discount-type-name)))

(defn retrieve-travel-discount [db travel-discount-id]
  (settings.domain/db->discount
   (d/find-by db :travel.discount/discount-id travel-discount-id travel-discount-pattern)))

(def team-pattern [:team/team-id
                   :team/name
                   :team/team-type
                   {:team/members member-pattern}])
(defn retrieve-all-teams [db]
  (->>
   (d/find-all db :team/team-id team-pattern)
   (map first)
   (util/isort-by :team/name)))

(defn retrieve-team [db team-id]
  (d/find-by db :team/team-id team-id team-pattern))

(defn retrieve-team-type [db team-type]
  (->> (retrieve-all-teams db)
       (m/find-first #(= team-type (:team/team-type %)))))

(defn member-of-team? [db team-type member-id]
  (= 1
     (count
      (datomic/q '[:find ?t
                   :in $ ?team-type ?member-ref
                   :where
                   [?t :team/team-type ?team-type]
                   [?t :team/members ?member-ref]]
                 db team-type [:member/member-id member-id]))))

(defn insurance-team-member? [db {:member/keys [member-id]}]
  (member-of-team? db :team.type/insurance member-id))

(def ledger-entry-pattern [:ledger.entry/entry-id
                           :ledger.entry/amount
                           :ledger.entry/tx-date
                           :ledger.entry/posting-date
                           :ledger.entry/description])
(def ledger-entry-pattern-with-ledger
  (conj ledger-entry-pattern {:ledger/_entries [:ledger/ledger-id :ledger/balance {:ledger/owner member-pattern}]}))

(def ledger-pattern
  [:ledger/ledger-id :ledger/balance
   {:ledger/owner member-pattern}
   {:ledger/entries ledger-entry-pattern}])

(defn retrieve-ledger [db member-id]
  (ledger.domain/db->ledger
   (d/find-by db :ledger/owner [:member/member-id member-id] ledger-pattern)))

(defn retrieve-ledger-entry [db entry-id]
  (ledger.domain/db->ledger
   (d/find-by db :ledger.entry/entry-id entry-id ledger-entry-pattern-with-ledger)))

(defn find-ledger-entries-insurance [db member-id]
  (->>
   (datomic/q '[:find (pull ?ledger-entry pattern)
                :in $ ?member-ref pattern
                :where [?ledger :ledger/owner ?member-ref]
                [?ledger :ledger/entries ?ledger-entry]
                [?ledger-entry :ledger.entry/metadata ?metadata]
                [?metadata :ledger.entry.meta/meta-type :ledger.entry.meta.type/insurance]]
              db [:member/member-id member-id]
              [:ledger.entry/entry-id
               :ledger.entry/amount
               :ledger.entry/tx-date
               :ledger.entry/description
               {:ledger.entry/metadata [:ledger.entry.meta/meta-type {:ledger.entry.meta.insurance/policy [:insurance.policy/policy-id]}]}])
   (map first)
   (map ledger.domain/db->entry)))

(defn ledger-entry-debit-for-policy
  "Return the ledger-entries (if one exists) for the given member and policy that are debits"
  [db member-id policy-id]
  (->>
   (datomic/q '[:find (pull ?ledger-entry pattern)
                :in $ ?member-ref ?policy-ref pattern
                :where [?ledger :ledger/owner ?member-ref]
                [?ledger :ledger/entries ?ledger-entry]
                [?ledger-entry :ledger.entry/metadata ?metadata]
                [?ledger-entry :ledger.entry/amount ?amount]
                [(> ?amount 0)]
                [?metadata :ledger.entry.meta/meta-type :ledger.entry.meta.type/insurance]
                [?metadata :ledger.entry.meta.insurance/policy ?policy-ref]]
              db [:member/member-id member-id] [:insurance.policy/policy-id policy-id]
              [:ledger.entry/entry-id
               :ledger.entry/amount
               :ledger.entry/tx-date
               :ledger.entry/description
               {:ledger.entry/metadata [:ledger.entry.meta/meta-type {:ledger.entry.meta.insurance/policy [:insurance.policy/policy-id]}]}])
   (map first)
   (map ledger.domain/db->entry)))

(def insurance-survey-coverage-report-pattern
  [:insurance.survey.report/report-id
   {:insurance.survey.report/coverage instrument-coverage-detail-pattern}
   :insurance.survey.report/completed-at])

(def insurance-survey-response-pattern
  [:insurance.survey.response/response-id
   {:insurance.survey.response/member member-pattern}
   :insurance.survey.response/completed-at
   {:insurance.survey.response/coverage-reports insurance-survey-coverage-report-pattern}])

(def insurance-survey-response-pattern-backwards
  (conj insurance-survey-response-pattern
        {:insurance.survey/_responses [:insurance.survey/survey-id
                                       :insurance.survey/survey-name
                                       {:insurance.survey/policy policy-lite-pattern}
                                       :insurance.survey/created-at
                                       :insurance.survey/closes-at
                                       :insurance.survey/closed-at]}))
(def insurance-survey-report-pattern-backwards
  (-> insurance-survey-coverage-report-pattern
      (conj  {:insurance.survey.response/_coverage-reports [:insurance.survey.response/response-id
                                                            {:insurance.survey.response/member member-pattern}
                                                            :insurance.survey.response/completed-at]})))

(def insurance-survey-pattern
  [:insurance.survey/survey-id
   :insurance.survey/survey-name
   {:insurance.survey/policy policy-lite-pattern}
   :insurance.survey/created-at
   :insurance.survey/closes-at
   :insurance.survey/closed-at
   {:insurance.survey/responses insurance-survey-response-pattern}])

(defn surveys-for-policy [db policy]
  (->>
   (datomic/q '[:find (pull ?survey pattern)
                :in $ ?policy-id pattern
                :where [?survey :insurance.survey/policy ?policy-id]]
              db (d/ref policy)  insurance-survey-pattern)
   (map first)
   (map insurance.domain/db->survey)))

(defn policy-has-open-surveys? [db policy-id]
  (boolean
   (seq
    (datomic/q '[:find ?survey
                 :in $ ?policy-id
                 :where
                 [?policy :insurance.policy/policy-id ?policy-id]
                 [?survey :insurance.survey/policy ?policy]
                 [(missing? $ ?survey :insurance.survey/closed-at)]]
               db policy-id))))

(defn open-survey-for-member-items [db member policy]
  (or
   (->>
    (datomic/q '[:find (count ?report)
                 :in $ ?member ?policy
                 :where
                 [?response :insurance.survey.response/member ?member]
                 [?survey :insurance.survey/responses ?response]
                 [?survey :insurance.survey/policy ?policy]
                 [(missing? $ ?survey :insurance.survey/closed-at)]
                 [(missing? $ ?response :insurance.survey.response/completed-at)]
                 [?response :insurance.survey.response/coverage-reports ?report]
                 [(missing? $ ?report :insurance.survey.report/completed-at)]]
               db (d/ref member) (d/ref policy))
    (map first)
    first)
   0))

(defn open-survey-for-member-policy
  "Returns the response for the member for the first open survey"
  [db member policy]
  (->>
   (datomic/q '[:find (pull ?response pattern)
                :in $ ?member ?policy pattern
                :where
                [?response :insurance.survey.response/member ?member]
                [?survey :insurance.survey/responses ?response]
                [?survey :insurance.survey/policy ?policy]
                [(missing? $ ?survey :insurance.survey/closed-at)]]
              db (d/ref member) (d/ref policy) insurance-survey-response-pattern-backwards)
   (map first)
   (map insurance.domain/db->survey-response)
   first))

(defn open-survey-for-member
  "Returns the response for the member for the first open survey"
  [db member]
  (->>
   (datomic/q '[:find (pull ?response pattern)
                :in $ ?member pattern
                :where
                [?response :insurance.survey.response/member ?member]
                [?survey :insurance.survey/responses ?response]
                [?survey :insurance.survey/policy ?policy]
                [(missing? $ ?survey :insurance.survey/closed-at)]]
              db (d/ref member) insurance-survey-response-pattern-backwards)
   (map first)
   (map insurance.domain/db->survey-response)
   first))

(defn retrieve-survey-report [db report-id]
  (insurance.domain/db->survey-report
   (d/find-by db :insurance.survey.report/report-id report-id insurance-survey-report-pattern-backwards)))

(defn retrieve-survey-response [db response-id]
  (insurance.domain/db->survey-response
   (d/find-by db :insurance.survey.response/response-id response-id insurance-survey-response-pattern-backwards)))

(defn retrieve-survey [db survey-id]
  (insurance.domain/db->survey
   (d/find-by db :insurance.survey/survey-id survey-id insurance-survey-pattern)))

(defn retrieve-file [db file-id]
  (d/find-by db :filestore.file/file-id file-id file-pattern))

(defn retrieve-image
  "Fetches the image-id"
  [db image-id]
  (-> (d/find-by db :image/image-id image-id image-pattern)
      (filestore.domain/db->image)))

#_(defn retrieve-image-renditions
    "Fetches the image-id with its renditions"
    [db image-id]
    (->
     (d/find-by db :image/image-id image-id image-pattern-with-renditions)
     (filestore.domain/db->image)))

(defn retrieve-renditions-for
  "Fetches the image-id and renditions with the given filter-spec"
  [db image-id filter-spec-encoded]
  (->>
   (datomic/q '[:find (pull ?rendition pattern)
                :in $ ?image-id ?filter-spec pattern
                :where
                [?parent-image :image/image-id ?image-id]
                [?parent-image :image/renditions ?rendition]
                [?rendition :image/filter-spec ?filter-spec]]
              db image-id filter-spec-encoded image-pattern-with-renditions)
   (map first)
   (map filestore.domain/db->image)))

;;;; END
(comment
  (do
    (require '[integrant.repl.state :as state])
    (require  '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))) ;; rcf

  (->> (retrieve-all-teams db)
       (m/find-first #(= :team.type/insurance (:team/team-type %))))

  :insurance.survey.response/response-id
  (def my-response-id
    #uuid "018f5861-30d2-896f-a4f6-9fb26e50b6b2")

  (open-survey-for-member db {:member/member-id #uuid "01860c2a-2929-8727-af1a-5545941b1111"})
  (open-survey-for-member db {:member/member-id #uuid "01860c2a-292b-8545-a6b4-a4783be6e8c6"})

  (surveys-for-policy db #uuid "018f1059-4acd-86c1-8f55-4b3e5eb856e0")

  (member-of-team? db :team.type/insurance #uuid "01887b0c-23f8-86f0-8891-89e322a505d9")

  (let [sheets (->>
                (d/q '[:find
                       (pull ?sm pattern)
                       :in $ ?song pattern
                       :where
                       [?sm :sheet-music/section ?section]
                       [?sm :sheet-music/song ?song]]
                     db
                     [:song/song-id (parse-uuid "01844740-3eed-856d-84c1-c26f07068209")]
                     [:sheet-music/sheet-id :sheet-music/title :file/webdav-path {:sheet-music/section [:section/name  :section/position :section/default?]}])
                (mapv first))
        section-sheets (group-by :sheet-music/section sheets)
        section-sheets (map (fn [[k v]]
                              (assoc k :sheet-music/_section v)) section-sheets)]
    section-sheets)

  (load-play-stats db)
  (sheet-music-for-song db #uuid "01844740-3eed-856d-84c1-c26f07068207")
  (sheet-music-dir-for-song db #uuid "01844740-3eed-856d-84c1-c26f07068209")

  (retrieve-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q7OCQw")

  (active-members-by-section db)

  (instruments-for-member-covered-by db
                                     {:member/member-id "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA086WiwoM"}
                                     (insurance-policy-effective-as-of db (t/now) policy-pattern)
                                     instrument-coverage-detail-pattern)

  (d/transact conn {:tx-data [{:attendance/gig [:gig/gig-id "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q7OCww"]
                               :attendance/member [:member/member-id "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA086WiwoM"]
                               :attendance/gig+member (pr-str ["ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q7OCww" "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA086WiwoM"])
                               :attendance/section [:section/name "flute"]
                               :attendance/updated (t/inst)
                               :attendance/plan :plan/definitely-not}]})

  (def plans)

  (def sections (active-members-by-section db))
  (map (fn [section]
         (update section :member/_section
                 (fn [members]
                   (map (fn [member]
                          (assoc member :foo :bar)
                          ;; (get plans (:section/name section))
                          ;;
                          ) members)
                   ;;
                   ))) sections)
  plans

  (def active-mem (active-members db))
  active-mem

  (let [plans (->>
               (d/find-by db :gig/gig-id "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q7OCww"
                          [:gig/gig-type :gig/title {:attendance/_gig [{:attendance/section [:section/name]}
                                                                       {:attendance/member [:member/name :member/member-id :member/nick]}
                                                                       :attendance/plan
                                                                       :attendance/comment
                                                                       :attendance/motivation
                                                                       :attendance/updated]}])
               :attendance/_gig
                ;; (group-by #(-> % :attendance/section :section/name))
               )
        plans (attendances-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q7OCww")
        members (active-members db)
        no-plan]

    (concat plans
            (map (fn [member]
                   {:attendance/section (:member/section member)
                    :attendance/member  member
                    :attendance/plan :plan/no-response}) no-plan)))
  (active-members-by-section db)
  (->>
   (attendance-for-gig-with-all-active-members db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q7OCww"))

  (d/q '[:find ?result
         :in $ ?gig-id
         :where
         [?e :probeplan/gig ?gig-id]
         [?e :probeplan.classic/ordered-songs ?tup]
         [(untuple  ?tup) [?song-eid ?position ?emphasis]]
         [?song-eid :song/title ?song-title]
         [?song-eid :song/song-id ?song-id]
         [(tuple ?song-id ?song-title ?position ?emphasis) ?result]]
       db [:gig/gig-id "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCgw"])

  (d/find-by db :probeplan/gig
             [:gig/gig-id "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCgw"]
             '[*])
  (d/q '[:find ?song-title ;; [?song-id ?song-title ?position ?emphasis]
         :in $ ?val
         :where
         [?e :probeplan/gig ?val]
         [?e :probeplan.classic/ordered-songs ?tup]
         [(untuple ?song-eid ?position ?emphasis) ?tup]
         [?song-eid :song/title ?song-title]
         [?song-eid :song/song-id ?song-id]]
       db :probeplan/gig
       [:gig/gig-id "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCgw"])
  (probeplan-songs-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCgw")
  (probeplan-song-tuples-for-gig db "ag1zfmdpZy1vLW1hdGljcjMLEgRCYW5kIghiYW5kX2tleQwLEgRCYW5kGICAgMD9ycwLDAsSA0dpZxiAgMD81q6uCgw")

  (mapv first
        (d/q '[:find (pull ?e pattern)
               :in $ pattern
               :where
               [?e :member/member-id _]
               [?e :member/active? true]
               [(missing? $ ?e :member/keycloak-id)]]
             db member-pattern))

  (d/find-all db :member/email member-detail-pattern)
  (d/find-all db :gig/gig-id gig-detail-pattern)

  (gig-reminder-for db (parse-uuid "0187e415-7b26-8e55-9371-4391baf9fe09") (parse-uuid "01860c2a-2929-8727-af1a-5545941b1110"))
  ;;
  (previous-probe db)

  ;;
  )
