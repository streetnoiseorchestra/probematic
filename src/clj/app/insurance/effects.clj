(ns app.insurance.effects
  (:require
   [app.datastar :as d*]
   [app.insurance.excel :as excel]
   [app.queries :as q]
   [datomic.api :as d]))

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
    (let [result (dispatch on-success)]
      (when-let [error (some-> result :errors first :err)]
        (throw error)))
    (d*/redirect request redirect)))
