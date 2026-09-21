(ns app.insurance.effects
  (:require
   [app.datastar :as d*]
   [app.email :as email]
   [app.errors :as errors]))

(defn- ordered-dispatch!
  [dispatch actions]
  (let [result (dispatch actions)]
    (when-let [error (some-> result :errors first :err)]
      (throw error))
    result))

(defn send-payment-notifications-fx
  [{:keys [dispatch]} {request :request}
   {:keys [failure-message members-data result-path sender-name success time-range tx-data]}]
  (try
    (ordered-dispatch! dispatch [[:db/transact tx-data {}]])
    (email/send-insurance-debt-notifications!
     request
     sender-name
     time-range
     members-data)
    (d*/state-transact! request #(assoc-in % result-path success))
    (catch Exception error
      (errors/report-error! error {:effect :app.insurance/send-payment-notifications})
      (d*/state-transact! request
                          #(assoc-in % result-path
                                     {:status  :error
                                      :message failure-message}))))
  nil)

(defn send-survey-notifications-fx
  [_ {request :request}
   {:keys [email-data failure-message members policy result-path sender-name success]}]
  (try
    (email/send-survey-notifications!
     request
     sender-name
     policy
     members
     email-data)
    (d*/state-transact! request #(assoc-in % result-path success))
    (catch Exception error
      (errors/report-error! error {:effect :app.insurance/send-survey-notifications})
      (d*/state-transact! request
                          #(assoc-in % result-path
                                     {:status  :error
                                      :message failure-message}))))
  nil)
