(ns app.members.invite.workflows-test
  (:require
   [app.members.invite.domain :as domain]
   [app.members.invite.views :as views]
   [app.members.invite.workflows :as workflows]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [malli.core :as m]
   [mycelium.core :as myc]))

(defn- valid-malli-schema? [schema]
  (try
    (m/schema schema)
    true
    (catch Exception _exception
      false)))

(defn- valid-output-contract? [output]
  (if (map? output)
    (every? valid-malli-schema? (vals output))
    (valid-malli-schema? output)))

(deftest manifest-is-the-complete-executable-contract-test
  (let [manifest workflows/validated-manifest
        cell-ids (into #{} (map :id) (vals (:cells manifest)))
        keycloak-cells (->> (:cells manifest)
                            vals
                            (filter #(= "keycloak" (namespace (:id %)))))]
    (is (= :member-invitation/accept-or-recover (:id manifest)))
    (is (= #{:entry :provisioning :activation :compensation}
           (set (keys (:regions manifest)))))
    (is (= #{:keycloak/find-users-by-attributes
             :keycloak/get-user
             :keycloak/find-group-by-name
             :keycloak/create-user
             :keycloak/add-user-to-group
             :keycloak/set-user-enabled
             :keycloak/delete-user}
           (into #{} (filter #(= "keycloak" (namespace %))) cell-ids)))
    (is (contains? cell-ids :member-invite/plan-entry))
    (is (contains? cell-ids :member/read-invitation-state))
    (is (contains? cell-ids :member/begin-keycloak-create))
    (is (not-any? #(= "member-invitation" (namespace %)) cell-ids))
    (is (not (re-find #"member-invite/" (pr-str keycloak-cells))))
    (is (every? #(contains? % :on-error) (vals (:cells manifest))))
    (is (every? nil? (map :on-error (vals (:cells manifest)))))
    (is (every? fn?
                (map second (mapcat val (:dispatches manifest)))))))

(deftest workflow-input-excludes-the-bearer-code-test
  (let [input-schema (:input-schema workflows/validated-manifest)]
    (is (m/schema? input-schema))
    (is (not (re-find #"invite-code" (pr-str input-schema))))
    (is (= #{:member/member-id
             :member-invite/observed-status
             :member-invite/observed-generation
             :member-invite/requested-at
             :keycloak/group-name}
           (into #{}
                 (keep #(when (vector? %) (first %)))
                 (rest (m/form input-schema)))))))

(deftest workflow-input-requires-inst-values-test
  (let [input-schema (:input-schema workflows/validated-manifest)
        input        {:member/member-id (random-uuid)
                      :member-invite/observed-status
                      :member.invite.status/pending
                      :member-invite/observed-generation 1
                      :member-invite/requested-at
                      #inst "2026-07-16T08:05:00.000-00:00"
                      :keycloak/group-name "Mitglieder"}]
    (is (= {:t-inst            true
            :java-time-instant false}
           {:t-inst (m/validate input-schema input)
            :java-time-instant
            (m/validate
             input-schema
             (assoc input
                    :member-invite/requested-at
                    (java.time.Instant/parse "2026-07-16T08:05:00Z")))}))))

(deftest manifest-inherits-cell-owned-malli-contracts-test
  (is (every? #(= :inherit (:schema %))
              (vals (:cells workflows/manifest))))
  (is (every? m/schema?
              (map #(get-in % [:schema :input])
                   (vals (:cells workflows/validated-manifest)))))
  (is (every? valid-output-contract?
              (map #(get-in % [:schema :output])
                   (vals (:cells workflows/validated-manifest))))))

(deftest conditional-edges-have-one-label-per-target-test
  (doseq [[cell-name edges] (:edges workflows/manifest)
          :when (map? edges)]
    (is (= (count (vals edges))
           (count (set (vals edges))))
        (str cell-name " has multiple transition labels for one target"))))

(deftest creating-state-fences-create-and-recovers-without-recreating-test
  (let [edges (:edges workflows/manifest)]
    (testing "group lookup completes before create ownership is acquired"
      (is (= :begin-create
             (get-in edges [:find-group-before-create :found])))
      (is (= {:begun :build-user-spec
              :conflict :end}
             (:begin-create edges)))
      (is (= :create-user
             (:build-user-spec edges))))
    (testing "creating recovery only reconciles an already-started create"
      (is (= :find-creating-user
             (get-in edges [:plan-progress :reconcile-create])))
      (is (= :end
             (get-in edges [:find-creating-user :not-found])))
      (is (= :flag-ambiguous-creating-user
             (get-in edges [:find-creating-user :ambiguous])))
      (is (= :end
             (:flag-ambiguous-creating-user edges)))
      (is (not= :create-user
                (get-in edges [:find-creating-user :not-found])))
      (is (not= :begin-compensation
                (get-in edges [:find-creating-user :not-found]))))))

(deftest workflow-rejects-extra-input-before-building-a-trace-test
  (let [result
        (myc/run-compiled
         (workflows/pre-compile)
         {}
         {:member/member-id (random-uuid)
          :member-invite/observed-status :member.invite.status/pending
          :member-invite/observed-generation 1
          :member-invite/requested-at (java.util.Date.)
          :keycloak/group-name "Mitglieder"
          :member/invite-code "must-not-enter-workflow"})]
    (is (contains? result :mycelium/input-error))
    (is (not (contains? result :mycelium/trace)))))

(deftest workflow-definition-preserves-path-constraints-test
  (let [definition (workflows/workflow-definition)]
    (testing "the released manifest adapter does not silently drop constraints"
      (is (= (:constraints workflows/validated-manifest)
             (:constraints definition))))
    (testing "the complete definition still pre-compiles"
      (is (map? (workflows/pre-compile))))))

(def issued-at #inst "2026-07-16T08:00:00.000-00:00")
(def requested-at #inst "2026-07-16T08:05:00.000-00:00")
(def expired-retry-at #inst "2026-07-18T08:00:00.000-00:00")
(def expires-at #inst "2026-07-17T08:00:00.000-00:00")

(def pending :member.invite.status/pending)
(def accepting :member.invite.status/accepting)
(def creating :member.invite.status/creating)
(def activating :member.invite.status/activating)
(def compensating :member.invite.status/compensating)
(def accepted :member.invite.status/accepted)

(def member-group {:id "group-members" :name "Mitglieder"})

(defn exact-attributes? [expected actual]
  (every? (fn [[attribute values]]
            (= values (get actual attribute)))
          expected))

(defn fake-keycloak
  ([]
   (fake-keycloak {}))
  ([{:keys [users groups failures after-failures hide-users create-outcomes]
     :or {users []
          groups [member-group]
          failures {}
          after-failures {}
          hide-users #{}
          create-outcomes [:created]}}]
   (let [state (atom {:users (into {} (map (juxt :id identity)) users)
                      :groups groups
                      :memberships #{}
                      :calls []
                      :failures (into {} (map (fn [[operation values]]
                                                [operation (vec values)])) failures)
                      :after-failures
                      (into {} (map (fn [[operation values]]
                                      [operation (vec values)])) after-failures)
                      :hide-users hide-users
                      :create-outcomes (vec create-outcomes)
                      :next-user-id (count users)})
         invoke
         (fn [operation arguments f]
           (swap! state update :calls conj [operation arguments])
           (let [failure (first (get-in @state [:failures operation]))]
             (when failure
               (swap! state update-in [:failures operation] subvec 1)
               (throw failure))
             (let [result (f)
                   after-failure
                   (first (get-in @state [:after-failures operation]))]
               (when after-failure
                 (swap! state update-in [:after-failures operation] subvec 1)
                 (throw after-failure))
               result)))]
     {:state state
      :find-users-by-attributes
      (fn [attributes]
        (invoke
         :find-users-by-attributes
         [attributes]
         #(->> (vals (:users @state))
               (remove (comp (:hide-users @state) :id))
               (filter (comp (partial exact-attributes? attributes) :attributes))
               vec)))
      :get-user
      (fn [user-id]
        (invoke
         :get-user
         [user-id]
         #(when-not ((:hide-users @state) user-id)
            (get-in @state [:users user-id]))))
      :find-groups-by-name
      (fn [group-name]
        (invoke
         :find-groups-by-name
         [group-name]
         #(->> (:groups @state)
               (filter (comp (partial = group-name) :name))
               vec)))
      :create-user!
      (fn [user-spec]
        (invoke
         :create-user!
         [user-spec]
         #(let [outcome (first (:create-outcomes @state))]
            (swap! state update :create-outcomes subvec 1)
            (case outcome
              :rejected
              {:outcome :rejected}

              :created
              (let [user-id (str "created-user-"
                                 (inc (:next-user-id @state)))
                    user (-> user-spec
                             (select-keys
                              [:username :email :enabled? :attributes])
                             (assoc :id user-id))]
                (swap! state
                       (fn [current]
                         (-> current
                             (assoc-in [:users user-id] user)
                             (update :next-user-id inc))))
                {:outcome :created :user-id user-id})))))
      :add-user-to-group!
      (fn [user-id group-id]
        (invoke
         :add-user-to-group!
         [user-id group-id]
         #(do
            (swap! state update :memberships conj [user-id group-id])
            {:outcome :joined})))
      :set-user-enabled!
      (fn [user-id enabled?]
        (invoke
         :set-user-enabled!
         [user-id enabled?]
         #(do
            (swap! state assoc-in [:users user-id :enabled?] enabled?)
            {:outcome :updated})))
      :delete-user!
      (fn [user-id]
        (invoke
         :delete-user!
         [user-id]
         #(if (get-in @state [:users user-id])
            (do
              (swap! state update :users dissoc user-id)
              {:outcome :deleted})
            {:outcome :not-found})))})))

(defn seed-member! [conn member-id]
  @(d/transact
    conn
    [{:member/member-id member-id
      :member/name "Alice Example"
      :member/email "alice@example.com"
      :member/username "alice.example"}]))

(defn issue! [conn member-id]
  (domain/issue!
   {:datomic-conn conn :clock (constantly issued-at)}
   {:member-id member-id :code "invite-code" :expires-at expires-at}))

(defn input [conn member-id requested]
  (let [{:keys [status generation]} (domain/state (d/db conn) member-id)]
    {:member/member-id member-id
     :member-invite/observed-status status
     :member-invite/observed-generation generation
     :member-invite/requested-at requested
     :keycloak/group-name "Mitglieder"}))

(defn resources [conn keycloak]
  {:datomic-conn conn
   :clock (constantly requested-at)
   :keycloak keycloak})

(defn invitation [conn member-id]
  (domain/state (d/db conn) member-id))

(defn markers [member-id generation]
  {"probematic-member-id" [(str member-id)]
   "probematic-invite-generation" [(str generation)]})

(deftest new-acceptance-provisions-links-enables-and-finalizes-test
  (let [{:keys [conn member-id]} (tc/new-system "invitation-workflow-success")
        keycloak (fake-keycloak)]
    (seed-member! conn member-id)
    (is (= {:outcome :issued :generation 1} (issue! conn member-id)))
    (is (= {:member/member-id member-id
            :member-invite/result :accepted}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id requested-at))))
    (let [{:keys [status generation keycloak-id]}
          (invitation conn member-id)]
      (is (= accepted status))
      (is (= 5 generation))
      (is (= "created-user-1" keycloak-id))
      (is (nil? (:member/invite-code
                 (d/entity (d/db conn) [:member/member-id member-id]))))
      (is (= true (get-in @(:state keycloak)
                          [:users keycloak-id :enabled?])))
      (is (= (markers member-id 3)
             (get-in @(:state keycloak)
                     [:users keycloak-id :attributes])))
      (is (= #{[keycloak-id "group-members"]}
             (:memberships @(:state keycloak)))))))

(deftest invalid-profile-never-enters-keycloak-creation-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invitation-workflow-invalid-profile")
        keycloak (fake-keycloak)]
    (seed-member! conn member-id)
    @(d/transact conn [[:db/add
                        [:member/member-id member-id]
                        :member/name
                        ""]])
    (issue! conn member-id)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"invalid Keycloak profile"
         (workflows/accept-or-recover!
          (resources conn keycloak)
          (input conn member-id requested-at))))
    (is (= {:status accepting
            :generation 2
            :expires-at expires-at}
           (invitation conn member-id)))
    (is (not-any? (comp #{:create-user!} first)
                  (:calls @(:state keycloak))))))

(deftest user-retry-recovers-an-accepting-attempt-after-expiry-test
  (let [{:keys [conn member-id]} (tc/new-system "invitation-workflow-accepting-recovery")
        timeout (ex-info "group timeout" {:type :timeout})
        keycloak (fake-keycloak {:failures {:add-user-to-group! [timeout]}})]
    (seed-member! conn member-id)
    (issue! conn member-id)
    (is (identical?
         timeout
         (try
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id requested-at))
           nil
           (catch Exception exception exception))))
    (is (= creating (:status (invitation conn member-id))))
    (is (= false (get-in @(:state keycloak)
                         [:users "created-user-1" :enabled?])))
    (is (= {:member/member-id member-id
            :member-invite/result :accepted}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id expired-retry-at))))
    (is (= accepted (:status (invitation conn member-id))))))

(deftest user-retry-recovers-a-linked-disabled-user-test
  (let [{:keys [conn member-id]} (tc/new-system "invitation-workflow-activation-recovery")
        timeout (ex-info "enable timeout" {:type :timeout})
        keycloak (fake-keycloak {:failures {:set-user-enabled! [timeout]}})]
    (seed-member! conn member-id)
    (issue! conn member-id)
    (is (identical?
         timeout
         (try
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id requested-at))
           nil
           (catch Exception exception exception))))
    (is (= {:status activating
            :generation 4
            :expires-at expires-at
            :keycloak-id "created-user-1"}
           (invitation conn member-id)))
    (is (= {:member/member-id member-id
            :member-invite/result :accepted}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id expired-retry-at))))
    (is (= accepted (:status (invitation conn member-id))))))

