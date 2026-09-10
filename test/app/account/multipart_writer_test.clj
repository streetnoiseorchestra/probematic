(ns app.account.multipart-writer-test
  (:require
   [app.account.routes :as account.routes]
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
   [reitit.interceptor.sieppari :as sieppari]))

(use-fixtures :each tc/with-released-test-connections)

(deftest multipart-route-preserves-writer-rejection-and-deletes-the-parsed-upload
  (writer-fixtures/with-runtime
    (fn [runtime _ conn]
      (let [member-id   (random-uuid)
            parsed-file (atom nil)
            system      {:datomic    {:conn conn}
                         :frame-loop runtime
                         :nexus      (nexus/nexus)
                         :env        {:ig/system {:app.ig/profile :test}}
                         :i18n-langs (i18n/read-langs)}
            handler     (http/ring-handler
                         (http/router
                          ["" {:coercion (interceptors/default-coercion)
                               :muuntaja interceptors/formats-instance
                               :interceptors
                               (conj (interceptors/default-reitit-interceptors system)
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
          (writer/close! (:write-runner runtime))
          (let [before-t (d/basis-t (d/db conn))
                response @(client/post
                           (str "http://127.0.0.1:" (:local-port (meta stop-server)) "/account-settings/profile/save")
                           {:timeout   10000
                            :multipart [{:name "tab-id" :content (str (random-uuid))}
                                        {:name "name" :content "Ada"}
                                        {:name "nick" :content ""}
                                        {:name "email" :content "ada@example.test"}
                                        {:name "username" :content "ada_byron"}
                                        {:name "avatar-removed?" :content "true"}
                                        {:name    "avatar"                                                  :filename "avatar.jpg" :content-type "image/jpeg"
                                         :content (io/file "resources/public/img/tuba-robot-boat-1000.jpg")}]})]
            (is (= {:status 503 :body ""} (select-keys response [:status :body :error])))
            (is (and @parsed-file (not (fs/exists? @parsed-file))))
            (is (= before-t (d/basis-t (d/db conn))))
            (is (= "Before" (:member/name (d/entity (d/db conn) [:member/member-id member-id])))))
          (finally
            (stop-server)
            (when-let [file @parsed-file]
              (fs/delete-if-exists file))))))))
