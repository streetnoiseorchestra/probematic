(ns app.datomic.migrations-test
  (:require
   [app.datomic.migrations]
   [app.datomic.system :as datomic.system]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]
   [dev.gethop.stork :as stork]))

(def ^:dynamic *test-connections* nil)

(defn- with-released-test-connections [f]
  (binding [*test-connections* (atom [])]
    (try
      (f)
      (finally
        (run! d/release @*test-connections*)))))

(use-fixtures :each with-released-test-connections)

(defn- fresh-connection [name-prefix]
  (let [uri (str "datomic:mem://" name-prefix "-" (random-uuid))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      (swap! *test-connections* conj conn)
      conn)))

(defn- install-current-schema! [conn]
  @(d/transact conn (stork/read-resource "schema-meta.edn"))
  @(d/transact conn (stork/read-resource "schema.edn")))

(defn- ref-ident [db entity-id attribute]
  (get-in (d/pull db [{attribute [:db/ident]}] entity-id)
          [attribute :db/ident]))

(defn- migration-ids [db]
  (if (d/entid db :stork/installed-migrations)
    (set
     (d/q '[:find [?migration ...]
            :where
            [_ :stork/installed-migrations ?migration]]
          db))
    #{}))

(defn- exception-data-of-type [type f]
  (try
    (f)
    ::not-thrown
    (catch Throwable throwable
      (or
       (some (fn [cause]
               (let [data (ex-data cause)]
                 (when (= type (:type data))
                   data)))
             (take-while some? (iterate ex-cause throwable)))
       ::unexpected-exception))))

(defn- survey-closed-at [db survey-id]
  (:insurance.survey/closed-at
   (d/pull db
           [:insurance.survey/closed-at]
           [:insurance.survey/survey-id survey-id])))

(deftest peer-start-prepares-database-test
  (let [uri  (str "datomic:mem://schema-lifecycle-peer-" (random-uuid))
        peer (datomic.system/start-peer {:peer {:db-uri uri}})]
    (try
      (let [conn (:conn peer)]
        (is (= {:schema-installed? true
                :migration-ids
                #{:app.migration/pre001-prepare-member-uniqueness
                  :app.migration/post001-normalize-active-insurance-surveys}}
               {:schema-installed?
                (boolean (d/entid (d/db conn) :member/member-id))
                :migration-ids (migration-ids (d/db conn))})))
      (finally
        (datomic.system/stop-peer peer)))))

(deftest peer-start-failure-releases-connection-test
  (let [uri         (str "datomic:mem://schema-lifecycle-peer-failure-"
                         (random-uuid))
        seed-conn   (do
                      (d/create-database uri)
                      (d/connect uri))
        started-conn (atom nil)]
    (try
      @(d/transact seed-conn
                   [{:db/ident :member/nick
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}])
      @(d/transact seed-conn
                   [{:db/id (d/tempid :db.part/user)
                     :member/nick "private-duplicate"}
                    {:db/id (d/tempid :db.part/user)
                     :member/nick "private-duplicate"}])
      (let [connect      d/connect
            failure-data
            (with-redefs [d/connect
                          (fn [db-uri]
                            (let [conn (connect db-uri)]
                              (reset! started-conn conn)
                              conn))]
              (exception-data-of-type
               :app.datomic.migrations/duplicate-values
               #(datomic.system/start-peer {:peer {:db-uri uri}})))]
        (is (= :app.datomic.migrations/duplicate-values
               (:type failure-data)))
        (is (thrown? IllegalStateException
                     (d/db @started-conn))))
      (finally
        (d/release seed-conn)))))

(deftest fresh-database-preparation-test
  (let [conn              (fresh-connection "schema-lifecycle-fresh")
        prepared          (datomic.system/prepare-database! conn)
        preparation-basis (d/basis-t (d/db conn))
        member-id         (random-uuid)]
    @(d/transact conn
                 [{:db/id (d/tempid :db.part/user)
                   :member/member-id member-id
                   :member/nick "fresh-nick"
                   :member/email "fresh@example.test"
                   :member/username "fresh-user"}])
    (let [db (d/db conn)]
      (is (= {:returned-final-db? true
              :metadata {:app.schema/deprecated? true}
              :member {:member/member-id member-id
                       :member/nick "fresh-nick"
                       :member/email "fresh@example.test"
                       :member/username "fresh-user"}
              :stored-function? true
              :migration-ids
              #{:app.migration/pre001-prepare-member-uniqueness
                :app.migration/post001-normalize-active-insurance-surveys}}
             {:returned-final-db?
              (= (d/basis-t prepared) preparation-basis)
              :metadata
              (d/pull db
                      [:app.schema/deprecated?]
                      :insurance.survey/survey-name)
              :member
              (d/q '[:find (pull ?member
                                 [:member/member-id
                                  :member/nick
                                  :member/email
                                  :member/username]) .
                     :in $ ?member-id
                     :where
                     [?member :member/member-id ?member-id]]
                   db member-id)
              :stored-function?
              (boolean
               (:db/fn (d/entity db :insurance.survey/activate)))
              :migration-ids (migration-ids db)})))))

