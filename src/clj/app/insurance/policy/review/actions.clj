(ns app.insurance.policy.review.actions
  (:require
   [clojure.string :as str]
   [app.insurance.coverage.queries :as coverage.queries]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]))

(def form-key :insurance-review)

(defn- target-coverage-id
  [signals]
  (try
    (some-> (or (:targetid signals)
                (:coverage-id signals))
            util/ensure-uuid!)
    (catch Exception _
      nil)))

(defn- insurance-team-member?
  [db current-member-id]
  (when-let [member (and current-member-id (q/retrieve-member db current-member-id))]
    (q/insurance-team-member? db member)))

(defn- error-effects
  [tr key]
  [support/clear-loading
   [:app.datastar/assoc-state [form-key :error]
    {:error (tr key)}]])

(defn- success-effects
  [current-member-id tx-data]
  [[:db/transact
    (support/with-audit tx-data current-member-id)
    {}]
   support/clear-loading
   [:app.datastar/assoc-state [form-key :error] nil]])

(defn- mark-coverage-attr-action
  [{:keys [current-member-id db tr]} signals attr value]
  (let [coverage-id (target-coverage-id signals)
        coverage    (when coverage-id
                      (q/retrieve-coverage db coverage-id))
        policy      (:insurance.policy/_covered-instruments coverage)]
    (cond
      (nil? coverage)
      (error-effects tr [:insurance.review/error-coverage-not-found])

      (not (insurance-team-member? db current-member-id))
      (error-effects tr [:insurance.review/error-not-allowed])

      (not (coverage.queries/policy-editable? policy))
      (error-effects tr [:insurance.review/error-frozen-policy])

      :else
      (success-effects current-member-id
                       [[:db/add
                         [:instrument.coverage/coverage-id coverage-id]
                         attr
                         value]]))))

(defn mark-coverage-reviewed-action
  [state signals]
  (mark-coverage-attr-action state
                             signals
                             :instrument.coverage/status
                             :instrument.coverage.status/reviewed))

(defn- insurer-id-signal-key
  [coverage-id]
  (keyword (str "insurer-id-" coverage-id)))

(defn- present-text
  [& values]
  (some (fn [value]
          (let [text (str/trim (str value))]
            (when-not (str/blank? text)
              text)))
        values))

(defn- signal-insurer-id
  [signals coverage-id]
  (or (present-text (get-in signals [:insuranceReview :insurerId])
                    (get-in signals [:insuranceReview :insurer-id])
                    (get-in signals [:insurance-review :insurerId])
                    (get-in signals [:insurance-review (insurer-id-signal-key coverage-id)])
                    (get-in signals [:insurance-review :insurer-id])
                    (:insurerId signals)
                    (:insurer-id signals))
      ""))

(defn- has-db-transact?
  [effects]
  (some #(= :db/transact (first %)) effects))

(defn update-insurer-id-action
  [{:keys [tr] :as state} signals]
  (let [coverage-id (target-coverage-id signals)
        insurer-id  (signal-insurer-id signals coverage-id)]
    (if (str/blank? insurer-id)
      [support/clear-loading
       [:app.datastar/assoc-state [form-key :error]
        {:error (tr [:error/is-required] [(tr [:instrument.coverage/insurer-id])])}]]
      (let [effects (mark-coverage-attr-action state
                                               signals
                                               :instrument.coverage/insurer-id
                                               insurer-id)]
        (if (has-db-transact? effects)
          (conj effects
                [:app.datastar/remove-signals
                 ["insuranceReview.insurerId"
                  "insurance-review.insurer-id"
                  (str "insurance-review.insurer-id-" coverage-id)]])
          effects)))))

(def actions
  {::mark-coverage-reviewed #'mark-coverage-reviewed-action
   ::update-insurer-id      #'update-insurer-id-action})
