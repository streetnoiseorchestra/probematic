(ns app.gigs.edit.actions
  (:require
   [app.auth :as auth]
   [app.discourse :as discourse]
   [app.form :as form]
   [app.gigs.domain :as domain]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]))

(defn- form-params [signals]
  (let [params (or (:gig-edit signals) signals)]
    (dissoc params :tab-id :_error :validate-field)))

(defn- normalize-form [params]
  (reduce
   (fn [params [k f]]
     (form/update-present params k f))
   params
   [[:gig-id #(some-> % str)]
    [:title form/trim-value]
    [:date form/trim-value]
    [:end-date form/trim-value]
    [:location form/trim-value]
    [:contact form/trim-value]
    [:gig-type form/trim-value]
    [:status form/trim-value]
    [:call-time form/trim-value]
    [:set-time form/trim-value]
    [:end-time form/trim-value]
    [:leader form/trim-value]
    [:rehearsal-leader1 form/trim-value]
    [:rehearsal-leader2 form/trim-value]
    [:pay-deal form/trim-value]
    [:outfit form/trim-value]
    [:more-details #(or % "")]
    [:description #(or % "")]
    [:setlist #(or % "")]
    [:post-gig-plans #(or % "")]
    [:topic-id form/trim-value]
    [:notify? form/normalize-bool]
    [:thread? form/normalize-bool]
    [:takeover-topic? form/normalize-bool]]))

(defn- label [tr field]
  (case field
    :title (tr [:gig/title])
    :date (tr [:gig/date])
    :location (tr [:gig/location])
    :gig-type (tr [:gig/gig-type])
    :status (tr [:gig/status])
    :call-time (tr [:gig/call-time])
    (name field)))

(defn- required-error [tr field]
  {:error (tr [:error/is-required] [(label tr field)])})

(defn- after? [a b]
  (pos? (compare a b)))

(defn- str->status [status]
  (some->> status (keyword "gig.status")))

(defn- str->gig-type [gig-type]
  (some->> gig-type (keyword "gig.type")))

(defn- member-ref [member-id]
  (when-let [member-id (form/blank->nil member-id)]
    [:member/member-id (util/ensure-uuid! member-id)]))

(defn- gig-update-map
  [{:keys [gig-id title date end-date status gig-type location contact call-time set-time end-time leader rehearsal-leader1 rehearsal-leader2 pay-deal outfit more-details setlist description post-gig-plans topic-id]}]
  {:gig/gig-id             (util/ensure-uuid! gig-id)
   :gig/title              title
   :gig/status             (str->status status)
   :gig/date               (form/parse-date date)
   :gig/end-date           (form/parse-date end-date)
   :gig/gig-type           (str->gig-type gig-type)
   :gig/location           location
   :gig/contact            (member-ref contact)
   :gig/call-time          (form/parse-time call-time)
   :gig/set-time           (form/parse-time set-time)
   :gig/end-time           (form/parse-time end-time)
   :gig/leader             (form/optional-text leader)
   :gig/rehearsal-leader1  (member-ref rehearsal-leader1)
   :gig/rehearsal-leader2  (member-ref rehearsal-leader2)
   :gig/pay-deal           (form/optional-text pay-deal)
   :gig/outfit             (form/optional-text outfit)
   :gig/more-details       (form/optional-text more-details)
   :gig/setlist            (form/optional-text setlist)
   :gig/description        (form/optional-text description)
   :gig/post-gig-plans     (form/optional-text post-gig-plans)
   :forum.topic/topic-id   (some-> topic-id form/optional-text discourse/parse-topic-id)})

(def retractable-attrs
  [:gig/end-date
   :gig/contact
   :gig/set-time
   :gig/end-time
   :gig/leader
   :gig/rehearsal-leader1
   :gig/rehearsal-leader2
   :gig/pay-deal
   :gig/outfit
   :gig/more-details
   :gig/setlist
   :gig/description
   :gig/post-gig-plans
   :forum.topic/topic-id])

(defn update-gig-tx-data [params]
  (let [gig       (gig-update-map params)
        nil-attrs (->> (select-keys gig retractable-attrs)
                       (filter (comp nil? val))
                       (into {}))]
    [(merge (domain/gig->db (util/remove-nils gig))
            nil-attrs)]))

(defn create-gig-tx-data [params]
  [(domain/gig->db (util/remove-nils (gig-update-map params)))])

(defn- can-edit-gig? [state gig]
  (or (auth/admin? (:current-user-roles state))
      (not (domain/gig-archived? gig))))

(defn- top-error [message]
  {:_top {:error message}})

(defn- with-generic-top-error [tr errors]
  (cond-> errors
    (and (seq errors) (nil? (:_top errors)))
    (assoc :_top {:error (tr [:error/form-has-errors])})))

(defn validation-errors
  [{:keys [tr]}
   {:keys [title date end-date location gig-type status call-time set-time end-time rehearsal-leader1 rehearsal-leader2]}]
  (merge
   (when (str/blank? title)
     {:title (required-error tr :title)})
   (when (str/blank? date)
     {:date (required-error tr :date)})
   (when (and (seq date) (seq end-date) (after? date end-date))
     {:end-date {:error (tr [:error/gig-end-date-before-date])}})
   (when (str/blank? location)
     {:location (required-error tr :location)})
   (when (str/blank? gig-type)
     {:gig-type (required-error tr :gig-type)})
   (when (str/blank? status)
     {:status (required-error tr :status)})
   (when (str/blank? call-time)
     {:call-time (required-error tr :call-time)})
   (when (and (seq call-time) (seq set-time) (after? call-time set-time))
     {:set-time {:error (tr [:error/gig-set-time-before-call-time])}})
   (when (and (seq call-time) (seq end-time) (str/blank? set-time) (after? call-time end-time))
     {:end-time {:error (tr [:error/gig-end-time-before-call-time])}})
   (when (and (seq set-time) (seq end-time) (after? set-time end-time))
     {:end-time {:error (tr [:error/gig-end-time-before-set-time])}})
   (when (and (seq rehearsal-leader1)
              (seq rehearsal-leader2)
              (= rehearsal-leader1 rehearsal-leader2))
     {:rehearsal-leader2 {:error (tr [:error/gig-rehearsal-leaders-same])}})))

(defn validate-gig-field-action
  [{:keys [tr]} signals]
  (let [raw   (or (:gig-edit signals) {})
        field (some-> (:validate-field raw) keyword)
        form  (dissoc (normalize-form raw) :_error :validate-field)
        error (get (validation-errors {:tr tr} form) field)]
    (cond-> [[:app.datastar/merge-state [:gig-edit] form]]
      field (conj [:app.datastar/assoc-state
                   [:gig-edit :_error field]
                   error]))))

(defn update-gig-action
  [{:keys [db tr] :as state} signals]
  (let [params          (normalize-form (form-params signals))
        gig-id          (util/ensure-uuid! (:gig-id params))
        gig             (q/retrieve-gig db gig-id)
        errors          (with-generic-top-error
                          tr
                          (merge
                           (when-not gig
                             (top-error (tr [:error/gig-edit-not-found])))
                           (when (and gig (not (can-edit-gig? state gig)))
                             (top-error (tr [:error/gig-edit-not-allowed])))
                           (validation-errors {:tr tr} params)))
        notify?         (form/normalize-bool (:notify? params))
        takeover-topic? (form/normalize-bool (:takeover-topic? params))]
    (tap> [:update-gig-action :params params :errors errors])
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [:gig-edit] (assoc params :_error errors)]]
      (let [gig-tx-data (update-gig-tx-data params)]
        (tap> [:gig-tx-data gig-tx-data])
        [[:db/transact
          gig-tx-data
          {:transact-w-nils? true
           :on-success       [[:app.gigs/trigger-gig-details-edited gig-id notify? takeover-topic?]]}]
         [:app.datastar/redirect (urls/link-gig gig-id)]]))))

(defn create-gig-action
  [{:keys [tr]} signals]
  (let [params  (normalize-form (form-params signals))
        errors  (with-generic-top-error tr (validation-errors {:tr tr} params))
        notify? (form/normalize-bool (:notify? params))
        thread? (form/normalize-bool (:thread? params))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [:gig-edit] (assoc params :_error errors)]]
      (let [gig-id (sq/generate-squuid)
            params (assoc params :gig-id (str gig-id))]
        [[:db/transact
          (create-gig-tx-data params)
          {:on-success [[:app.gigs/trigger-gig-created gig-id notify? thread?]]}]
         [:app.datastar/redirect (urls/link-gig gig-id)]]))))

