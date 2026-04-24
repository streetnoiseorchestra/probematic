(ns app.settings.actions-test
  (:require
   [app.settings.actions :as actions]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn new-system []
  (tc/new-system "settings-actions"))

(defn state-for [{:keys [conn member-id]}]
  {:db                (d/db conn)
   :current-member-id member-id})

(defn seed-discount-type! [conn {:keys [discount-type-id discount-type-name enabled?]}]
  @(d/transact conn [{:travel.discount.type/discount-type-id   discount-type-id
                      :travel.discount.type/discount-type-name discount-type-name
                      :travel.discount.type/enabled?           enabled?}]))

(defn seed-team! [conn {:keys [team-id team-name team-type]}]
  @(d/transact conn [(cond-> {:team/team-id team-id
                              :team/name    team-name}
                       team-type (assoc :team/team-type team-type))]))

(defn seed-member! [conn member-id]
  @(d/transact conn [{:member/member-id member-id}]))

(defn seed-section! [conn {:keys [section-name active? position]}]
  @(d/transact conn [(cond-> {:section/name    section-name
                              :section/active? active?}
                       position (assoc :section/position position))]))

(deftest discount-type-actions-test
  (testing "creates a new discount type"
    (let [{:keys [member-id] :as system} (new-system)]
      (is (= [[:db/transact [{:travel.discount.type/discount-type-id   :db/gen-uuid
                              :travel.discount.type/enabled?           true
                              :travel.discount.type/discount-type-name "Klimaticket"}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:discount-type-create] false]]
             (actions/create-discount-type-action
              (state-for system)
              {:discount-type-create {:discount-type-name "Klimaticket"}})))))

  (testing "returns a validation error when the discount type name is blank"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:discount-type-create :error :discount-type-name]
             {:error "Discount type name is required."}]]
           (actions/create-discount-type-action
            {:current-member-id (random-uuid)}
            {:discount-type-create {:discount-type-name ""}}))))

  (testing "rejects a duplicate discount type name"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-discount-type! conn {:discount-type-id   (random-uuid)
                                 :discount-type-name "Klimaticket"
                                 :enabled?           true})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/merge-state
               [:discount-type-create]
               {:discount-type-name "Klimaticket"
                :error {:discount-type-name
                        {:error "Discount type named 'Klimaticket' already exists."}}}]]
             (actions/create-discount-type-action
              (state-for system)
              {:discount-type-create {:discount-type-name "Klimaticket"}})))))

  (testing "updates an existing discount type"
    (let [{:keys [member-id] :as system} (new-system)
          discount-type-id               (random-uuid)]
      (seed-discount-type! (:conn system) {:discount-type-id   discount-type-id
                                           :discount-type-name "Old Name"
                                           :enabled?           true})
      (is (= [[:db/transact [{:travel.discount.type/discount-type-id   discount-type-id
                              :travel.discount.type/enabled?           false
                              :travel.discount.type/discount-type-name "New Name"}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:discount-type] false]]
             (actions/update-discount-type-action
              (state-for system)
              {:discount-type {:discount-type-id      (str discount-type-id)
                               :discount-type-name    "New Name"
                               :discount-type-enabled false}})))))

  (testing "rejects a duplicate discount type name during update"
    (let [{:keys [conn] :as system} (new-system)
          discount-type-id          (random-uuid)]
      (seed-discount-type! conn {:discount-type-id   discount-type-id
                                 :discount-type-name "Old Name"
                                 :enabled?           true})
      (seed-discount-type! conn {:discount-type-id   (random-uuid)
                                 :discount-type-name "Taken"
                                 :enabled?           true})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/merge-state
               [:discount-type]
               {:discount-type-name "Taken"
                :error {:discount-type-name
                        {:error "Discount type named 'Taken' already exists."}}}]]
             (actions/update-discount-type-action
              (state-for system)
              {:discount-type {:discount-type-id      (str discount-type-id)
                               :discount-type-name    "Taken"
                               :discount-type-enabled true}})))))

  (testing "deletes a discount type"
    (let [{:keys [member-id] :as system} (new-system)
          discount-type-id               (random-uuid)]
      (is (= [[:db/transact [[:db/retractEntity [:travel.discount.type/discount-type-id discount-type-id]]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:discount-type] false]]
             (actions/delete-discount-type-action
              (state-for system)
              {:targetid (str discount-type-id)})))))

  (testing "opens the discount type edit form"
    (let [{:keys [conn] :as system} (new-system)
          discount-type-id          (random-uuid)]
      (seed-discount-type! conn {:discount-type-id   discount-type-id
                                 :discount-type-name "Old Name"
                                 :enabled?           true})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:discount-type]
               {:discount-type-id      discount-type-id
                :discount-type-name    "Old Name"
                :discount-type-enabled true}]]
             (actions/open-discount-type-edit-action
              (state-for system)
              {:targetid (str discount-type-id)})))))

  (testing "closes the discount type edit form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:discount-type] false]]
           (actions/close-discount-type-edit-action {} {}))))

  (testing "opens the discount type create form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:discount-type-create]
             {:open true
              :discount-type-name ""}]]
           (actions/open-discount-type-create-action {} {}))))

  (testing "closes the discount type create form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:discount-type-create] false]]
           (actions/close-discount-type-create-action {} {})))))

