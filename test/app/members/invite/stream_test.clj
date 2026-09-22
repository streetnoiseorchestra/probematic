(ns app.members.invite.stream-test
  (:require
   [app.datastar :as datastar]
   [app.game-loop :as game]
   [app.members.invite.admission :as admission]
   [app.members.invite.domain :as domain]
   [app.members.invite.stream :as stream]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [integrant.core :as ig]
   [org.httpkit.server :as server]
   [tick.core :as t])
  (:import [java.io InputStream]
           [java.net HttpURLConnection URI]))

(use-fixtures :each tc/with-released-test-connections)

(defn open-http ^HttpURLConnection [url]
  (doto ^HttpURLConnection (.openConnection (.toURL (URI/create url)))
    (.setConnectTimeout 5000)
    (.setReadTimeout 5000)))

(defn await! [pred]
  (loop [remaining 200]
    (if (or (pred) (zero? remaining))
      (boolean (pred))
      (do (Thread/sleep 25) (recur (dec remaining))))))

(deftest disabled-runtime-rejects-public-streams
  (is (= 503 (:status (stream/response {} {} "test-bearer" (constantly ""))))))

(deftest public-streams-ignore-tab-ids-and-revalidate-without-action-ownership
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [a           (random-uuid)
            b           (random-uuid)
            actor       (random-uuid)
            tab-id      (str (random-uuid))
            observed    (atom {})
            expiry      (t/inst (t/>> (t/instant) (t/new-duration 1 :hours)))
            system      {:frame-loop runtime :write-runner (:write-runner runtime) :job-queue {:client client}}
            stop-server (server/run-server
                         (fn [request]
                           (stream/response
                            system
                            (assoc request :body-params {:tab-id tab-id}
                                   :app/session {:session/member {:member/member-id actor}})
                            (get {"/a" "stream-code-a" "/b" "stream-code-b"} (:uri request))
                            (fn [request status]
                              (swap! observed assoc (:uri request) status)
                              (str "<div id=\"status\">" (name (:status status)) "</div>"))))
                         {:ip "127.0.0.1" :port 0})
            base        (str "http://127.0.0.1:" (:local-port (meta stop-server)))]
        (try
          (writer/call! runtime
                        #(deref (d/transact conn
                                            (mapv (fn [[id label code]]
                                                    {:member/member-id         id                         :member/name              label                         :member/username label
                                                     :member/email             (str label "@example.com")
                                                     :member/invite-code       code                       :member/invite-status     :member.invite.status/pending
                                                     :member/invite-generation 1                          :member/invite-expires-at expiry
                                                     :member/invite-status-at  (t/inst)})
                                                  [[a "a" "stream-code-a"] [b "b" "stream-code-b"]]))))
          (let [missing (open-http (str base "/missing"))]
            (try (is (= 404 (.getResponseCode missing)))
                 (finally (.disconnect missing))))
          (let [ca (open-http (str base "/a"))
                cb (open-http (str base "/b"))]
            (try
              (with-open [ba  ^InputStream (.getInputStream ca)
                          _bb ^InputStream (.getInputStream cb)]
                (is (= 200 (.getResponseCode ca)))
                (is (= 200 (.getResponseCode cb)))
                (is (await! #(= 2 (count @observed))))
                (is (= a (get-in @observed ["/a" :member :member/member-id])))
                (is (= b (get-in @observed ["/b" :member :member/member-id])))
                (let [clients @(:clients runtime)]
                  (is (= 2 (count clients)))
                  (is (not (contains? clients tab-id)))
                  (doseq [[id client] clients]
                    (is (nil? (:member-id client)))
                    (is (nil? (datastar/assoc-connection-token
                               runtime {:body-params {:tab-id id}
                                        :app/session {:session/member {:member/member-id actor}}})))
                    (is (not (contains? @datastar/!page-state id)))))
                (writer/call! runtime
                              #(deref (d/transact conn (:tx-data (domain/revoke-tx
                                                                  (d/db conn)
                                                                  {:member-id       a        :state (domain/invitation-state (d/db conn) a)
                                                                   :transitioned-at (t/inst)})))))
                (is (await! #(= {:status :unavailable} (get @observed "/a"))))
                (is (= :pending (get-in @observed ["/b" :status])))
                (is (= {:status :creating :member-id b}
                       (admission/request-setup! {:datomic-conn conn :write-runner (:write-runner runtime) :clock t/inst}
                                                 "stream-code-b")))
                (is (await! #(= :creating (get-in @observed ["/b" :status]))))
                (.close ba)
                (.disconnect ca)
                (is (await! #(= 1 (count @(:clients runtime)))))
                (ig/halt-key! :app.ig/frame-loop runtime)
                (is (await! #(empty? @(:clients runtime))))
                (is (zero? (.size ^java.util.concurrent.ConcurrentHashMap (::game/conns runtime))))
                (is (= 503 (:status (stream/response system {} "stream-code-b" (constantly ""))))))
              (finally (.disconnect ca) (.disconnect cb))))
          (finally (stop-server)))))))
