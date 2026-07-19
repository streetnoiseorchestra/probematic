(ns app.keycloak-test
  (:require
   [app.keycloak :as keycloak]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.lang.reflect InvocationHandler Method Proxy]
   [java.net SocketTimeoutException URI]
   [jakarta.ws.rs WebApplicationException]
   [jakarta.ws.rs.core Response]
   [org.keycloak.admin.client.resource UsersResource]
   [org.keycloak.representations.idm UserRepresentation]))

(def ^:private member-id
  "230d7ed6-bff7-421b-969e-6b19f38fbf13")

(def ^:private attempt-attributes
  {"probematic-member-id" [member-id]
   "probematic-invite-generation" ["4"]})

(defn- user-representation
  [{:keys [id username email enabled? attributes]}]
  (doto (UserRepresentation.)
    (.setId id)
    (.setUsername username)
    (.setEmail email)
    (.setEnabled enabled?)
    (.setAttributes attributes)))

(defn- users-resource [pages requests]
  (Proxy/newProxyInstance
   (.getClassLoader UsersResource)
   (into-array Class [UsersResource])
   (reify InvocationHandler
     (invoke [_ _ method args]
       (let [^Method method method
             [first-result max-results enabled brief? query] (vec args)]
         (when-not (= "searchByAttributes" (.getName method))
           (throw (UnsupportedOperationException. (.getName method))))
         (swap! requests conj
                {:first-result first-result
                 :max-results max-results
                 :enabled enabled
                 :brief? brief?
                 :query query})
         (get pages first-result []))))))

(deftest member-match-transactions-use-the-invitation-aware-link-guard-test
  (let [member-id (random-uuid)]
    (is (= [[:member/set-keycloak-id member-id "keycloak-user"]
            [:db/add
             [:member/member-id member-id]
             :member/username
             "alice"]]
           (vec
            (keycloak/match-txs
             [{:member/member-id member-id
               :member/keycloak-id "keycloak-user"
               :member/username "alice"}]))))))

(deftest user-representation-normalization-test
  (let [attributes attempt-attributes]
    (is (= {:id "user-alice"
            :username "alice"
            :email "alice@example.com"
            :enabled? false
            :attributes attributes}
           (keycloak/user-representation->keycloak-user
            (user-representation
             {:id "user-alice"
              :username "alice"
              :email "alice@example.com"
              :enabled? false
              :attributes attributes}))))))

(deftest exact-user-attribute-matching-test
  (let [expected attempt-attributes
        exact (user-representation
               {:id "exact"
                :username "alice"
                :email "alice@example.com"
                :enabled? false
                :attributes (assoc expected "unrelated" ["preserved"])})
        stale (user-representation
               {:id "stale"
                :username "alice-old"
                :email "alice-old@example.com"
                :enabled? false
                :attributes (assoc expected
                                   "probematic-invite-generation"
                                   ["3"])})
        extra-value (user-representation
                     {:id "extra-value"
                      :username "alice-extra"
                      :email "alice-extra@example.com"
                      :enabled? false
                      :attributes (assoc expected
                                         "probematic-member-id"
                                         [member-id "another-member"])})]
    (is (= [{:id "exact"
             :username "alice"
             :email "alice@example.com"
             :enabled? false
             :attributes (assoc expected "unrelated" ["preserved"])}]
           (keycloak/exact-user-matches [exact stale extra-value]
                                        expected)))))

