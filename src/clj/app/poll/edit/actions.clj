(ns app.poll.edit.actions
  (:require
   [app.form :as form]
   [app.nexus.actions :as support]
   [app.poll.domain :as domain]
   [app.poll.queries :as queries]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]
   [tick.core :as t]))

(defn- parse-int [value]
  (when-let [value (form/optional-text value)]
    (try
      (Long/parseLong value)
      (catch NumberFormatException _
        nil))))

(defn- str->poll-type [poll-type]
  (when-let [poll-type (form/optional-text poll-type)]
    (keyword "poll.type" poll-type)))

(defn- normalize-option [option]
  {:value (form/trim-value (or (:value option)
                               (:poll.option/value option)))})

(defn- normalize-options [options]
  (cond
    (nil? options)
    []

    (and (map? options) (sequential? (:value options)))
    (mapv (fn [value] {:value (form/trim-value value)}) (:value options))

    (sequential? options)
    (mapv normalize-option options)

    :else
    []))

(defn- normalize-form [params]
  (reduce
   (fn [params [k f]]
     (form/update-present params k f))
   (update params :options normalize-options)
   [[:poll-id #(some-> % str)]
    [:title form/trim-value]
    [:description form/trim-value]
    [:poll-type form/trim-value]
    [:min-choice form/trim-value]
    [:max-choice form/trim-value]
    [:closes-at form/trim-value]
    [:autoremind? form/normalize-bool]]))

(defn- nonblank-options [{:keys [options]}]
  (->> options
       (mapv normalize-option)
       (filterv (comp not str/blank? :value))))

(defn- label [tr field]
  (case field
    :title (tr [:polls/poll-title-label])
    :description (tr [:polls/description-label])
    :closes-at (tr [:polls/closes-at-label])
    :min-choice (tr [:polls/min-choices-label])
    :max-choice (tr [:polls/max-choices-label])
    :options (tr [:polls/options])
    (name field)))

(defn- required-error [tr field]
  {:error (tr [:error/is-required] {:field (label tr field)})})

(defn- top-error [message]
  {:_top {:error message}})

(defn- with-generic-top-error [tr errors]
  (cond-> errors
    (and (seq errors) (nil? (:_top errors)))
    (assoc :_top {:error (tr [:error/form-has-errors])})))

(defn validation-errors [{:keys [tr]} {:keys [title description poll-type min-choice max-choice closes-at] :as params}]
  (let [multiple? (= "multiple" poll-type)
        min-choice (parse-int min-choice)
        max-choice (parse-int max-choice)
        options    (nonblank-options params)]
    (merge
     (when (str/blank? title)
       {:title (required-error tr :title)})
     (when (str/blank? description)
       {:description (required-error tr :description)})
     (when (str/blank? closes-at)
       {:closes-at (required-error tr :closes-at)})
     (when (empty? options)
       {:options (required-error tr :options)})
     (when multiple?
       (merge
        (when (nil? min-choice)
          {:min-choice (required-error tr :min-choice)})
        (when (nil? max-choice)
          {:max-choice (required-error tr :max-choice)})
        (when (and min-choice max-choice (> min-choice max-choice))
          {:max-choice {:error (tr [:polls/error-min-greater-than-max])}})
        (when (and max-choice (> max-choice (count options)))
          {:options {:error (tr [:polls/error-too-few-options])}}))))))

(defn validate-poll-field-action
  [{:keys [tr]} signals]
  (let [raw   (or (:poll-edit signals) {})
        field (some-> (:validate-field raw) keyword)
        form  (dissoc (normalize-form raw) :_error :validate-field)
        error (get (validation-errors {:tr tr} form) field)]
    (cond-> [[:app.datastar/merge-state [:poll-edit] form]]
      field (conj [:app.datastar/assoc-state
                   [:poll-edit :_error field]
                   error]))))

(defn- poll-ref [poll-id]
  [:poll/poll-id poll-id])

(defn- option-txs [poll-ref options]
  (mapcat
   (fn [idx {:keys [value]}]
     (let [option-id (sq/generate-squuid)
           tempid    (str "poll-option-" option-id)]
       [{:db/id                      tempid
         :poll.option/poll-option-id option-id
         :poll.option/position       idx
         :poll.option/value          value}
        [:db/add poll-ref :poll/options tempid]]))
   (range)
   (nonblank-options {:options options})))

(defn- retract-options-txs [{:poll/keys [options]}]
  (mapv (fn [{:poll.option/keys [poll-option-id]}]
          [:db/retractEntity [:poll.option/poll-option-id poll-option-id]])
        options))

(defn- min-max-retractions [poll-id existing-poll poll-type]
  (when (= :poll.type/single poll-type)
    (cond-> []
      (contains? existing-poll :poll/min-choice)
      (conj [:db/retract (poll-ref poll-id) :poll/min-choice])
      (contains? existing-poll :poll/max-choice)
      (conj [:db/retract (poll-ref poll-id) :poll/max-choice]))))

(defn- poll-tx-map [{:keys [current-member-id now]} existing-poll poll-id params]
  (let [poll-type (str->poll-type (:poll-type params))]
    (domain/poll->db
     (cond-> {:poll/poll-id     poll-id
              :poll/title       (:title params)
              :poll/description (:description params)
              :poll/poll-type   poll-type
              :poll/poll-status (or (:poll/poll-status existing-poll) :poll.status/draft)
              :poll/chart-type   :poll.chart.type/bar
              :poll/author       [:member/member-id current-member-id]
              :poll/closes-at    (t/date-time (:closes-at params))
              :poll/created-at   (or (some-> existing-poll :poll/created-at t/inst)
                                     now
                                     (t/inst))
              :poll/autoremind?  (form/normalize-bool (:autoremind? params))}
       (= :poll.type/multiple poll-type)
       (assoc :poll/min-choice (parse-int (:min-choice params))
              :poll/max-choice (parse-int (:max-choice params)))))))

(defn create-poll-action
  [{:keys [tr] :as state} signals]
  (let [params (normalize-form (dissoc (or (:poll-edit signals) signals) :tab-id :_error :validate-field))
        errors (with-generic-top-error tr (validation-errors {:tr tr} params))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [:poll-edit] (assoc params :_error errors)]]
      (let [poll-id (sq/generate-squuid)
            poll-tx (assoc (poll-tx-map state nil poll-id params) :db/id "new-poll")]
        [[:db/transact
          (vec (concat [poll-tx]
                       (option-txs "new-poll" (:options params))))
          {}]
         [:app.datastar/respond-sse
          [[:app.datastar.sse/redirect (urls/link-poll poll-id)]]]]))))

