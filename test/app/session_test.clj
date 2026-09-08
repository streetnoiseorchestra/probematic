(ns app.session-test
  (:require
   [app.session :as session]
   [app.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [sqlite4clj.core :as sql]
   [tick.core :as t])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-filename [f]
  (let [directory (.toFile (Files/createTempDirectory "probematic-sessions-" (make-array FileAttribute 0)))
        filename  (str (io/file directory "nested" "auxiliary.sqlite"))]
    (try
      (f filename)
      (finally
        (doseq [file (reverse (file-seq directory))]
          (io/delete-file file true))))))

(defn- with-db [f]
  (with-filename
    (fn [filename]
      (let [db (sqlite/start {:filename filename :config {:pool-size 2}})]
        (try
          (f db)
          (finally (sqlite/stop db)))))))

(deftest session-lifecycle
  (with-db
    (fn [db]
      (let [s    (session/init! db {:expire-secs 60})
            data {:session/email "member@example.com"
                  :session/roles #{:admin :Mitglieder}
                  :session/member-id (random-uuid)
                  :session/access-token "access-token"
                  :session/refresh-token "refresh-token"
                  :nested {:items [nil false 42]}}
            key  (session/write-session! s nil data)]
        (is (re-matches #"[A-Za-z0-9_-]{27}" key))
        (is (= 20 (alength (.decode (java.util.Base64/getUrlDecoder) ^String key))))
        (is (not= key (session/write-session! s nil {})))
        (is (= data (session/read-session s key)))
        (is (= [[data "blob"]]
               (sql/q (:reader db) ["SELECT data, typeof(data) FROM http_sessions WHERE session_key = ?" key])))
        (is (= key (session/write-session! s key {:session/roles #{:Mitglieder}})))
        (is (= {:session/roles #{:Mitglieder}} (session/read-session s key)))
        (is (nil? (session/delete-session! s key)))
        (is (nil? (session/delete-session! s key)))
        (is (nil? (session/delete-session! s nil)))
        (is (= [nil nil nil]
               (mapv #(session/read-session s %) [nil key "' OR 1=1 --"])))))))

(deftest expiry-is-refreshed-only-by-writes
  (with-db
    (fn [db]
      (let [s (session/init! db {:expire-secs 60})
            key (t/with-clock (t/instant "2026-08-01T00:00:00Z")
                  (session/write-session! s nil {:value :original}))]
        (t/with-clock (t/instant "2026-08-01T00:00:59Z")
          (is (= {:value :original} (session/read-session s key))))
        (t/with-clock (t/instant "2026-08-01T00:01:00Z")
          (is (nil? (session/read-session s key))))
        (t/with-clock (t/instant "2026-08-01T00:00:30Z")
          (is (= key (session/write-session! s key {:value :updated}))))
        (t/with-clock (t/instant "2026-08-01T00:01:00Z")
          (is (= {:value :updated} (session/read-session s key))))
        (t/with-clock (t/instant "2026-08-01T00:01:30Z")
          (is (nil? (session/read-session s key)))
          (session/write-session! s nil {:value :new})
          (is (= [1] (sql/q (:reader db) ["SELECT count(*) FROM http_sessions"]))))))))

(deftest startup-removes-expired-sessions
  (with-db
    (fn [db]
      (let [s (session/init! db {:expire-secs 60})
            expired (t/with-clock (t/instant "2026-08-01T00:00:00Z")
                      (session/write-session! s nil {:expired true}))
            live (t/with-clock (t/instant "2026-08-01T00:00:30Z")
                   (session/write-session! s nil {:live true}))]
        (t/with-clock (t/instant "2026-08-01T00:01:00Z")
          (let [recreated (session/init! db {:expire-secs 60})]
            (is (= [nil {:live true}]
                   (mapv #(session/read-session recreated %) [expired live])))
            (is (= [live] (sql/q (:reader db) ["SELECT session_key FROM http_sessions"])))))))))

(deftest sessions-survive-database-reopen
  (with-filename
    (fn [filename]
      (let [config {:filename filename :config {:pool-size 2}}
            data {:session/roles #{:admin}}
            key (let [db (sqlite/start config)]
                  (try
                    (session/write-session! (session/init! db {:expire-secs 60}) nil data)
                    (finally (sqlite/stop db))))]
        (testing "all connections close and SQLite removes the WAL"
          (is (not (.exists (io/file (str filename "-wal"))))))
        (let [db (sqlite/start config)]
          (try
            (is (= data (session/read-session (session/init! db {:expire-secs 60}) key)))
            (finally (sqlite/stop db))))))))

(deftest separate-databases-share-session-updates
  (with-filename
    (fn [filename]
      (let [config {:filename filename :config {:pool-size 1}}
            a (sqlite/start config)]
        (try
          (let [sa (session/init! a {:expire-secs 60})
                key (session/write-session! sa nil {:value 1})
                b (sqlite/start config)]
            (try
              (let [sb (session/init! b {:expire-secs 60})]
                (is (= {:value 1} (session/read-session sb key)))
                (session/write-session! sb key {:value 2})
                (is (= {:value 2} (session/read-session sa key)))
                (session/delete-session! sb key)
                (is (nil? (session/read-session sa key))))
              (finally (sqlite/stop b))))
          (finally (sqlite/stop a)))))))

(deftest in-memory-database-shares-one-pool
  (let [db (sqlite/start {:filename ":memory:"})]
    (try
      (let [s (session/init! db {:expire-secs 60})
            key (session/write-session! s nil {:value 1})]
        (is (identical? (:writer db) (:reader db)))
        (is (= {:value 1} (session/read-session s key))))
      (finally (sqlite/stop db)))))

(deftest shutdown-waits-for-borrowed-connections
  (let [db (sqlite/start {:filename ":memory:"})
        borrowed (promise)
        release (promise)
        reader (future
                 (sql/with-conn [conn (:reader db)]
                   (deliver borrowed true)
                   @release
                   (sql/q conn ["SELECT 1"])))]
    (is (= true (deref borrowed 5000 ::timeout)))
    (let [closing (future (sqlite/stop db))]
      (try
        (is (= ::waiting (deref closing 100 ::waiting)))
        (finally (deliver release true)))
      (is (= [1] (deref reader 5000 ::timeout)))
      (is (nil? (deref closing 5000 ::timeout))))))