(deftest compensating-recovery-deletes-only-an-owned-disabled-user-test
  (let [{:keys [conn member-id]} (tc/new-system "invitation-workflow-compensation-recovery")
        attempt-generation 2
        user {:id "attempt-user"
              :username "alice.example"
              :email "alice@example.com"
              :enabled? false
              :attributes (markers member-id attempt-generation)}
        keycloak (fake-keycloak {:users [user]})]
    (seed-member! conn member-id)
    @(d/transact
      conn
      [{:member/member-id member-id
        :member/invite-code "invite-code"
        :member/invite-expires-at expires-at
        :member/invite-status compensating
        :member/invite-generation 3
        :member/invite-status-at requested-at}])
    (is (= {:member/member-id member-id
            :member-invite/result :pending}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id expired-retry-at))))
    (is (= {:status pending
            :generation 4
            :expires-at expires-at}
           (invitation conn member-id)))
    (is (empty? (:users @(:state keycloak))))))

(deftest enabled-unlinked-attempt-user-needs-an-operator-and-is-not-mutated-test
  (let [{:keys [conn member-id]} (tc/new-system "invitation-workflow-unsafe-user")
        user {:id "enabled-attempt-user"
              :username "alice.example"
              :email "alice@example.com"
              :enabled? true
              :attributes (markers member-id 2)}
        keycloak (fake-keycloak {:users [user]})]
    (seed-member! conn member-id)
    @(d/transact
      conn
      [{:member/member-id member-id
        :member/invite-code "invite-code"
        :member/invite-expires-at expires-at
        :member/invite-status accepting
        :member/invite-generation 2
        :member/invite-status-at requested-at}])
    (is (= {:member/member-id member-id
            :member-invite/result :operator-required}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id requested-at))))
    (is (= accepting (:status (invitation conn member-id))))
    (is (= user (get-in @(:state keycloak) [:users (:id user)])))
    (is (not-any? (comp #{:delete-user! :add-user-to-group!}
                        first)
                  (:calls @(:state keycloak))))))

