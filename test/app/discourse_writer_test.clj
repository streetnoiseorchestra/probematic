(ns app.discourse-writer-test
  (:require
   [app.discourse :as discourse]
   [app.datomic :as datomic]
   [app.game-loop :as game]
   [app.gigs.answer-link.service-test :as gigs]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [tick.core :as t])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest topic-persistence-does-not-resurrect-a-deleted-gig-or-overwrite-a-new-link
  (doseq [change [:deleted :relinked]]
    (fixtures/with-runtime
      (fn [runtime _ conn]
        (let [{:keys [gig-id]} (writer/call! runtime
                                             #(gigs/seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days))))
              db               (d/db conn)
              eid              (d/entid db [:gig/gig-id gig-id])
              entered          (promise)
              release          (promise)
              system           {:write-runner (:write-runner runtime)                                              :datomic {:conn conn} :db db
                                :env          {:app-base-url "https://example.test" :discourse {:username "test"}}}]
          (with-redefs [discourse/request! (fn [& _] (deliver entered true) @release {:id 42})]
            (try
              (let [result (future (discourse/create-topic-for-gig! system gig-id))]
                (is (= true (deref entered 5000 ::timeout)))
                (writer/call! runtime
                              #(deref (d/transact conn [(if (= :deleted change)
                                                          [:db/retractEntity eid]
                                                          [:db/add eid :forum.topic/topic-id "99"])])))
                (let [before-t (d/basis-t (d/db conn))]
                  (deliver release true)
                  (is (nil? (deref result 5000 ::timeout)))
                  (is (= before-t (d/basis-t (d/db conn))))
                  (is (= (when (= :relinked change) "99")
                         (:forum.topic/topic-id (d/entity (d/db conn) eid))))))
              (finally (deliver release true)))))))))

(deftest topic-creation-persists-on-the-writer-and-recovers-an-existing-remote-topic
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (let [{:keys [gig-id]} (writer/call! runtime
                                           #(gigs/seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days))))
            actor-id         (random-uuid)
            _                @(d/transact conn [{:member/member-id actor-id}])
            source-t         (-> (datomic/transact
                                  conn
                                  {:tx-data [{:team/team-id (random-uuid)
                                              :team/name    "Source change"}]
                                   :audit   {:audit/user [:member/member-id actor-id]}})
                                 :db-after
                                 d/basis-t)
            system           {:write-runner (:write-runner runtime)                                              :datomic {:conn conn} :db (d/db conn)
                              :env          {:app-base-url "https://example.test" :discourse {:username "test"}}}
            entered          (promise)
            release          (promise)
            posted           (promise)
            posts            (atom 0)
            remote-topic     (atom nil)]
        (with-redefs [discourse/request!
                      (fn [_ {:keys [method]}]
                        (case method
                          :get (or @remote-topic (throw (ex-info "Not found" {:resp {:status 404}})))
                          :post (do (swap! posts inc)
                                    (reset! remote-topic {:id 42})
                                    (deliver posted true)
                                    {:topic_id 42})))]
          (try
            (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                  (fn [_] (deliver entered true) @release))
            (is (= true (deref entered 5000 ::timeout)))
            (let [result (future (discourse/create-topic-for-gig! system gig-id source-t))]
              (is (= true (deref posted 5000 ::timeout)))
              (is (= ::waiting (deref result 1000 ::waiting)))
              (is (nil? (:forum.topic/topic-id (d/entity (d/db conn) [:gig/gig-id gig-id]))))
              (deliver release true)
              (is (map? (deref result 5000 ::timeout))))
            (writer/call! runtime
                          #(deref (d/transact conn [[:db/retract [:gig/gig-id gig-id] :forum.topic/topic-id "42"]])))
            (discourse/create-topic-for-gig! system gig-id source-t)
            (is (= 1 @posts))
            (is (= "42" (:forum.topic/topic-id (d/entity (d/db conn) [:gig/gig-id gig-id]))))
            (is (= actor-id
                   (d/q '[:find ?member-id .
                          :in $ ?action
                          :where
                          [?tx :audit/action ?action]
                          [?tx :audit/user ?member]
                          [?member :member/member-id ?member-id]]
                        (d/db conn)
                        :app.discourse/create-topic-for-gig)))
            (finally (deliver release true))))))))
