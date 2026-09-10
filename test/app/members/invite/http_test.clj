(ns app.members.invite.http-test
  (:require
   [app.i18n :as i18n]
   [app.icons :as icons]
   [app.interceptors :as interceptors]
   [app.members.invite.domain :as domain]
   [app.members.invite.status-views :as views]
   [app.members.routes :as routes]
   [app.test-common :as tc]
   [app.ui2.button :as button]
   [app.util :as util]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [lookup.core :as l]
   [org.httpkit.client :as client]
   [org.httpkit.server :as server]
   [reitit.http :as http]
   [reitit.interceptor.sieppari :as sieppari]
   [reitit.ring :as ring]
   [s-exp.drip :as drip]
   [tick.core :as t]))

(use-fixtures :each tc/with-released-test-connections)

(defn with-public-http [f]
  (fixtures/with-runtime
    (fn [runtime queue-client conn]
      (let [handler     (atom (constantly {:status 503 :body ""}))
            stop-server (server/run-server #(@handler %) {:ip "127.0.0.1" :port 0})
            url         (str "http://127.0.0.1:" (:local-port (meta stop-server)))
            member-id   (random-uuid)
            code        (str (random-uuid))
            system      {:datomic    {:conn conn}                                           :frame-loop runtime :job-queue {:client queue-client}
                         :i18n-langs (i18n/read-langs)
                         :env        {:app-base-url url :ig/system {:app.ig/profile :test}}}]
        (try
          (reset! handler
                  (http/ring-handler
                   (http/router
                    ["" {:coercion     (interceptors/default-coercion)
                         :muuntaja     interceptors/formats-instance
                         :interceptors (into (interceptors/default-reitit-interceptors system)
                                             [(interceptors/system-interceptor system)
                                              (interceptors/datomic-interceptor system)])}
                     (routes/unauthenticated-routes)
                     (icons/routes nil)])
                   (ring/create-resource-handler {:path "/"})
                   {:executor sieppari/executor}))
          (writer/call! (:write-runner runtime)
                        #(deref (d/transact conn [{:member/member-id         member-id                                             :member/name             "Invitation Browser"
                                                   :member/username          "invitation.browser"                                  :member/email            "invitation-browser@example.test"
                                                   :member/invite-code       code                                                  :member/invite-status    :member.invite.status/pending
                                                   :member/invite-generation 1                                                     :member/invite-status-at (t/inst)
                                                   :member/invite-expires-at (t/inst (t/>> (t/instant) (t/new-duration 1 :hours)))}])))
          (f {:url url :runtime runtime :client queue-client :conn conn :member-id member-id :code code :system system})
          (finally (stop-server)))))))

(deftest native-post-only-admits-and-redirects-to-the-live-status-page
  (with-public-http
    (fn [{:keys [url runtime client conn member-id code]}]
      (let [location (str "/invite-accept?invite-code=" (util/url-encode code))
            response @(client/post (str url "/invite-accept")
                                   {:form-params {:invite-code code} :follow-redirects false})]
        (is (= 303 (:status response)))
        (is (= location (get-in response [:headers :location])))
        (is (= :member.invite.status/accepting (:status (domain/invitation-state (d/db conn) member-id))))
        (writer/call! (:write-runner runtime) (constantly nil))
        (is (= 1 (count (drip/list-jobs client {}))))
        (is (= 200 (:status @(client/get (str url location)))))
        (is (= 200 (:status @(client/get (str url "/js/invitation-status.js")))))
        (is (= 303 (:status @(client/post (str url "/invite-accept")
                                          {:form-params {:invite-code code} :follow-redirects false}))))
        (is (= 1 (count (drip/list-jobs client {}))))))))

(deftest invalid-capabilities-and-closed-writer-do-not-start-setup
  (with-public-http
    (fn [{:keys [url runtime client conn member-id code]}]
      (is (= 404 (:status @(client/get (str url "/invite-status?invite-code=invalid")))))
      (is (= 200 (:status @(client/post (str url "/invite-accept")
                                        {:form-params {:invite-code "invalid"} :follow-redirects false}))))
      (is (empty? (drip/list-jobs client {})))
      (writer/close! (:write-runner runtime))
      (is (= 503 (:status @(client/post (str url "/invite-accept")
                                        {:form-params {:invite-code code} :follow-redirects false}))))
      (is (= :member.invite.status/pending (:status (domain/invitation-state (d/db conn) member-id))))
      (is (empty? (drip/list-jobs client {}))))))

(deftest status-fragment-shows-progress-success-and-safe-recovery
  (let [creating (views/fragment {:status :creating} "bearer" "/login")
        accepted (views/fragment {:status :accepted} "bearer" "/login?login_hint=member")
        pending  (views/fragment {:status :pending} "bearer" "/login")
        help     (views/fragment {:status :operator-required} "bearer" "/login")]
    (is (some? (l/select-one :wa-spinner creating)))
    (is (nil? (l/select-one button/Button creating)))
    (is (= "/login?login_hint=member" (:href (second (l/select-one button/Button accepted)))))
    (is (some? (l/select-one "#invitation-success-mark" accepted)))
    (is (nil? (l/select-one :wa-spinner accepted)))
    (is (= {:method "POST" :action "/invite-accept"} (second (l/select-one :form pending))))
    (is (= "bearer" (:value (second (l/select-one :input pending)))))
    (is (nil? (l/select-one :form help)))
    (is (nil? (l/select-one button/Button help)))))
