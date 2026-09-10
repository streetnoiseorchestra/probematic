(ns app.account.multipart-writer-test
  (:require
   [app.account.effects-test :as profile-fixtures]
   [app.account.routes :as account.routes]
   [app.datastar :as datastar]
   [app.game-loop :as game]
   [app.i18n :as i18n]
   [app.interceptors :as interceptors]
   [app.nexus :as nexus]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as writer-fixtures]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [org.httpkit.client :as client]
   [org.httpkit.server :as server]
   [reitit.http :as http]
   [reitit.interceptor.sieppari :as sieppari])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(defn with-profile-server [f]
  (writer-fixtures/with-runtime
    (fn [runtime _ conn]
      (profile-fixtures/with-temp-filestore
        (fn [store]
          (let [member-id   (random-uuid)
                tab-id      (str (random-uuid))
                token       (random-uuid)
                parsed-file (atom nil)
                system      {:datomic    {:conn conn}      :frame-loop runtime                              :filestore store
                             :nexus      (nexus/nexus)     :env        {:ig/system {:app.ig/profile :test}}
                             :i18n-langs (i18n/read-langs)}
                handler     (http/ring-handler
                             (http/router
                              ["" {:coercion     (interceptors/default-coercion)
                                   :muuntaja     interceptors/formats-instance
                                   :interceptors (conj (interceptors/default-reitit-interceptors system)
                                                       {:name  ::observe-upload
                                                        :enter (fn [ctx]
                                                                 (reset! parsed-file (get-in ctx [:request :parameters :multipart :avatar :tempfile]))
                                                                 ctx)})}
                               (account.routes/routes system)])
                             nil {:executor sieppari/executor})
                stop-server (server/run-server
                             (fn [request]
                               (handler (assoc request :app/session {:session/member {:member/member-id member-id}})))
                             {:ip "127.0.0.1" :port 0})]
            (try
              (writer/call! (:write-runner runtime)
                            #(deref (d/transact conn [{:member/member-id member-id :member/name "Before"}])))
              (swap! (:clients runtime) assoc tab-id {:member-id member-id :token token :revision 0 :events [] :close! (constantly nil)})
              (swap! datastar/!page-state assoc tab-id {::datastar/state-token token})
              (f {:runtime runtime                                                                                     :conn conn :member-id member-id :tab-id tab-id :parsed-file parsed-file
                  :url     (str "http://127.0.0.1:" (:local-port (meta stop-server)) "/account-settings/profile/save")})
              (finally
                (stop-server)
                (swap! datastar/!page-state dissoc tab-id)
                (when-let [file @parsed-file] (fs/delete-if-exists file))))))))))

(defn post-profile! [url tab-id fields]
  @(client/post url
                {:timeout   10000
                 :multipart (conj (mapv (fn [[k v]] {:name (name k) :content v})
                                        (merge {:tab-id   tab-id      :name            "Ada"   :nick "" :email "ada@example.test"
                                                :username "ada_byron" :avatar-removed? "false"} (dissoc fields :avatar-type)))
                                  {:name    "avatar"                                                  :filename "avatar.jpg" :content-type (get fields :avatar-type "image/jpeg")
                                   :content (io/file "resources/public/img/tuba-robot-boat-1000.jpg")})}))

(deftest multipart-admission-rejection-deletes-the-parsed-upload
  (doseq [rejection [:stopped :full :missing :foreign]]
    (with-profile-server
      (fn [{:keys [runtime conn member-id tab-id parsed-file url]}]
        (let [entered  (promise)
              release  (promise)
              before-t (d/basis-t (d/db conn))]
          (try
            (case rejection
              :stopped (do (reset! (:stopped? runtime) true) (writer/close! (:write-runner runtime)))
              :full (do
                      (.put ^ConcurrentHashMap (::game/conns runtime) :barrier (fn [_] (deliver entered true) @release))
                      (is (= true (deref entered 5000 ::timeout)))
                      (let [accepted (loop [n 0]
                                       (if (and (< n 2048) ((::game/submit! runtime) {::writer/work (constantly nil) ::writer/result (promise)}))
                                         (recur (inc n))
                                         n))]
                        (is (< 0 accepted 2048))))
              :missing nil
              :foreign (swap! (:clients runtime) assoc-in [tab-id :member-id] (random-uuid)))
            (let [response (post-profile! url (if (= :missing rejection) (str (random-uuid)) tab-id) {})]
              (is (= {:status (if (#{:missing :foreign} rejection) 409 503) :body ""}
                     (select-keys response [:status :body :error])))
              (is (and @parsed-file (not (fs/exists? @parsed-file))))
              (is (= before-t (d/basis-t (d/db conn))))
              (is (= "Before" (:member/name (d/entity (d/db conn) [:member/member-id member-id])))))
            (finally (deliver release true))))))))

(deftest multipart-acceptance-precedes-commit-and-save-feedback
  (with-profile-server
    (fn [{:keys [runtime conn member-id tab-id parsed-file url]}]
      (let [entered  (promise)
            release  (promise)
            before-t (d/basis-t (d/db conn))]
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (is (= {:status 204 :body ""} (select-keys (post-profile! url tab-id {}) [:status :body :error])))
          (is (and @parsed-file (not (fs/exists? @parsed-file))))
          (is (= before-t (d/basis-t (d/db conn))))
          (is (empty? (get-in @(:clients runtime) [tab-id :events])))
          (deliver release true)
          (writer/call! (:write-runner runtime) (constantly nil))
          (let [member (d/entity (d/db conn) [:member/member-id member-id])]
            (is (= "Ada" (:member/name member)))
            (is (some? (:member/avatar member))))
          (is (some #{[:app.datastar.sse/execute-script "window.StreetnoiseAccountAvatar.saved();"]}
                    (mapcat second (get-in @(:clients runtime) [tab-id :events]))))
          (finally (deliver release true)))))))

(deftest queued-profile-validation-preserves-input-without-committing
  (doseq [[fields error-key] [[{:name ""} :name] [{:avatar-type "image/svg+xml"} :avatar]]]
    (with-profile-server
      (fn [{:keys [runtime conn tab-id parsed-file url]}]
        (let [before-t (d/basis-t (d/db conn))]
          (is (= {:status 204 :body ""} (select-keys (post-profile! url tab-id fields) [:status :body :error])))
          (writer/call! (:write-runner runtime) (constantly nil))
          (is (and @parsed-file (not (fs/exists? @parsed-file))))
          (is (= before-t (d/basis-t (d/db conn))))
          (is (= (get fields :name "Ada") (get-in @datastar/!page-state [tab-id :account-profile :name])))
          (is (seq (get-in @datastar/!page-state [tab-id :account-profile :_error error-key])))
          (is (not-any? #{[:app.datastar.sse/execute-script "window.StreetnoiseAccountAvatar.saved();"]}
                        (mapcat second (get-in @(:clients runtime) [tab-id :events])))))))))
