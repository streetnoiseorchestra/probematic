(ns app.insurance.coverage.edit.actions
  (:require
   [app.form :as form]
   [app.insurance.queries :as queries]
   [app.insurance.domain :as domain]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]))

(def form-key :coverage-edit)

(defn- form-params [signals]
  (let [params (or (form-key signals) signals)]
    (dissoc params :tab-id :_error :validate-field)))

(defn normalize-form [params]
  (reduce
   (fn [params [k f]]
     (form/update-present params k f))
   params
   [[:policy-id form/trim-value]
    [:coverage-id form/trim-value]
    [:instrument-id form/trim-value]
    [:instrument-name form/trim-value]
    [:owner-member-id form/trim-value]
    [:category-id form/trim-value]
    [:make form/trim-value]
    [:model #(or (form/trim-value %) "")]
    [:serial-number #(or (form/trim-value %) "")]
    [:build-year #(or (form/trim-value %) "")]
    [:description #(or % "")]
    [:item-count form/trim-value]
    [:value form/trim-value]
    [:private-band form/trim-value]
    [:coverage-types #(->> (util/ensure-coll %)
                           (keep form/optional-text)
                           vec)]
    [:insurer-id form/trim-value]]))

(defn- parse-positive-long [value]
  (when-let [parsed (some-> value form/optional-text parse-long)]
    (when (pos? parsed)
      parsed)))

(defn- label [tr field]
  (case field
    :owner-member-id (tr [:instrument/owner])
    :category-id (tr [:instrument/category])
    :instrument-name (tr [:instrument/name])
    :make (tr [:instrument/make])
    :item-count (tr [:insurance/item-count])
    :value (tr [:insurance/value])
    :private-band (tr [:insurance/ownership])
    (name field)))

(defn- required-error [tr field]
  {:error (tr [:error/is-required] {:field (label tr field)})})

(defn- number-error [tr field]
  {:error (tr [:insurance/error-invalid-number]
              {:field (label tr field)})})

(defn- top-error [message]
  {:_top {:error message}})

(defn- with-generic-top-error [tr errors]
  (cond-> errors
    (and (seq errors) (nil? (:_top errors)))
    (assoc :_top {:error (tr [:error/form-has-errors])})))

(defn- coverage-context [db params]
  (let [coverage-id   (some-> (:coverage-id params) form/optional-text util/ensure-uuid!)
        instrument-id (some-> (:instrument-id params) form/optional-text util/ensure-uuid!)
        policy-id     (some-> (:policy-id params) form/optional-text util/ensure-uuid!)
        coverage      (when coverage-id
                        (q/retrieve-coverage db coverage-id))
        policy        (:insurance.policy/_covered-instruments coverage)
        instrument    (:instrument.coverage/instrument coverage)]
    {:coverage-id   coverage-id
     :instrument-id instrument-id
     :policy-id     policy-id
     :coverage      coverage
     :policy        policy
     :instrument    instrument}))

(defn- policy-type-ids [policy]
  (set (map :insurance.coverage.type/type-id
            (:insurance.policy/coverage-types policy))))

(defn- selected-coverage-type-ids [{:keys [policy]} {:keys [coverage-types private-band]}]
  (let [selected-ids (->> coverage-types
                          (map util/ensure-uuid!)
                          (remove nil?)
                          util/remove-dummy-uuid
                          vec)
        required-ids (->> (:insurance.policy/coverage-types policy)
                          (filter :insurance.coverage.type/required?)
                          (map :insurance.coverage.type/type-id))]
    (if (= "private" private-band)
      (->> (concat required-ids selected-ids)
           distinct
           vec)
      (mapv :insurance.coverage.type/type-id (:insurance.policy/coverage-types policy)))))

(defn- invalid-coverage-types? [{:keys [policy]} {:keys [coverage-types]}]
  (let [valid-type-ids (policy-type-ids policy)]
    (->> coverage-types
         (map util/ensure-uuid!)
         (some #(not (contains? valid-type-ids %)))
         boolean)))

(defn validation-errors
  ([state params]
   (validation-errors state params {}))
  ([{:keys [tr] :as state} params {:keys [allow-frozen-policy?]}]
   (let [{:keys [coverage policy instrument-id policy-id] :as ctx} (coverage-context (:db state) params)
         item-count (parse-positive-long (:item-count params))
         value      (parse-positive-long (:value params))]
     (merge
      (when-not coverage
        (top-error (tr [:error/not-found-title])))
      (when (and coverage (not= policy-id (:insurance.policy/policy-id policy)))
        (top-error (tr [:error/not-found-title])))
      (when (and coverage (not= instrument-id (get-in coverage [:instrument.coverage/instrument :instrument/instrument-id])))
        (top-error (tr [:error/not-found-title])))
      (when (and policy
                 (not allow-frozen-policy?)
                 (not (queries/policy-editable? policy)))
        (top-error (tr [:insurance/error-edit-frozen-policy])))
      (when (str/blank? (:owner-member-id params))
        {:owner-member-id (required-error tr :owner-member-id)})
      (when (str/blank? (:category-id params))
        {:category-id (required-error tr :category-id)})
      (when (str/blank? (:instrument-name params))
        {:instrument-name (required-error tr :instrument-name)})
      (when (str/blank? (:make params))
        {:make (required-error tr :make)})
      (when-not item-count
        {:item-count (number-error tr :item-count)})
      (when-not value
        {:value (number-error tr :value)})
      (when-not (#{"band" "private"} (:private-band params))
        {:private-band (required-error tr :private-band)})
      (when (and policy (invalid-coverage-types? ctx params))
        {:coverage-types {:error (tr [:insurance/error-invalid-coverage-type])}})))))

(defn- private? [params]
  (= "private" (:private-band params)))

(defn- coverage-type-txs [ctx params]
  (if (private? params)
    (domain/txs-private-instrument-coverage-types
     (:coverage ctx)
     (selected-coverage-type-ids ctx params))
    (domain/txs-band-instrument-coverage-types (:policy ctx) (:coverage ctx))))

(defn- change-status [coverage upstream-change?]
  (let [current-change (get coverage :instrument.coverage/change :instrument.coverage.change/none)]
    (cond
      (not upstream-change?) current-change
      (= :instrument.coverage.change/new current-change) :instrument.coverage.change/new
      :else :instrument.coverage.change/changed)))

(defn update-coverage-tx-data [ctx params]
  (let [{:keys [coverage coverage-id]} ctx
        coverage-type-txs (vec (coverage-type-txs ctx params))
        item-count        (parse-positive-long (:item-count params))
        value             (bigdec (parse-positive-long (:value params)))
        private?          (private? params)
        owner-changed?    (not= (util/ensure-uuid! (:owner-member-id params))
                                (get-in coverage [:instrument.coverage/instrument :instrument/owner :member/member-id]))
        category-changed? (not= (util/ensure-uuid! (:category-id params))
                                (get-in coverage [:instrument.coverage/instrument :instrument/category :instrument.category/category-id]))
        upstream-change?  (domain/has-upstream-change? coverage
                                                       owner-changed?
                                                       category-changed?
                                                       value
                                                       item-count
                                                       private?
                                                       (seq coverage-type-txs))
        coverage-ref      [:instrument.coverage/coverage-id coverage-id]]
    (vec
     (concat
      coverage-type-txs
      [[:db/add coverage-ref :instrument.coverage/value value]
       [:db/add coverage-ref :instrument.coverage/status (if upstream-change?
                                                           :instrument.coverage.status/needs-review
                                                           (:instrument.coverage/status coverage))]
       [:db/add coverage-ref :instrument.coverage/change (change-status coverage upstream-change?)]
       [:db/add coverage-ref :instrument.coverage/private? private?]
       [:db/add coverage-ref :instrument.coverage/item-count item-count]]
      (when-let [insurer-id (form/optional-text (:insurer-id params))]
        [[:db/add coverage-ref :instrument.coverage/insurer-id insurer-id]])))))

(defn update-instrument-tx-data [ctx params]
  {:instrument/instrument-id (:instrument-id ctx)
   :instrument/description   (:description params)
   :instrument/build-year    (:build-year params)
   :instrument/serial-number (:serial-number params)
   :instrument/make          (:make params)
   :instrument/model         (:model params)
   :instrument/name          (:instrument-name params)
   :instrument/owner         [:member/member-id (util/ensure-uuid! (:owner-member-id params))]
   :instrument/category      [:instrument.category/category-id (util/ensure-uuid! (:category-id params))]})

(defn- instrument-share-link-txs [{:keys [env]} instrument-id]
  (when env
    (domain/txs-instrument-share-link {:system {:env env}}
                                      [:instrument/instrument-id instrument-id]
                                      instrument-id)))

(defn update-instrument-coverage-tx-data [state ctx params]
  (vec
   (concat
    (update-coverage-tx-data ctx params)
    [(update-instrument-tx-data ctx params)]
    (instrument-share-link-txs state (:instrument-id ctx)))))

(defn validate-coverage-field-action
  [state signals]
  (let [raw    (or (form-key signals) {})
        field  (some-> (:validate-field raw) keyword)
        params (dissoc (normalize-form raw) :_error :validate-field)
        error  (get (validation-errors state params) field)]
    (cond-> [[:app.datastar/merge-state [form-key] params]]
      field (conj [:app.datastar/assoc-state
                   [form-key :_error field]
                   error]))))

(defn update-instrument-coverage-action
  [{:keys [current-member-id db tr] :as state} signals]
  (let [params (normalize-form (form-params signals))
        ctx    (coverage-context db params)
        errors (with-generic-top-error tr (validation-errors state params))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [form-key] (assoc params :_error errors)]]
      [[:db/transact
        (support/with-audit (update-instrument-coverage-tx-data state ctx params)
          current-member-id)
        {}]
       [:app.datastar/respond-sse
        [support/clear-loading-event
         [:app.datastar.sse/redirect (urls/link-coverage (:coverage-id ctx))]]]])))

(defn- delete-coverage-id [signals]
  (some-> (or (:targetid signals)
              (get-in signals [form-key :coverage-id])
              (:coverage-id signals))
          form/optional-text
          util/ensure-uuid!))

(defn- insurance-team-member? [db current-member-id]
  (when-let [member (and current-member-id (q/retrieve-member db current-member-id))]
    (q/insurance-team-member? db member)))

(defn delete-instrument-coverage-action
  [{:keys [current-member-id db tr]} signals]
  (let [coverage-id (delete-coverage-id signals)
        coverage    (when coverage-id
                      (q/retrieve-coverage db coverage-id))
        policy-id   (get-in coverage [:insurance.policy/_covered-instruments :insurance.policy/policy-id])]
    (cond
      (nil? coverage)
      [support/clear-loading
       [:app.datastar/assoc-state [form-key :_error :_top]
        {:error (tr [:error/not-found-title])}]]

      (not (insurance-team-member? db current-member-id))
      [support/clear-loading
       [:app.datastar/assoc-state [form-key :_error :_top]
        {:error (tr [:error/not-allowed])}]]

      :else
      [[:db/transact
        (support/with-audit [[:db/retractEntity [:instrument.coverage/coverage-id coverage-id]]]
          current-member-id)
        {}]
       [:app.datastar/respond-sse
        [support/clear-loading-event
         [:app.datastar.sse/redirect (urls/link-policy policy-id)]]]])))

(def actions
  {::validate-coverage-field      #'validate-coverage-field-action
   ::update-instrument-coverage   #'update-instrument-coverage-action
   ::delete-instrument-coverage   #'delete-instrument-coverage-action})
