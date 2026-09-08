(ns app.session
  (:require
   [app.crypto :as crypto]
   [cljc.java-time.instant :as instant]
   [sqlite4clj.core :as sql]
   [tick.core :as t]))

(defn read-session [db sid]
  (when sid
    (first (sql/q (:reader db)
                  ["SELECT data FROM http_sessions WHERE session_key = ? AND expires_at > ?"
                   sid (instant/get-epoch-second (t/instant))]))))

(defn delete-session! [db sid]
  (when sid
    (sql/q (:writer db) ["DELETE FROM http_sessions WHERE session_key = ?" sid]))
  nil)

(defn write-session!
  "Saves `data` and refreshes expiry. A different `sid` atomically replaces `old-sid`."
  ([db old-sid data]
   (write-session! db old-sid (or old-sid (crypto/new-uid)) data))
  ([db old-sid sid data]
   (let [now (instant/get-epoch-second (t/instant))]
     (sql/with-write-tx [conn (:writer db)]
       (sql/q conn ["DELETE FROM http_sessions WHERE expires_at <= ?" now])
       (when (and old-sid (not= old-sid sid))
         (sql/q conn ["DELETE FROM http_sessions WHERE session_key = ?" old-sid]))
       (sql/q conn ["INSERT INTO http_sessions (session_key, data, expires_at) VALUES (?, ?, ?)
                      ON CONFLICT (session_key) DO UPDATE SET data = excluded.data, expires_at = excluded.expires_at"
                    sid data (+ now (:expire-secs db))]))
     sid)))

(defn init!
  "Initializes the session table and returns `db` with its expiry configuration.

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
  (assoc db :expire-secs expire-secs))