(deftest team-actions-test
  (testing "creates a new team"
    (let [{:keys [member-id] :as system} (new-system)]
      (is (= [[:db/transact [{:team/team-id :db/gen-uuid
                              :team/name    "Booking"}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:team-create] false]]
             (actions/create-team-action
              (state-for system)
              {:team-create {:team-name "Booking"}})))))

  (testing "returns a validation error when the team name is blank"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:team-create :error :team-name]
             {:error "Team name is required."}]]
           (actions/create-team-action
            {:current-member-id (random-uuid)}
            {:team-create {:team-name ""}}))))

  (testing "rejects a duplicate team name"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-team! conn {:team-id   (random-uuid)
                        :team-name "Booking"})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/merge-state
               [:team-create]
               {:team-name "Booking"
                :error {:team-name
                        {:error "Team named 'Booking' already exists."}}}]]
             (actions/create-team-action
              (state-for system)
              {:team-create {:team-name "Booking"}})))))

  (testing "updates a team and removes its type when cleared"
    (let [{:keys [member-id] :as system} (new-system)
          team-id                        (random-uuid)]
      (seed-team! (:conn system) {:team-id   team-id
                                  :team-name "Old Team"
                                  :team-type :team.type/insurance})
      (is (= [[:db/transact [[:db/add [:team/team-id team-id] :team/name "New Team"]
                             [:db/retract [:team/team-id team-id] :team/team-type :team.type/insurance]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:team] false]]
             (actions/update-team-action
              (state-for system)
              {:team {:team-id   (str team-id)
                      :team-name " New Team "
                      :team-type nil}})))))

  (testing "rejects a duplicate team name during update"
    (let [{:keys [conn] :as system} (new-system)
          team-id                   (random-uuid)]
      (seed-team! conn {:team-id   team-id
                        :team-name "Old Team"
                        :team-type :team.type/insurance})
      (seed-team! conn {:team-id   (random-uuid)
                        :team-name "Taken"})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/merge-state
               [:team]
               {:team-name "Taken"
                :error {:team-name
                        {:error "Team named 'Taken' already exists."}}}]]
             (actions/update-team-action
              (state-for system)
              {:team {:team-id   (str team-id)
                      :team-name "Taken"
                      :team-type "insurance"}})))))

  (testing "deletes a team"
    (let [{:keys [member-id] :as system} (new-system)
          team-id                        (random-uuid)]
      (is (= [[:db/transact [[:db/retractEntity [:team/team-id team-id]]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:team] false]]
             (actions/delete-team-action
              (state-for system)
              {:targetid (str team-id)})))))

  (testing "removes a team member"
    (let [{:keys [member-id] :as system} (new-system)
          team-id                        (random-uuid)
          remove-member-id               (random-uuid)]
      (is (= [[:db/transact [[:db/retract [:team/team-id team-id] :team/members [:member/member-id remove-member-id]]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:team :remove-member-id] nil]]
             (actions/remove-team-member-action
              (state-for system)
              {:team {:team-id          (str team-id)
                      :remove-member-id (str remove-member-id)}})))))

  (testing "adds a team member"
    (let [{:keys [member-id] :as system} (new-system)
          team-id                        (random-uuid)
          new-member-id                  (random-uuid)]
      (seed-member! (:conn system) new-member-id)
      (is (= [[:db/transact [[:db/add [:team/team-id team-id] :team/members [:member/member-id new-member-id]]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:team :member-id] ""]]
             (actions/add-team-member-action
              (state-for system)
              {:team {:team-id   (str team-id)
                      :member-id (str new-member-id)}})))))

  (testing "opens the team edit form"
    (let [{:keys [conn] :as system} (new-system)
          team-id                   (random-uuid)]
      (seed-team! conn {:team-id   team-id
                        :team-name "Old Team"
                        :team-type :team.type/insurance})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:team]
               {:team-id   team-id
                :team-name "Old Team"
                :team-type "insurance"
                :member-id ""}]]
             (actions/open-team-edit-action
              (state-for system)
              {:targetid (str team-id)})))))

  (testing "closes the team edit form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:team] false]]
           (actions/close-team-edit-action {} {}))))

  (testing "opens the team create form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:team-create]
             {:open true
              :team-name ""}]]
           (actions/open-team-create-action {} {}))))

  (testing "closes the team create form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:team-create] false]]
           (actions/close-team-create-action {} {})))))