(deftest legacy-member-uniqueness-preparation-test
  (let [conn (fresh-connection "schema-lifecycle-member-uniqueness")]
    @(d/transact conn
                 [{:db/ident :member/nick
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :member/email
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity}
                  {:db/ident :member/username
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity}])
    @(d/transact conn
                 [{:db/id (d/tempid :db.part/user)
                   :member/nick "alpha"
                   :member/email "alpha@example.test"
                   :member/username "alpha-user"}
                  {:db/id (d/tempid :db.part/user)
                   :member/nick "beta"
                   :member/email "beta@example.test"
                   :member/username "beta-user"}])
    (datomic.system/prepare-database! conn)
    (let [db (d/db conn)]
      (is (= {:unique-modes
              {:member/nick :db.unique/value
               :member/email :db.unique/value
               :member/username :db.unique/value}
              :members
              [{:member/nick "alpha"
                :member/email "alpha@example.test"
                :member/username "alpha-user"}
               {:member/nick "beta"
                :member/email "beta@example.test"
                :member/username "beta-user"}]}
             {:unique-modes
              (into {}
                    (map (fn [ident]
                           [ident (ref-ident db ident :db/unique)]))
                    [:member/nick :member/email :member/username])
              :members
              (->> (d/q '[:find [(pull ?member
                                       [:member/nick
                                        :member/email
                                        :member/username]) ...]
                          :where
                          [?member :member/nick]]
                        db)
                   (sort-by :member/nick)
                   vec)})))))

