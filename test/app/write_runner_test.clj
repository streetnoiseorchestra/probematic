(ns app.write-runner-test
  (:require
   [app.game-loop :as game]
   [app.ig]
   [app.job-queue :as queue]
   [app.nexus :as nexus]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [integrant.core :as ig]
   [s-exp.drip :as drip])
  (:import (java.util.concurrent ConcurrentHashMap)))

(use-fixtures :each tc/with-released-test-connections)

(defn with-runtime [f]
  (let [{:keys [conn]} (tc/new-system "write-runner")
        control        (writer/create)
        job-queue      (queue/start! {:filename ":memory:" :write-runner control})]
    (try
      (let [runtime (ig/init-key :app.ig/frame-loop
                                 {:profile      :dev                    :enabled?  true
                                  :datomic      {:conn conn}            :nexus     (nexus/nexus)
                                  :write-runner control                 :job-queue job-queue
                                  :cutover-t    (d/basis-t (d/db conn))})]
        (try (f runtime (:client job-queue) conn)
             (finally (ig/halt-key! :app.ig/frame-loop runtime))))
      (finally (queue/stop! job-queue)))))

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
          (is (= :nested (writer/call! control #(writer/call! control (constantly :nested)))))
          (is (identical? failure
                          (try (writer/call! control #(throw failure))
                               (catch Exception e e))))
          (is (= :still-running (writer/call! control (constantly :still-running))))
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
           (:write-runner runtime)
           #(nexus/db-transact-fx
             {} {:system {:datomic {:conn conn}} :request {}}
             [[[{:team/team-id (random-uuid) :team/name "Committed with intent"}]
               {:jobs [["external-test" {} {:queue "external"}]]}]]))
          (let [id (deref finished 5000 ::timeout)]
            (is (not= ::timeout id))
            (is (not (identical? (::game/thread runtime) (deref handler-thread 5000 nil))))
            (is (= :completed (:state (drip/get-job client id)))))
          (finally (is (true? (drip/stop-worker! worker :drain true)))))))))