(deftest section-actions-test
  (testing "creates a new section"
    (let [{:keys [member-id] :as system} (new-system)]
      (is (= [[:db/transact [{:section/active? true
                              :section/name    "Trumpets"}
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:section-create] false]]
             (actions/create-section-action
              (state-for system)
              {:section-create {:section-name "Trumpets"}})))))

  (testing "returns a validation error when the section name is blank"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:section-create :error :section-name]
             {:error "Section name is required."}]]
           (actions/create-section-action
            {:current-member-id (random-uuid)}
            {:section-create {:section-name ""}}))))

  (testing "rejects a duplicate section name"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-section! conn {:section-name "Trumpets"
                           :active?      true})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/merge-state
               [:section-create]
               {:section-name "Trumpets"
                :error {:section-name
                        {:error "Section named 'Trumpets' already exists."}}}]]
             (actions/create-section-action
              (state-for system)
              {:section-create {:section-name "Trumpets"}})))))

  (testing "updates an existing section"
    (let [{:keys [member-id] :as system} (new-system)]
      (seed-section! (:conn system) {:section-name "Old Section"
                                     :active?      true})
      (is (= [[:db/transact [[:db/add [:section/name "Old Section"] :section/name "New Section"]
                             [:db/add [:section/name "Old Section"] :section/active? false]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:section] false]]
             (actions/update-section-action
              (state-for system)
              {:section {:section-id      "Old Section"
                         :section-name    "New Section"
                         :section-enabled false}})))))

  (testing "rejects a duplicate section name during update"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-section! conn {:section-name "Old Section"
                           :active?      true})
      (seed-section! conn {:section-name "Taken"
                           :active?      true})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/merge-state
               [:section]
               {:section-name "Taken"
                :error {:section-name
                        {:error "Section named 'Taken' already exists."}}}]]
             (actions/update-section-action
              (state-for system)
              {:section {:section-id      "Old Section"
                         :section-name    "Taken"
                         :section-enabled true}})))))

  (testing "deletes a section"
    (let [{:keys [member-id] :as system} (new-system)]
      (is (= [[:db/transact [[:db/retractEntity [:section/name "Trumpets"]]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]
              [:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:section] false]]
             (actions/delete-section-action
              (state-for system)
              {:targetid "Trumpets"})))))

  (testing "opens the section edit form"
    (let [{:keys [conn] :as system} (new-system)]
      (seed-section! conn {:section-name "Trumpets"
                           :active?      false})
      (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
              [:app.datastar/assoc-state [:section]
               {:section-id      "Trumpets"
                :section-name    "Trumpets"
                :section-enabled false}]]
             (actions/open-section-edit-action
              (state-for system)
              {:targetid "Trumpets"})))))

  (testing "closes the section edit form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:section] false]]
           (actions/close-section-edit-action {} {}))))

  (testing "opens the section create form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:section-create]
             {:open true
              :section-name ""}]]
           (actions/open-section-create-action {} {}))))

  (testing "closes the section create form"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:section-create] false]]
           (actions/close-section-create-action {} {}))))

  (testing "opens section reordering"
    (is (= [[:app.datastar/merge-signals {:loading false :targetid false}]
            [:app.datastar/assoc-state [:section-reorder :open] true]]
           (actions/open-section-reorder-action {} {}))))

  (testing "closes section reordering"
    (is (= [[:app.datastar/assoc-state [:section-reorder :open] false]
            [:app.datastar/merge-signals {:section-reorder {:open false}}]]
           (actions/close-section-reorder-action {} {}))))

  (testing "updates the section order"
    (let [{:keys [member-id] :as system} (new-system)
          order                          ["sax soprano/clarinet" "Trumpets"]]
      (is (= [[:db/transact [[:db/add [:section/name "sax soprano/clarinet"] :section/position 0]
                             [:db/add [:section/name "Trumpets"] :section/position 1]
                             [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                      {:transact-w-nils? false}]]
             (actions/update-section-order-action
              (state-for system)
              {:section {:order order}}))))))