(deftest missing-created-user-is-retryable-without-exposing-trace-test
  (let [{:keys [conn member-id]} (tc/new-system "invitation-workflow-created-user-missing")
        keycloak (fake-keycloak {:hide-users #{"created-user-1"}})]
    (seed-member! conn member-id)
    (issue! conn member-id)
    (let [result (workflows/accept-or-recover!
                  (resources conn keycloak)
                  (input conn member-id requested-at))]
      (is (= {:member/member-id member-id
              :member-invite/result :retry}
             result))
      (is (not (contains? result :mycelium/trace))))
    (is (= {:status creating
            :generation 3
            :expires-at expires-at}
           (invitation conn member-id)))
    (is (= {:member/member-id member-id
            :member-invite/result :retry}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id expired-retry-at))))
    (is (= 1
           (count (filter (comp #{:create-user!} first)
                          (:calls @(:state keycloak))))))))

(deftest commit-then-timeout-is-reconciled-without-a-second-create-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invitation-workflow-create-commit-timeout")
        timeout (ex-info "create response timeout" {:type :timeout})
        keycloak (fake-keycloak
                  {:after-failures {:create-user! [timeout]}})]
    (seed-member! conn member-id)
    (issue! conn member-id)
    (is (identical?
         timeout
         (try
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id requested-at))
           nil
           (catch Exception exception exception))))
    (is (= {:status creating
            :generation 3
            :expires-at expires-at}
           (invitation conn member-id)))
    (is (= 1 (count (:users @(:state keycloak)))))
    (is (= {:member/member-id member-id
            :member-invite/result :accepted}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id expired-retry-at))))
    (is (= 1
           (count (filter (comp #{:create-user!} first)
                          (:calls @(:state keycloak)))))
        "creating recovery must not issue another create")
    (is (= accepted (:status (invitation conn member-id))))))

