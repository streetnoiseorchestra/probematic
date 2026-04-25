(ns app.members.detail.actions-test
  (:require
   [app.members.detail.actions :as actions]
   [app.settings.action-support :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn new-system []
  (tc/new-system "members-detail-actions"))

(defn state-for [{:keys [conn member-id]}]
  {:db                (d/db conn)
   :current-member-id member-id})

(defn seed-section! [conn section-name]
  @(d/transact conn [{:section/name section-name
                      :section/active? true
                      :section/position 0}]))

(defn seed-member! [conn member]
  @(d/transact conn [member]))

(defn tr [path & [args]]
  (case path
    [:member/name] "Name"
    [:Email] "Email"
    [:Phone] "Phone"
    [:section] "Section"
    [:error/is-required] (format "%s is required." (first args))
    [:error/member-phone-format] "Phone format is invalid."
    [:error/member-unique-email] "A member already has that email address"
    [:error/member-unique-nick] "A member already has that nick"
    [:error/member-unique-phone] "A member already has that phone number"
    [:error/member-section-invalid] "Please choose a valid section."
    (str path)))

(defn contact-signals [member-id overrides]
  {:member-detail
   {:contact
    (merge {:member-id    (str member-id)
            :name         "Alice Admin"
            :nick         "ally"
            :email        "ALICE@example.com  "
            :phone        "+43 677 123456"
            :section-name "Trumpets"
            :active       true}
           overrides)}})

(deftest open-contact-edit-action-test
  (testing "opens the contact edit form with current member data"
    (let [{:keys [conn] :as system} (new-system)
          member-id                 (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id member-id
                          :member/name "Alice Admin"
                          :member/nick "ally"
                          :member/email "alice@example.com"
                          :member/phone "+43677123456"
                          :member/section [:section/name "Trumpets"]
                          :member/active? true})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :contact]
               {:member-id    (str member-id)
                :name         "Alice Admin"
                :nick         "ally"
                :email        "alice@example.com"
                :phone        "+43677123456"
                :section-name "Trumpets"
                :active       true
                :error        {}}]]
             (actions/open-contact-edit-action
              (state-for system)
              {:targetid (str member-id)}))))))

(deftest close-contact-edit-action-test
  (is (= [support/clear-loading
          [:app.datastar/assoc-state [:member-detail :contact] false]]
         (actions/close-contact-edit-action {} {}))))

(deftest update-contact-action-test
  (testing "updates contact fields and closes the form"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id edited-member-id
                          :member/name "Alice Old"
                          :member/nick "old"
                          :member/email "old@example.com"
                          :member/phone "+431111111"
                          :member/section [:section/name "Trumpets"]
                          :member/active? false})
      (is (= [[:db/transact [{:db/id            [:member/member-id edited-member-id]
                              :member/name      "Alice Admin"
                              :member/nick      "ally"
                              :member/email     "alice@example.com"
                              :member/phone     "+43677123456"
                              :member/section   [:section/name "Trumpets"]
                              :member/active?   true}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? true}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :contact] false]]
             (actions/update-contact-action
              (assoc (state-for system) :tr tr)
              (contact-signals edited-member-id {}))))))

  (testing "retracts a cleared nick"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id edited-member-id
                          :member/name "Alice Old"
                          :member/nick "old"
                          :member/email "old@example.com"
                          :member/phone "+431111111"
                          :member/section [:section/name "Trumpets"]
                          :member/active? true})
      (is (= [[:db/transact [{:db/id            [:member/member-id edited-member-id]
                              :member/name      "Alice Admin"
                              :member/nick      nil
                              :member/email     "alice@example.com"
                              :member/phone     "+43677123456"
                              :member/section   [:section/name "Trumpets"]
                              :member/active?   true}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? true}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :contact] false]]
             (actions/update-contact-action
              (assoc (state-for system) :tr tr)
              (contact-signals edited-member-id {:nick "  "}))))))

  (testing "requests keycloak sync when keycloak-backed fields change"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id edited-member-id
                          :member/name "Alice Old"
                          :member/email "old@example.com"
                          :member/phone "+431111111"
                          :member/keycloak-id "kc-123"
                          :member/section [:section/name "Trumpets"]
                          :member/active? false})
      (is (= [[:db/transact [{:db/id            [:member/member-id edited-member-id]
                              :member/name      "Alice Admin"
                              :member/nick      "ally"
                              :member/email     "alice@example.com"
                              :member/phone     "+43677123456"
                              :member/section   [:section/name "Trumpets"]
                              :member/active?   true}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? true}]
              [:app.members/update-keycloak-meta edited-member-id]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :contact] false]]
             (actions/update-contact-action
              (assoc (state-for system) :tr tr)
              (contact-signals edited-member-id {}))))))

  (testing "returns validation errors"
    (let [{:keys [conn] :as system} (new-system)
          edited-member-id          (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id edited-member-id})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :contact]
               {:member-id    (str edited-member-id)
                :name         ""
                :nick         ""
                :email        ""
                :phone        "123"
                :section-name "Unknown"
                :active       true
                :error        {:name         {:error "Name is required."}
                               :email        {:error "Email is required."}
                               :phone        {:error "Phone format is invalid."}
                               :section-name {:error "Please choose a valid section."}}}]]
             (actions/update-contact-action
              (assoc (state-for system) :tr tr)
              (contact-signals edited-member-id {:name ""
                                                 :nick ""
                                                 :email ""
                                                 :phone "123"
                                                 :section-name "Unknown"}))))))

  (testing "returns uniqueness errors for other members"
    (let [{:keys [conn] :as system} (new-system)
          edited-member-id          (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id edited-member-id
                          :member/email "old@example.com"
                          :member/phone "+431111111"})
      (seed-member! conn {:member/member-id (random-uuid)
                          :member/name "Existing Member"
                          :member/nick "ally"
                          :member/email "alice@example.com"
                          :member/phone "+43677123456"
                          :member/section [:section/name "Trumpets"]
                          :member/active? true})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :contact]
               {:member-id    (str edited-member-id)
                :name         "Alice Admin"
                :nick         "ally"
                :email        "alice@example.com"
                :phone        "+43677123456"
                :section-name "Trumpets"
                :active       true
                :error        {:email {:error "A member already has that email address"}
                               :nick  {:error "A member already has that nick"}
                               :phone {:error "A member already has that phone number"}}}]]
             (actions/update-contact-action
              (assoc (state-for system) :tr tr)
              (contact-signals edited-member-id {})))))))
