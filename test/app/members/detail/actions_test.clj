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

(defn seed-discount-type! [conn {:keys [discount-type-id discount-type-name enabled?]}]
  @(d/transact conn [{:travel.discount.type/discount-type-id   discount-type-id
                      :travel.discount.type/discount-type-name discount-type-name
                      :travel.discount.type/enabled?           enabled?}]))

(defn seed-travel-discount! [conn {:keys [member-id discount-id discount-type-id expiry-date]}]
  @(d/transact conn [{:db/id                         "seed-travel-discount"
                      :travel.discount/discount-id   discount-id
                      :travel.discount/discount-type [:travel.discount.type/discount-type-id discount-type-id]
                      :travel.discount/expiry-date   expiry-date}
                     [:db/add
                      [:member/member-id member-id]
                      :member/travel-discounts
                      "seed-travel-discount"]]))

(defn seed-ledger! [conn {:keys [member-id ledger-id balance]}]
  @(d/transact conn [{:ledger/ledger-id ledger-id
                      :ledger/owner     [:member/member-id member-id]
                      :ledger/balance   balance}]))

(defn seed-ledger-entry! [conn {:keys [ledger-id entry-id amount tx-date posting-date description]}]
  @(d/transact conn [{:db/id                     "seed-ledger-entry"
                      :ledger.entry/entry-id     entry-id
                      :ledger.entry/amount       amount
                      :ledger.entry/tx-date      tx-date
                      :ledger.entry/posting-date posting-date
                      :ledger.entry/description  description}
                     [:db/add
                      [:ledger/ledger-id ledger-id]
                      :ledger/entries
                      "seed-ledger-entry"]]))

(defn tr [path & [args]]
  (case path
    [:member/name] "Name"
    [:member/nick] "Nick"
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
                :_error       {}}]]
             (actions/open-contact-edit-action
              (state-for system)
              {:targetid (str member-id)}))))))

(deftest close-contact-edit-action-test
  (is (= [support/clear-loading
          [:app.datastar/assoc-state [:member-detail :contact] false]]
         (actions/close-contact-edit-action {} {}))))

(deftest validate-contact-field-action-test
  (testing "sets a validation error for the blurred field"
    (let [{:keys [conn] :as system} (new-system)
          member-id                 (random-uuid)]
      (seed-section! conn "Trumpets")
      (is (= [[:app.datastar/merge-state
               [:member-detail :contact]
               {:member-id    (str member-id)
                :name         "Alice Admin"
                :nick         "ally"
                :email        "alice@example.com"
                :phone        "123"
                :section-name "Trumpets"
                :active       true}]
              [:app.datastar/assoc-state
               [:member-detail :contact :_error :phone]
               {:error "Phone format is invalid."}]]
             (actions/validate-contact-field-action
              (assoc (state-for system) :tr tr)
              (contact-signals member-id {:email "alice@example.com"
                                          :phone "123"
                                          :validate-field "phone"}))))))

  (testing "clears an existing error for the blurred field"
    (let [{:keys [conn] :as system} (new-system)
          member-id                 (random-uuid)]
      (seed-section! conn "Trumpets")
      (is (= [[:app.datastar/merge-state
               [:member-detail :contact]
               {:member-id    (str member-id)
                :name         "Alice Admin"
                :nick         "ally"
                :email        "alice@example.com"
                :phone        "+43677123456"
                :section-name "Trumpets"
                :active       true}]
              [:app.datastar/assoc-state
               [:member-detail :contact :_error :phone]
               nil]]
             (actions/validate-contact-field-action
              (assoc (state-for system) :tr tr)
              (contact-signals member-id {:email "alice@example.com"
                                          :phone "+43 677 123456"
                                          :validate-field "phone"
                                          :_error {:phone {:error "Phone format is invalid."}}}))))))

  (testing "ignores stale error signals while validating the current field"
    (let [{:keys [conn] :as system} (new-system)
          member-id                 (random-uuid)]
      (seed-section! conn "Trumpets")
      (is (= [[:app.datastar/merge-state
               [:member-detail :contact]
               {:member-id    (str member-id)
                :name         ""
                :nick         "ally"
                :email        "alice@example.com"
                :phone        "123"
                :section-name "Trumpets"
                :active       true}]
              [:app.datastar/assoc-state
               [:member-detail :contact :_error :name]
               {:error "Name is required."}]]
             (actions/validate-contact-field-action
              (assoc (state-for system) :tr tr)
              (contact-signals member-id {:name ""
                                          :email "alice@example.com"
                                          :phone "123"
                                          :validate-field "name"
                                          :_error {:phone {:error "Previous phone error"}}}))))))

  (testing "requires nick on blur"
    (let [{:keys [conn] :as system} (new-system)
          member-id                 (random-uuid)]
      (seed-section! conn "Trumpets")
      (is (= [[:app.datastar/merge-state
               [:member-detail :contact]
               {:member-id    (str member-id)
                :name         "Alice Admin"
                :nick         ""
                :email        "alice@example.com"
                :phone        "+43677123456"
                :section-name "Trumpets"
                :active       true}]
              [:app.datastar/assoc-state
               [:member-detail :contact :_error :nick]
               {:error "Nick is required."}]]
             (actions/validate-contact-field-action
              (assoc (state-for system) :tr tr)
              (contact-signals member-id {:nick ""
                                          :email "alice@example.com"
                                          :phone "+43 677 123456"
                                          :validate-field "nick"})))))))

