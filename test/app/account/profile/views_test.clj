(ns app.account.profile.views-test
  (:require
   [app.account.test-support :as support]
   [app.test-common :as tc]
   [app.ui2.card :as card]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [lookup.core :as l]))

(def legacy-template "/user_avatar/ada/{size}/1.png")

(def keycloak-account-url
  "https://identity.example.test/realms/test-realm/account")

(defn profile-request
  ([]
   (profile-request {}))
  ([options]
   (let [{:keys [conn member-id]} (tc/new-system "account-profile-view")
         avatar-template (if (contains? options :avatar-template)
                           (:avatar-template options)
                           legacy-template)]
     @(d/transact
       conn
       [(cond-> {:db/id [:member/member-id member-id]
                 :member/name "Ada Lovelace"
                 :member/nick "ada"
                 :member/email "ada@example.test"
                 :member/username "ada_l"
                 :member/phone "+436601234567"
                 :member/active? true}
          avatar-template
          (assoc :member/avatar-template avatar-template)

          (:managed-avatar? options)
          (assoc :member/avatar
                 {:image/image-id (random-uuid)
                  :image/width 160
                  :image/height 160}))])
     (support/request
      {:db (d/db conn)
       :system {:env {:name "Test Instance"
                      :keycloak {:auth-server-url
                                 "https://identity.example.test/"
                                 :realm "test-realm"}}}
       :session {:session/member {:member/member-id member-id}}}))))

(defn control [id view]
  (some-> (support/element-by-id id view) support/attrs))

(deftest profile-page-uses-the-standard-account-shell-and-multipart-save
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page) "app.account.profile.views/page should exist")
    (when page
      (let [view (page (profile-request))
            contract (page-shell/page-contract view {:include-header? true})
            form-attrs (control "account-profile-form" view)]
        (is (= {:width :standard
                :breadcrumbs [:account-settings/title
                              :account-settings/profile-title]
                :mobile {:href "/account-settings"
                         :label :account-settings/title}
                :actions [{:label :action/save
                           :form "account-profile-form"
                           :type "submit"
                           :appearance "filled"
                           :variant "brand"}]
                :overflow []
                :heading nil
                :subtitle nil}
               (select-keys contract
                            [:width :breadcrumbs :mobile :actions :overflow
                             :heading :subtitle])))
        (is (= {:method "post"
                :action "/account-settings/profile/save"
                :enctype "multipart/form-data"}
               (select-keys form-attrs [:method :action :enctype])))
        (is (re-find #"contentType: 'form'" (:data-on:submit form-attrs)))
        (is (re-find #"value = \$tab-id" (:data-on:submit form-attrs)))
        (is (= :main (first view)))
        (is (some? (l/select-one page-surface/PageSurface view)))))))

(deftest profile-page-uses-native-named-controls-and-real-file-input
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page))
    (when page
      (let [view (page (profile-request))]
        (is (empty? (support/elements :wa-input view)))
        (is (= {:type "text"
                :name "name"
                :required true
                :data-bind "account-profile.name"}
               (select-keys (control "account-profile-name" view)
                            [:type :name :required :data-bind])))
        (is (= {:type "email"
                :name "email"
                :required true
                :data-bind "account-profile.email"}
               (select-keys (control "account-profile-email" view)
                            [:type :name :required :data-bind])))
        (is (= {:type "date"
                :name "date-of-birth"
                :autocomplete "bday"
                :data-bind "account-profile.date-of-birth"}
               (select-keys (control "account-profile-date-of-birth" view)
                            [:type :name :autocomplete :data-bind])))
        (is (= {:type "file"
                :name "avatar"
                :accept "image/png,image/jpeg,image/webp"}
               (select-keys (control "account-profile-avatar" view)
                            [:type :name :accept])))
        (is (= {:type "hidden"
                :name "avatar-removed?"
                :data-bind "account-profile.avatar-removed?"}
               (select-keys (control "account-profile-avatar-removed" view)
                            [:type :name :data-bind])))
        (is (= {:type "hidden"
                :name "tab-id"}
               (select-keys (control "account-profile-tab-id" view)
                            [:type :name :data-bind])))
        (is (= #{:app.account.actions/validate-profile-field
                 :app.account.actions/stage-avatar}
               (support/actions-in view)))))))

(deftest profile-page-combines-all-profile-content-in-one-unheaded-card
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page))
    (when page
      (let [view (page (profile-request))
            cards (l/select card/Card view)
            profile-card (first cards)
            security-link
            (some #(when (= keycloak-account-url
                            (:href (support/attrs %)))
                     %)
                  (support/elements :a profile-card))]
        (is (= 1 (count cards)))
        (is (empty? (l/select "[slot=header]" profile-card)))
        (is (some? (support/element-by-id
                    "account-profile-avatar-preview"
                    profile-card)))
        (is (some? (support/element-by-id "account-profile-name" profile-card)))
        (is (some? security-link))))))

(deftest profile-edit-ignores-legacy-avatar-until-managed-cutover-completes
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page))
    (when page
      (let [legacy-view (page (profile-request))
            managed-view (page (profile-request {:managed-avatar? true}))
            preview (control "account-profile-avatar-preview" legacy-view)
            security-link
            (some #(when (= keycloak-account-url
                            (:href (support/attrs %)))
                     (support/attrs %))
                  (support/elements :a legacy-view))]
        (is (= "src class" (:data-preserve-attr preview)))
        (is (= {:target "_blank" :rel "noopener"}
               (select-keys security-link [:target :rel])))
        (is (contains? (support/translation-keys legacy-view)
                       :account-settings/avatar-empty-callout))
        (is (contains? (support/translation-keys legacy-view)
                       :account-settings/avatar-choose))
        (is (not (contains? (support/translation-keys legacy-view)
                            :account-settings/avatar-remove)))
        (is (contains? (support/translation-keys managed-view)
                       :account-settings/avatar-replace))
        (is (contains? (support/translation-keys managed-view)
                       :account-settings/avatar-remove))
        (is (contains? (support/translation-keys legacy-view)
                       :account-settings/login-security-description))))))

(deftest profile-security-copy-uses-the-configured-instance-name
  (let [page (support/public-fn 'app.account.profile.views/page)]
    (is (fn? page))
    (when page
      (let [view (page (assoc-in (profile-request)
                                 [:system :env :name]
                                 "The Test Band"))]
        (doseq [message-id [:account-settings/login-security-description
                            :account-settings/login-security-link]]
          (is (= {:instance-name "The Test Band"}
                 (nth (support/translation-node message-id view) 2))))))))