(defn- option-values [options]
  (->> options
       (sort-by :poll.option/position)
       (mapv :poll.option/value)))

(defn- submitted-option-values [params]
  (mapv :value (nonblank-options params)))

(defn- open-immutable-change? [existing-poll params]
  (and (= :poll.status/open (:poll/poll-status existing-poll))
       (or (not= (:poll/poll-type existing-poll) (str->poll-type (:poll-type params)))
           (not= (option-values (:poll/options existing-poll))
                 (submitted-option-values params)))))

(defn update-poll-action
  [{:keys [db tr] :as state} signals]
  (let [params        (normalize-form (dissoc (or (:poll-edit signals) signals) :tab-id :_error :validate-field))
        poll-id       (util/ensure-uuid! (:poll-id params))
        existing-poll (queries/retrieve-poll db poll-id)
        status        (:poll/poll-status existing-poll)
        errors        (with-generic-top-error
                        tr
                        (merge
                         (when-not existing-poll
                           (top-error (tr [:polls/error-not-found])))
                         (when (= :poll.status/closed status)
                           (top-error (tr [:polls/error-edit-closed])))
                         (when (open-immutable-change? existing-poll params)
                           (top-error (tr [:polls/error-open-immutable])))
                         (validation-errors {:tr tr} params)))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [:poll-edit] (assoc params :_error errors)]]
      (let [poll-type (str->poll-type (:poll-type params))
            poll-tx   (poll-tx-map state existing-poll poll-id params)
            tx-data   (vec (concat [poll-tx]
                                   (min-max-retractions poll-id existing-poll poll-type)
                                   (when-not (= :poll.status/open status)
                                     (concat (retract-options-txs existing-poll)
                                             (option-txs (poll-ref poll-id) (:options params))))))]
        [[:db/transact tx-data {}]
         [:app.datastar/respond-sse
          [[:app.datastar.sse/redirect (urls/link-poll poll-id)]]]]))))

(defn delete-poll-action
  [_state signals]
  (let [params  (or (:poll-edit signals) signals)
        poll-id (util/ensure-uuid! (or (:poll-id params) (:targetid params)))]
    [[:db/transact
      [[:db/retractEntity (poll-ref poll-id)]]
      {}]
     [:app.datastar/respond-sse
      [[:app.datastar.sse/redirect (urls/link-polls-home)]]]]))

(defn add-option-action [_state signals]
  (let [params  (or (:poll-edit signals) {})
        options (normalize-options (:options params))]
    [[:app.datastar/assoc-state
      [:poll-edit :options]
      (conj (vec options) {:value ""})]
     support/clear-loading]))

(defn remove-option-action [_state signals]
  (let [params  (or (:poll-edit signals) {})
        idx     (parse-int (:targetid signals))
        options (normalize-options (:options params))
        options (if idx
                  (vec (keep-indexed (fn [i option]
                                       (when (not= i idx)
                                         option))
                                     options))
                  options)]
    [[:app.datastar/assoc-state
      [:poll-edit :options]
      (if (seq options) options [{:value ""}])]
     support/clear-loading]))

(def actions
  {::validate-poll-field #'validate-poll-field-action
   ::add-option          #'add-option-action
   ::remove-option       #'remove-option-action
   ::create-poll         #'create-poll-action
   ::update-poll         #'update-poll-action
   ::delete-poll         #'delete-poll-action})
