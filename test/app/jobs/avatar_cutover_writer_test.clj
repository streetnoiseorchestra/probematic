(ns app.jobs.avatar-cutover-writer-test
  (:require
   [app.game-loop :as game]
   [app.jobs.avatar-cutover :as cutover]
   [app.jobs.avatar-cutover-test :as avatars]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest avatar-download-runs-outside-the-barrier-but-persistence-waits
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (avatars/with-temp-filestore
        (fn [store]
          (let [entered    (promise)
                release    (promise)
                downloaded (promise)
                member-id  (random-uuid)]
            (avatars/with-avatar-server
              (fn [_]
                (deliver downloaded true)
                {:status 200 :headers {"content-type" "image/jpeg"} :body (avatars/jpeg-bytes)})
              (fn [port]
                (writer/call! (:write-runner runtime)
                              #(avatars/seed-cutover-member! conn member-id (str "http://127.0.0.1:" port "/avatar/{size}.jpg")))
                (try
                  (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                        (fn [_] (deliver entered true) @release))
                  (is (= true (deref entered 5000 ::timeout)))
                  (let [result (future (cutover/cutover-avatars! {:frame-loop runtime :datomic {:conn conn} :filestore store}))]
                    (is (= true (deref downloaded 5000 ::timeout)))
                    (is (= ::waiting (deref result 2000 ::waiting)))
                    (is (nil? (avatars/avatar-id (d/db conn) member-id)))
                    (deliver release true)
                    (is (= {:processed 1 :migrated 1 :failed 0 :conflicted 0} (deref result 5000 ::timeout)))
                    (is (uuid? (avatars/avatar-id (d/db conn) member-id))))
                  (finally (deliver release true)))))))))))
