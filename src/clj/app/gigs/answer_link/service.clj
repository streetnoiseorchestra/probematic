(ns app.gigs.answer-link.service
  (:require
   [app.config :as config]
   [app.datomic :as db]
   [app.datomic.shim :as datomic]
   [app.gigs.answer-link.actions :as actions]
   [app.queries :as q]
   [app.secret-box :as secret-box]
   [app.util :as util]
   [app.write-runner :as writer]
   [com.yetanalytics.squuid :as sq]
   [tick.core :as t]))

(defn- param [req k]
  (or (get-in req [:params k])
      (get-in req [:params (name k)])))

(defn answer-token [req]
  (param req :answer))

(defn- decode-answer [token secret]
  (try
    (let [answer (secret-box/decrypt token secret)]
      {:gig-id    (util/ensure-uuid! (:gig/gig-id answer))
       :member-id (util/ensure-uuid! (:member/member-id answer))
       :plan      (:attendance/plan answer)
       :reminder? (boolean (:reminder answer))})
    (catch Exception _exception
      {:error {:type ::invalid-answer-link}})))

(defn- request->command [req]
  (let [env    (or (:env req) (get-in req [:system :env]))
        secret (config/app-secret-key env)]
    (when-not secret
      (throw (ex-info "Answer-link decryption requires an application secret" {})))
    (let [answer (decode-answer (answer-token req) secret)]
      (if (:error answer)
        answer
        (assoc answer
               :actor-id (get-in req [:app/session :session/member :member/member-id])
               :env env
               :reminder-id (sq/generate-squuid)
               :submitted-at (t/instant)
               :submitted-on (t/date))))))

(defn- execute-plan! [conn command plan]
  (if (:error plan)
    plan
    (let [report   (db/transact conn plan)
          db-after (:db-after report)]
      {:gig       (q/retrieve-gig db-after (:gig-id command))
       :member    (q/retrieve-member db-after (:member-id command))
       :reminder? (:reminder? command)})))

(defn submit-answer! [req]
  (let [command (request->command req)]
    (if (:error command)
      command
      (let [conn (:datomic-conn req)]
        (when-not conn
          (throw (ex-info "Answer-link submission requires a Datomic connection" {})))
        (writer/call!
         (:system req)
         (fn []
           (execute-plan!
            conn
            command
            (actions/plan-submission (datomic/db conn) command))))))))