(deftest absent-user-during-creating-recovery-remains-fenced-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invitation-workflow-creating-absent")
        keycloak (fake-keycloak)]
    (seed-member! conn member-id)
    @(d/transact
      conn
      [{:member/member-id member-id
        :member/invite-code "invite-code"
        :member/invite-expires-at expires-at
        :member/invite-status creating
        :member/invite-generation 3
        :member/invite-status-at requested-at}])
    (dotimes [_ 2]
      (is (= {:member/member-id member-id
              :member-invite/result :retry}
             (workflows/accept-or-recover!
              (resources conn keycloak)
              (input conn member-id expired-retry-at)))))
    (is (= {:status creating
            :generation 3
            :expires-at expires-at}
           (invitation conn member-id)))
    (is (not-any? (comp #{:create-user! :delete-user!} first)
                  (:calls @(:state keycloak))))))

(deftest definite-create-rejection-can-compensate-and-release-test
  (let [{:keys [conn member-id]}
        (tc/new-system "invitation-workflow-create-rejected")
        keycloak (fake-keycloak {:create-outcomes [:rejected]})]
    (seed-member! conn member-id)
    (issue! conn member-id)
    (is (= {:member/member-id member-id
            :member-invite/result :pending}
           (workflows/accept-or-recover!
            (resources conn keycloak)
            (input conn member-id requested-at))))
    (is (= {:status pending
            :generation 5
            :expires-at expires-at}
           (invitation conn member-id)))
    (is (= 1
           (count (filter (comp #{:create-user!} first)
                          (:calls @(:state keycloak))))))))

(def service-requested-at #inst "2026-07-16T10:00:00.000-00:00")
(def service-expires-at #inst "2026-07-17T10:00:00.000-00:00")
(def expired-at #inst "2026-07-15T10:00:00.000-00:00")

(def service-pending :member.invite.status/pending)
(def service-accepted :member.invite.status/accepted)

(defn seed-invited-member!
  [conn member-id {:keys [code expiry status generation]
                   :or {status service-pending generation 1}}]
  @(d/transact
    conn
    [(cond-> {:member/member-id member-id
              :member/name "Alice Example"
              :member/nick "alice"
              :member/email "alice@example.com"
              :member/username "alice.example"
              :member/phone "+43677123456"
              :member/invite-status status
              :member/invite-generation generation
              :member/invite-status-at service-requested-at}
       code (assoc :member/invite-code code)
       expiry (assoc :member/invite-expires-at expiry))]))

(defn setup-req [{:keys [conn invite-code]}]
  {:db (d/db conn)
   :datomic-conn conn
   :params {:invite-code invite-code}
   :system {:datomic {:conn conn}
            :env {:app/base-url "https://dev.streetnoise.at"}
            :keycloak {:adapter :fake}}})

(defn thrown-reason [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (:reason (ex-data exception)))))

(deftest load-invite-uses-the-datomic-lifecycle-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-load")
        member-id (random-uuid)]
    (seed-invited-member! conn member-id
                          {:code "load-code"
                           :expiry service-expires-at})
    (is (= {:member {:member/member-id member-id
                     :member/name "Alice Example"
                     :member/email "alice@example.com"
                     :member/username "alice.example"}
            :invite-code "load-code"}
           (views/load-invite
            (assoc (setup-req {:conn conn :invite-code "load-code"})
                   :now service-requested-at))))))

(deftest setup-account-rejects-an-expired-pending-code-before-running-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-expired")
        member-id (random-uuid)
        runs (atom 0)]
    (seed-invited-member! conn member-id
                          {:code "expired"
                           :expiry expired-at})
    (is (= :code-expired
           (thrown-reason
            #(workflows/setup-account!
              {:now (constantly service-requested-at)
               :accept-or-recover! (fn [_resources _input]
                                     (swap! runs inc))}
              (setup-req {:conn conn :invite-code "expired"})))))
    (is (zero? @runs))))

