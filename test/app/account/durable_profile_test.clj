(ns app.account.durable-profile-test
  (:require
   [app.account.effects :as effects]
   [app.account.effects-test :as profile-fixtures]
   [app.filestore.controller :as filestore]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as writer-fixtures]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]))

(use-fixtures :each tc/with-released-test-connections)

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
