(ns app.jobs.avatar-cutover-test
  (:require
   [app.account.test-support :as support]
   [app.filestore :as filestore]
   [app.queries :as queries]
   [app.test-common :as tc]
   [babashka.fs :as bfs]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [org.httpkit.server :as http-server])
  (:import
   [java.nio.file Files]))

(def jpeg-path "resources/public/img/tuba-robot-boat-1000.jpg")

(defn jpeg-bytes []
  (Files/readAllBytes (.toPath (bfs/file jpeg-path))))

(defn with-temp-filestore [f]
  (let [dir (bfs/create-temp-dir {:prefix "probematic.avatar-cutover-test."})]
    (try
      (let [store (filestore/start! {:store-path dir})]
        (try
          (f store)
          (finally
            (filestore/halt! store))))
      (finally
        (bfs/delete-tree dir)))))

(defn with-avatar-server [handler f]
  (let [server (http-server/run-server handler {:ip "127.0.0.1" :port 0})]
    (try
      (f (:local-port (meta server)))
      (finally
        (server)))))

(defn seed-cutover-member! [conn member-id template]
  @(d/transact conn [{:member/member-id member-id
                      :member/name (str "Member " member-id)
                      :member/avatar-template template}]))

(defn avatar-id [db member-id]
  (get-in (queries/retrieve-member db member-id)
          [:member/avatar :image/image-id]))

(deftest avatar-cutover-processes-members-sequentially-with-one-transaction-each
  (let [cutover! (support/public-fn
                  'app.jobs.avatar-cutover/cutover-avatars!)]
    (is (fn? cutover!)
        "app.jobs.avatar-cutover/cutover-avatars! should exist")
    (when cutover!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn]} (tc/new-system "avatar-cutover")
                requests (atom [])
                first-id #uuid "11111111-1111-4111-8111-111111111111"
                failed-id #uuid "22222222-2222-4222-8222-222222222222"
                last-id #uuid "33333333-3333-4333-8333-333333333333"
                bytes (jpeg-bytes)]
            (with-avatar-server
              (fn [{:keys [uri]}]
                (swap! requests conj uri)
                (if (.startsWith ^String uri "/failed/")
                  {:status 500 :body "no avatar"}
                  {:status 200
                   :headers {"content-type" "image/jpeg"}
                   :body bytes}))
              (fn [port]
                (seed-cutover-member!
                 conn first-id
                 (str "http://127.0.0.1:" port "/first/{size}.jpg"))
                (seed-cutover-member!
                 conn failed-id
                 (str "http://127.0.0.1:" port "/failed/{size}.jpg"))
                (seed-cutover-member!
                 conn last-id
                 (str "http://127.0.0.1:" port "/last/{size}.jpg"))
                (is (= {:processed 3 :migrated 2 :failed 1 :conflicted 0}
                       (cutover! {:datomic {:conn conn}
                                  :filestore store
                                  :env {:discourse {:forum-url
                                                    (str "http://127.0.0.1:"
                                                         port)}}})))
                (let [db (d/db conn)]
                  (is (uuid? (avatar-id db first-id)))
                  (is (nil? (avatar-id db failed-id)))
                  (is (uuid? (avatar-id db last-id)))
                  (is (string? (:member/avatar-template
                                (queries/retrieve-member db first-id))))
                  (is (= 2
                         (->> (d/q '[:find ?tx
                                     :in $ [?member-id ...]
                                     :where
                                     [?member :member/member-id ?member-id]
                                     [?member :member/avatar _ ?tx]]
                                   db
                                   [first-id last-id])
                              (map first)
                              distinct
                              count))))
                (is (= ["/first/320.jpg"
                        "/failed/320.jpg"
                        "/last/320.jpg"]
                       @requests))
                (testing "a second run is a no-op for migrated members but retries failures"
                  (let [before (count @requests)
                        result (cutover! {:datomic {:conn conn}
                                          :filestore store
                                          :env {:discourse
                                                {:forum-url
                                                 (str "http://127.0.0.1:"
                                                      port)}}})]
                    (is (= {:processed 1
                            :migrated 0
                            :failed 1
                            :conflicted 0}
                           result))
                    (is (= (inc before) (count @requests)))))))))))))

(deftest avatar-cutover-compare-and-swap-preserves-a-concurrent-profile-upload
  (let [cutover! (support/public-fn
                  'app.jobs.avatar-cutover/cutover-avatars!)]
    (is (fn? cutover!))
    (when cutover!
      (with-temp-filestore
        (fn [store]
          (let [{:keys [conn]} (tc/new-system "avatar-cutover-cas")
                member-id #uuid "44444444-4444-4444-8444-444444444444"
                newer-avatar-id #uuid "55555555-5555-4555-8555-555555555555"
                bytes (jpeg-bytes)]
            (with-avatar-server
              (fn [_request]
                @(d/transact
                  conn
                  [{:db/id [:member/member-id member-id]
                    :member/avatar {:image/image-id newer-avatar-id
                                    :image/width 160
                                    :image/height 160}}])
                {:status 200
                 :headers {"content-type" "image/jpeg"}
                 :body bytes})
              (fn [port]
                (seed-cutover-member!
                 conn
                 member-id
                 (str "http://127.0.0.1:" port "/conflict/{size}.jpg"))
                (is (= {:processed 1
                        :migrated 0
                        :failed 0
                        :conflicted 1}
                       (cutover! {:datomic {:conn conn}
                                  :filestore store
                                  :env {:discourse
                                        {:forum-url
                                         (str "http://127.0.0.1:" port)}}})))
                (is (= newer-avatar-id
                       (avatar-id (d/db conn) member-id)))))))))))

(deftest application-job-definitions-register-the-one-shot-avatar-cutover
  (let [job-defs (support/public-fn 'app.jobs/job-defs)]
    (is (fn? job-defs))
    (when job-defs
      (is (fn? (:job/avatar-cutover
                (job-defs {:datomic ::datomic
                           :filestore ::filestore
                           :env ::env})))))))