(deftest setup-account-passes-only-safe-workflow-input-and-returns-canonical-member-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-success")
        member-id (random-uuid)
        invocation (atom nil)]
    (seed-invited-member! conn member-id
                          {:code "invite-123"
                           :expiry service-expires-at})
    (let [member
          (workflows/setup-account!
           {:now (constantly service-requested-at)
            :accept-or-recover!
            (fn [resources input]
              (reset! invocation {:resources resources :input input})
              @(d/transact
                conn
                [[:db/add [:member/member-id member-id]
                  :member/keycloak-id "keycloak-123"]
                 [:db/add [:member/member-id member-id]
                  :member/invite-status service-accepted]
                 [:db/add [:member/member-id member-id]
                  :member/invite-generation 2]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-code "invite-123"]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-expires-at service-expires-at]])
              {:member/member-id member-id
               :member-invite/result :accepted})}
           (setup-req {:conn conn :invite-code "invite-123"}))]
      (is (= "keycloak-123" (:member/keycloak-id member)))
      (is (= "keycloak-123"
             (:member/keycloak-id
              (q/retrieve-member (d/db conn) member-id))))
      (is (= {:member/member-id member-id
              :member-invite/observed-status service-pending
              :member-invite/observed-generation 1
              :member-invite/requested-at service-requested-at
              :keycloak/group-name "Mitglieder"}
             (:input @invocation)))
      (is (not (contains? (:input @invocation) :member/invite-code)))
      (is (= conn (get-in @invocation [:resources :datomic-conn])))
      (is (= {:adapter :fake}
             (get-in @invocation [:resources :keycloak])))
      (is (fn? (get-in @invocation [:resources :clock]))))))

