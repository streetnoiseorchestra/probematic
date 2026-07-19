(ns app.keycloak.cells-test
  (:require
   [app.keycloak.cells]
   [app.members.invite.domain :as invite.domain]
   [app.members.invite.workflows :as workflow]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [mycelium.cell :as cell]
   [mycelium.dev :as myc-dev]
   [mycelium.schema :as myc-schema]))

(def ^:private member-id
  "230d7ed6-bff7-421b-969e-6b19f38fbf13")

(def ^:private attempt-attributes
  {"probematic-member-id" [member-id]
   "probematic-invite-generation" ["4"]})

(def ^:private alice
  {:id "user-alice"
   :username "alice"
   :email "alice@example.com"
   :enabled? false
   :attributes attempt-attributes})

(def ^:private bob
  {:id "user-bob"
   :username "bob"
   :email "bob@example.com"
   :enabled? false
   :attributes attempt-attributes})

(def ^:private member-group
  {:id "group-members"
   :name "Mitglieder"})

(def ^:private keycloak-cell-ids
  [:keycloak/find-users-by-attributes
   :keycloak/get-user
   :keycloak/find-group-by-name
   :keycloak/create-user
   :keycloak/add-user-to-group
   :keycloak/set-user-enabled
   :keycloak/delete-user])

(defn- valid-malli-schema? [schema]
  (try
    (m/schema schema {:registry invite.domain/registry})
    true
    (catch Exception _exception
      false)))

(defn- valid-output-contract? [output]
  (cond
    (myc-schema/per-transition? output)
    (every? valid-malli-schema? (vals (myc-schema/transitions-map output)))

    (map? output)
    (every? valid-malli-schema? (vals output))

    :else
    (valid-malli-schema? output)))

(defn- exact-attributes? [expected actual]
  (every? (fn [[attribute values]]
            (= values (get actual attribute)))
          expected))

(defn- operation-result [state operation f]
  (let [failure (get-in @state [:failures operation])]
    (cond
      (instance? Throwable failure) (throw failure)
      (= :rejected failure) {:outcome :rejected}
      :else (f))))

(defn- fake-keycloak
  ([]
   (fake-keycloak {}))
  ([{:keys [users groups failures]
     :or {users [] groups [] failures {}}}]
   (let [state (atom {:users (into {} (map (juxt :id identity)) users)
                      :groups groups
                      :memberships #{}
                      :next-user-id (count users)
                      :failures failures})]
     {:state state
      :find-users-by-attributes
      (fn [attributes]
        (operation-result
         state
         :find-users-by-attributes
         #(->> (vals (:users @state))
               (filter (comp (partial exact-attributes? attributes)
                             :attributes))
               vec)))
      :get-user
      (fn [user-id]
        (operation-result state :get-user #(get-in @state [:users user-id])))
      :find-groups-by-name
      (fn [group-name]
        (operation-result
         state
         :find-groups-by-name
         #(->> (:groups @state)
               (filter (comp (partial = group-name) :name))
               vec)))
      :create-user!
      (fn [user-spec]
        (operation-result
         state
         :create-user!
         #(let [user-id (str "created-user-" (inc (:next-user-id @state)))
                user (-> user-spec
                         (select-keys [:username :email :enabled? :attributes])
                         (assoc :id user-id))]
            (swap! state
                   (fn [current]
                     (-> current
                         (assoc-in [:users user-id] user)
                         (update :next-user-id inc))))
            {:outcome :created
             :user-id user-id})))
      :add-user-to-group!
      (fn [user-id group-id]
        (operation-result
         state
         :add-user-to-group!
         #(if (and (get-in @state [:users user-id])
                   (some (comp (partial = group-id) :id) (:groups @state)))
            (do
              (swap! state update :memberships conj [user-id group-id])
              {:outcome :joined})
            {:outcome :rejected})))
      :set-user-enabled!
      (fn [user-id enabled?]
        (operation-result
         state
         :set-user-enabled!
         #(if (get-in @state [:users user-id])
            (do
              (swap! state assoc-in [:users user-id :enabled?] enabled?)
              {:outcome :updated})
            {:outcome :rejected})))
      :delete-user!
      (fn [user-id]
        (operation-result
         state
         :delete-user!
         #(if (get-in @state [:users user-id])
            (do
              (swap! state update :users dissoc user-id)
              {:outcome :deleted})
            {:outcome :not-found})))})))

