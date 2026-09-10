(ns app.account.durable-profile-test
  (:require
   [app.account.effects :as effects]
   [app.account.effects-test :as profile-fixtures]
   [app.game-loop :as game]
   [app.filestore.controller :as filestore]
   [app.interceptors :as interceptors]
   [babashka.fs :as bfs]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as writer-fixtures]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest rejected-profile-write-cleans-the-upload-and-returns-service-unavailable
  (doseq [rejection [:stopped :full]]
    (writer-fixtures/with-runtime
      (fn [runtime _ conn]
        (profile-fixtures/with-temp-filestore
          (fn [store]
            (let [member-id (random-uuid)
                  upload    (profile-fixtures/avatar-upload "rejected-writer")
                  control   (:write-runner runtime)
                  before-t  (d/basis-t (d/db conn))
                  entered   (promise)
                  release   (promise)]
              (try
                (if (= :stopped rejection)
                  (writer/close! control)
                  (do
                    (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                          (fn [_] (deliver entered true) @release))
                    (is (= true (deref entered 5000 ::timeout)))
                    (let [accepted (loop [n 0]
                                     (if (and (< n 2048)
                                              ((::game/submit! runtime) {::writer/work (constantly nil) ::writer/result (promise)}))
                                       (recur (inc n))
                                       n))]
                      (is (< 0 accepted 2048)))))
                (let [error (try
                              (effects/save-profile! {:datomic {:conn conn} :filestore store :frame-loop runtime}
                                                     {:member-id     member-id :profile        profile-fixtures/profile
                                                      :avatar-upload upload    :sync-keycloak? false})
                              (catch Exception error error))]
                  (is (= :app.write-runner/admission-rejected (:app/error-type (ex-data error))))
                  (is (not (bfs/exists? (:tempfile upload))))
                  (is (= before-t (d/basis-t (d/db conn))))
                  (let [context ((:error (interceptors/error-interceptor)) {:request {} :error error})]
                    (is (= 503 (get-in context [:response :status])))
                    (is (nil? (:error context)))))
                (finally
                  (deliver release true)
                  (bfs/delete-if-exists (:tempfile upload)))))))))))

(deftest profile-save-reads-current-identity-after-upload-preparation
  (writer-fixtures/with-runtime
    (fn [runtime _ conn]
      (profile-fixtures/with-temp-filestore
        (fn [store]
          (let [member-id     (random-uuid)
                control       (:write-runner runtime)
                prepared      (promise)
                release       (promise)
                result        (promise)
                store-avatar! filestore/store-avatar!]
            (writer/call! control
                          #(deref (d/transact conn [{:member/member-id   member-id
                                                     :member/name        "Ada"
                                                     :member/email       "ada@example.test"
                                                     :member/keycloak-id "original-link"}])))
            (try
              (with-redefs [filestore/store-avatar!
                            (fn [& args]
                              (let [avatar (apply store-avatar! args)]
                                (deliver prepared true)
                                @release
                                avatar))]
                (future
                  (deliver result
                           (try
                             (effects/save-profile!
                              {:datomic {:conn conn} :filestore store :frame-loop runtime}
                              {:member-id      member-id                                           :profile profile-fixtures/profile
                               :avatar-upload  (profile-fixtures/avatar-upload "current-identity")
                               :sync-keycloak? true})
                             (catch Throwable e e))))
                (is (= true (deref prepared 5000 ::timeout)))
                (writer/call! control
                              #(deref (d/transact conn [[:db/add [:member/member-id member-id]
                                                         :member/keycloak-id "replacement-link"]])))
                (deliver release true)
                (let [saved (deref result 5000 ::timeout)]
                  (is (= :saved (:status saved)))
                  (when-let [db (get-in saved [:tx-result :db-after])]
                    (let [intent (edn/read-string (:audit/jobs (d/entity db (d/t->tx (d/basis-t db)))))]
                      (is (= "replacement-link" (get-in intent [:jobs 0 1 :keycloak-id])))))))
              (finally (deliver release true)))))))))
