(ns app.write-runner-test
  (:require
   [app.game-loop :as game]
   [app.game-loop.storage :as storage]
   [app.ig]
   [app.job-queue :as queue]
   [app.nexus :as nexus]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [integrant.core :as ig]
   [s-exp.drip :as drip])
  (:import (java.util.concurrent ConcurrentHashMap)))

(use-fixtures :each tc/with-released-test-connections)

(defn with-runtime [f]
  (let [{:keys [conn]} (tc/new-system "write-runner")
        control        (writer/create)
        dir            (fs/create-temp-dir {:prefix "write-runner-"})]
    (try
      (let [job-queue (queue/start! {:filename (str (fs/path dir "jobs.sqlite"))
                                     :config   {:pool-size 4}                    :write-runner control})]
        (try
          (let [runtime (ig/init-key :app.ig/frame-loop
                                     {:profile      :dev                    :enabled?  true
                                      :datomic      {:conn conn}            :nexus     (nexus/nexus)
                                      :write-runner control                 :job-queue job-queue
                                      :cutover-t    (d/basis-t (d/db conn))})]
            (try (f runtime (:client job-queue) conn)
                 (finally (ig/halt-key! :app.ig/frame-loop runtime))))
          (finally (queue/stop! job-queue))))
      (finally (fs/delete-tree dir)))))

(deftest contexts-require-a-direct-writer-before-running-work
  (let [control (writer/create)
        ran?    (atom false)]
    (try
      (doseq [ctx [{}
                   {:write-runner nil}
                   {:write-runner :invalid}
                   {:frame-loop {:write-runner control}}
                   {:system {:write-runner control}}]]
        (let [error (try
                      (writer/call! ctx #(reset! ran? true))
                      (catch Exception error error))]
          (is (= {:app/error-type ::writer/invalid-context} (ex-data error)))
          (is (false? @ran?))))
      (is (thrown? Exception (writer/call! control #(reset! ran? true))))
      (is (false? @ran?))
      (finally (writer/close! control)))))

(deftest startup-context-runs-work-and-closed-context-rejects-it
  (let [control (writer/create)
        ctx     {:write-runner control :request-id "startup"}
        ran?    (atom false)]
    (try
      (is (identical? (Thread/currentThread) (writer/call! ctx #(Thread/currentThread))))
      (writer/close! control)
      (let [error (try
                    (writer/call! ctx #(reset! ran? true))
                    (catch Exception error error))]
        (is (= {:app/error-type ::writer/admission-rejected} (ex-data error)))
        (is (false? @ran?)))
      (finally (writer/close! control)))))

(deftest frame-job-readers-return-after-render-error-before-next-write-and-close
  (let [pool
        (with-runtime
          (fn [runtime client _]
            (let [read-pool (get-in client [:pool :reader :conn-pool])
                  observed  (atom {})
                  entered   (promise)
                  release   (promise)]
              (try
                (writer/call!
                 runtime
                 #(doseq [id [:reader-a :reader-b]]
                    (.put ^ConcurrentHashMap (::game/conns runtime) id
                          (fn [frame]
                            (let [tx (get-in frame [::storage/read-dbs :jobs])]
                              (swap! observed assoc id {:identity (System/identityHashCode tx)
                                                        :jobs     (vec (drip/list-jobs! client tx {}))})
                              (when (= 2 (count @observed)) (deliver entered true)))
                            @release
                            (when (= :reader-a id) (throw (ex-info "test-render-failure" {})))))))
                (is (= true (deref entered 5000 ::timeout)))
                (is (= 2 (count (set (map :identity (vals @observed))))))
                (is (every? empty? (map :jobs (vals @observed))))
                (is (= 2 (.size ^java.util.concurrent.BlockingQueue read-pool)))
                (let [queued (future (drip/insert-job client "after-readers" {}))]
                  (is (= ::waiting (deref queued 100 ::waiting)))
                  (is (empty? (drip/list-jobs client {})))
                  (doseq [id [:reader-a :reader-b]] (.remove ^ConcurrentHashMap (::game/conns runtime) id))
                  (deliver release true)
                  (is (= "after-readers" (:kind (deref queued 5000 {}))))
                  (writer/call! runtime (constantly nil))
                  (is (= 4 (.size ^java.util.concurrent.BlockingQueue read-pool))))
                read-pool
                (finally
                  (doseq [id [:reader-a :reader-b]] (.remove ^ConcurrentHashMap (::game/conns runtime) id))
                  (deliver release true))))))]
    (is (zero? (.size ^java.util.concurrent.BlockingQueue pool)))))

(deftest background-transactions-wait-for-the-render-barrier
  (with-runtime
    (fn [runtime client _]
      (let [entered (promise)
            release (promise)
            result  (promise)
            control (:write-runner runtime)
            failure (ex-info "transaction failed" {})]
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (future
            (deliver result
                     (drip/with-tx [tx client]
                       (drip/insert-job! client tx "after-render" {})
                       (Thread/currentThread))))
          (is (= ::waiting (deref result 50 ::waiting)))
          (is (empty? (drip/list-jobs client {})))
          (deliver release true)
          (is (identical? (::game/thread runtime) (deref result 5000 ::timeout)))
          (is (= ["after-render"] (mapv :kind (drip/list-jobs client {}))))
          (is (= :nested (writer/call! runtime #(writer/call! runtime (constantly :nested)))))
          (is (identical? failure
                          (try (writer/call! runtime #(throw failure))
                               (catch Exception e e))))
          (is (= :still-running (writer/call! runtime (constantly :still-running))))
          (writer/close! control)
          (is (thrown-with-msg? Exception #"stopped"
                                (drip/insert-job client "not-accepted" {})))
          (finally (deliver release true)))))))

(deftest log-dispatch-and-worker-completion-share-the-writer
  (with-runtime
    (fn [runtime client conn]
      (let [finished       (promise)
            handler-thread (promise)
            worker         (drip/start-worker!
                            {:client   client                                             :queues ["external"] :poll-interval 10
                             :registry {"external-test"
                                        (fn [client job]
                                          (deliver handler-thread (Thread/currentThread))
                                          (drip/complete-job client (:id job))
                                          (deliver finished (:id job)))}})]
        (try
          (writer/call!
           runtime
           #(nexus/db-transact-fx
             {} {:system {:datomic {:conn conn}} :request {}}
             [[[{:team/team-id (random-uuid) :team/name "Committed with intent"}]
               {:jobs [["external-test" {} {:queue "external"}]]}]]))
          (let [id (deref finished 5000 ::timeout)]
            (is (not= ::timeout id))
            (is (not (identical? (::game/thread runtime) (deref handler-thread 5000 nil))))
            (is (= :completed (:state (drip/get-job client id)))))
          (finally (is (true? (drip/stop-worker! worker :drain true)))))))))