(defn- dispatches [cell-name]
  (get-in workflow/accept-or-recover [:dispatches cell-name]))

(defn- test-cell [cell-id cell-name keycloak input expected-dispatch]
  (-> (myc-dev/test-cell
       cell-id
       {:input input
        :malli/registry invite.domain/registry
        :resources {:keycloak keycloak}
        :dispatches (dispatches cell-name)
        :expected-dispatch expected-dispatch})
      (dissoc :duration-ms)))

(defn- handler [cell-id]
  (:handler (cell/get-cell! cell-id)))

(deftest find-users-by-attributes-cell-test
  (testing "no exact user"
    (is (= {:pass? true
            :errors []
            :output #:keycloak{:user-lookup :not-found}
            :matched-dispatch :not-found}
           (test-cell
            :keycloak/find-users-by-attributes
            :cleanup-find-user
            (fake-keycloak {:users [(assoc alice
                                           :attributes
                                           (assoc attempt-attributes
                                                  "probematic-invite-generation"
                                                  ["3"]))]})
            #:keycloak{:user-attributes attempt-attributes}
            :not-found))))
  (testing "one exact user"
    (is (= {:pass? true
            :errors []
            :output {:keycloak/user-lookup :found
                     :keycloak/user-id "user-alice"
                     :keycloak/user alice}
            :matched-dispatch :found}
           (test-cell
            :keycloak/find-users-by-attributes
            :cleanup-find-user
            (fake-keycloak {:users [alice]})
            #:keycloak{:user-attributes attempt-attributes}
            :found))))
  (testing "multiple exact users"
    (is (= {:pass? true
            :errors []
            :output {:keycloak/user-lookup :ambiguous
                     :keycloak/match-count 2}
            :matched-dispatch :ambiguous}
           (test-cell
            :keycloak/find-users-by-attributes
            :cleanup-find-user
            (fake-keycloak {:users [alice bob]})
            #:keycloak{:user-attributes attempt-attributes}
            :ambiguous)))))

(deftest get-user-cell-test
  (testing "existing user"
    (is (= {:pass? true
            :errors []
            :output {:keycloak/user-lookup :found
                     :keycloak/user alice}
            :matched-dispatch :found}
           (test-cell
            :keycloak/get-user
            :activate-load-user
            (fake-keycloak {:users [alice]})
            #:keycloak{:user-id "user-alice"}
            :found))))
  (testing "missing user"
    (is (= {:pass? true
            :errors []
            :output #:keycloak{:user-lookup :not-found}
            :matched-dispatch :not-found}
           (test-cell
            :keycloak/get-user
            :activate-load-user
            (fake-keycloak)
            #:keycloak{:user-id "missing"}
            :not-found)))))

