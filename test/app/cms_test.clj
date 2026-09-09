(ns app.cms-test
  (:require
   [app.cms :as cms]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [org.httpkit.server :as http]))

(use-fixtures :each tc/with-released-test-connections)

(deftest cms-failures-propagate-so-durable-jobs-can-retry
  (let [{:keys [conn]} (tc/new-system "cms-retry")
        song-id        (random-uuid)
        status         (atom 503)
        server         (http/run-server (fn [_] {:status @status :body "CMS response"}) {:ip "127.0.0.1" :port 0})]
    (try
      @(d/transact conn [{:song/song-id song-id :song/title "Test song" :song/active? true}])
      (let [system {:datomic {:conn conn}                                                                                :db (d/db conn)
                    :env     {:cms {:token "test-token" :cms-url (str "http://127.0.0.1:" (:local-port (meta server)))}}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CMS request failed" (cms/sync-song! system song-id)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CMS request failed" (cms/sync-all-songs! system)))
        (reset! status 200)
        (is (= 200 (:status (cms/sync-song! system song-id))))
        (is (nil? (cms/sync-all-songs! system))))
      (finally (server)))))
