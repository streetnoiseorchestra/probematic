(ns app.jobs.log-dispatch-test
  (:require
   [app.jobs.log-dispatch :as log]
   [app.nexus :as nexus]
   [app.sqlite :as app-sqlite]
   [app.test-common :as tc]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]
   [sqlite4clj.core :as sqlite]))

(use-fixtures :each tc/with-released-test-connections tc/with-sqlite-db)

(defn client! []
  (let [client (drip/make-client tc/*sqlite-db*)]
    (drip/migrate! client)
    client))

(defn transact-jobs! [conn jobs]
  (d/basis-t (:db-after @(d/transact conn (log/intent-tx jobs)))))

(deftest checked-cursor-distinguishes-pending-work-from-consumed-or-pruned-work
  (let [{:keys [conn]} (tc/new-system "log-read-cursor")
        client         (client!)
        historical-t   (transact-jobs! conn [["historical" {} {}]])]
    (log/initialize! conn client historical-t)
    (let [source-t (transact-jobs! conn [["current" {} {}]])
          snapshot (d/db conn)]
      (sqlite/with-read-tx [tx (:reader tc/*sqlite-db*)]
        (is (true? (log/processed-through? snapshot tx historical-t)))
        (is (false? (log/processed-through? snapshot tx source-t))))
      (log/dispatch-pending! conn client 100)
      (doseq [job (drip/list-jobs client {})]
        (drip/delete-job client (:id job)))
      (sqlite/with-read-tx [tx (:reader tc/*sqlite-db*)]
        (is (true? (log/processed-through? snapshot tx source-t)))
        (is (empty? (drip/list-jobs! client tx {}))))
      (transact-jobs! conn [["later" {} {}]])
      (log/dispatch-pending! conn client 100)
      (sqlite/with-read-tx [tx (:reader tc/*sqlite-db*)]
        (is (thrown-with-msg? Exception #"Log cursor does not match the source database"
                              (log/processed-through? snapshot tx source-t))))
      (let [{other :conn} (tc/new-system "log-read-other-source")]
        (sqlite/with-read-tx [tx (:reader tc/*sqlite-db*)]
          (is (thrown-with-msg? Exception #"Log cursor does not match the source database"
                                (log/processed-through? (d/db other) tx source-t))))))))

(deftest nexus-commits-job-intent-with-the-business-change
  (let [{:keys [conn]} (tc/new-system "log-nexus")
        client         (client!)
        context        {:system  {:datomic {:conn conn}}
                        :request {::nexus/audit-action ::save-team}}]
    (log/initialize! conn client (d/basis-t (d/db conn)))
    (let [report  (nexus/db-transact-fx
                   {} context
                   [[[{:team/team-id [:db/gen-uuid :team] :team/name "Queued"}]
                     {:jobs [["notify" {:team-id [:db/gen-uuid :team]} {}]]}]])
          t       (d/basis-t (:db-after report))
          team-id (:team/team-id (d/entity (:db-after report) [:team/name "Queued"]))]
      (is (uuid? team-id))
      (is (empty? (drip/list-jobs client {})))
      (is (= ::save-team (:audit/action (d/entity (:db-after report) (d/t->tx t)))))
      (is (= t (log/dispatch-pending! conn client 100)))
      (is (= [{:team-id team-id :source-t t}] (mapv :args (drip/list-jobs client {}))))
      (is (thrown? Exception
                   (nexus/db-transact-fx
                    {} context
                    [[[{:db/id [:team/name "does-not-exist"] :team/name "Invalid"}]
                      {:jobs [["must-not-exist" {} {}]]}]])))
      (is (= t (log/dispatch-pending! conn client 100)))
      (is (= ["notify"] (mapv :kind (drip/list-jobs client {})))))))

(deftest starts-after-cutover-and-resumes-without-duplicates
  (let [{:keys [conn]} (tc/new-system "log-cutover")
        client         (client!)
        historical-t   (transact-jobs! conn [["historical" {} {}]])]
    (is (= historical-t (log/initialize! conn client historical-t)))
    (let [team-id  (random-uuid)
          tx       @(d/transact conn
                                (into [{:team/team-id team-id :team/name "Committed"}]
                                      (log/intent-tx [["notify" {:team-id team-id :source-t -1} {}]])))
          source-t (d/basis-t (:db-after tx))]
      ;; The database identity transaction has no jobs, but counts toward the limit.
      (is (< (log/dispatch-pending! conn client 1) source-t))
      (is (empty? (drip/list-jobs client {})))
      (is (= source-t (log/dispatch-pending! conn client 1)))
      (is (= [{:kind "notify" :args {:team-id team-id :source-t source-t}}]
             (mapv #(select-keys % [:kind :args]) (drip/list-jobs client {}))))
      (is (= source-t (log/initialize! conn client nil)))
      (is (= source-t (log/dispatch-pending! conn client 100)))
      (is (= 1 (count (drip/list-jobs client {})))))))

(deftest failed-job-insertion-rolls-back-only-its-source-transaction
  (let [{:keys [conn]} (tc/new-system "log-rollback")
        client         (client!)]
    (log/initialize! conn client (d/basis-t (d/db conn)))
    (let [first-t  (transact-jobs! conn [["earlier" {} {}]])
          failed-t (transact-jobs! conn [["partial" {} {}] ["blocked" {} {}]])
          last-t   (transact-jobs! conn [["later" {} {}]])]
      (drip/with-tx [tx client]
        (sqlite/q tx ["CREATE TRIGGER reject_blocked_job BEFORE INSERT ON drip_job WHEN NEW.kind = 'blocked' BEGIN SELECT RAISE(ABORT, 'blocked job'); END"]))
      (is (thrown? Exception (log/dispatch-pending! conn client 100)))
      (is (= first-t (log/initialize! conn client nil)))
      (is (= ["earlier"] (mapv :kind (drip/list-jobs client {}))))
      (drip/with-tx [tx client]
        (sqlite/q tx ["DROP TRIGGER reject_blocked_job"]))
      (is (= failed-t (log/dispatch-pending! conn client 1)))
      (is (= last-t (log/dispatch-pending! conn client 1)))
      (is (= {"earlier" 1 "partial" 1 "blocked" 1 "later" 1}
             (frequencies (map :kind (drip/list-jobs client {}))))))))

(deftest rejects-unknown-intent-version-without-skipping-it
  (let [{:keys [conn]} (tc/new-system "log-version")
        client         (client!)]
    (log/initialize! conn client (d/basis-t (d/db conn)))
    (let [before-t (log/dispatch-pending! conn client 100)]
      @(d/transact conn [{:db/id "datomic.tx" :audit/jobs "{:version 2 :jobs []}"}])
      (transact-jobs! conn [["later" {} {}]])
      (is (thrown-with-msg? Exception #"Unsupported durable job intent version"
                            (log/dispatch-pending! conn client 100)))
      (is (= before-t (log/initialize! conn client nil)))
      (is (empty? (drip/list-jobs client {}))))))

(deftest requires-cutover-and-rejects-another-source
  (let [{a :conn} (tc/new-system "log-source-a")
        {b :conn} (tc/new-system "log-source-b")
        client    (client!)]
    (is (thrown-with-msg? Exception #"explicit cutover"
                          (log/initialize! a client nil)))
    (log/initialize! a client (d/basis-t (d/db a)))
    (is (thrown-with-msg? Exception #"does not match"
                          (log/initialize! b client nil)))
    (is (thrown-with-msg? Exception #"does not match"
                          (log/dispatch-pending! b client 10)))))

(deftest reopening-the-job-database-preserves-progress
  (let [{:keys [conn]} (tc/new-system "log-reopen")
        directory      (fs/create-temp-dir {:prefix "probematic-log-"})
        config         {:filename (str (fs/path directory "jobs.sqlite"))}]
    (try
      (let [db (app-sqlite/start config)]
        (try
          (let [client (drip/make-client db)]
            (drip/migrate! client)
            (log/initialize! conn client (d/basis-t (d/db conn)))
            (transact-jobs! conn [["persisted" {} {}]])
            (log/dispatch-pending! conn client 100))
          (finally (app-sqlite/stop db))))
      (let [db (app-sqlite/start config)]
        (try
          (let [client  (drip/make-client db)
                saved-t (log/initialize! conn client nil)]
            (is (= saved-t (log/dispatch-pending! conn client 100)))
            (is (= ["persisted"] (mapv :kind (drip/list-jobs client {})))))
          (finally (app-sqlite/stop db))))
      (finally (fs/delete-tree directory)))))

(deftest intent-is-not-truncated-by-repl-print-settings
  (let [jobs [["notify" {:ids (vec (range 20))} {}]]]
    (is (= (log/intent-tx jobs)
           (binding [*print-length* 1 *print-level* 1]
             (log/intent-tx jobs))))
    (is (= [] (log/intent-tx [])))
    (is (thrown? Exception (log/intent-tx [["notify" {:f identity} {}]])))))

(deftest persisted-intent-values-remain-readable
  (let [{:keys [conn]} (tc/new-system "log-edn-values")
        jobs           [["notify" {:id     #uuid "00000000-0000-0000-0000-000000000001"
                                   :at     #inst "2026-09-19"
                                   :values [nil true false :a/b "Grüße\n\"" #{:x} '(1 2) 1M 2N]} {}]]
        text           (pr-str {:version 1 :jobs jobs})
        report         @(d/transact conn [{:db/id "datomic.tx" :audit/jobs text}])]
    (is (= jobs (#'log/transaction-jobs (:db-after report) (d/basis-t (:db-after report)))))
    (is (= text (:audit/jobs (first (log/intent-tx jobs)))))))

(deftest malformed-persisted-intent-does-not-advance-cursor
  (doseq [text ["{:version 1 :jobs [" "#unknown/tag {}" "{:version 2 :jobs []}"]]
    (tc/with-sqlite-db
      (fn []
        (let [{:keys [conn]} (tc/new-system "log-bad-edn")
              client         (client!)
              _              (log/initialize! conn client (d/basis-t (d/db conn)))
              before         (log/dispatch-pending! conn client 100)
              report         @(d/transact conn [{:db/id "datomic.tx" :audit/jobs text}])]
          (is (thrown? Exception (log/dispatch-pending! conn client 100)))
          (is (empty? (drip/list-jobs client {})))
          (sqlite/with-read-tx [tx (:reader tc/*sqlite-db*)]
            (is (true? (log/processed-through? (:db-after report) tx before)))
            (is (false? (log/processed-through? (:db-after report) tx (d/basis-t (:db-after report)))))))))))
