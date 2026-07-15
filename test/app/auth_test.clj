(ns app.auth-test
  (:require
   [app.auth :as auth]
   [app.i18n :as i18n]
   [buddy.sign.jwt :as jwt]
   [clojure.test :refer [deftest is]])
  (:import
   [java.security KeyPairGenerator]))

(defn- signed-token [claims private-key]
  (jwt/sign claims private-key {:alg :rs256}))

(def test-tr (i18n/tr-with (i18n/read-langs) [i18n/default-locale]))

(deftest build-oauth2-session-stores-keycloak-subject
  (let [key-pair (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                     (.initialize 2048)))
        private-key (.getPrivate key-pair)
        public-key (.getPublic key-pair)
        access-token (signed-token {:sub "kc-123"
                                    :preferred_username "casey"
                                    :email "casey@example.com"
                                    :groups ["/Mitglieder"]
                                    :realm_access {:roles ["admin" "unknown"]}}
                                   private-key)
        id-token (signed-token {:sub "kc-123"} private-key)]
    (is (= {:session/username "casey"
            :session/email "casey@example.com"
            :session/keycloak-id "kc-123"
            :session/access-token access-token
            :session/refresh-token "refresh-token"
            :session/id-token id-token
            :session/groups #{"/Mitglieder"}
            :session/roles #{:admin}}
           (auth/build-oauth2-session {:access_token access-token
                                       :refresh_token "refresh-token"
                                       :id_token id-token}
                                      public-key
                                      #{:admin})))))

(deftest require-authenticated-user-shows-identity-mismatch
  (let [ctx ((:enter auth/require-authenticated-user)
             {:request {:uri "/"
                        :query-string nil
                        :tr test-tr
                        :session {:session/email "new@example.com"}}})]
    (is (= {:status 403}
           (select-keys (:response ctx) [:status])))
    (is (re-find #"new@example.com" (get-in ctx [:response :body])))))

(deftest require-authenticated-user-keeps-datastar-login-navigation-same-origin
  (let [enter    (:enter auth/require-authenticated-user)
        request  {:uri "/account-settings"
                  :query-string "tab=profile"}
        document-response
        (:response (enter {:request request}))
        datastar-response
        (:response
         (enter {:request
                 (assoc request :headers {"datastar-request" "true"})}))]
    (is (= {:document
            {:status 302
             :headers
             {"location"
              "/login?next=%2Faccount-settings%3Ftab%3Dprofile"}
             :body ""}
            :datastar
            {:status 401
             :headers {"Cache-Control" "no-store"}
             :body ""}}
           {:document document-response
            :datastar datastar-response}))))
