(ns app.jobs.integrations-test
  (:require
   [app.caldav :as caldav]
   [app.discourse :as discourse]
   [app.email.job-queue-test :as queue-fixtures]
   [app.game-loop :as game]
   [app.gigs.answer-link.service-test :as gigs]
   [app.jobs.integrations :as integrations]
   [app.jobs.worker :as jobs-worker]
   [app.nexus :as nexus]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [org.httpkit.server :as http]
   [s-exp.drip :as drip]
   [tick.core :as t]))

(use-fixtures :each tc/with-released-test-connections)

(deftest gig-integrations-retain-options-and-deletion-ids-and-read-current-state
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [{:keys [gig-id]} (writer/call! runtime
                                           #(gigs/seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days))))
            deleted-id       (random-uuid)
            calls            (atom [])
            record!          (fn [operation system id & options]
                               (swap! calls conj [operation id (:gig/title (q/retrieve-gig (:db system) id))
                                                  (vec options) (identical? (::game/thread runtime) (Thread/currentThread))]))
            system           {:write-runner (:write-runner runtime)              :job-queue {:client client} :datomic {:conn conn}
                              :env          {:ig/system {:app.ig/profile :prod}}}]
        (writer/call! runtime
                      #(deref (d/transact conn [{:gig/gig-id deleted-id :gig/title "Delete me"}])))
        (writer/call! runtime
                      #(nexus/db-transact-fx {} {:system system :request {}}
                                             [[[[:db/retractEntity [:gig/gig-id deleted-id]]]
                                               {:jobs [(integrations/gig-job gig-id {:operation :created :thread? true})
                                                       (integrations/gig-job gig-id {:operation :updated :takeover-topic? true})
                                                       (integrations/gig-job deleted-id {:operation :deleted})]}]]))
        (writer/call! runtime
                      #(deref (d/transact conn [[:db/add [:gig/gig-id gig-id] :gig/title "Latest title"]])))
        (writer/call! runtime (constantly nil))
        (with-redefs [discourse/create-topic-for-gig!       (partial record! :create-topic)
                      discourse/update-topic-for-gig!       (partial record! :update-topic)
                      discourse/maybe-delete-topic-for-gig! (partial record! :delete-topic)
                      caldav/update-gig-event!              (partial record! :update-calendar)
                      caldav/delete-gig-event!              (partial record! :delete-calendar)]
          (let [jobs     (drip/list-jobs client {})
                source-t (get-in (first jobs) [:args :source-t])
                worker   (jobs-worker/start! system)]
            (try
              (is (= 3 (count jobs)))
              (is (= 1 (count (set (map #(get-in % [:args :source-t]) jobs)))))
              (doseq [job jobs]
                (is (= :completed (:state (queue-fixtures/await-state client (:id job) :completed)))))
              (is (= (frequencies [[:create-topic gig-id "Latest title" [source-t] false]
                                   [:update-topic gig-id "Latest title" [true source-t] false]
                                   [:update-calendar gig-id "Latest title" [] false]
                                   [:update-calendar gig-id "Latest title" [] false]
                                   [:delete-topic deleted-id nil [] false]
                                   [:delete-calendar deleted-id nil [] false]])
                     (frequencies @calls)))
              (finally (jobs-worker/stop! worker)))))))))
(deftest updated-gig-topic-fallback-retains-the-source-transaction-actor
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [{:keys [gig-id]} (writer/call! runtime
                                           #(gigs/seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days))))
            actor-id         (random-uuid)
            system           {:write-runner (:write-runner runtime)
                              :job-queue    {:client client}
                              :datomic      {:conn conn}
                              :env          {:ig/system    {:app.ig/profile :prod}
                                             :app-base-url "https://example.test"
                                             :discourse    {:username "test"}}}]
        (writer/call!
         runtime
         (fn []
           @(d/transact conn [{:member/member-id actor-id}])
           (nexus/db-transact-fx
            {}
            {:system  system
             :request {:app/session         {:session/member {:member/member-id actor-id}}
                       ::nexus/audit-action ::edit-gig}}
            [[[] {:jobs [(integrations/gig-job
                          gig-id
                          {:operation :updated :takeover-topic? true})]}]])))
        (writer/call! runtime (constantly nil))
        (with-redefs [discourse/request!
                      (fn [_ {:keys [method]}]
                        (case method
                          :get (throw (ex-info "Not found" {:resp {:status 404}}))
                          :post {:topic_id 42}))
                      caldav/update-gig-event! (fn [& _] nil)]
          (let [job    (first (drip/list-jobs client {:kind "sync-gig"}))
                worker (jobs-worker/start! system)]
            (try
              (is (= :completed (:state (queue-fixtures/await-state client (:id job) :completed))))
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
              (finally (jobs-worker/stop! worker)))))))))

(deftest cms-http-failure-is-retried-before-completing-the-job
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [song-id  (random-uuid)
            requests (atom 0)
            server   (http/run-server (fn [_] {:status (if (= 1 (swap! requests inc)) 503 200) :body "CMS response"})
                                      {:ip "127.0.0.1" :port 0})
            system   {:write-runner (:write-runner runtime)                                                                           :job-queue {:client client} :datomic {:conn conn}
                      :env          {:ig/system {:app.ig/profile :prod}
                                     :cms       {:token "test-token" :cms-url (str "http://127.0.0.1:" (:local-port (meta server)))}}}]
        (try
          (writer/call! runtime
                        #(nexus/db-transact-fx {} {:system system :request {}}
                                               [[[{:song/song-id song-id :song/title "Outbox song" :song/active? true}]
                                                 {:jobs [(integrations/song-job song-id)]}]]))
          (writer/call! runtime (constantly nil))
          (let [job    (first (drip/list-jobs client {}))
                worker (drip/start-worker! {:client   client                                                    :queues ["start-within-15m"] :poll-interval 10 :retry-interval 10
                                            :registry {"sync-song" (partial integrations/handle! system :song)}})]
            (try
              (is (= :completed (:state (queue-fixtures/await-state client (:id job) :completed))))
              (is (= 2 @requests))
              (finally (drip/stop-worker! worker :drain true))))
          (finally (server)))))))
