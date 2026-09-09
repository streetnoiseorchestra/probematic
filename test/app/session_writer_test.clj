(ns app.session-writer-test
  (:require
   [app.game-loop :as game]
   [app.session :as session]
   [app.sqlite :as sqlite]
   [app.system :as system]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [integrant.core :as ig])
  (:import (java.util.concurrent ConcurrentHashMap)))

(use-fixtures :each tc/with-released-test-connections)

(deftest session-storage-and-scheduled-jobs-have-shutdown-dependencies
  (let [config (:ig/system (system/config {:profile :test}))]
    (is (= (ig/ref :app.ig/auxiliary) (get-in config [:app.ig/frame-loop :auxiliary])))
    (is (= (ig/ref :app.ig/frame-loop) (get-in config [:app.ig.jobs/definitions :frame-loop])))))

(deftest session-rotation-and-deletion-use-the-owned-writer
  (fixtures/with-runtime
    (fn [runtime _ _]
      (let [db      (sqlite/start {:filename ":memory:"})
            control (:write-runner runtime)
            entered (promise)
            release (promise)
            result  (promise)]
        (try
          (let [sessions (session/init! db {:expire-secs 60 :write-runner control})]
            (session/write-session! sessions nil "old" {:value :old})
            (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                  (fn [_] (deliver entered true) @release))
            (is (= true (deref entered 5000 ::timeout)))
            (future (deliver result (try (session/write-session! sessions "old" "new" {:value :new})
                                         (catch Throwable e e))))
            (is (= ::waiting (deref result 50 ::waiting)))
            (is (= {:value :old} (session/read-session sessions "old")))
            (is (nil? (session/read-session sessions "new")))
            (deliver release true)
            (is (= "new" (deref result 5000 ::timeout)))
            (is (nil? (session/read-session sessions "old")))
            (is (= {:value :new} (session/read-session sessions "new")))
            (session/delete-session! sessions "new")
            (is (nil? (session/read-session sessions "new")))
            (session/write-session! sessions nil "retained" {:value :retained})
            (writer/close! control)
            (is (thrown-with-msg? Exception #"stopped"
                                  (session/write-session! sessions nil "rejected" {})))
            (is (thrown-with-msg? Exception #"stopped"
                                  (session/delete-session! sessions "retained")))
            (is (thrown-with-msg? Exception #"stopped"
                                  (session/init! db {:expire-secs 60 :write-runner control})))
            (is (= {:value :retained} (session/read-session sessions "retained")))
            (is (nil? (session/read-session sessions "rejected"))))
          (finally
            (deliver release true)
            (sqlite/stop db)))))))
