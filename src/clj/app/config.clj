(ns app.config)

(defn profile
  [env]
  (-> env :ig/system :app.ig/profile))

(defn dev-mode?
  [env]
  (= :dev (profile env)))

(defn demo-mode?
  [env]
  (= :demo (profile env)))

(defn prod-mode?
  [env]
  (= :prod (profile env)))

(defn test-mode?
  [env]
  (= :test (profile env)))

(defn app-secret-key
  [env]
  (assert env)
  (:app-secret-key env))

(defn app-base-url
  [env]
  (assert env)
  (:app-base-url env))

(defn session-config
  [env]
  (let [{:keys [session-ttl-s] :as sc} (:session-config env)]
    (-> sc
        (assoc-in [:cookie-attrs :max-age] session-ttl-s)
        (assoc-in [:cookie-attrs :secure] (not (dev-mode? env))))))

(defn oauth2-certificate-filename
  [env]
  (-> env :authorization :cert-filename))

(defn oauth2-known-roles
  [env]
  (-> env :authorization :known-roles))

(defn keycloak-auth-server-url
  [env]
  (-> env :keycloak :auth-server-url))

(defn discourse-forum-url
  [env]
  (-> env :discourse :forum-url))

(defn nextcloud-api-config [env]
  (-> env :nextcloud (select-keys [:username :password])))

(defn nextcloud-url
  [env]
  (format "https://%s" (-> env :nextcloud :host)))

(defn nextcloud-path-current-songs
  [env]
  (-> env :nextcloud :current-songs-path))

(defn nextcloud-path-sheet-music
  [env]
  (-> env :nextcloud :sheet-music-path))

(defn nextcloud-path-insurance-upload
  [env]
  (-> env :nextcloud :insurance-upload-path))

(defn nextcloud-path-song-image-upload
  [env]
  (-> env :nextcloud :songs-images-upload-path))
(defn public-calendar-url [env]
  (-> env :nextcloud :public-calendar-url))

(defn band-bank-info [env]
  (-> env :band-bank-info))

(defn external-insurance-policy [env]
  (-> env :insurance :insurance-policy))

(defn insurance-image-settings [env]
  (-> env :insurance :images))
