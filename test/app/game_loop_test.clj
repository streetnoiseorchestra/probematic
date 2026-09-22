(ns app.game-loop-test
  (:require
   [app.game-loop :as game]
   [app.game-loop.storage :as storage]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]
   [sqlite4clj.core :as sqlite])
  (:import
   [java.util.concurrent ConcurrentHashMap CountDownLatch ExecutorService TimeUnit]))

(use-fixtures :each tc/with-released-test-connections)

(deftest guardrails-rejects-missing-hooks-and-invalid-limits
  (let [pool (game/start-render-pool {:pool-size 1})
        ctx  {::game/conns (ConcurrentHashMap.) ::game/render-pool pool}
        opts {:process-batch!      (fn [_ _])
              :capture-frame       identity
              :with-render-context (fn [frame render!] (render! frame))}]
    (try
      (doseq [invalid (concat
                       (map #(dissoc opts %) [:process-batch! :capture-frame :with-render-context])
                       (map #(assoc opts % 0) [:batch-tick-ms :queue-capacity :batch-size]))]
        (let [runtime (atom nil)]
          (try
            (is (thrown? clojure.lang.ExceptionInfo
                         (reset! runtime (game/start-batch-loop! ctx invalid))))
            (finally
              (when @runtime ((::game/stop! @runtime)))))))
      (finally (.close ^ExecutorService pool)))))

(deftest waits-for-worker-cleanup-before-processing-and-drains-on-stop
  (let [pool          (game/start-render-pool {:pool-size 2})
        conns         (ConcurrentHashMap.)
        rendered      (CountDownLatch. 2)
        release       (CountDownLatch. 1)
        state         (atom [])
        batches       (atom [])
        snapshots     (atom [])
        cleaned       (atom 0)
        write-threads (atom [])
        first-write   (promise)
        stopped       (promise)
        runtime       (atom nil)]
    (try
      (doseq [id [:a :b]]
        (.put conns id
              (fn [frame]
                (swap! snapshots conj (:snapshot frame))
                (.countDown rendered))))
      (reset! runtime
              (game/start-batch-loop!
               {::game/conns conns ::game/render-pool pool}
               {:queue-capacity      3
                :batch-size          2
                :batch-tick-ms       1
                :process-batch!      (fn [_ batch]
                                       (when (seq batch)
                                         (swap! write-threads conj (Thread/currentThread))
                                         (swap! batches conj (vec batch))
                                         (swap! state into batch)
                                         (deliver first-write true)))
                :capture-frame       (fn [_] {:snapshot @state})
                :with-render-context (fn [frame render!]
                                       (try
                                         (render! frame)
                                         (finally
                                           (.await release)
                                           (swap! cleaned inc))))}))
      (is (.await rendered 5 TimeUnit/SECONDS))
      (is (= [true true true] (mapv (::game/submit! @runtime) [1 2 3])))
      (is (false? ((::game/submit! @runtime) :overflow)))
      (is (= :blocked (deref first-write 100 :blocked)))
      (is (= [[] []] @snapshots))
      (.start (Thread/ofPlatform)
              (fn []
                ((::game/stop! @runtime))
                (deliver stopped true)))
      (.countDown release)
      (is (= true (deref stopped 5000 :timeout)))
      (is (= [1 2 3] @state))
      (is (= [[1 2] [3]] @batches))
      (is (= [1 2 3] (last @snapshots)))
      (is (>= @cleaned 6))
      (is (= 1 (count (set @write-threads))))
      (is (every? #(not (.isVirtual ^Thread %)) @write-threads))
      (is (false? ((::game/submit! @runtime) :late)))
      (finally
        (.countDown release)
        (when @runtime ((::game/stop! @runtime)))
        (.close ^ExecutorService pool)))))

(deftest captures-once-and-scopes-workers-not-callbacks
  (let [pool     (game/start-render-pool {:pool-size 3})
        conns    (ConcurrentHashMap.)
        captures (atom 0)
        opened   (atom 0)
        closed   (atom 0)
        seen     (atom [])
        snapshot (Object.)]
    (try
      (dotimes [id 17]
        (.put conns id
              (fn [frame]
                (swap! seen conj [id (:snapshot frame) (.isVirtual (Thread/currentThread))])
                (when (= id 4)
                  (throw (ex-info "Expected render failure" {}))))))
      (game/render-frame!
       {::game/conns conns ::game/render-pool pool}
       (fn [_] (swap! captures inc) {:snapshot snapshot})
       (fn [frame render!]
         (swap! opened inc)
         (try
           (render! frame)
           (finally (swap! closed inc)))))
      (is (= 1 @captures))
      (is (= 3 @opened @closed))
      (is (= (zipmap (range 17) (repeat 1)) (frequencies (map first @seen))))
      (is (every? #(identical? snapshot (second %)) @seen))
      (is (every? #(false? (nth % 2)) @seen))
      (finally (.close ^ExecutorService pool)))))

(deftest renders-idle-ticks-but-sends-only-changed-output
  (let [pool    (game/start-render-pool {:pool-size 1})
        conns   (ConcurrentHashMap.)
        renders (atom 0)
        sent    (atom [])
        idle    (promise)
        runtime (atom nil)]
    (try
      (.put conns :page
            (game/render-callback
             (fn [_]
               (when (>= (swap! renders inc) 3) (deliver idle true))
               "unchanged")
             (fn [html] (swap! sent conj html) true)))
      (reset! runtime
              (game/start-batch-loop!
               {::game/conns conns ::game/render-pool pool}
               {:process-batch!      (fn [_ _])
                :capture-frame       identity
                :with-render-context (fn [ctx render!] (render! ctx))
                :batch-tick-ms       1}))
      (is (= true (deref idle 5000 :timeout)))
      ((::game/stop! @runtime))
      (is (>= @renders 3))
      (is (= ["unchanged"] @sent))
      (finally
        (when @runtime ((::game/stop! @runtime)))
        (.close ^ExecutorService pool)))))

(deftest storage-hooks-require-datomic-and-release-sqlite-after-failure
  (is (thrown? clojure.lang.ExceptionInfo (storage/render-hooks nil {})))
  (tc/with-sqlite-db
    (fn []
      (let [{:keys [conn member-id]} (tc/new-system "game-loop-storage")
            {:keys [capture-frame with-render-context]}
            (storage/render-hooks conn {:main tc/*sqlite-db*})
            frame                    (capture-frame {:context :preserved})
            before-name              (:member/name (d/pull (:db frame) [:member/name]
                                                           [:member/member-id member-id]))]
        @(d/transact conn [[:db/add [:member/member-id member-id] :member/name "After capture"]])
        (is (= before-name (:member/name (d/pull (:db frame) [:member/name]
                                                 [:member/member-id member-id]))))
        (is (= "After capture" (:member/name (d/pull (:db (capture-frame {})) [:member/name]
                                                     [:member/member-id member-id]))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Scoped failure"
             (with-render-context frame
               (fn [ctx]
                 (sqlite/q (get-in ctx [::storage/read-dbs :main]) ["SELECT 7"])
                 (throw (ex-info "Scoped failure" {}))))))
        (is (= [:preserved [7]]
               (with-render-context frame
                 (fn [ctx]
                   [(:context ctx)
                    (sqlite/q (get-in ctx [::storage/read-dbs :main]) ["SELECT 7"])]))))))))

(deftest injected-processor-selects-per-action-or-batch-transactions
  (doseq [[policy expected] [[:per-action [1 3]] [:whole-batch nil]]]
    (testing (name policy)
      (tc/with-sqlite-db
        (fn []
          (let [{:keys [conn]} (tc/new-system "game-loop-transactions")
                db             tc/*sqlite-db*
                pool           (game/start-render-pool {:pool-size 1})
                conns          (ConcurrentHashMap.)
                entered        (promise)
                release        (promise)
                failures       (atom [])
                batches        (atom [])
                runtime        (atom nil)
                apply-action!  (fn [tx action]
                                 (if (= action :fail)
                                   (throw (ex-info "Rejected action" {:action action}))
                                   (sqlite/q tx ["INSERT INTO changes VALUES (?)" action])))
                process!       (case policy
                                 :per-action
                                 (fn [_ batch]
                                   (doseq [action batch]
                                     (try
                                       (sqlite/with-write-tx [tx (:writer db)]
                                         (apply-action! tx action))
                                       (catch Exception e
                                         (swap! failures conj (:action (ex-data e)))))))
                                 :whole-batch
                                 (fn [_ batch]
                                   (when (seq batch)
                                     (try
                                       (sqlite/with-write-tx [tx (:writer db)]
                                         (doseq [action batch] (apply-action! tx action)))
                                       (catch Exception e
                                         (swap! failures conj (:action (ex-data e))))))))]
            (try
              (sqlite/with-write-tx [tx (:writer db)]
                (sqlite/q tx ["CREATE TABLE changes (value INTEGER)"]))
              (.put conns :page (fn [_] (deliver entered true) @release))
              (reset! runtime
                      (game/start-batch-loop!
                       {::game/conns conns ::game/render-pool pool}
                       (assoc (storage/render-hooks conn {:main db})
                              :batch-tick-ms 1
                              :process-batch! (fn [ctx batch]
                                                (when (seq batch) (swap! batches conj (vec batch)))
                                                (process! ctx batch)))))
              (is (= true (deref entered 5000 :timeout)))
              (is (= [true true true] (mapv (::game/submit! @runtime) [1 :fail 3])))
              (deliver release true)
              ((::game/stop! @runtime))
              (is (= [[1 :fail 3]] @batches))
              (is (= [:fail] @failures))
              (is (= expected (sqlite/q (:reader db) ["SELECT value FROM changes ORDER BY value"])))
              (finally
                (deliver release true)
                (when @runtime ((::game/stop! @runtime)))
                (.close ^ExecutorService pool)))))))))
