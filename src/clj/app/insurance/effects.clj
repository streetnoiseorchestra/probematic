(ns app.insurance.effects
  (:require
   [app.datastar :as d*]
   [app.email :as email]
   [app.errors :as errors]
   [app.insurance.excel :as excel]
   [app.queries :as q]
   [datomic.api :as d]))

(defn- ordered-dispatch!
  [dispatch actions]
  (let [result (dispatch actions)]
    (when-let [error (some-> result :errors first :err)]
      (throw error))
    result))

(defn send-policy-changes-fx
  [{:keys [dispatch]} {:keys [request system]}
   {:keys [attachment-filename-changes attachment-filename-new body on-success
           policy-id recipient redirect subject]}]
  (let [db     (or (:db request) (some-> system :datomic :conn d/db))
        policy (q/retrieve-policy db policy-id)
        smtp   (-> system :env :smtp-sno)]
    (excel/send-email! policy
                       smtp
                       (:from smtp)
                       recipient
                       subject
                       body
                       attachment-filename-new
                       attachment-filename-changes)
    (ordered-dispatch! dispatch on-success)
    (d*/redirect request redirect)))

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
  (d*/respond-signals request :merge {:loading false :targetid false}))
