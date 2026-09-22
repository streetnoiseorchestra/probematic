(ns app.jobs.sync-songs-test
  (:require
   [app.game-loop :as game]
   [app.jobs.sync-songs :as sync-songs]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest scheduled-song-sync-records-an-audited-intent-after-the-render-barrier
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [entered  (promise)
            release  (promise)
            before-t (d/basis-t (d/db conn))]
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (let [result (future (sync-songs/song-sync-job {:write-runner (:write-runner runtime) :datomic {:conn conn}} nil))]
            (is (= ::waiting (deref result 250 ::waiting)))
            (is (= before-t (d/basis-t (d/db conn))))
            (deliver release true)
            (is (map? (deref result 5000 ::timeout)))
            (writer/call! runtime (constantly nil))
            (let [jobs     (drip/list-jobs client {})
                  source-t (get-in jobs [0 :args :source-t])
                  tx       (when source-t (d/entity (d/db conn) (d/t->tx source-t)))]
              (is (= ["sync-all-songs"] (mapv :kind jobs)))
              (is (= (d/basis-t (d/db conn)) source-t))
              (is (= ::sync-songs/song-sync (:audit/action tx)))
              (is (= :app.origin/job (:audit/origin tx)))))
          (finally (deliver release true)))))))