(deftest set-active-tab-action-test
  (is (= [support/clear-loading
          [:app.datastar/assoc-state [:member-detail :active-tab] "ledger"]]
         (actions/set-active-tab-action
          {}
          {:targetid "ledger"})))

  (is (= [support/clear-loading
          [:app.datastar/assoc-state [:member-detail :active-tab] "insurance"]]
         (actions/set-active-tab-action
          {}
          {:member-detail {:active-tab "insurance"}})))

  (is (= [support/clear-loading
          [:app.datastar/assoc-state [:member-detail :active-tab] "discounts"]]
         (actions/set-active-tab-action
          {}
          {:targetid "unknown"}))))

(deftest travel-discount-actions-test
  (testing "opens and closes the create form"
    (let [{:keys [conn] :as system} (new-system)
          member-id                 (random-uuid)]
      (seed-member! conn {:member/member-id member-id})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :travel-discount-create]
               {:member-id         (str member-id)
                :discount-type-id  ""
                :expiry-date       ""
                :_error            {}}]]
             (actions/open-travel-discount-create-action
              (state-for system)
              {:targetid (str member-id)}))))
    (is (= [support/clear-loading
            [:app.datastar/assoc-state [:member-detail :travel-discount-create] false]]
           (actions/close-travel-discount-create-action {} {}))))

  (testing "adds a discount to the member"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)
          discount-type-id                    (random-uuid)]
      (seed-member! conn {:member/member-id edited-member-id})
      (seed-discount-type! conn {:discount-type-id   discount-type-id
                                 :discount-type-name "Klimaticket"
                                 :enabled?           true})
      (is (= [[:db/transact [{:db/id                         "new-travel-discount"
                              :travel.discount/discount-id   :db/gen-uuid
                              :travel.discount/discount-type [:travel.discount.type/discount-type-id discount-type-id]
                              :travel.discount/expiry-date   #inst "2027-01-15T00:00:00.000-00:00"}
                             [:db/add
                              [:member/member-id edited-member-id]
                              :member/travel-discounts
                              "new-travel-discount"]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :travel-discount-create] false]]
             (actions/add-travel-discount-action
              (state-for system)
              {:member-detail
               {:travel-discount-create
                {:member-id        (str edited-member-id)
                 :discount-type-id (str discount-type-id)
                 :expiry-date      "2027-01-15"}}})))))

  (testing "returns create validation errors"
    (let [{:keys [conn] :as system} (new-system)
          edited-member-id          (random-uuid)]
      (seed-member! conn {:member/member-id edited-member-id})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :travel-discount-create]
               {:member-id        (str edited-member-id)
                :discount-type-id ""
                :expiry-date      "not-a-date"
                :_error           {:discount-type-id {:error "Discount type is required."}
                                   :expiry-date      {:error "Please enter a valid expiry date."}}}]]
             (actions/add-travel-discount-action
              (state-for system)
              {:member-detail
               {:travel-discount-create
                {:member-id        (str edited-member-id)
                 :discount-type-id ""
                 :expiry-date      "not-a-date"}}})))))

  (testing "rejects an unknown discount type"
    (let [{:keys [conn] :as system} (new-system)
          edited-member-id          (random-uuid)
          discount-type-id          (random-uuid)]
      (seed-member! conn {:member/member-id edited-member-id})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :travel-discount-create]
               {:member-id        (str edited-member-id)
                :discount-type-id (str discount-type-id)
                :expiry-date      "2027-01-15"
                :_error           {:discount-type-id {:error "Please choose a valid discount type."}}}]]
             (actions/add-travel-discount-action
              (state-for system)
              {:member-detail
               {:travel-discount-create
                {:member-id        (str edited-member-id)
                 :discount-type-id (str discount-type-id)
                 :expiry-date      "2027-01-15"}}})))))

  (testing "rejects a missing member id instead of transacting against nil"
    (let [{:keys [conn] :as system} (new-system)
          discount-type-id          (random-uuid)]
      (seed-discount-type! conn {:discount-type-id   discount-type-id
                                 :discount-type-name "Klimaticket"
                                 :enabled?           true})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :travel-discount-create]
               {:member-id        ""
                :discount-type-id (str discount-type-id)
                :expiry-date      "2027-01-15"
                :_error           {:_top {:error "Member id is missing."}}}]]
             (actions/add-travel-discount-action
              (state-for system)
              {:member-detail
               {:travel-discount-create
                {:member-id        ""
                 :discount-type-id (str discount-type-id)
                 :expiry-date      "2027-01-15"}}})))))

  (testing "opens and closes expiry editing for an existing discount"
    (let [{:keys [conn] :as system} (new-system)
          edited-member-id          (random-uuid)
          discount-type-id          (random-uuid)
          discount-id               (random-uuid)]
      (seed-member! conn {:member/member-id edited-member-id})
      (seed-discount-type! conn {:discount-type-id   discount-type-id
                                 :discount-type-name "Klimaticket"
                                 :enabled?           true})
      (seed-travel-discount! conn {:member-id        edited-member-id
                                   :discount-id      discount-id
                                   :discount-type-id discount-type-id
                                   :expiry-date      #inst "2026-03-01T00:00:00.000-00:00"})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :travel-discount]
               {:discount-id discount-id
                :expiry-date "2026-03-01"
                :_error      {}}]]
             (actions/open-travel-discount-edit-action
              (state-for system)
              {:targetid (str discount-id)}))))
    (is (= [support/clear-loading
            [:app.datastar/assoc-state [:member-detail :travel-discount] false]]
           (actions/close-travel-discount-edit-action {} {}))))

  (testing "updates a discount expiry date"
    (let [{:keys [member-id] :as system} (new-system)
          discount-id                    (random-uuid)]
      (is (= [[:db/transact [[:db/add
                              [:travel.discount/discount-id discount-id]
                              :travel.discount/expiry-date
                              #inst "2027-12-31T00:00:00.000-00:00"]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :travel-discount] false]]
             (actions/update-travel-discount-action
              (state-for system)
              {:member-detail
               {:travel-discount
                {:discount-id (str discount-id)
                 :expiry-date "2027-12-31"}}})))))

  (testing "returns expiry edit validation errors"
    (let [{:keys [_conn] :as system} (new-system)
          discount-id                (random-uuid)]
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :travel-discount]
               {:discount-id discount-id
                :expiry-date ""
                :_error      {:expiry-date {:error "Expiry date is required."}}}]]
             (actions/update-travel-discount-action
              (state-for system)
              {:member-detail
               {:travel-discount
                {:discount-id (str discount-id)
                 :expiry-date ""}}})))))

  (testing "deletes a discount"
    (let [{:keys [member-id] :as system} (new-system)
          discount-id                    (random-uuid)]
      (is (= [[:db/transact [[:db/retractEntity [:travel.discount/discount-id discount-id]]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :travel-discount] false]]
             (actions/delete-travel-discount-action
              (state-for system)
              {:targetid (str discount-id)}))))))

