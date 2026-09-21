(ns app.ig
  "This namespace contains our application's integrant system implementations"
  (:require [app.auth :as auth]
            [app.caldav :as caldav]
            [app.server]
            [app.config :as config]
            [app.datomic.system :as datomic]
            [app.sqlite :as sqlite]
            [app.email.email-worker :as email-worker]
            [app.email.lettermint :as lettermint]
            [app.errors :as error]
            [app.filestore :as filestore]
            [app.icons]
            [app.i18n :as i18n]
            [app.job-queue :as job-queue]
            [app.jobs :as jobs]
            [app.keycloak :as keycloak]
            [app.game-loop.lifecycle :as frame-lifecycle]
            [app.jobs.identity :as identity-jobs]
            [app.jobs.invitations :as invitation-jobs]
            [app.jobs.integrations :as integrations]
            [app.jobs.play-stats :as play-stats]
            [app.jobs.policy-mail :as policy-mail]
            [app.write-runner :as writer]
            [s-exp.drip :as drip]
            [app.nexus :as app-nexus]
            [app.routes :as routes]
            [app.sardine :as sardine]
            [com.brunobonacci.mulog :as μ]
            [integrant.core :as ig]
            [nrepl.server :as nrepl]
            [ol.jobs.ig]
            [app.system :as system]))
(defmethod ig/init-key ::profile [_ profile]
  profile)

(defmethod ig/init-key ::env [_ profile]
  (let [env (system/config profile)]
    (μ/set-global-context! {:app-name   (:name env)
                            :git-hash   (:git-hash env)
                            :build-date (:build-date env)
                            :stage      (-> env :ig/system :app.ig/profile)})
    env))

(defmethod ig/init-key ::handler [_ system]
  (routes/default-handler system))

(defmethod ig/init-key ::nexus
  [_ _config]
  (app-nexus/nexus))

(defmethod ig/init-key ::write-runner [_ _]
  (writer/create))

(defmethod ig/halt-key! ::write-runner [_ control]
  (when control
    (writer/close! control)))

(defmethod ig/init-key ::frame-loop [_ system]
  (frame-lifecycle/start! system))

(defmethod ig/halt-key! ::frame-loop [_ runtime]
  (frame-lifecycle/stop! runtime))

(defmethod ig/init-key :app.ig.router/routes
  [_ system]
  (routes/routes system))

(defmethod ig/init-key :app.ig.jobs/definitions
  [_ {:keys [env] :as system}]
  (if (config/demo-mode? env)
    {}
    (jobs/job-defs system)))

(comment
  ;; CORS

  {:creds true
   :allowed-origins
   ["" (config/app-base-url env) (config/keycloak-auth-server-url env)]
   ;; (constantly true)
   })

(defmethod ig/init-key ::gigo-client
  [_ {:keys [env]}]
  (when-not (config/demo-mode? env)
    {:username    (get-in env [:gigo :username])
     :password    (get-in env [:gigo :password])
     :cookie-atom (atom nil)}))

(defmethod ig/init-key ::datomic-db
  [_ config]
  (μ/log ::init-datomic)
  (datomic/start config))

(defmethod ig/halt-key! ::datomic-db
  [_ config]
  (μ/log ::halt-datomic)
  (datomic/stop config))

(defmethod ig/init-key ::auxiliary
  [_ config]
  (μ/log ::init-auxiliary)
  (sqlite/start config))

(defmethod ig/halt-key! ::auxiliary
  [_ config]
  (μ/log ::halt-auxiliary)
  (sqlite/stop config))

(defmethod ig/init-key ::job-queue
  [_ config]
  (job-queue/start! config))

(defmethod ig/init-key ::job-maintenance [_ {:keys [job-queue]}]
  (drip/start-maintenance-worker! {:client (:client job-queue) :queues []}))

(defmethod ig/init-key ::invitation-worker [_ system]
  (invitation-jobs/start! system))

(defmethod ig/halt-key! ::invitation-worker [_ worker]
  (when worker (invitation-jobs/stop! worker)))

(defmethod ig/init-key ::identity-worker [_ system]
  (identity-jobs/start! system))

