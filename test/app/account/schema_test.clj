(ns app.account.schema-test
  (:require
   [app.datomic.system :as datomic.system]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(deftest account-schema-upgrades-an-existing-unindexed-unique-attribute
  (let [uri (str "datomic:mem://account-schema-upgrade-" (random-uuid))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn [{:db/ident :member/nick
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}])
      @(d/transact conn [{:db/id "existing-member"
                          :member/nick "existing"}])

      (is (= [{:db/id :member/nick
               :db/index true}]
             (datomic.system/schema-index-upgrades
              (d/db conn)
              [{:db/ident :member/nick
                :db/index true
                :db/unique :db.unique/value}])))

      @(datomic.system/transact-schema conn)

      (let [db (d/db conn)]
        (is (= true (:db/index (d/pull db [:db/index] :member/nick))))
        (is (= :db.unique/value
               (get-in (d/pull db [{:db/unique [:db/ident]}] :member/nick)
                       [:db/unique :db/ident])))))))