(deftest setup-account-maps-finite-workflow-results-test
  (doseq [[result expected-reason]
          [[:pending :acceptance-retry]
           [:retry :acceptance-retry]
           [:stale :code-expired]
           [:operator-required :operator-required]]]
    (testing result
      (let [{:keys [conn]} (tc/new-system (str "invite-accept-" (name result)))
            member-id (random-uuid)]
        (seed-invited-member! conn member-id
                              {:code (name result)
                               :expiry service-expires-at})
        (is (= expected-reason
               (thrown-reason
                #(workflows/setup-account!
                  {:now (constantly service-requested-at)
                   :accept-or-recover!
                   (fn [_resources _input]
                     {:member/member-id member-id
                      :member-invite/result result})}
                  (setup-req {:conn conn
                              :invite-code (name result)})))))))))

(deftest service-accepted-result-is-verified-against-canonical-state-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-false-success")
        member-id (random-uuid)]
    (seed-invited-member! conn member-id
                          {:code "false-success"
                           :expiry service-expires-at})
    (is (= :operator-required
           (thrown-reason
            #(workflows/setup-account!
              {:now (constantly service-requested-at)
               :accept-or-recover!
               (fn [_resources _input]
                 {:member/member-id member-id
                  :member-invite/result :accepted})}
              (setup-req {:conn conn
                          :invite-code "false-success"})))))))

