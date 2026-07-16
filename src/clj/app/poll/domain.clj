(ns app.poll.domain
  (:require
   [app.schemas :as s]
   [medley.core :as m]
   [tick.core :as t]))

(def poll-types [:poll.type/single
                 :poll.type/multiple])
(def str->poll-type (zipmap (map name poll-types) poll-types))

(def poll-type-label-key
  {:poll.type/single   :polls/single-choice
   :poll.type/multiple :polls/multiple-choice})

(def poll-statuses [:poll.status/draft
                    :poll.status/open
                    :poll.status/closed])

(def str->status (zipmap (map name poll-statuses) poll-statuses))

(def status-label-key
  {:poll.status/draft  :polls/draft-status
   :poll.status/open   :polls/open-status
   :poll.status/closed :polls/closed-status})

(def validation-field-label-key
  {:title       :polls/poll-title-label
   :description :polls/description-label
   :closes-at   :polls/closes-at-label
   :min-choice  :polls/min-choices-label
   :max-choice  :polls/max-choices-label
   :options     :polls/options})

(def poll-chart-types [:poll.chart.type/pie
                       :poll.chart.type/bar])

(def str->chart-type (zipmap (map name poll-chart-types) poll-chart-types))

(def PollEntity
  [:and
   [:fn (fn [{:keys [min-choice max-choice options poll-type]}]
          (if (= poll-type :poll.type/multiple)
            (and
             (>= max-choice min-choice)
             (if options
               (>= (count options) max-choice)
               true))
            true))]

   [:map {:name :app.entity/poll}
    [:poll/poll-id :uuid]
    [:poll/poll-type (s/enum-from poll-types)]
    [:poll/poll-status (s/enum-from poll-statuses)]
    [:poll/chart-type (s/enum-from poll-chart-types)]
    [:poll/min-choice {:optional true} :int]
    [:poll/max-choice {:optional true} :int]
    [:poll/author ::s/datomic-ref]
    [:poll/created-at ::s/inst]
    [:poll/closes-at ::s/inst]
    [:poll/autoremind? :boolean]
    [:poll/votes {:optional true} [:sequential ::s/datomic-ref]]
    [:poll/options {:optional true} [:sequential ::s/datomic-ref]]
    [:poll/title {:max 200} ::s/non-blank-string]
    [:poll/description {:max 4096} ::s/non-blank-string]
    [:forum.topic/topic-id {:optional true :max 30} :string]]])

(def PollOptionEntity
  [:map {:name :app.entity/poll-option}
   [:poll.option/poll-option-id :uuid]
   [:poll.option/position :int]
   [:poll.option/value {:max 100} ::s/non-blank-string]])

(def PollVoteEntity
  [:map {:name :app.entity/poll-option}
   [:poll.vote/poll-vote-id :uuid]
   [:poll.vote/poll-option ::s/datomic-ref]
   [:poll.vote/author ::s/datomic-ref]
   [:poll.vote/created-at ::s/inst]])

(t/inst
 (->
  (t/date-time "2023-10-22T13:57")))

(defn closes-at-instant [closes-at]
  (t/instant (t/in closes-at (t/zone "Europe/Vienna"))))

(defn closes-at-inst [closes-at]
  (t/inst (t/in closes-at (t/zone "Europe/Vienna"))))

(defn poll->db
  ([poll]
   (poll->db PollEntity poll))
  ([schema poll]
   (let [poll (update poll :poll/closes-at closes-at-inst)]
     (when-not (s/valid? schema poll)
       (throw
        (ex-info "Poll not valid" {:poll poll
                                   :schema schema
                                   :error (s/explain schema poll)
                                   :human (s/explain-human schema poll)})))
     (s/encode-datomic schema poll))))

(defn ->date-time [kw comment]
  (-> comment
      (m/update-existing kw t/date-time)))

(defn db->poll
  [poll]
  (-> (s/decode-datomic PollEntity poll)
      (m/update-existing :poll/created-at t/date-time)
      (m/update-existing :poll/closes-at t/date-time)))
