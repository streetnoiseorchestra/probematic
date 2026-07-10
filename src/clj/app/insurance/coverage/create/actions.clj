(ns app.insurance.coverage.create.actions
  (:require
   [app.form :as form]
   [app.insurance.coverage.queries :as coverage.queries]
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
     (when (and policy (not (coverage.queries/policy-editable? policy)))
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
         [:app.datastar/redirect
          (urls/link-coverage-create2 (:policy-id ctx)
                                      instrument-id
                                      (form/optional-text (:redirect params)))]]))))

(def actions
  {::validate-instrument-field #'validate-instrument-field-action
   ::save-instrument-step      #'save-instrument-step-action})