(deftest setup-account-recovers-a-finalize-commit-before-a-thrown-response-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-finalize-timeout")
        member-id (random-uuid)
        timeout (ex-info "Datomic response timed out" {:type :timeout})]
    (seed-invited-member! conn member-id
                          {:code "committed-code"
                           :expiry service-expires-at})
    (let [member
          (workflows/setup-account!
           {:now (constantly service-requested-at)
            :accept-or-recover!
            (fn [_resources _input]
              @(d/transact
                conn
                [[:db/add [:member/member-id member-id]
                  :member/keycloak-id "committed-user"]
                 [:db/add [:member/member-id member-id]
                  :member/invite-status service-accepted]
                 [:db/add [:member/member-id member-id]
                  :member/invite-generation 2]
                 [:db/add [:member/member-id member-id]
                  :member/invite-accepted-code-digest
                  (domain/accepted-receipt-digest "committed-code")]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-code "committed-code"]
                 [:db/retract [:member/member-id member-id]
                  :member/invite-expires-at service-expires-at]])
              (throw timeout))}
           (setup-req {:conn conn :invite-code "committed-code"}))]
      (is (= member-id (:member/member-id member)))
      (is (= "committed-user" (:member/keycloak-id member))))))

(deftest setup-account-preserves-an-error-when-finalize-did-not-commit-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-finalize-no-commit")
        member-id (random-uuid)
        timeout (ex-info "Datomic response timed out" {:type :timeout})]
    (seed-invited-member! conn member-id
                          {:code "uncommitted-code"
                           :expiry service-expires-at})
    (is (identical?
         timeout
         (try
           (workflows/setup-account!
            {:now (constantly service-requested-at)
             :accept-or-recover! (fn [_resources _input]
                                   (throw timeout))}
            (setup-req {:conn conn :invite-code "uncommitted-code"}))
           nil
           (catch Throwable exception
             exception))))))

(deftest service-accepted-receipt-resolves-a-fresh-retry-without-running-workflow-test
  (let [{:keys [conn]} (tc/new-system "invite-accept-durable-receipt")
        member-id (random-uuid)
        runs (atom 0)]
    (seed-invited-member! conn member-id
                          {:status service-accepted
                           :generation 5})
    @(d/transact
      conn
      [{:member/member-id member-id
        :member/keycloak-id "receipt-user"
        :member/invite-accepted-code-digest
        (domain/accepted-receipt-digest "accepted-code")}])
    (is (= member-id
           (:member/member-id
            (workflows/setup-account!
             {:now (constantly service-requested-at)
              :accept-or-recover! (fn [_resources _input]
                                    (swap! runs inc))}
             (setup-req {:conn conn :invite-code "accepted-code"})))))
    (is (zero? @runs))
    (is (= {:member {:member/member-id member-id
                     :member/name "Alice Example"
                     :member/email "alice@example.com"
                     :member/username "alice.example"}
            :invite-accepted? true}
           (views/load-invite
            (setup-req {:conn conn :invite-code "accepted-code"}))))))

(deftest service-accepted-receipt-requires-canonical-complete-state-test
  (doseq [[label status keycloak-id]
          [["wrong status" service-pending "receipt-user"]
           ["missing Keycloak link" service-accepted nil]]]
    (testing label
      (let [{:keys [conn]} (tc/new-system
                            (str "invite-accept-incomplete-receipt-"
                                 (name status)
                                 "-"
                                 (boolean keycloak-id)))
            member-id (random-uuid)]
        (seed-invited-member! conn member-id
                              {:status status
                               :generation 5})
        @(d/transact
          conn
          [(cond-> {:member/member-id member-id
                    :member/invite-accepted-code-digest
                    (domain/accepted-receipt-digest "unsafe-code")}
             keycloak-id (assoc :member/keycloak-id keycloak-id))])
        (is (= :code-expired
               (thrown-reason
                #(workflows/setup-account!
                  {:now (constantly service-requested-at)}
                  (setup-req {:conn conn
                              :invite-code "unsafe-code"})))))))))