(defmethod ig/halt-key! ::identity-worker [_ worker]
  (when worker (identity-jobs/stop! worker)))

(defmethod ig/init-key ::integrations-worker [_ system]
  (integrations/start! system))

(defmethod ig/halt-key! ::integrations-worker [_ worker]
  (when worker (integrations/stop! worker)))

(defmethod ig/init-key ::play-stats-worker [_ system]
  (play-stats/start! system))

(defmethod ig/halt-key! ::play-stats-worker [_ worker]
  (when worker (play-stats/stop! worker)))

(defmethod ig/init-key ::policy-mail-worker [_ system]
  (policy-mail/start! system))

(defmethod ig/halt-key! ::policy-mail-worker [_ worker]
  (when worker (policy-mail/stop! worker)))

(defmethod ig/halt-key! ::job-maintenance [_ maintenance]
  (when-not (drip/stop-maintenance-worker! maintenance)
    (throw (ex-info "Job queue maintenance did not stop" {}))))

(defmethod ig/halt-key! ::job-queue
  [_ queue]
  (job-queue/stop! queue))

(defmethod ig/init-key ::i18n-langs
  [_ _]
  (i18n/read-langs))

(defmethod ig/init-key ::webdav-sardine
  [_ {:keys [env]}]
  (sardine/build-config (:nextcloud env)))

(defmethod ig/halt-key! ::webdav-sardine
  [_ {:keys [client]}]
  (sardine/shutdown client))

(defmethod ig/init-key ::lettermint [_ system]
  (lettermint/start-component! system))

(defmethod ig/init-key ::email-worker
  [_ sys]
  (email-worker/start! sys))

(defmethod ig/halt-key! ::email-worker
  [_ sys]
  (email-worker/stop! sys))

(defmethod ig/init-key ::oauth2
  [_ sys]
  (auth/build-oauth2-config (:env sys)))

(defmethod ig/init-key ::keycloak [_ sys]
  (keycloak/init! sys))

(defmethod ig/halt-key! ::keycloak
  [_ sys]
  (keycloak/halt! sys))

(defmethod ig/init-key ::nrepl-server
  [_ {:keys [port bind ack-port] :as config}]
  (when (> port 0)
    (try
      (let [server (nrepl/start-server :port port
                                       :bind bind
                                       :ack-port ack-port)]

        (μ/log ::init-nrepl :msg "nREPL server started" :port port)
        (assoc config ::server server))
      (catch Exception e
        (μ/log ::error-nrepl :ex e :msg "failed to start the nREPL server on port:" :port port)
        (throw e)))))

(defmethod ig/halt-key! ::nrepl-server
  [_ {::keys [server]}]
  (when server
    (nrepl/stop-server server))
  (μ/log ::halt-nrepl))

(defmethod ig/init-key ::calendar
  [_ {:keys [env]}]
  (caldav/init-calendar env))

(defmethod ig/init-key ::filestore
  [_ {:keys [env]}]
  (filestore/start! (:filestore env)))

(defmethod ig/halt-key! ::filestore
  [_ store]
  (filestore/halt! store))

(defmethod ig/init-key ::console-logging
  [_ {:keys [pretty?]}]
  (μ/start-publisher!
   {:type      :console
    :pretty?   pretty?
    :transform error/redact-mulog-events}))

(defmethod ig/halt-key! ::console-logging
  [_ stop-pub]
  (stop-pub))

(defmethod ig/init-key ::openobserve
  [_ {:keys [url user password data-stream enabled?]}]
  (when enabled?
    (μ/log ::openobserve-publisher :data-stream data-stream)
    (assert url "url is required")
    (assert user "user is required")
    (assert password "password is required")
    (assert data-stream "data-stream is required")
    (μ/start-publisher!
     {:type        :elasticsearch
      :url         url
      :els-version :v7.x
      :data-stream data-stream
      :http-opts   {:basic-auth [user password]}
      :transform   error/redact-mulog-events})))

(defmethod ig/halt-key! ::openobserve
  [_ stop-pub]
  (when stop-pub
    (stop-pub)))