(deftest find-group-by-name-cell-test
  (testing "no exact group"
    (is (= {:pass? true
            :errors []
            :output #:keycloak{:group-lookup :not-found}
            :matched-dispatch :not-found}
           (test-cell
            :keycloak/find-group-by-name
            :provision-find-group
            (fake-keycloak {:groups [(assoc member-group :name "Other")]})
            #:keycloak{:group-name "Mitglieder"}
            :not-found))))
  (testing "one exact group"
    (is (= {:pass? true
            :errors []
            :output {:keycloak/group-lookup :found
                     :keycloak/group-id "group-members"}
            :matched-dispatch :found}
           (test-cell
            :keycloak/find-group-by-name
            :provision-find-group
            (fake-keycloak {:groups [member-group]})
            #:keycloak{:group-name "Mitglieder"}
            :found))))
  (testing "multiple exact groups"
    (is (= {:pass? true
            :errors []
            :output {:keycloak/group-lookup :ambiguous
                     :keycloak/match-count 2}
            :matched-dispatch :ambiguous}
           (test-cell
            :keycloak/find-group-by-name
            :provision-find-group
            (fake-keycloak {:groups [member-group
                                     (assoc member-group :id "group-duplicate")]})
            #:keycloak{:group-name "Mitglieder"}
            :ambiguous)))))

(deftest create-user-cell-test
  (let [keycloak (fake-keycloak)
        user-spec {:username "alice"
                   :email "alice@example.com"
                   :first-name "Alice"
                   :enabled? false
                   :email-verified? true
                   :attributes attempt-attributes}]
    (testing "created user"
      (is (= {:pass? true
              :errors []
              :output {:keycloak/create-status :created
                       :keycloak/user-id "created-user-1"}
              :matched-dispatch :created}
             (test-cell
              :keycloak/create-user
              :provision-create-user
              keycloak
              #:keycloak{:user-spec user-spec}
              :created)))
      (is (= {:id "created-user-1"
              :username "alice"
              :email "alice@example.com"
              :enabled? false
              :attributes attempt-attributes}
             (get-in @(:state keycloak) [:users "created-user-1"]))))
    (testing "definite rejection"
      (is (= {:pass? true
              :errors []
              :output #:keycloak{:create-status :rejected}
              :matched-dispatch :rejected}
             (test-cell
              :keycloak/create-user
              :provision-create-user
              (fake-keycloak {:failures {:create-user! :rejected}})
              #:keycloak{:user-spec user-spec}
              :rejected))))))

(deftest add-user-to-group-cell-test
  (let [keycloak (fake-keycloak {:users [alice] :groups [member-group]})]
    (testing "joined group"
      (is (= {:pass? true
              :errors []
              :output #:keycloak{:group-membership-status :joined}
              :matched-dispatch :joined}
             (test-cell
              :keycloak/add-user-to-group
              :configure-add-user-to-group
              keycloak
              {:keycloak/user-id "user-alice"
               :keycloak/group-id "group-members"}
              :joined)))
      (is (= #{["user-alice" "group-members"]}
             (:memberships @(:state keycloak)))))
    (testing "definite rejection"
      (is (= {:pass? true
              :errors []
              :output #:keycloak{:group-membership-status :rejected}
              :matched-dispatch :rejected}
             (test-cell
              :keycloak/add-user-to-group
              :configure-add-user-to-group
              (fake-keycloak {:users [alice]
                              :groups [member-group]
                              :failures {:add-user-to-group! :rejected}})
              {:keycloak/user-id "user-alice"
               :keycloak/group-id "group-members"}
              :rejected))))))

(deftest set-user-enabled-cell-test
  (let [keycloak (fake-keycloak {:users [alice]})]
    (testing "updated enabled state"
      (is (= {:pass? true
              :errors []
              :output #:keycloak{:update-status :updated}
              :matched-dispatch :updated}
             (test-cell
              :keycloak/set-user-enabled
              :activate-enable-user
              keycloak
              {:keycloak/user-id "user-alice"
               :keycloak/enabled? true}
              :updated)))
      (is (= true (get-in @(:state keycloak)
                          [:users "user-alice" :enabled?]))))
    (testing "definite rejection"
      (is (= {:pass? true
              :errors []
              :output #:keycloak{:update-status :rejected}
              :matched-dispatch :rejected}
             (test-cell
              :keycloak/set-user-enabled
              :activate-enable-user
              (fake-keycloak {:users [alice]
                              :failures {:set-user-enabled! :rejected}})
              {:keycloak/user-id "user-alice"
               :keycloak/enabled? true}
              :rejected))))))

