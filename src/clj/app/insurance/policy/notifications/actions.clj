(ns app.insurance.policy.notifications.actions
  (:require
   [app.insurance.policy.notifications.queries :as queries]
   [app.ledger.domain :as ledger.domain]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]
   [tick.core :as t]))

(def form-key :insurance-payments)
(def signal-key :insurancePayments)

(defn- result-effects
  [result]
  [support/clear-loading
   [:app.datastar/assoc-state [form-key :result] result]])

(defn- selected-member-ids
  [value]
  (->> (if (sequential? value) value [value])
       (keep (fn [member-id]
               (when-not (str/blank? (str member-id))
                 (try
                   (util/ensure-uuid! member-id)
                   (catch Exception _
                     nil)))))
       set))

(defn member-debited-for-policy?
  [db policy-id member-id]
  (boolean (seq (q/ledger-entry-debit-for-policy db member-id policy-id))))

(defn transaction-tx-data
  [db policy-id policy-name members-data]
  (->> members-data
       (remove #(member-debited-for-policy?
                 db
                 policy-id
                 (get-in % [:member :member/member-id])))
       (mapcat (fn [{:keys [member private-cost-total]}]
                 (ledger.domain/prepare-transaction-data
                  db
                  (q/retrieve-ledger db (:member/member-id member))
                  {:ledger.entry/amount       private-cost-total
                   :ledger.entry/tx-date      (t/date)
                   :ledger.entry/posting-date (t/inst)
                   :ledger.entry/description   policy-name
                   :ledger.entry/entry-id     (sq/generate-squuid)}
                  {:ledger.entry.meta/meta-type :ledger.entry.meta.type/insurance
                   :ledger.entry.meta.insurance/policy
                   [:insurance.policy/policy-id policy-id]})))
       vec))

(defn send-notifications-action
  [{:keys [current-member-id db tr]} signals]
  (let [params     (signal-key signals)
        policy-id  (try
                     (some-> (:policyId params) util/ensure-uuid!)
                     (catch Exception _
                       nil))
        member-ids (selected-member-ids (:memberIds params))
        data       (when policy-id
                     (try
                       (queries/notification-data db policy-id current-member-id)
                       (catch Exception _
                         nil)))]
    (cond
      (nil? (:insurance.policy/policy-id (:policy data)))
      (result-effects {:status  :error
                       :message (tr [:error/not-found-title])})

      (empty? member-ids)
      (result-effects {:status  :error
                       :message (tr [:insurance/select-payment-members])})

      :else
      (let [{:keys [to-send unavailable]}
            (queries/select-members (:members-data data) member-ids)]
        (cond
          (seq unavailable)
          (result-effects
           {:status  :error
            :message (tr [:insurance/payments-missing-category-factors]
                         {:category-names (->> unavailable
                                               (mapcat :missing-category-names)
                                               distinct
                                               sort
                                               (str/join ", "))})})

          (empty? to-send)
          (result-effects {:status  :error
                           :message (tr [:insurance/select-payment-members])})

          :else
          [[:app.insurance/send-payment-notifications
            {:tx-data         (support/with-audit
                                (transaction-tx-data
                                 db
                                 policy-id
                                 (:insurance.policy/name (:policy data))
                                 to-send)
                                current-member-id)
             :sender-name     (:sender-name data)
             :time-range      (:time-range data)
             :members-data    to-send
             :result-path     [form-key :result]
             :success         {:status :sent :count-sent (count to-send)}
             :failure-message (tr [:insurance/send-payment-notifications-failed])}]])))))

(def actions
  {::send-notifications #'send-notifications-action})