(deftest member-uniqueness-schema-mismatch-test
  (let [conn (fresh-connection "schema-lifecycle-cardinality-mismatch")]
    @(d/transact conn
                 [{:db/ident :member/nick
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/many}])
    (is (= {:type :app.datomic.migrations/schema-mismatch
            :attribute :member/nick
            :expected-cardinality :db.cardinality/one
            :actual-cardinality :db.cardinality/many}
           (exception-data-of-type
            :app.datomic.migrations/schema-mismatch
            #(datomic.system/prepare-database! conn))))))

(deftest member-uniqueness-conflict-diagnostics-test
  (let [conn    (fresh-connection "schema-lifecycle-duplicate-members")
        tempids (repeatedly 4 #(d/tempid :db.part/user))]
    @(d/transact conn
                 [{:db/ident :member/nick
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}])
    (let [tx-report
          @(d/transact conn
                       (mapv (fn [tempid nick]
                               {:db/id tempid
                                :member/nick nick})
                             tempids
                             ["private-a" "private-a"
                              "private-b" "private-b"]))
          entity-ids
          (->> tempids
               (map #(d/resolve-tempid (:db-after tx-report)
                                       (:tempids tx-report)
                                       %))
               sort
               vec)
          exception-data
          (exception-data-of-type
           :app.datomic.migrations/duplicate-values
           #(datomic.system/prepare-database! conn))
          db (d/db conn)]
      (is (= {:exception-data
              {:type :app.datomic.migrations/duplicate-values
               :attribute :member/nick
               :duplicate-group-count 2
               :conflicting-entity-ids entity-ids}
              :unique-mode nil
              :migration-installed? false}
             {:exception-data exception-data
              :unique-mode (ref-ident db :member/nick :db/unique)
              :migration-installed?
              (boolean
               (stork/installed?
                db
                :app.migration/pre001-prepare-member-uniqueness))})))))

(deftest active-survey-normalization-test
  (let [conn (fresh-connection "schema-lifecycle-survey-normalization")
        retained-id #uuid "00000000-0000-0000-0000-000000000005"
        older-id #uuid "00000000-0000-0000-0000-000000000004"
        missing-created-id #uuid "00000000-0000-0000-0000-000000000003"
        expired-id #uuid "00000000-0000-0000-0000-000000000002"
        closed-id #uuid "00000000-0000-0000-0000-000000000001"
        existing-close #inst "2024-01-01T00:00:00.000-00:00"]
    (install-current-schema! conn)
    @(d/transact conn
                 [{:db/id (d/tempid :db.part/user)
                   :insurance.survey/survey-id retained-id
                   :insurance.survey/created-at
                   #inst "2026-01-05T00:00:00.000-00:00"
                   :insurance.survey/closes-at
                   #inst "2099-01-01T00:00:00.000-00:00"}
                  {:db/id (d/tempid :db.part/user)
                   :insurance.survey/survey-id older-id
                   :insurance.survey/created-at
                   #inst "2026-01-04T00:00:00.000-00:00"
                   :insurance.survey/closes-at
                   #inst "2099-01-01T00:00:00.000-00:00"}
                  {:db/id (d/tempid :db.part/user)
                   :insurance.survey/survey-id missing-created-id
                   :insurance.survey/closes-at
                   #inst "2099-01-01T00:00:00.000-00:00"}
                  {:db/id (d/tempid :db.part/user)
                   :insurance.survey/survey-id expired-id
                   :insurance.survey/created-at
                   #inst "2026-01-06T00:00:00.000-00:00"
                   :insurance.survey/closes-at
                   #inst "2000-01-01T00:00:00.000-00:00"}
                  {:db/id (d/tempid :db.part/user)
                   :insurance.survey/survey-id closed-id
                   :insurance.survey/created-at
                   #inst "2026-01-07T00:00:00.000-00:00"
                   :insurance.survey/closes-at
                   #inst "2099-01-01T00:00:00.000-00:00"
                   :insurance.survey/closed-at existing-close}])
    (let [before (java.util.Date.)]
      (datomic.system/prepare-database! conn)
      (let [after                  (java.util.Date.)
            db                     (d/db conn)
            older-closed-at        (survey-closed-at db older-id)
            missing-created-closed (survey-closed-at
                                    db missing-created-id)]
        (is (= {:retained nil
                :older-and-missing-closed-together? true
                :expired nil
                :explicitly-closed existing-close}
               {:retained (survey-closed-at db retained-id)
                :older-and-missing-closed-together?
                (and (inst? older-closed-at)
                     (= older-closed-at missing-created-closed)
                     (not (neg? (compare older-closed-at before)))
                     (not (pos? (compare older-closed-at after))))
                :expired (survey-closed-at db expired-id)
                :explicitly-closed (survey-closed-at db closed-id)}))))))

(deftest active-survey-normalization-no-op-test
  (testing "zero active surveys"
    (let [conn       (fresh-connection "schema-lifecycle-zero-active")
          expired-id #uuid "10000000-0000-0000-0000-000000000001"]
      (install-current-schema! conn)
      @(d/transact conn
                   [{:db/id (d/tempid :db.part/user)
                     :insurance.survey/survey-id expired-id
                     :insurance.survey/created-at
                     #inst "2026-01-01T00:00:00.000-00:00"
                     :insurance.survey/closes-at
                     #inst "2000-01-01T00:00:00.000-00:00"}])
      (datomic.system/prepare-database! conn)
      (let [db (d/db conn)]
        (is (= {:closed-at nil
                :migration-installed? true}
               {:closed-at (survey-closed-at db expired-id)
                :migration-installed?
                (boolean
                 (stork/installed?
                  db
                  :app.migration/post001-normalize-active-insurance-surveys))})))))
  (testing "one active survey"
    (let [conn      (fresh-connection "schema-lifecycle-one-active")
          survey-id #uuid "10000000-0000-0000-0000-000000000002"]
      (install-current-schema! conn)
      @(d/transact conn
                   [{:db/id (d/tempid :db.part/user)
                     :insurance.survey/survey-id survey-id
                     :insurance.survey/created-at
                     #inst "2026-01-01T00:00:00.000-00:00"
                     :insurance.survey/closes-at
                     #inst "2099-01-01T00:00:00.000-00:00"}])
      (datomic.system/prepare-database! conn)
      (let [db (d/db conn)]
        (is (= {:closed-at nil
                :migration-installed? true}
               {:closed-at (survey-closed-at db survey-id)
                :migration-installed?
                (boolean
                 (stork/installed?
                  db
                  :app.migration/post001-normalize-active-insurance-surveys))}))))))

(deftest active-survey-normalization-tie-breaking-test
  (testing "survey UUID string orders equal creation times"
    (let [conn       (fresh-connection "schema-lifecycle-survey-uuid-tie")
          retained-id #uuid "20000000-0000-0000-0000-000000000001"
          closed-id   #uuid "20000000-0000-0000-0000-000000000002"]
      (install-current-schema! conn)
      @(d/transact conn
                   [{:db/id (d/tempid :db.part/user)
                     :insurance.survey/survey-id closed-id
                     :insurance.survey/created-at
                     #inst "2026-02-01T00:00:00.000-00:00"
                     :insurance.survey/closes-at
                     #inst "2099-01-01T00:00:00.000-00:00"}
                    {:db/id (d/tempid :db.part/user)
                     :insurance.survey/survey-id retained-id
                     :insurance.survey/created-at
                     #inst "2026-02-01T00:00:00.000-00:00"
                     :insurance.survey/closes-at
                     #inst "2099-01-01T00:00:00.000-00:00"}])
      (datomic.system/prepare-database! conn)
      (let [db (d/db conn)]
        (is (= {:retained nil
                :closed? true}
               {:retained (survey-closed-at db retained-id)
                :closed? (inst? (survey-closed-at db closed-id))})))))
  (testing "entity ID orders malformed surveys without UUIDs"
    (let [conn    (fresh-connection "schema-lifecycle-survey-eid-tie")
          tempids [(d/tempid :db.part/user) (d/tempid :db.part/user)]]
      (install-current-schema! conn)
      (let [tx-report
            @(d/transact conn
                         (mapv (fn [tempid]
                                 {:db/id tempid
                                  :insurance.survey/created-at
                                  #inst "2026-03-01T00:00:00.000-00:00"
                                  :insurance.survey/closes-at
                                  #inst "2099-01-01T00:00:00.000-00:00"})
                               tempids))
            entity-ids
            (->> tempids
                 (map #(d/resolve-tempid (:db-after tx-report)
                                         (:tempids tx-report)
                                         %))
                 sort
                 vec)]
        (datomic.system/prepare-database! conn)
        (let [db (d/db conn)]
          (is (= {:lower-eid-closed-at nil
                  :higher-eid-closed? true}
                 {:lower-eid-closed-at
                  (:insurance.survey/closed-at
                   (d/pull db
                           [:insurance.survey/closed-at]
                           (first entity-ids)))
                  :higher-eid-closed?
                  (inst?
                   (:insurance.survey/closed-at
                    (d/pull db
                            [:insurance.survey/closed-at]
                            (second entity-ids))))})))))))
