;; From https://github.com/clojusc/ring-redis-session/commit/7bd934794066924d06447c090a9800ad881fd98b
;; Copyright © 2013 Zhe Wu wu@madk.org
;; Copyright © 2016-2018 Clojure-Aided Enrichment Center
;; Distributed under the Eclipse Public License, the same as Clojure.
(ns app.session (:require
                 [ring.middleware.session.store :refer [SessionStore]]
                 [taoensso.carmine :as redis])
    (:import
     [java.util UUID]))

(defn new-session-key [prefix]
  (str prefix ":" (UUID/randomUUID)))

(deftype RedisStore [redis-conn prefix expiration reset-on-read read-handler write-handler]
  SessionStore

  (read-session [_ session-key]
    (when session-key
      (when-let [data (redis/wcar redis-conn (redis/get session-key))]
        (let [read-handler read-handler]
          (when (and expiration reset-on-read)
            (redis/wcar redis-conn (redis/expire session-key expiration)))
          (read-handler data)))))

  (write-session
    [_ old-session-key data]
    (let [session-key (or old-session-key (new-session-key prefix))]
      (let [write-handler write-handler]
        (if expiration
          (redis/wcar redis-conn (redis/setex session-key expiration (write-handler data)))
          (redis/wcar redis-conn (redis/set session-key (write-handler data)))))
      session-key))

  (delete-session
    [_ session-key]
    (redis/wcar redis-conn (redis/del session-key))
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;   Constructor   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn redis-store
  "Creates a redis-backed session storage engine."
  ([redis-conn]
   (redis-store redis-conn {}))
  ([redis-conn {:keys [prefix expire-secs reset-on-read read-handler write-handler]
                :or   {prefix        "session"
                       read-handler  identity
                       write-handler identity
                       reset-on-read false}}]
   (RedisStore. redis-conn prefix expire-secs reset-on-read read-handler write-handler)))
