(ns app.interceptors-test
  (:require
   [app.interceptors :as interceptors]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn- seed-member! [conn member-id attrs]
  @(d/transact conn [(merge {:db/id [:member/member-id member-id]}
                            attrs)]))

(deftest current-user-interceptor-prefers-keycloak-id
  (let [{:keys [conn member-id]} (tc/new-system "current-user-keycloak")]
    (seed-member! conn member-id {:member/email "old@example.com"
                                  :member/username "casey"
                                  :member/keycloak-id "kc-123"})
    (let [interceptor (interceptors/current-user-interceptor {:datomic {:conn conn}})
          result ((:enter interceptor)
                  {:request {:session {:session/email "new@example.com"
                                       :session/keycloak-id "kc-123"}}})]
      (is (= #:member{:member-id member-id
                      :email "old@example.com"
                      :username "casey"
                      :keycloak-id "kc-123"}
             (select-keys (get-in result [:request :session :session/member])
                          [:member/member-id
                           :member/email
                           :member/username
                           :member/keycloak-id]))))))

(deftest current-user-interceptor-falls-back-to-email
  (let [{:keys [conn member-id]} (tc/new-system "current-user-email")]
    (seed-member! conn member-id {:member/email "casey@example.com"
                                  :member/username "casey"})
    (let [interceptor (interceptors/current-user-interceptor {:datomic {:conn conn}})
          result ((:enter interceptor)
                  {:request {:session {:session/email "casey@example.com"}}})]
      (is (= #:member{:member-id member-id
                      :email "casey@example.com"
                      :username "casey"}
             (select-keys (get-in result [:request :session :session/member])
                          [:member/member-id
                           :member/email
                           :member/username]))))))

(deftest current-user-interceptor-rejects-email-fallback-with-different-keycloak-id
  (let [{:keys [conn member-id]} (tc/new-system "current-user-keycloak-mismatch")]
    (seed-member! conn member-id {:member/email "casey@example.com"
                                  :member/username "casey"
                                  :member/keycloak-id "different-kc"})
    (let [interceptor (interceptors/current-user-interceptor {:datomic {:conn conn}})
          result ((:enter interceptor)
                  {:request {:session {:session/email "casey@example.com"
                                       :session/keycloak-id "kc-123"}}})]
      (is (= {:session/email "casey@example.com"
              :session/keycloak-id "kc-123"}
             (get-in result [:request :session]))))))

(deftest current-user-interceptor-leaves-session-without-member-unchanged
  (let [{:keys [conn]} (tc/new-system "current-user-missing")
        interceptor (interceptors/current-user-interceptor {:datomic {:conn conn}})
        result ((:enter interceptor)
                {:request {:session {:session/email "missing@example.com"
                                     :session/keycloak-id "missing-kc"}}})]
    (is (= {:session/email "missing@example.com"
            :session/keycloak-id "missing-kc"}
           (get-in result [:request :session])))))

(defn- apply-cache-control [ctx]
  ((:leave interceptors/cache-control-interceptor) ctx))

(deftest cache-control-interceptor-adds-cache-headers
  (testing "long-lived cache headers for static asset content types"
    (is (= "max-age=31536000,immutable,public"
           (get-in (apply-cache-control {:response {:headers {"Content-Type" "text/css; charset=utf-8"}}})
                   [:response :headers "Cache-Control"])))
    (is (= "max-age=31536000,immutable,public"
           (get-in (apply-cache-control {:response {:headers {"content-type" "application/javascript"}}})
                   [:response :headers "Cache-Control"]))))
  (testing "no-cache for non-asset content types"
    (is (= "no-cache"
           (get-in (apply-cache-control {:response {:headers {"Content-Type" "text/html; charset=utf-8"}}})
                   [:response :headers "Cache-Control"]))))
  (testing "existing cache-control header is preserved"
    (is (= "private"
           (get-in (apply-cache-control {:response {:headers {"content-type" "text/css"
                                                              "cache-control" "private"}}})
                   [:response :headers "cache-control"])))))
