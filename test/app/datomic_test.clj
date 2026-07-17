(ns app.datomic-test
  (:require
   [app.datomic :as datomic]
   [clojure.test :refer [deftest is testing]]))

(deftest inflate-schema-test
  (testing "inflates compact entries alongside explicit schema maps"
    (let [explicit-schema {:db/ident       :user/roles+name
                           :db/valueType   :db.type/tuple
                           :db/cardinality :db.cardinality/one
                           :db/tupleAttrs  [:user/roles :user/name]}
          schema          [[:user/id :uuid "Unique user ID" :identity]
                           explicit-schema]
          expected        [{:db/ident       :user/id
                            :db/valueType   :db.type/uuid
                            :db/doc         "Unique user ID"
                            :db/cardinality :db.cardinality/one
                            :db/unique      :db.unique/identity}
                           explicit-schema]]
      (is (= expected (datomic/inflate-schema schema)))))

  (testing "preserves an empty explicit map"
    (is (= [{}] (datomic/inflate-schema [{}])))))
