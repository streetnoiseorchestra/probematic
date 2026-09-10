(ns app.members.invite.http
  "Public invitation HTTP adapters for durable setup and its read-only status page."
  (:require
   [app.datastar :as datastar]
   [app.game-loop.storage :as storage]
   [app.members.invite.admission :as admission]
   [app.members.invite.queries :as queries]
   [app.members.invite.status-views :as status-views]
   [app.members.invite.stream :as stream]
   [app.members.invite.views :as legacy]
   [app.util :as util]
   [tick.core :as t]))

(defn- durable? [req]
  (get-in req [:system :frame-loop :durable-jobs?]))

(defn- code-url [path code]
  (str path "?invite-code=" (util/url-encode code)))

(defn invite-accept [req]
  (if-not (durable? req)
    (legacy/invite-accept req)
    (let [code (legacy/form-invite-code req)]
      (datastar/render-in-frame!
       (get-in req [:system :frame-loop])
       (fn [frame]
         (let [req        (assoc req :db (:db frame))
               projection (queries/status-for-code (:db frame) (get-in req [:system :job-queue :client])
                                                   (get-in frame [::storage/read-dbs :jobs]) (t/inst) code)]
           (update
            (if (contains? #{:pending :unavailable} (:status projection))
              (legacy/invite-accept req)
              (status-views/page req projection code (legacy/login-link req (:member projection))
                                 (code-url "/invite-status" code)))
            :headers merge {"Cache-Control" "no-store" "Referrer-Policy" "no-referrer"})))))))

(defn invite-accept-post [req]
  (if-not (durable? req)
    (legacy/invite-accept-post req)
    (let [code   (legacy/form-invite-code req)
          result (admission/request-setup! {:datomic-conn (:datomic-conn req)
                                            :write-runner (get-in req [:system :frame-loop :write-runner])
                                            :clock        t/inst}
                                           code)]
      (if (contains? #{:creating :accepted} (:status result))
        {:status 303 :headers {"Location"      (code-url "/invite-accept" code)
                               "Cache-Control" "no-store"                       "Referrer-Policy" "no-referrer"} :body ""}
        (invite-accept req)))))

(defn invite-status [req]
  (if-not (durable? req)
    {:status 503 :headers {} :body ""}
    (let [code (legacy/form-invite-code req)]
      (stream/response (:system req) req code
                       (fn [request projection]
                         (status-views/fragment-html request projection code
                                                     (legacy/login-link request (:member projection))))))))
