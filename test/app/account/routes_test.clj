(ns app.account.routes-test
  (:require
   [app.account.test-support :as support]
   [app.auth :as auth]
   [app.datastar :as datastar]
   [app.i18n :as i18n]
   [app.routes :as app-routes]
   [app.test-common :as tc]
   [app.urls]
   [clojure.test :refer [deftest is testing]]
   [reitit.core :as r]
   [reitit.http :as http]))

(def expected-routes
  [{:path "/account-settings"
    :page-name :app.account.routes/index}
   {:path "/account-settings/profile"
    :page-name :app.account.routes/profile}
   {:path "/account-settings/preferences"
    :page-name :app.account.routes/preferences}
   {:path "/account-settings/notifications"
    :page-name :app.account.routes/notifications}
   {:path "/account-settings/on-a-break"
    :page-name :app.account.routes/on-a-break}])

(def expected-links
  {'app.urls/link-account-settings      "/account-settings"
   'app.urls/link-account-profile       "/account-settings/profile"
   'app.urls/link-account-preferences   "/account-settings/preferences"
   'app.urls/link-account-notifications "/account-settings/notifications"
   'app.urls/link-account-break         "/account-settings/on-a-break"})

(def test-system
  {:nexus {:nexus/actions {}}
   :datomic {:conn ::conn}
   :filestore ::filestore})

(defn authenticated-branch [route-tree]
  (some
   (fn [node]
     (when (and (vector? node)
                (map? (second node))
                (some #{auth/require-authenticated-user}
                      (:interceptors (second node))))
       node))
   (tree-seq coll? seq route-tree)))

(deftest account-routes-expose-five-stable-pages
  (let [routes-fn (support/public-fn 'app.account.routes/routes)]
    (is (fn? routes-fn) "app.account.routes/routes should exist")
    (when routes-fn
      (let [route-tree (routes-fn test-system)
            router     (http/router ["" route-tree])]
        (is (= :app/account-settings
               (get-in route-tree [1 :app.route/name])))
        (is (nil? (get-in route-tree [1 :app.auth/roles])))
        (is (= expected-routes
               (mapv (fn [{:keys [path]}]
                       {:path      path
                        :page-name (get-in (r/match-by-path router path)
                                           [:data :name])})
                     expected-routes)))
        (is (every? #(nil? (r/match-by-path router (str (:path %) "/")))
                    expected-routes))))))

(deftest account-routes-include-multipart-save-and-owned-avatar-delivery
  (let [routes-fn (support/public-fn 'app.account.routes/routes)]
    (is (fn? routes-fn))
    (when routes-fn
      (let [router (http/router ["" (routes-fn test-system)])]
        (is (= :app.account.routes/save-profile
               (get-in (r/match-by-path
                        router
                        "/account-settings/profile/save")
                       [:data :name])))
        (is (= :app.account.routes/member-avatar
               (get-in (r/match-by-path
                        router
                        "/member-avatar/11111111-1111-4111-8111-111111111111/160")
                       [:data :name])))))))

(deftest multipart-profile-route-adapts-form-data-to-the-qualified-action
  (let [handler (support/public-fn
                 'app.account.routes/profile-save-handler)
        tempfile (java.io.File. "/tmp/account-route-avatar.png")]
    (is (fn? handler) "app.account.routes/profile-save-handler should exist")
    (when handler
      (is (= [[:app.account.actions/save-profile
               {:account-profile
                {:name "Ada Byron"
                 :nick "Countess"
                 :email "ada@example.test"
                 :username "ada_byron"
                 :phone "+436601234567"
                 :current-status "Rehearsing"
                 :date-of-birth "1815-12-10"
                 :avatar-removed? true}
                :avatar-upload
                {:filename "avatar.png"
                 :mime-type "image/png"
                 :size 2048
                 :tempfile tempfile}}]]
             (handler
              {:parameters
               {:multipart
                {:name "Ada Byron"
                 :nick "Countess"
                 :email "ada@example.test"
                 :username "ada_byron"
                 :phone "+436601234567"
                 :current-status "Rehearsing"
                 :date-of-birth "1815-12-10"
                 :avatar-removed? "true"
                 :avatar {:filename "avatar.png"
                          :content-type "image/png"
                          :size 2048
                          :tempfile tempfile}}}})))
      (testing "only the form's true string marks the avatar for removal"
        (is (false?
             (get-in
              (handler
               {:parameters
                {:multipart {:avatar-removed? "on"}}})
              [0 1 :account-profile :avatar-removed?])))))))

(deftest account-url-helpers-match-the-public-routes
  (doseq [[qualified-symbol expected] expected-links]
    (let [link-fn (some-> (ns-resolve 'app.urls
                                      (clojure.core/symbol
                                       (name qualified-symbol)))
                          deref)]
      (is (fn? link-fn) (str qualified-symbol " should exist"))
      (when link-fn
        (is (= expected (link-fn)))))))

(deftest account-routes-are-mounted-inside-the-authenticated-branch
  (testing "The Account slice inherits authentication without adding a role gate."
    (let [{:keys [conn]} (tc/new-system "account-authenticated-routes")
          branch (authenticated-branch
                  (app-routes/routes
                   {:env {:ig/system {:app.ig/profile :test}
                          :session-config {:session-ttl-s 3600
                                           :cookie-attrs {}}}
                    :i18n-langs (i18n/read-langs)
                    :datomic {:conn conn}
                    :filestore {}
                    :redis {}
                    :datastar-refresh-mult
                    {::datastar/refresh-mult ::refresh-mult}}))
          paths  (into #{}
                       (keep #(when (and (vector? %) (string? (first %)))
                                (first %)))
                       (tree-seq coll? seq branch))]
      (is (some? branch))
      (is (every? paths (map :path expected-routes))))))