(deftest delete-user-cell-test
  (testing "deleted user"
    (let [keycloak (fake-keycloak {:users [alice]})]
      (is (= {:pass? true
              :errors []
              :output #:keycloak{:delete-status :deleted}
              :matched-dispatch :absent}
             (test-cell
              :keycloak/delete-user
              :cleanup-delete-user
              keycloak
              #:keycloak{:user-id "user-alice"}
              :absent)))
      (is (= {} (:users @(:state keycloak))))))
  (testing "already absent user"
    (is (= {:pass? true
            :errors []
            :output #:keycloak{:delete-status :not-found}
            :matched-dispatch :absent}
           (test-cell
            :keycloak/delete-user
            :cleanup-delete-user
            (fake-keycloak)
            #:keycloak{:user-id "missing"}
            :absent))))
  (testing "definite rejection"
    (is (= {:pass? true
            :errors []
            :output #:keycloak{:delete-status :rejected}
            :matched-dispatch :rejected}
           (test-cell
            :keycloak/delete-user
            :cleanup-delete-user
            (fake-keycloak {:users [alice]
                            :failures {:delete-user! :rejected}})
            #:keycloak{:user-id "user-alice"}
            :rejected)))))

(deftest registered-cells-have-runtime-schema-contracts-test
  (is (= (zipmap keycloak-cell-ids (repeat {:input? true :output? true}))
         (into {}
               (map (fn [cell-id]
                      (let [schema (:schema (cell/get-cell! cell-id))]
                        [cell-id {:input? (m/schema? (:input schema))
                                  :output? (valid-output-contract?
                                            (:output schema))}])))
               keycloak-cell-ids))))

