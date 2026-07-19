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
    [:error/is-required] (format "%s is required." (first args))
    [:members/error-email-invalid] "Enter a valid email address."
    [:error/member-username-format] "Username format is invalid."
    [:error/member-phone-format] "Phone format is invalid."
    [:error/member-unique-username] "A member already has that username"
    [:error/member-unique-email] "A member already has that email address"
    [:error/member-unique-nick] "A member already has that nick"
    [:error/member-unique-phone] "A member already has that phone number"
    [:error/member-section-invalid] "Please choose a valid section."
    (str path)))

(defn submit-signals [overrides]
  {:member-invite
   (merge {:name          "Alice Admin"
           :nick          ""
           :email         "ALICE@example.com  "
           :username      "Alice.Admin"
           :phone         "+43 677 123456"
           :section-name  "Trumpets"
           :active        true
           :create-sno-id true}
          overrides)})

(defn- validate-field [state signals]
  (if-let [action (get actions/actions
                       ::actions/validate-member-invite-field)]
    (action state signals)
    ::missing-validate-member-invite-field-action))

(deftest validate-member-invite-field-action-test
  (let [{:keys [conn] :as system} (new-system)
        state    (assoc (state-for system) :tr tr)
        raw-form (:member-invite (submit-signals {}))]
    (seed-section! conn "Trumpets")

    (testing "validates each field independently"
      (doseq [{:keys [case field value error]}
              [{:case  "name is required"
                :field :name
                :value "  "
                :error {:error "Name is required."}}
               {:case  "email is required"
                :field :email
                :value "  "
                :error {:error "Email is required."}}
               {:case  "email format is checked"
                :field :email
                :value "sdfsdf"
                :error {:error "Enter a valid email address."}}
               {:case  "username is required"
                :field :username
                :value "  "
                :error {:error "Username is required."}}
               {:case  "phone is required"
                :field :phone
                :value "  "
                :error {:error "Phone is required."}}
               {:case  "section is required"
                :field :section-name
                :value "  "
                :error {:error "Section is required."}}
               {:case  "username format is checked"
                :field :username
                :value "Bad User"
                :error {:error "Username format is invalid."}}
               {:case  "phone format is checked"
                :field :phone
                :value "123"
                :error {:error "Phone format is invalid."}}
               {:case  "section existence is checked"
                :field :section-name
                :value "Unknown"
                :error {:error "Please choose a valid section."}}
               {:case  "optional blank nick is valid"
                :field :nick
                :value "  "
                :error nil}]]
        (testing case
          (is (= [[:app.datastar/assoc-state
                   [:member-invite :error field]
                   error]]
                 (validate-field
                  state
                  {:member-invite
                   (assoc raw-form
                          field value
                          :validate-field (name field)
                          :error {:phone {:error "stale client error"}})}))))))

    (testing "checks every unique member attribute after normalization"
      (seed-member! conn {:member/member-id (random-uuid)
                          :member/name      "Existing Member"
                          :member/nick      "existing"
                          :member/email     "existing@example.com"
                          :member/username  "existing.user"
                          :member/phone     "+43699123456"
                          :member/section   [:section/name "Trumpets"]
                          :member/active?   true})
      (let [state (assoc state :db (d/db conn))]
        (doseq [{:keys [case field value error]}
                [{:case  "email"
                  :field :email
                  :value "EXISTING@example.com  "
                  :error "A member already has that email address"}
                 {:case  "username"
                  :field :username
                  :value "Existing.User"
                  :error "A member already has that username"}
                 {:case  "nick"
                  :field :nick
                  :value "  existing  "
                  :error "A member already has that nick"}
                 {:case  "phone"
                  :field :phone
                  :value "+43 699 123456"
                  :error "A member already has that phone number"}]]
          (testing case
            (is (= [[:app.datastar/assoc-state
                     [:member-invite :error field]
                     {:error error}]]
                   (validate-field
                    state
                    {:member-invite
                     (assoc raw-form
                            field value
                            :validate-field (name field))})))))))

    (testing "rejects unsupported client-provided field names"
      (is (= []
             (validate-field
              state
              {:member-invite
               (assoc raw-form :validate-field "not-an-invite-field")}))))))

(deftest submit-member-invite-action-test
  (testing "delegates valid invitations to the member invitation effect"
    (let [system (new-system)]
      (seed-section! (:conn system) "Trumpets")
      (is (= [[:app.members/invite-member
               {:name          "Alice Admin"
                :nick          ""
                :email         "alice@example.com"
                :username      "alice.admin"
                :phone         "+43677123456"
                :section-name  "Trumpets"
                :active        true
                :create-sno-id true}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals {}))))))

  (testing "delegates member creation without an invitation to the same form effect"
    (let [system (new-system)]
      (seed-section! (:conn system) "Trumpets")
      (is (= [[:app.members/invite-member
               {:name          "Alice Admin"
                :nick          ""
                :email         "alice@example.com"
                :username      "alice.admin"
                :phone         "+43677123456"
                :section-name  "Trumpets"
                :active        false
                :create-sno-id false}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals {:active false
                               :create-sno-id false}))))))

  (testing "returns required field errors without restoring a client member ID"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-section! conn "Trumpets")
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:name          ""
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
              (submit-signals {:name ""
                               :email ""
                               :username ""
                               :phone ""
                               :section-name ""}))))))

  (testing "returns format validation errors"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-section! conn "Trumpets")
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:name          "Alice Admin"
                :nick          ""
                :email         "not-an-email"
                :username      "bad user"
                :phone         "123"
                :section-name  "Trumpets"
                :active        true
                :create-sno-id true
                :error         {:email    {:error "Enter a valid email address."}
                                :username {:error "Username format is invalid."}
                                :phone    {:error "Phone format is invalid."}}}]]
             (actions/submit-member-invite-action
              (assoc (state-for system) :tr tr)
              (submit-signals {:email "not-an-email"
                               :username "bad user"
                               :phone "123"}))))))

  (testing "returns uniqueness errors for duplicate member attributes"
    (let [{:keys [conn] :as system} (new-system)]
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
               {:name          "Alice Admin"
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
              (submit-signals {:nick "alice"}))))))

  (testing "returns a validation error when the section does not exist"
    (let [system (new-system)]
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-invite]
               {:name          "Alice Admin"
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
              (submit-signals {:section-name "Unknown"})))))))
