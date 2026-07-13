(ns app.account.profile.views-test
  (:require
   [app.account.test-support :as support]
   [app.test-common :as tc]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]))

(defn profile-request
  ([]
   (profile-request "/user_avatar/ada/{size}/1.png"))
  ([avatar-template]
   (let [{:keys [conn member-id]} (tc/new-system "account-profile-view")]
     @(d/transact
       conn
       [(cond-> {:db/id            [:member/member-id member-id]
                 :member/name      "Ada Lovelace"
                 :member/nick      "ada"
                 :member/email     "ada@example.test"
                 :member/username  "ada_l"
                 :member/phone     "+436601234567"
                 :member/active?   true}
          avatar-template (assoc :member/avatar-template avatar-template))])
     (support/request {:db                (d/db conn)
                       :session           {:session/member
                                           {:member/member-id member-id}}}))))

(defn control [id view]
  (some-> (support/element-by-id id view) support/attrs))

(deftest profile-page-uses-the-standard-account-shell-and-toolbar-save
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page) "app.account.profile.views/page should exist")
    (when page
      (let [view     (page (profile-request))
            contract (page-shell/page-contract view {:include-header? true})]
        (is (= {:width         :standard
                :breadcrumbs   [:account-settings/title
                                :account-settings/profile-title]
                :mobile        {:href "/account-settings"
                                :label :account-settings/title}
                :actions       [{:label      :action/save
                                 :form       "account-profile-form"
                                 :type       "submit"
                                 :appearance "filled"
                                 :variant    "brand"}]
                :overflow      []
                :heading       :account-settings/profile-title}
               (select-keys contract
                            [:width :breadcrumbs :mobile :actions :overflow :heading])))
        (is (= "account-profile-form"
               (:id (support/attrs
                     (support/element-by-id "account-profile-form" view)))))
        (is (= :main (first view)))
        (is (some? (l/select-one page-surface/PageSurface view)))))))

(deftest profile-page-uses-native-controls-and-complete-account-profile-signals
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page) "app.account.profile.views/page should exist")
    (when page
      (let [view (page (profile-request))]
        (is (empty? (support/elements :wa-input view)))
        (is (= {:type "text" :required true :data-bind "account-profile.name"}
               (select-keys (control "account-profile-name" view)
                            [:type :required :data-bind])))
        (is (= {:type "email" :required true :data-bind "account-profile.email"}
               (select-keys (control "account-profile-email" view)
                            [:type :required :data-bind])))
        (is (= {:type "text" :required true :data-bind "account-profile.username"}
               (select-keys (control "account-profile-username" view)
                            [:type :required :data-bind])))
        (is (= "account-profile.current-status"
               (:data-bind (control "account-profile-current-status" view))))
        (is (= {:type "date"
                :autocomplete "bday"
                :data-bind "account-profile.date-of-birth"}
               (select-keys (control "account-profile-date-of-birth" view)
                            [:type :autocomplete :data-bind])))
        (is (= {:type "file" :accept "image/png,image/jpeg,image/webp"}
               (select-keys (control "account-profile-avatar" view)
                            [:type :accept])))
        (is (= #{:app.account.actions/validate-profile-field
                 :app.account.actions/stage-avatar
                 :app.account.actions/remove-avatar
                 :app.account.actions/save-profile}
               (support/actions-in view)))))))

(deftest profile-page-preserves-browser-preview-and-ends-with-security-guidance
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page) "app.account.profile.views/page should exist")
    (when page
      (let [view          (page (profile-request))
            empty-view    (page (profile-request nil))
            preview      (control "account-profile-avatar-preview" view)
            security-link (some #(when (= "https://id.streetnoise.at/realms/sno/account"
                                          (:href (support/attrs %)))
                                   (support/attrs %))
                                (support/elements :a view))]
        (is (= "src class" (:data-preserve-attr preview)))
        (is (= {:target "_blank" :rel "noopener"}
               (select-keys security-link [:target :rel])))
        (is (contains? (support/translation-keys empty-view)
                       :account-settings/avatar-empty-callout))
        (is (contains? (support/translation-keys view)
                       :account-settings/avatar-replace))
        (is (contains? (support/translation-keys view)
                       :account-settings/login-security-title))))))

(deftest profile-security-copy-uses-the-configured-instance-name
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page) "app.account.profile.views/page should exist")
    (when page
      (let [view (page (assoc-in (profile-request)
                                 [:system :env :name]
                                 "The Test Band"))]
        (doseq [message-id [:account-settings/login-security-description
                            :account-settings/login-security-link]]
          (is (= {:instance-name "The Test Band"}
                 (nth (support/translation-node message-id view) 2))))))))