(deftest ledger-entry-actions-test
  (testing "opens and closes debt and payment entry forms"
    (let [member-id (random-uuid)
          now       #inst "2026-04-25T10:15:00.000-00:00"]
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :ledger-entry]
               {:member-id    (str member-id)
                :tx-kind      "debt"
                :tx-direction ""
                :tx-date      "2026-04-25"
                :description  ""
                :amount       ""
                :_error       {}}]]
             (actions/open-ledger-debt-create-action
              {:now now}
              {:targetid (str member-id)})))
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :ledger-entry]
               {:member-id    (str member-id)
                :tx-kind      "payment"
                :tx-direction ""
                :tx-date      "2026-04-25"
                :description  ""
                :amount       ""
                :_error       {}}]]
             (actions/open-ledger-payment-create-action
              {:now now}
              {:targetid (str member-id)}))))
    (is (= [support/clear-loading
            [:app.datastar/assoc-state [:member-detail :ledger-entry] false]]
           (actions/close-ledger-entry-create-action {} {}))))

  (testing "adds an entry to an existing ledger"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)
          ledger-id                           (random-uuid)
          now                                 #inst "2026-04-26T06:06:51.760-00:00"]
      (seed-member! conn {:member/member-id edited-member-id})
      (seed-ledger! conn {:member-id edited-member-id
                          :ledger-id ledger-id
                          :balance   1000})
      (is (= [[:db/transact [{:db/id                     "new-ledger-entry"
                              :ledger.entry/entry-id     :db/gen-uuid
                              :ledger.entry/tx-date      "2026-04-20"
                              :ledger.entry/posting-date #inst "2026-04-26T06:06:51.760-00:00"
                              :ledger.entry/description  "Monthly dues"
                              :ledger.entry/amount       4205}
                             [:db/add [:ledger/ledger-id ledger-id] :ledger/entries "new-ledger-entry"]
                             [:db/add [:ledger/ledger-id ledger-id] :ledger/balance 5205]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :ledger-entry] false]]
             (actions/add-ledger-entry-action
              (assoc (state-for system) :now now)
              {:member-detail
               {:ledger-entry
                {:member-id    (str edited-member-id)
                 :tx-kind      "debt"
                 :tx-direction "debit"
                 :tx-date      "2026-04-20"
                 :description  "Monthly dues"
                 :amount       "42,05"}}})))))

  (testing "creates a ledger when adding an entry for a member without one"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)
          now                                 #inst "2026-04-26T06:06:51.760-00:00"]
      (seed-member! conn {:member/member-id edited-member-id})
      (is (= [[:db/transact [{:db/id                     "new-ledger-entry"
                              :ledger.entry/entry-id     :db/gen-uuid
                              :ledger.entry/tx-date      "2026-04-20"
                              :ledger.entry/posting-date #inst "2026-04-26T06:06:51.760-00:00"
                              :ledger.entry/description  "Refund"
                              :ledger.entry/amount       -1050}
                             {:db/id            "new-ledger"
                              :ledger/ledger-id :db/gen-uuid
                              :ledger/owner     [:member/member-id edited-member-id]
                              :ledger/balance   -1050
                              :ledger/entries   ["new-ledger-entry"]}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              support/clear-loading
              [:app.datastar/assoc-state [:member-detail :ledger-entry] false]]
             (actions/add-ledger-entry-action
              (assoc (state-for system) :now now)
              {:member-detail
               {:ledger-entry
                {:member-id    (str edited-member-id)
                 :tx-kind      "payment"
                 :tx-direction "credit"
                 :tx-date      "2026-04-20"
                 :description  "Refund"
                 :amount       "10.50"}}})))))

  (testing "returns validation errors"
    (let [{:keys [_conn] :as system} (new-system)
          edited-member-id          (random-uuid)]
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :ledger-entry]
               {:member-id    (str edited-member-id)
                :tx-kind      "debt"
                :tx-direction ""
                :tx-date      "not-a-date"
                :description  ""
                :amount       "0"
                :_error       {:tx-direction {:error "Choose a transaction direction."}
                               :tx-date      {:error "Please enter a valid transaction date."}
                               :description  {:error "Reference is required."}
                               :amount       {:error "Amount must be greater than zero."}}}]]
             (actions/add-ledger-entry-action
              (state-for system)
              {:member-detail
               {:ledger-entry
                {:member-id    (str edited-member-id)
                 :tx-kind      "debt"
                 :tx-direction ""
                 :tx-date      "not-a-date"
                 :description  ""
                 :amount       "0"}}})))))

  (testing "deletes an entry and adjusts the balance"
    (let [{:keys [conn member-id] :as system} (new-system)
          edited-member-id                    (random-uuid)
          ledger-id                           (random-uuid)
          entry-id                            (random-uuid)]
      (seed-member! conn {:member/member-id edited-member-id})
      (seed-ledger! conn {:member-id edited-member-id
                          :ledger-id ledger-id
                          :balance   5205})
      (seed-ledger-entry! conn {:ledger-id     ledger-id
                                :entry-id      entry-id
                                :amount        4205
                                :tx-date       "2026-04-20"
                                :posting-date  #inst "2026-04-20T12:00:00.000-00:00"
                                :description   "Monthly dues"})
      (is (= [[:db/transact [[:db/retractEntity [:ledger.entry/entry-id entry-id]]
                             [:db/add [:ledger/ledger-id ledger-id] :ledger/balance 1000]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
               {:transact-w-nils? false}]
              support/clear-loading]
             (actions/delete-ledger-entry-action
              (state-for system)
              {:targetid (str entry-id)}))))))

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

  (testing "returns validation error for a cleared nick"
    (let [{:keys [conn] :as system} (new-system)
          edited-member-id          (random-uuid)]
      (seed-section! conn "Trumpets")
      (seed-member! conn {:member/member-id edited-member-id
                          :member/name "Alice Old"
                          :member/nick "old"
                          :member/email "old@example.com"
                          :member/phone "+431111111"
                          :member/section [:section/name "Trumpets"]
                          :member/active? true})
      (is (= [support/clear-loading
              [:app.datastar/assoc-state
               [:member-detail :contact]
               {:member-id    (str edited-member-id)
                :name         "Alice Admin"
                :nick         ""
                :email        "alice@example.com"
                :phone        "+43677123456"
                :section-name "Trumpets"
                :active       true
                :_error       {:nick {:error "Nick is required."}}}]]
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
                :_error       {:name         {:error "Name is required."}
                               :nick         {:error "Nick is required."}
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
                :_error       {:email {:error "A member already has that email address"}
                               :nick  {:error "A member already has that nick"}
                               :phone {:error "A member already has that phone number"}}}]]
             (actions/update-contact-action
              (assoc (state-for system) :tr tr)
              (contact-signals edited-member-id {})))))))
