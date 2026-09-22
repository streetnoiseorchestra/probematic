(ns app.datomic-test
  (:require
   [app.datomic :as datomic]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]))

(use-fixtures :each tc/with-released-test-connections)

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

(defn- authenticated-request [conn member-id]
  {:datomic-conn conn
   :system       {:frame-loop {:write-runner (writer/create)}}
   :app/session  {:session/member {:member/member-id member-id}}})

(deftest transact-test
  (let [{:keys [conn]} (tc/new-system "datomic-transact")
        success        (datomic/transact
                        conn
                        {:tx-data [{:member/nick "unique-nick"}]})
        audited        (datomic/transact
                        conn
                        {:tx-data [{:member/nick "audited-nick"}
                                   {:db/id "datomic.tx" :audit/jobs "intent"}]
                         :audit   {:audit/action ::background-write
                                   :audit/origin :app.origin/job}})
        audit          (d/entity (:db-after audited)
                                 (d/t->tx (d/basis-t (:db-after audited))))]
    (testing "returns the transaction report on success"
      (is (contains? success :db-after))
      (is (datomic/db-ok? success)))

    (testing "adds trusted background metadata and preserves job intents"
      (is (= ::background-write (:audit/action audit)))
      (is (= :app.origin/job (:audit/origin audit)))
      (is (= "intent" (:audit/jobs audit)))
      (is (nil? (:audit/user audit))))

    (testing "throws Datomic uniqueness failures"
      (is (thrown? Exception
                   (datomic/transact conn
                                     {:tx-data [{:member/nick "unique-nick"}]}))))))

(deftest source-audit-user-test
  (let [{:keys [conn member-id]} (tc/new-system "datomic-source-audit-user")
        report                   (datomic/transact
                                  conn
                                  {:tx-data [{:team/team-id (random-uuid)
                                              :team/name    "Audited team"}]
                                   :audit   {:audit/user [:member/member-id member-id]}})
        source-t                 (d/basis-t (:db-after report))]
    (is (= [:member/member-id member-id]
           (datomic/source-audit-user conn source-t)))
    @(d/transact conn [[:db/retractEntity [:member/member-id member-id]]])
    (is (nil? (datomic/source-audit-user conn source-t)))))

(deftest transact-wrapper!-test
  (let [{:keys [conn member-id]} (tc/new-system "datomic-transact-wrapper")
        req                      (authenticated-request conn member-id)
        _                        (datomic/transact-wrapper!
                                  req
                                  {:tx-data [[:db/add
                                              [:member/member-id member-id]
                                              :member/name
                                              "Ada"]]
                                   :audit   {:audit/action ::wrapper-write}}
                                  "Updated profile")
        db                       (d/db conn)
        audit                    (d/q '[:find (pull ?tx [:audit/action
                                                         :audit/comment
                                                         :audit/origin
                                                         {:audit/user [:member/member-id]}]) .
                                        :in $ ?comment
                                        :where [?tx :audit/comment ?comment]]
                                      db
                                      "Updated profile")]
    (testing "applies the requested transaction"
      (is (= {:member/name "Ada"}
             (d/pull db [:member/name] [:member/member-id member-id]))))

    (testing "records the authenticated member and optional comment on the transaction"
      (is (= {:audit/action  ::wrapper-write
              :audit/comment "Updated profile"
              :audit/origin  :app.origin/browser
              :audit/user    {:member/member-id member-id}}
             audit)))))

(deftest entity-history-test
  (let [{:keys [conn member-id]} (tc/new-system "datomic-entity-history")
        req                      (authenticated-request conn member-id)
        instrument-id            (random-uuid)
        _                        @(d/transact
                                   conn
                                   [{:instrument/instrument-id instrument-id
                                     :instrument/name          "Clarinet I"}])
        _                        (datomic/transact-wrapper!
                                  req
                                  {:tx-data [[:db/add
                                              [:instrument/instrument-id instrument-id]
                                              :instrument/name
                                              "Clarinet II"]
                                             [:db/add
                                              [:instrument/instrument-id instrument-id]
                                              :instrument/owner
                                              [:member/member-id member-id]]]}
                                  "Corrected instrument")
        event                    (some #(when (= "Corrected instrument"
                                                 (get-in % [:audit :audit/comment]))
                                          %)
                                       (datomic/entity-history
                                        (d/db conn)
                                        :instrument/instrument-id
                                        instrument-id))
        changes                  (:changes event)
        owner-change             (some #(when (= :instrument/owner (first %)) %) changes)]
    (testing "identifies the entity and audit member"
      (is (= :instrument/instrument-id (:ent-id-key event)))
      (is (= instrument-id (:ent-id-value event)))
      (is (= member-id (get-in event [:audit :audit/member :member/member-id]))))

    (testing "reports additions and retractions using schema idents"
      (is (some #{[:instrument/name "Clarinet I" :retracted]} changes))
      (is (some #{[:instrument/name "Clarinet II" :added]} changes))
      (is (= member-id (get-in owner-change [1 :member/member-id])))
      (is (= :added (last owner-change))))))

(deftest find-by-test
  (let [{:keys [conn]} (tc/new-system "datomic-find-by")
        member-id      (random-uuid)
        _              @(d/transact
                         conn
                         [{:member/member-id member-id
                           :member/name      "Ada"}])
        db             (d/db conn)]
    (testing "returns the requested pull result for a matching attribute value"
      (is (= {:member/member-id member-id
              :member/name      "Ada"}
             (datomic/find-by db
                              :member/member-id
                              member-id
                              [:member/member-id :member/name]))))

    (testing "returns nil when no entity matches"
      (is (nil? (datomic/find-by db
                                 :member/member-id
                                 (random-uuid)
                                 [:member/member-id :member/name]))))))

(deftest ref-test
  (let [member-id (random-uuid)
        gig-id    (random-uuid)
        entity    {:member/member-id member-id
                   :gig/gig-id       gig-id}]
    (testing "uses the first known entity id when no key is supplied"
      (is (= [:gig/gig-id gig-id]
             (datomic/ref entity))))

    (testing "uses an explicitly selected entity id"
      (is (= [:member/member-id member-id]
             (datomic/ref entity :member/member-id))))

    (testing "explains which keys are accepted when an entity has no known id"
      (let [entity {:member/name "Ada"}
            error  (try
                     (datomic/ref entity)
                     (catch clojure.lang.ExceptionInfo exception
                       exception))]
        (is (= {:entity-map       entity
                :possible-id-keys datomic/entity-ids}
               (ex-data error)))))))