(deftest cell-input-schemas-reject-invalid-data-test
  (let [cases [{:cell-id :keycloak/find-users-by-attributes
                :input #:keycloak{:user-attributes {"member-id" [42]}}}
               {:cell-id :keycloak/get-user
                :input #:keycloak{:user-id ""}}
               {:cell-id :keycloak/find-group-by-name
                :input #:keycloak{:group-name ""}}
               {:cell-id :keycloak/create-user
                :input #:keycloak{:user-spec
                                  {:username "alice"
                                   :email "not-an-email"
                                   :first-name "Alice"
                                   :enabled? false
                                   :email-verified? true
                                   :attributes attempt-attributes}}}
               {:cell-id :keycloak/add-user-to-group
                :input {:keycloak/user-id "user-alice"
                        :keycloak/group-id ""}}
               {:cell-id :keycloak/set-user-enabled
                :input {:keycloak/user-id "user-alice"
                        :keycloak/enabled? :yes}}
               {:cell-id :keycloak/delete-user
                :input #:keycloak{:user-id ""}}]]
    (doseq [{:keys [cell-id input]} cases]
      (testing (str cell-id " rejects malformed input before its handler")
        (let [result (myc-dev/test-cell
                      cell-id
                      {:input input
                       :malli/registry invite.domain/registry
                       :resources {:keycloak (fake-keycloak)}})]
          (is (= {:pass? false
                  :error-phases [:input]
                  :output nil}
                 {:pass? (:pass? result)
                  :error-phases (mapv :phase (:errors result))
                  :output (:output result)})))))))

(deftest cell-output-schema-rejects-malformed-adapter-data-test
  (let [result (myc-dev/test-cell
                :keycloak/get-user
                {:input #:keycloak{:user-id "user-alice"}
                 :malli/registry invite.domain/registry
                 :resources
                 {:keycloak
                  {:get-user
                   (fn [_]
                     {:id "user-alice"
                      :enabled? "yes"
                      :attributes {}})}}})]
    (is (= {:pass? false
            :error-phases [:output]
            :output {:keycloak/user-lookup :found
                     :keycloak/user {:id "user-alice"
                                     :enabled? "yes"
                                     :attributes {}}}}
           {:pass? (:pass? result)
            :error-phases (mapv :phase (:errors result))
            :output (:output result)}))))

(deftest generic-cell-outputs-are-domain-neutral-test
  (let [cases [{:cell-id :keycloak/find-users-by-attributes
                :keycloak (fake-keycloak {:users [alice bob]})
                :input #:keycloak{:user-attributes attempt-attributes}}
               {:cell-id :keycloak/get-user
                :keycloak (fake-keycloak)
                :input #:keycloak{:user-id "missing"}}
               {:cell-id :keycloak/find-group-by-name
                :keycloak (fake-keycloak)
                :input #:keycloak{:group-name "missing"}}
               {:cell-id :keycloak/create-user
                :keycloak (fake-keycloak
                           {:failures {:create-user! :rejected}})
                :input #:keycloak{:user-spec
                                  {:username "alice"
                                   :email "alice@example.com"
                                   :first-name "Alice"
                                   :enabled? false
                                   :email-verified? true
                                   :attributes attempt-attributes}}}
               {:cell-id :keycloak/add-user-to-group
                :keycloak (fake-keycloak
                           {:failures {:add-user-to-group! :rejected}})
                :input {:keycloak/user-id "user-alice"
                        :keycloak/group-id "group-members"}}
               {:cell-id :keycloak/set-user-enabled
                :keycloak (fake-keycloak
                           {:failures {:set-user-enabled! :rejected}})
                :input {:keycloak/user-id "user-alice"
                        :keycloak/enabled? true}}
               {:cell-id :keycloak/delete-user
                :keycloak (fake-keycloak
                           {:failures {:delete-user! :rejected}})
                :input #:keycloak{:user-id "user-alice"}}]]
    (doseq [{:keys [cell-id keycloak input]} cases]
      (testing (str cell-id " emits no invitation-domain keys")
        (let [output ((handler cell-id) {:keycloak keycloak} input)]
          (is (= []
                 (->> (keys output)
                      (filter #(= "member-invite" (namespace %)))
                      vec))))))))

(deftest unknown-keycloak-outcomes-escape-test
  (let [timeout (ex-info "Keycloak timeout" {:type :timeout})
        user-spec {:username "alice"
                   :email "alice@example.com"
                   :first-name "Alice"
                   :enabled? false
                   :email-verified? true
                   :attributes attempt-attributes}
        cases [{:cell-id :keycloak/find-users-by-attributes
                :operation :find-users-by-attributes
                :input #:keycloak{:user-attributes attempt-attributes}}
               {:cell-id :keycloak/get-user
                :operation :get-user
                :input #:keycloak{:user-id "user-alice"}}
               {:cell-id :keycloak/find-group-by-name
                :operation :find-groups-by-name
                :input #:keycloak{:group-name "Mitglieder"}}
               {:cell-id :keycloak/create-user
                :operation :create-user!
                :input #:keycloak{:user-spec user-spec}}
               {:cell-id :keycloak/add-user-to-group
                :operation :add-user-to-group!
                :input {:keycloak/user-id "user-alice"
                        :keycloak/group-id "group-members"}}
               {:cell-id :keycloak/set-user-enabled
                :operation :set-user-enabled!
                :input {:keycloak/user-id "user-alice"
                        :keycloak/enabled? true}}
               {:cell-id :keycloak/delete-user
                :operation :delete-user!
                :input #:keycloak{:user-id "user-alice"}}]]
    (doseq [{:keys [cell-id operation input]} cases]
      (testing (str operation " leaves the timeout visible")
        (let [thrown (try
                       ((handler cell-id)
                        {:keycloak (fake-keycloak
                                    {:users [alice]
                                     :groups [member-group]
                                     :failures {operation timeout}})}
                        input)
                       nil
                       (catch Exception e e))]
          (is (identical? timeout thrown)))))))
