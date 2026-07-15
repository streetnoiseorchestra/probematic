(ns app.insurance.coverage.create.actions
  (:require
   [app.form :as form]
   [app.insurance.queries :as queries]
   [app.insurance.domain :as domain]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]))

(def form-key :coverage-create)

(defn- form-params [signals]
  (let [params (or (form-key signals) signals)]
    (dissoc params :tab-id :_error :validate-field)))

(defn normalize-instrument-form [params]
  (reduce
   (fn [params [k f]]
     (form/update-present params k f))
   params
   [[:policy-id form/trim-value]
    [:instrument-id form/trim-value]
    [:redirect #(or (form/trim-value %) "")]
    [:instrument-name form/trim-value]
    [:owner-member-id form/trim-value]
    [:category-id form/trim-value]
    [:make form/trim-value]
    [:model #(or (form/trim-value %) "")]
    [:serial-number #(or (form/trim-value %) "")]
    [:build-year #(or (form/trim-value %) "")]
    [:description #(or (form/trim-value %) "")]]))

(defn- optional-uuid [value]
  (some-> value form/optional-text util/ensure-uuid!))

(defn- label [tr field]
  (case field
    :owner-member-id (tr [:instrument/owner])
    :category-id (tr [:instrument/category])
    :instrument-name (tr [:instrument/name])
    :make (tr [:instrument/make])
    :item-count (tr [:insurance/item-count])
    :value (tr [:insurance/value])
    :private-band (tr [:band-private])
    (name field)))

(defn- required-error [tr field]
  {:error (tr [:error/is-required] [(label tr field)])})

(defn- top-error [message]
  {:_top {:error message}})

(defn- with-generic-top-error [tr errors]
  (cond-> errors
    (and (seq errors) (nil? (:_top errors)))
    (assoc :_top {:error (tr [:error/form-has-errors])})))

(defn- present-policy [policy]
  (when (:insurance.policy/policy-id policy)
    policy))

(defn- retrieve-policy [db policy-id]
  (try
    (some-> (q/retrieve-policy db policy-id) present-policy)
    (catch Exception _
      nil)))

(defn- retrieve-instrument [db instrument-id]
  (try
    (q/retrieve-instrument db instrument-id)
    (catch Exception _
      nil)))

(defn- insurance-team-member? [db current-member-id]
  (when-let [member (q/retrieve-member db current-member-id)]
    (q/insurance-team-member? db member)))

(defn- instrument-context [db params]
  (let [policy-id     (optional-uuid (:policy-id params))
        instrument-id (optional-uuid (:instrument-id params))
        policy        (when policy-id
                        (retrieve-policy db policy-id))
        instrument    (when instrument-id
                        (retrieve-instrument db instrument-id))]
    {:policy-id     policy-id
     :instrument-id instrument-id
     :policy        policy
     :instrument    instrument}))

(defn instrument-validation-errors
  [{:keys [tr] :as state} params]
  (let [{:keys [instrument-id instrument policy] :as _ctx} (instrument-context (:db state) params)]
    (merge
     (when-not policy
       (top-error (tr [:error/not-found-title])))
     (when (and policy (not (queries/policy-editable? policy)))
       (top-error (tr [:insurance/error-edit-frozen-policy])))
     (when (and instrument-id (nil? instrument))
       (top-error (tr [:error/not-found-title])))
     (when (str/blank? (:owner-member-id params))
       {:owner-member-id (required-error tr :owner-member-id)})
     (when (str/blank? (:category-id params))
       {:category-id (required-error tr :category-id)})
     (when (str/blank? (:instrument-name params))
       {:instrument-name (required-error tr :instrument-name)})
     (when (str/blank? (:make params))
       {:make (required-error tr :make)}))))

(defn- instrument-share-link-txs [{:keys [env]} instrument-ref instrument-id]
  (when env
    (domain/txs-instrument-share-link {:system {:env env}}
                                      instrument-ref
                                      instrument-id)))

(defn save-instrument-step-tx-data [state ctx params instrument-id]
  (let [new?           (nil? (:instrument-id ctx))
        instrument-ref (if new?
                         "instrument"
                         [:instrument/instrument-id instrument-id])
        instrument-tx  (cond->
                        {:instrument/instrument-id instrument-id
                         :instrument/description   (:description params)
                         :instrument/build-year    (:build-year params)
                         :instrument/serial-number (:serial-number params)
                         :instrument/make          (:make params)
                         :instrument/model         (:model params)
                         :instrument/name          (:instrument-name params)
                         :instrument/owner         [:member/member-id (util/ensure-uuid! (:owner-member-id params))]
                         :instrument/category      [:instrument.category/category-id (util/ensure-uuid! (:category-id params))]}
                         new? (assoc :db/id "instrument"))]
    (vec
     (concat
      [instrument-tx]
      (instrument-share-link-txs state instrument-ref instrument-id)))))

(defn validate-instrument-field-action
  [state signals]
  (let [raw    (or (form-key signals) {})
        field  (some-> (:validate-field raw) keyword)
        params (dissoc (normalize-instrument-form raw) :_error :validate-field)
        error  (get (instrument-validation-errors state params) field)]
    (cond-> [[:app.datastar/merge-state [form-key] params]]
      field (conj [:app.datastar/assoc-state
                   [form-key :_error field]
                   error]))))

(defn save-instrument-step-action
  [{:keys [current-member-id db tr] :as state} signals]
  (let [params (normalize-instrument-form (form-params signals))
        ctx    (instrument-context db params)
        errors (with-generic-top-error tr (instrument-validation-errors state params))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [form-key] (assoc params :_error errors)]]
      (let [instrument-id (or (:instrument-id ctx) (sq/generate-squuid))]
        [[:db/transact
          (support/with-audit (save-instrument-step-tx-data state ctx params instrument-id)
            current-member-id)
          {}]
         [:app.datastar/respond-sse
          [[:app.datastar.sse/redirect
            (urls/link-coverage-create2 (:policy-id ctx)
                                        instrument-id
                                        (form/optional-text (:redirect params)))]]]]))))

(defn normalize-coverage-form [params]
  (reduce
   (fn [params [k f]]
     (form/update-present params k f))
   params
   [[:policy-id form/trim-value]
    [:instrument-id form/trim-value]
    [:redirect #(or (form/trim-value %) "")]
    [:item-count form/trim-value]
    [:value form/trim-value]
    [:private-band form/trim-value]
    [:coverage-types #(->> (util/ensure-coll %)
                           (keep form/optional-text)
                           vec)]
    [:insurer-id #(or (form/trim-value %) "")]]))

(defn- parse-positive-long [value]
  (try
    (when-let [parsed (some-> value form/optional-text parse-long)]
      (when (pos? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn- number-error [tr field]
  {:error (tr [:insurance/error-invalid-number] [(label tr field)])})

(defn- coverage-type-error [tr]
  {:error (tr [:insurance/error-invalid-coverage-type])})

(defn- policy-coverage-type-ids [policy]
  (mapv :insurance.coverage.type/type-id
        (:insurance.policy/coverage-types policy)))

(defn- required-policy-coverage-type-ids [policy]
  (->> (:insurance.policy/coverage-types policy)
       (filter :insurance.coverage.type/required?)
       (mapv :insurance.coverage.type/type-id)))

(def ^:private invalid-coverage-type-id ::invalid-coverage-type-id)

(defn- submitted-coverage-type-ids [coverage-types]
  (->> coverage-types
       (map (fn [value]
              (try
                (util/ensure-uuid! value)
                (catch Exception _
                  invalid-coverage-type-id))))
       (remove nil?)
       util/remove-dummy-uuid
       vec))

(defn- invalid-private-coverage-types? [policy params]
  (let [valid-type-ids (set (policy-coverage-type-ids policy))]
    (some #(not (contains? valid-type-ids %))
          (submitted-coverage-type-ids (:coverage-types params)))))

(defn coverage-validation-errors
  [{:keys [tr] :as state} params]
  (let [{:keys [instrument policy]} (instrument-context (:db state) params)
        policy-type-ids (policy-coverage-type-ids policy)
        private-band   (:private-band params)]
    (merge
     (cond
       (nil? policy)
       (top-error (tr [:error/not-found-title]))

       (not (queries/policy-editable? policy))
       (top-error (tr [:insurance/error-edit-frozen-policy]))

       (nil? instrument)
       (top-error (tr [:error/not-found-title]))

       (empty? policy-type-ids)
       (top-error (tr [:insurance/error-invalid-coverage-type]))

       :else
       nil)
     (when-not (parse-positive-long (:item-count params))
       {:item-count (number-error tr :item-count)})
     (when-not (parse-positive-long (:value params))
       {:value (number-error tr :value)})
     (when-not (#{"band" "private"} private-band)
       {:private-band (required-error tr :private-band)})
     (when (and policy
                (= "private" private-band)
                (invalid-private-coverage-types? policy params))
       {:coverage-types (coverage-type-error tr)}))))

(defn- selected-coverage-type-ids [policy params]
  (let [policy-type-ids (policy-coverage-type-ids policy)]
    (if (= "private" (:private-band params))
      (->> (concat (required-policy-coverage-type-ids policy)
                   (submitted-coverage-type-ids (:coverage-types params)))
           distinct
           vec)
      policy-type-ids)))

(defn create-coverage-tx-data
  [{:keys [insurance-team-member? instrument-id policy-id policy]} params coverage-id]
  (let [coverage-tx (cond-> {:db/id                           "covered_instrument"
                             :instrument.coverage/coverage-id coverage-id
                             :instrument.coverage/instrument  [:instrument/instrument-id instrument-id]
                             :instrument.coverage/value       (bigdec (parse-positive-long (:value params)))
                             :instrument.coverage/item-count  (parse-positive-long (:item-count params))
                             :instrument.coverage/status      :instrument.coverage.status/needs-review
                             :instrument.coverage/change      :instrument.coverage.change/new
                             :instrument.coverage/private?    (= "private" (:private-band params))}
                      (and insurance-team-member?
                           (form/optional-text (:insurer-id params)))
                      (assoc :instrument.coverage/insurer-id
                             (form/optional-text (:insurer-id params))))]
    (vec
     (concat
      (map (fn [type-id]
             [:db/add
              "covered_instrument"
              :instrument.coverage/types
              [:insurance.coverage.type/type-id type-id]])
           (selected-coverage-type-ids policy params))
      [coverage-tx
       [:db/add
        [:insurance.policy/policy-id policy-id]
        :insurance.policy/covered-instruments
        "covered_instrument"]]))))

(defn validate-coverage-field-action
  [state signals]
  (let [raw    (or (form-key signals) {})
        field  (some-> (:validate-field raw) keyword)
        params (dissoc (normalize-coverage-form raw) :_error :validate-field)
        error  (get (coverage-validation-errors state params) field)]
    (cond-> [[:app.datastar/merge-state [form-key] params]]
      field (conj [:app.datastar/assoc-state
                   [form-key :_error field]
                   error]))))

(defn create-coverage-action
  [{:keys [current-member-id db tr] :as state} signals]
  (let [params (normalize-coverage-form (form-params signals))
        ctx    (assoc (instrument-context db params)
                      :insurance-team-member? (insurance-team-member? db current-member-id))
        errors (with-generic-top-error tr (coverage-validation-errors state params))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [form-key] (assoc params :_error errors)]]
      (let [coverage-id (sq/generate-squuid)]
        [[:db/transact
          [[:instrument.coverage/create-once
            (:policy-id ctx)
            (:instrument-id ctx)
            (support/with-audit (create-coverage-tx-data ctx params coverage-id)
              current-member-id)]]
          {}]
         [:app.datastar/respond-sse
          [support/clear-loading-event
           [:app.datastar.sse/redirect
            (or (form/optional-text (:redirect params))
                (urls/link-policy (:policy-id ctx)))]]]]))))

(def actions
  {::validate-instrument-field #'validate-instrument-field-action
   ::save-instrument-step      #'save-instrument-step-action
   ::validate-coverage-field   #'validate-coverage-field-action
   ::create-coverage           #'create-coverage-action})
