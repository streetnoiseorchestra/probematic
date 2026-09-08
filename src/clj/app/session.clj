(ns app.session
  (:require
   [app.crypto :as crypto]
   [cljc.java-time.instant :as instant]
   [ring.middleware.session.store :refer [SessionStore]]
   [sqlite4clj.core :as sql]
   [tick.core :as t]))

(deftype SQLiteStore [db expire-secs]
  SessionStore
  (read-session [_ session-key]
    (when session-key
      (first (sql/q (:reader db)
                    ["SELECT data FROM http_sessions WHERE session_key = ? AND expires_at > ?"
                     session-key (instant/get-epoch-second (t/instant))]))))
  (write-session [_ old-session-key data]
    (let [session-key (or old-session-key (crypto/new-uid))
          now         (instant/get-epoch-second (t/instant))]
      (sql/with-write-tx [conn (:writer db)]
        (sql/q conn ["DELETE FROM http_sessions WHERE expires_at <= ?" now])
        (sql/q conn ["INSERT INTO http_sessions (session_key, data, expires_at) VALUES (?, ?, ?)
                       ON CONFLICT (session_key) DO UPDATE SET data = excluded.data, expires_at = excluded.expires_at"
                     session-key data (+ now expire-secs)]))
      session-key))
  (delete-session [_ session-key]
    (when session-key
      (sql/q (:writer db) ["DELETE FROM http_sessions WHERE session_key = ?" session-key]))
    nil))

(defn sqlite-store
  "Creates a Ring session store and its table in `db`.

  | Option | Description |
  |--------|-------------|
  | `:expire-secs` | Required positive session lifetime in seconds. |

  Writes refresh expiry; reads do not. Expired rows are removed at startup and
  on writes. The caller owns the database lifecycle."
  [db {:keys [expire-secs]}]
  {:pre [(pos-int? expire-secs)]}
  (sql/with-write-tx [conn (:writer db)]
    (sql/q conn ["CREATE TABLE IF NOT EXISTS http_sessions (
                   session_key TEXT PRIMARY KEY NOT NULL,
                   data BLOB NOT NULL,
                   expires_at INTEGER NOT NULL)"])
    (sql/q conn ["CREATE INDEX IF NOT EXISTS http_sessions_expiry ON http_sessions (expires_at)"])
    (sql/q conn ["DELETE FROM http_sessions WHERE expires_at <= ?"
                 (instant/get-epoch-second (t/instant))]))
  (SQLiteStore. db expire-secs))