(deftest attribute-search-queries-every-singleton-and-continues-pagination-test
  (let [requests (atom [])
        malformed (user-representation
                   {:id "malformed"
                    :username "malformed"
                    :email "malformed@example.com"
                    :enabled? false
                    :attributes (assoc attempt-attributes
                                       "probematic-member-id"
                                       [member-id "another-member"])})
        exact (user-representation
               {:id "exact"
                :username "alice"
                :email "alice@example.com"
                :enabled? false
                :attributes attempt-attributes})
        resource (users-resource {0 (vec (repeat 1000 malformed))
                                  1000 [exact]}
                                 requests)]
    (is (= [{:id "exact"
             :username "alice"
             :email "alice@example.com"
             :enabled? false
             :attributes attempt-attributes}]
           (#'keycloak/find-keycloak-users-in-resource
            resource
            attempt-attributes)))
    (is (= [{:first-result 0
             :max-results 1000
             :enabled nil
             :brief? false
             :query (str "probematic-invite-generation:4 "
                         "probematic-member-id:" member-id)}
            {:first-result 1000
             :max-results 1000
             :enabled nil
             :brief? false
             :query (str "probematic-invite-generation:4 "
                         "probematic-member-id:" member-id)}]
           @requests))))

(deftest attribute-search-detects-exact-duplicates-across-pages-test
  (let [requests (atom [])
        first-match (user-representation
                     {:id "first-match"
                      :username "alice"
                      :email "alice@example.com"
                      :enabled? false
                      :attributes attempt-attributes})
        other (user-representation
               {:id "other"
                :username "other"
                :email "other@example.com"
                :enabled? false
                :attributes {"other-attribute" ["other-value"]}})
        second-match (user-representation
                      {:id "second-match"
                       :username "alice-copy"
                       :email "alice-copy@example.com"
                       :enabled? false
                       :attributes attempt-attributes})
        first-page (into [first-match] (repeat 999 other))
        resource (users-resource {0 first-page
                                  1000 [second-match]}
                                 requests)]
    (is (= [{:id "first-match"
             :username "alice"
             :email "alice@example.com"
             :enabled? false
             :attributes attempt-attributes}
            {:id "second-match"
             :username "alice-copy"
             :email "alice-copy@example.com"
             :enabled? false
             :attributes attempt-attributes}]
           (#'keycloak/find-keycloak-users-in-resource
            resource
            attempt-attributes)))
    (is (= [0 1000]
           (mapv :first-result @requests)))))

(deftest definite-client-errors-are-rejected-test
  (let [^Response response (-> (Response/status 409) (.build))
        ^WebApplicationException exception (WebApplicationException. response)
        result (#'keycloak/rejected-on-web-application-error
                #(throw exception))]
    (is (= {:result {:outcome :rejected}
            :response-closed? true}
           {:result result
            :response-closed? (.isClosed response)}))))

(deftest ambiguous-http-errors-escape-test
  (doseq [status [302 503]]
    (testing (str status " is not a definite client rejection")
      (let [^Response response (-> (Response/status (int status)) (.build))
            ^WebApplicationException exception
            (WebApplicationException. response)
            thrown (try
                     (#'keycloak/rejected-on-web-application-error
                      #(throw exception))
                     nil
                     (catch WebApplicationException caught
                       caught))]
        (is (= {:same-exception? true
                :response-closed? true}
               {:same-exception? (identical? exception thrown)
                :response-closed? (.isClosed response)}))))))

(deftest transport-errors-escape-test
  (let [exception (SocketTimeoutException. "timed out")
        thrown (try
                 (#'keycloak/rejected-on-web-application-error
                  #(throw exception))
                 nil
                 (catch SocketTimeoutException caught
                   caught))]
    (is (identical? exception thrown))))

(deftest create-success-requires-location-user-id-test
  (testing "a valid Location yields its final path segment"
    (let [^Response response
          (-> (Response/created
               (URI. "https://keycloak.example/users/user-alice"))
              (.build))]
      (try
        (is (= "user-alice"
               (#'keycloak/response-location-id response)))
        (finally
          (.close response)))))
  (doseq [^Response response
          [(-> (Response/status 201) (.build))
           (-> (Response/created
                (URI. "https://keycloak.example/users/"))
               (.build))]]
    (testing "a missing user ID is an unknown create outcome"
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"did not include a user ID"
             (#'keycloak/response-location-id response)))
        (finally
          (.close response))))))
