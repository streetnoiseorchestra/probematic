(ns app.members.invite.actions-test
  (:require
   [app.members.invite.actions :as actions]
   [app.nexus.actions :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn new-system []
  (tc/new-system "members-invite-actions"))

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
    [:member/username] "Username"
    [:Phone] "Phone"
    [:section] "Section"
    [:error/is-required] (format "%s is required." (:field args))
    [:error/member-username-format] "Username format is invalid."
    [:error/member-phone-format] "Phone format is invalid."
    [:error/member-unique-username] "A member already has that username"
    [:error/member-unique-email] "A member already has that email address"
    [:error/member-unique-nick] "A member already has that nick"
    [:error/member-unique-phone] "A member already has that phone number"
    [:error/member-section-invalid] "Please choose a valid section."
    (str path)))

(defn submit-signals [member-id overrides]
  {:member-invite
   (merge {:member-id      (str member-id)
           :name           "Alice Admin"
           :nick           ""
           :email          "ALICE@example.com  "
           :username       "Alice.Admin"
           :phone          "+43 677 123456"
           :section-name   "Trumpets"
           :active         true
           :create-sno-id  true}
          overrides)})

(deftest submit-member-invite-action-test
  (testing "creates a member, ledger, and invitation when create-sno-id is enabled"
    (let [{:keys [member-id] :as system} (new-system)
          new-member-id                 (random-uuid)]
      (seed-section! (:conn system) "Trumpets")
      (is (= [[:db/transact [{:db/id            "new-member"
                              :member/member-id new-member-id
                              :member/name      "Alice Admin"
                              :member/email     "alice@example.com"
                              :member/username  "alice.admin"
                              :member/phone     "+43677123456"
                              :member/section   [:section/name "Trumpets"]
                              :member/active?   true}
                             {:db/id            "new-ledger"
                              :ledger/ledger-id :db/gen-uuid
                              :ledger/owner     "new-member"
                              :ledger/balance   0}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              [:app.members/send-user-invitation new-member-id]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/redirect (str "/member/" new-member-id)]]]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals new-member-id {}))))))

  (testing "creates a member without an invitation when create-sno-id is disabled and blank nick is omitted"
    (let [{:keys [member-id] :as system} (new-system)
          new-member-id                 (random-uuid)]
      (seed-section! (:conn system) "Trumpets")
      (is (= [[:db/transact [{:db/id            "new-member"
                              :member/member-id new-member-id
                              :member/name      "Alice Admin"
                              :member/email     "alice@example.com"
                              :member/username  "alice.admin"
                              :member/phone     "+43677123456"
                              :member/section   [:section/name "Trumpets"]
                              :member/active?   false}
                             {:db/id            "new-ledger"
                              :ledger/ledger-id :db/gen-uuid
                              :ledger/owner     "new-member"
                              :ledger/balance   0}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/redirect (str "/member/" new-member-id)]]]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals new-member-id {:active false
                                             :create-sno-id false}))))))

  (testing "returns required field errors"
    (let [{:keys [conn] :as system} (new-system)
          new-member-id            (random-uuid)]
      (seed-section! conn "Trumpets")
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:member-id     (str new-member-id)
                :name          ""
                :nick          ""
                :email         ""
                :username      ""
                :phone         ""
                :section-name  ""
                :active        true
                :create-sno-id true
                :error         {:name         {:error "Name is required."}
                                :email        {:error "Email is required."}
                                :username     {:error "Username is required."}
                                :phone        {:error "Phone is required."}
                                :section-name {:error "Section is required."}}}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals new-member-id {:name ""
                                             :email ""
                                             :username ""
                                             :phone ""
                                             :section-name ""}))))))

  (testing "returns format validation errors"
    (let [{:keys [conn] :as system} (new-system)
          new-member-id            (random-uuid)]
      (seed-section! conn "Trumpets")
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:member-id     (str new-member-id)
                :name          "Alice Admin"
                :nick          ""
                :email         "alice@example.com"
                :username      "bad user"
                :phone         "123"
                :section-name  "Trumpets"
                :active        true
                :create-sno-id true
                :error         {:username {:error "Username format is invalid."}
                                :phone    {:error "Phone format is invalid."}}}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals new-member-id {:username "bad user"
                                             :phone "123"}))))))

  (testing "returns uniqueness errors for duplicate member attributes"
    (let [{:keys [conn] :as system} (new-system)
          new-member-id            (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id (random-uuid)
                          :member/name "Existing Member"
                          :member/nick "alice"
                          :member/email "alice@example.com"
                          :member/username "alice.admin"
                          :member/phone "+43677123456"
                          :member/section [:section/name "Trumpets"]
                          :member/active? true})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:member-id     (str new-member-id)
                :name          "Alice Admin"
                :nick          "alice"
                :email         "alice@example.com"
                :username      "alice.admin"
                :phone         "+43677123456"
                :section-name  "Trumpets"
                :active        true
                :create-sno-id true
                :error         {:email    {:error "A member already has that email address"}
                                :username {:error "A member already has that username"}
                                :nick     {:error "A member already has that nick"}
                                :phone    {:error "A member already has that phone number"}}}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals new-member-id {:nick "alice"}))))))

  (testing "returns a validation error when the section does not exist"
    (let [system        (new-system)
          new-member-id (random-uuid)]
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:member-id     (str new-member-id)
                :name          "Alice Admin"
                :nick          ""
                :email         "alice@example.com"
                :username      "alice.admin"
                :phone         "+43677123456"
                :section-name  "Unknown"
                :active        true
                :create-sno-id true
                :error         {:section-name {:error "Please choose a valid section."}}}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals new-member-id {:section-name "Unknown"})))))))
