(ns app.ig
  "This namespace contains our application's integrant system implementations"
  (:require [app.auth :as auth]
            [app.caldav :as caldav]
            [app.server]
            [app.config :as config]
            [app.datomic.system :as datomic]
            [app.engine :as engine]
            [app.email.email-worker :as email-worker]
            [app.errors :as error]
            [app.filestore :as filestore]
            [app.i18n :as i18n]
            [app.jobs :as jobs]
            [app.keycloak :as keycloak]
            [app.routes :as routes]
            [app.sardine :as sardine]
            [com.brunobonacci.mulog :as μ]
            [ctmx.render :as ctmx.render]
            [hiccup2.core :as hiccup2]
            [integrant.core :as ig]
            [nrepl.server :as nrepl]
            [ol.jobs.ig]
            [app.system :as system]
            [app.datastar]
            [taoensso.carmine :as car]))
;; Ensure ctmx is using the XSS safe hiccup render function
(alter-var-root #'ctmx.render/html (constantly
                                    #(-> % ctmx.render/walk-attrs hiccup2/html str)))

(defmethod ig/init-key ::profile [_ profile]
  profile)

(defmethod ig/init-key ::env [_ profile]
  (let [env (system/config profile)]
    (μ/set-global-context! {:app-name (:name env)
                            :git-hash (:git-hash env)
                            :build-date  (:build-date env)
                            :stage (-> env :ig/system :app.ig/profile)})
    env))

(defmethod ig/init-key ::handler [_ system]
  (routes/default-handler system))

(defmethod ig/init-key ::engine
  [_ config]
  (engine/build-env config))

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

(defmethod ig/init-key ::i18n-langs
  [_ _]
  (i18n/read-langs))

(defmethod ig/init-key ::webdav-sardine
  [_ {:keys [env]}]
  (sardine/build-config (:nextcloud env)))

(defmethod ig/halt-key! ::webdav-sardine
  [_ {:keys [client]}]
  (sardine/shutdown client))

(defmethod ig/init-key ::mailgun
  [_ {:keys [env]}]
  (:mailgun env))

(defmethod ig/init-key ::redis
  [_ {:keys [env]}]
  {:pool (car/connection-pool {})
   :spec (-> env :redis :conn-spec)})

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
   {:type :console
    :pretty? pretty?
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
     {:type :elasticsearch
      :url url
      :els-version :v7.x
      :data-stream data-stream
      :http-opts {:basic-auth [user password]}
      :transform error/redact-mulog-events})))

(defmethod ig/halt-key! ::openobserve
  [_ stop-pub]
  (when stop-pub
    (stop-pub)))