(defn delete-gig-tx-data [db gig-id]
  (let [gig-ref     [:gig/gig-id gig-id]
        attendances (mapv (fn [{:attendance/keys [gig+member]}]
                            [:db/retractEntity [:attendance/gig+member gig+member]])
                          (q/attendances-for-gig db gig-id))
        played      (mapv (fn [{:played/keys [play-id]}]
                            [:db/retractEntity [:played/play-id play-id]])
                          (q/plays-by-gig db gig-id))]
    {:recalc-play-stats? (pos? (count played))
     :tx-data            (vec (concat attendances
                                      played
                                      [[:db/retractEntity [:setlist/gig gig-ref]]
                                       [:db/retractEntity [:probeplan/gig gig-ref]]
                                       [:db/retractEntity gig-ref]]))}))

(defn delete-gig-action
  [{:keys [db tr] :as state} signals]
  (let [params (form-params signals)
        gig-id (util/ensure-uuid! (or (:gig-id params) (:targetid params)))
        gig    (q/retrieve-gig db gig-id)]
    (cond
      (nil? gig)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:gig-edit :_error :_top]
        {:error (tr [:error/gig-edit-not-found])}]]

      (not (can-edit-gig? state gig))
      [support/clear-loading
       [:app.datastar/assoc-state
        [:gig-edit :_error :_top]
        {:error (tr [:error/gig-edit-not-allowed])}]]

      :else
      (let [{:keys [tx-data recalc-play-stats?]} (delete-gig-tx-data db gig-id)]
        [[:db/transact
          tx-data
          {:on-success [[:app.gigs/trigger-gig-deleted gig-id recalc-play-stats?]]}]
         [:app.datastar/redirect (urls/link-gigs-home)]]))))

(def actions
  {::validate-gig-field #'validate-gig-field-action
   ::create-gig         #'create-gig-action
   ::update-gig         #'update-gig-action
   ::delete-gig         #'delete-gig-action})
