(ns app.account.schema-test
  (:require
   [app.datomic.system :as datomic.system]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def expected-attributes
  {:member/current-status {:value-type :db.type/string}
   :member/date-of-birth {:value-type :db.type/string}
   :member/avatar {:value-type :db.type/ref :component? true}
   :member/timezone {:value-type :db.type/string}
   :member/week-start {:value-type :db.type/ref}
   :member/clock-format {:value-type :db.type/ref}
   :member.notify/enabled? {:value-type :db.type/boolean}
   :member.notify/scope {:value-type :db.type/ref}
   :member.notify/attendance-reminders? {:value-type :db.type/boolean}
   :member.notify/poll-reminders? {:value-type :db.type/boolean}
   :member.notify/email? {:value-type :db.type/boolean}
   :member.notify/browser? {:value-type :db.type/boolean}
   :member.notify/unread-style {:value-type :db.type/ref}
   :member.notify/schedule {:value-type :db.type/ref}
   :member.notify/batch-time {:value-type :db.type/string}
   :member.break/start-date {:value-type :db.type/string}
   :member.break/end-date {:value-type :db.type/string}})

(def expected-enums
  {:week-start/monday "Monday is the first day of the week"
   :week-start/sunday "Sunday is the first day of the week"
   :clock-format/hour-12 "Use a 12-hour clock"
   :clock-format/hour-24 "Use a 24-hour clock"
   :notify.scope/everything "Notify about all activity"
   :notify.scope/gigs "Notify only about gig activity"
   :notify.unread-style/numbered "Show the unread notification count"
   :notify.unread-style/unnumbered "Show unread notifications without a count"
   :notify.schedule/right-away "Deliver notifications as activity happens"
   :notify.schedule/daily-batch "Deliver notifications in a daily batch"})

(deftest account-schema-installs-cardinality-one-attributes
  (let [{:keys [conn]} (tc/new-system "account-schema")
        db (d/db conn)]
    (doseq [[ident {:keys [value-type component?]}] expected-attributes]
      (let [attribute (d/pull db
                              [:db/ident
                               {:db/valueType [:db/ident]}
                               {:db/cardinality [:db/ident]}
                               :db/isComponent]
                              ident)]
        (is (= ident (:db/ident attribute)))
        (is (= value-type (get-in attribute [:db/valueType :db/ident])))
        (is (= :db.cardinality/one
               (get-in attribute [:db/cardinality :db/ident])))
        (is (= (boolean component?)
               (boolean (:db/isComponent attribute))))))))

(deftest account-enumerations-are-ident-entities
  (let [{:keys [conn]} (tc/new-system "account-enums")
        db (d/db conn)]
    (doseq [[ident doc] expected-enums]
      (is (= {:db/ident ident :db/doc doc}
             (d/pull db [:db/ident :db/doc] ident))))))

(deftest schema-index-upgrades-selects-only-existing-uniqueness-changes
  (let [uri (str "datomic:mem://account-schema-upgrade-" (random-uuid))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn [{:db/ident :test/unindexed
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}
                         {:db/ident :test/already-unique
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/unique :db.unique/value}
                         {:db/ident :test/not-unique
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}])
      (is (= [{:db/id :test/unindexed
               :db/index true}]
             (datomic.system/schema-index-upgrades
              (d/db conn)
              [{:db/ident :test/unindexed
                :db/index true
                :db/unique :db.unique/value}
               {:db/ident :test/already-unique
                :db/unique :db.unique/value}
               {:db/ident :test/not-unique}
               {:db/ident :test/new-unique
                :db/unique :db.unique/value}]))))))

(deftest transact-schema-upgrades-an-existing-unindexed-unique-attribute
  (let [uri (str "datomic:mem://account-schema-transact-" (random-uuid))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn [{:db/ident :member/nick
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}])
      @(d/transact conn [{:db/id "existing-member"
                          :member/nick "existing"}])

      @(datomic.system/transact-schema conn)

      (let [db (d/db conn)]
        (is (= true (:db/index (d/pull db [:db/index] :member/nick))))
        (is (= :db.unique/value
               (get-in (d/pull db [{:db/unique [:db/ident]}] :member/nick)
                       [:db/unique :db/ident])))
        (is (= :member/timezone
               (:db/ident (d/pull db [:db/ident] :member/timezone))))))))
