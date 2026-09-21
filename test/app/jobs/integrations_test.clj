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
      (let [{:keys [gig-id]} (writer/call! (:write-runner runtime)
                                           #(gigs/seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days))))
            deleted-id       (random-uuid)
            calls            (atom [])
            record!          (fn [operation system id & options]
                               (swap! calls conj [operation id (:gig/title (q/retrieve-gig (:db system) id))
                                                  (vec options) (identical? (::game/thread runtime) (Thread/currentThread))]))
            system           {:frame-loop runtime                              :job-queue {:client client} :datomic {:conn conn}
                              :env        {:ig/system {:app.ig/profile :prod}}}]
        (writer/call! (:write-runner runtime)
                      #(deref (d/transact conn [{:gig/gig-id deleted-id :gig/title "Delete me"}])))
        (writer/call! (:write-runner runtime)
                      #(nexus/db-transact-fx {} {:system system :request {}}
                                             [[[[:db/retractEntity [:gig/gig-id deleted-id]]]
                                               {:jobs [(integrations/gig-job gig-id {:operation :created :thread? true})
                                                       (integrations/gig-job gig-id {:operation :updated :takeover-topic? true})
                                                       (integrations/gig-job deleted-id {:operation :deleted})]}]]))
        (writer/call! (:write-runner runtime)
                      #(deref (d/transact conn [[:db/add [:gig/gig-id gig-id] :gig/title "Latest title"]])))
        (writer/call! (:write-runner runtime) (constantly nil))
        (with-redefs [discourse/create-topic-for-gig!       (partial record! :create-topic)
                      discourse/update-topic-for-gig!       (partial record! :update-topic)
                      discourse/maybe-delete-topic-for-gig! (partial record! :delete-topic)
                      caldav/update-gig-event!              (partial record! :update-calendar)
                      caldav/delete-gig-event!              (partial record! :delete-calendar)]
          (let [jobs   (drip/list-jobs client {})
                worker (jobs-worker/start! system)]
            (try
              (is (= 3 (count jobs)))
              (is (= 1 (count (set (map #(get-in % [:args :source-t]) jobs)))))
              (doseq [job jobs]
                (is (= :completed (:state (queue-fixtures/await-state client (:id job) :completed)))))
              (is (= (frequencies [[:create-topic gig-id "Latest title" [] false]
                                   [:update-topic gig-id "Latest title" [true] false]
                                   [:update-calendar gig-id "Latest title" [] false]
                                   [:update-calendar gig-id "Latest title" [] false]
                                   [:delete-topic deleted-id nil [] false]
                                   [:delete-calendar deleted-id nil [] false]])
                     (frequencies @calls)))
              (finally (jobs-worker/stop! worker)))))))))

(deftest cms-http-failure-is-retried-before-completing-the-job
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [song-id  (random-uuid)
            requests (atom 0)
            server   (http/run-server (fn [_] {:status (if (= 1 (swap! requests inc)) 503 200) :body "CMS response"})
                                      {:ip "127.0.0.1" :port 0})
            system   {:frame-loop runtime                                                                                           :job-queue {:client client} :datomic {:conn conn}
                      :env        {:ig/system {:app.ig/profile :prod}
                                   :cms       {:token "test-token" :cms-url (str "http://127.0.0.1:" (:local-port (meta server)))}}}]
        (try
          (writer/call! (:write-runner runtime)
                        #(nexus/db-transact-fx {} {:system system :request {}}
                                               [[[{:song/song-id song-id :song/title "Outbox song" :song/active? true}]
                                                 {:jobs [(integrations/song-job song-id)]}]]))
          (writer/call! (:write-runner runtime) (constantly nil))
          (let [job    (first (drip/list-jobs client {}))
                worker (drip/start-worker! {:client   client                                                    :queues ["integrations"] :poll-interval 10 :retry-interval 10
                                            :registry {"sync-song" (partial integrations/handle! system :song)}})]
            (try
              (is (= :completed (:state (queue-fixtures/await-state client (:id job) :completed))))
              (is (= 2 @requests))
              (finally (drip/stop-worker! worker :drain true))))
          (finally (server)))))))
