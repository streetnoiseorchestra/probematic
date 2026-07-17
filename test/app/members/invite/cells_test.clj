(ns app.members.invite.cells-test
  (:require
   [app.members.invite.cells]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d]
   [malli.core :as m]
   [mycelium.cell :as cell]
   [mycelium.dev :as myc.dev]))

(def pending :member.invite.status/pending)
(def accepting :member.invite.status/accepting)
(def creating :member.invite.status/creating)
(def activating :member.invite.status/activating)
(def compensating :member.invite.status/compensating)
(def accepted :member.invite.status/accepted)

(def requested-at #inst "2026-07-16T08:05:00.000-00:00")
(def transitioned-at #inst "2026-07-16T08:06:00.000-00:00")
(def expires-at #inst "2026-07-17T08:00:00.000-00:00")

(def dispatches
  {:member/claim-invitation
   [[:claimed #(= :claimed (:member-invite/claim-status %))]
    [:conflict #(= :conflict (:member-invite/claim-status %))]]
   :member/begin-keycloak-create
   [[:begun #(= :begun (:member-invite/create-status %))]
    [:conflict #(= :conflict (:member-invite/create-status %))]]
   :member/link-keycloak-user
   [[:linked #(= :linked (:member-invite/link-status %))]
    [:conflict #(= :conflict (:member-invite/link-status %))]]
   :member/begin-invitation-compensation
   [[:begun #(= :begun (:member-invite/compensation-status %))]
    [:conflict #(= :conflict (:member-invite/compensation-status %))]]
   :member/release-invitation
   [[:released #(= :released (:member-invite/release-status %))]
    [:conflict #(= :conflict (:member-invite/release-status %))]]
   :member/finalize-invitation
   [[:finalized #(= :finalized (:member-invite/finalize-status %))]
    [:conflict #(= :conflict (:member-invite/finalize-status %))]]})

(def member-cell-ids
  #{:member-invite/read-entry-state
    :member/read-invitation-state
    :member/read-keycloak-profile
    :member/claim-invitation
    :member/begin-keycloak-create
    :member/link-keycloak-user
    :member/begin-invitation-compensation
    :member/release-invitation
    :member/finalize-invitation})

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

(defn fixed-clock []
  (constantly transitioned-at))

(defn resources [conn]
  {:datomic-conn conn
   :clock        (fixed-clock)})

(defn seed-member!
  [conn member-id {:keys [status generation code expiry keycloak-id]}]
  @(d/transact
    conn
    [(cond-> {:member/member-id         member-id
              :member/name              "Ada Lovelace"
              :member/email             "ada@example.test"
              :member/username          "ada_l"
              :member/invite-status     status
              :member/invite-generation generation
              :member/invite-status-at  requested-at}
       code        (assoc :member/invite-code code)
       expiry      (assoc :member/invite-expires-at expiry)
       keycloak-id (assoc :member/keycloak-id keycloak-id))]))

(defn invitation-view [conn member-id]
  (let [member (d/entity (d/db conn) [:member/member-id member-id])
        status (:member/invite-status member)]
    {:status      (if (keyword? status) status (:db/ident status))
     :generation  (:member/invite-generation member)
     :status-at   (:member/invite-status-at member)
     :code        (:member/invite-code member)
     :expires-at  (:member/invite-expires-at member)
     :keycloak-id (:member/keycloak-id member)}))

(defn run-cell [cell-id resources input expected-dispatch]
  (myc.dev/test-cell
   cell-id
   (cond-> {:resources resources
            :input     input}
     expected-dispatch
     (assoc :dispatches (dispatches cell-id)
            :expected-dispatch expected-dispatch))))

(defn result-view [result]
  (select-keys result [:pass? :output :matched-dispatch]))

(deftest registered-member-cells-have-valid-malli-contracts-test
  (doseq [cell-id member-cell-ids]
    (testing (str cell-id " owns compiled input and output schemas")
      (let [contract (:schema (cell/cell-spec cell-id))]
        (is (m/schema? (:input contract)))
        (is (valid-output-contract? (:output contract)))))))

(deftest invitation-cell-inputs-require-inst-values
  (let [member-id (random-uuid)]
    (doseq [[cell-id input]
            [[:member-invite/read-entry-state
              {:member/member-id member-id
               :member-invite/observed-status pending
               :member-invite/observed-generation 1
               :member-invite/requested-at requested-at
               :keycloak/group-name "Mitglieder"}]
             [:member/claim-invitation
              {:member/member-id member-id
               :member-invite/requested-at requested-at
               :member-invite/state {:status pending
                                     :generation 1
                                     :expires-at expires-at}}]
             [:member-invite/plan-entry
              {:member-invite/observed-status pending
               :member-invite/observed-generation 1
               :member-invite/requested-at requested-at
               :member-invite/state {:status pending
                                     :generation 1
                                     :expires-at expires-at}}]]]
      (testing cell-id
        (let [input-schema (get-in (cell/cell-spec cell-id) [:schema :input])]
          (is (= {:t-inst            true
                  :java-time-instant false}
                 {:t-inst (m/validate input-schema input)
                  :java-time-instant
                  (m/validate
                   input-schema
                   (assoc input
                          :member-invite/requested-at
                          (java.time.Instant/parse
                           "2026-07-16T08:05:00Z")))})))))))

(deftest read-cells-return-safe-contract-projections
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-read-cells")]
    (seed-member! conn member-id
                  {:status compensating
                   :generation 4
                   :code "secret-code"
                   :expiry expires-at})
    (is (= {:pass? true
            :output {:member-invite/state
                     {:status compensating
                      :generation 4
                      :expires-at expires-at}}
            :matched-dispatch nil}
           (result-view
            (run-cell
             :member-invite/read-entry-state
             {:datomic-conn conn}
             {:member/member-id member-id
              :member-invite/observed-status accepting
              :member-invite/observed-generation 2
              :member-invite/requested-at requested-at
              :keycloak/group-name "probematic"}
             nil))))
    (is (= {:pass? true
            :output {:member-invite/state
                     {:status compensating
                      :generation 4
                      :expires-at expires-at}}
            :matched-dispatch nil}
           (result-view
            (run-cell
             :member/read-invitation-state
             {:datomic-conn conn}
             {:member/member-id member-id}
             nil))))
    (is (= {:pass? true
            :output {:member-invite/keycloak-profile
                     {:username "ada_l"
                      :email "ada@example.test"
                      :first-name "Ada Lovelace"}}
            :matched-dispatch nil}
           (result-view
            (run-cell
             :member/read-keycloak-profile
             {:datomic-conn conn}
             {:member/member-id member-id}
             nil))))))

(deftest claim-cell-covers-claimed-and-stale-outcomes
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-claim-cell")
        state {:status pending :generation 1 :expires-at expires-at}]
    (seed-member! conn member-id
                  {:status pending
                   :generation 1
                   :code "claim-code"
                   :expiry expires-at})
    (is (= {:pass? true
            :output {:member-invite/claim-status :claimed
                     :member-invite/attempt-generation 2}
            :matched-dispatch :claimed}
           (result-view
            (run-cell
             :member/claim-invitation
             (resources conn)
             {:member/member-id member-id
              :member-invite/requested-at requested-at
              :member-invite/state state}
             :claimed))))
    (is (= {:status accepting
            :generation 2
            :status-at transitioned-at
            :code "claim-code"
            :expires-at expires-at
            :keycloak-id nil}
           (invitation-view conn member-id)))
    (is (= {:pass? true
            :output {:member-invite/claim-status :conflict
                     :member-invite/result :retry}
            :matched-dispatch :conflict}
           (result-view
            (run-cell
             :member/claim-invitation
             (resources conn)
             {:member/member-id member-id
              :member-invite/requested-at requested-at
              :member-invite/state state}
             :conflict))))))

(deftest claim-cell-enforces-the-strict-expiry-boundary
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-expired-claim-cell")
        state {:status pending :generation 1 :expires-at requested-at}]
    (seed-member! conn member-id
                  {:status pending
                   :generation 1
                   :code "expired-code"
                   :expiry requested-at})
    (is (= {:pass? true
            :output {:member-invite/claim-status :conflict
                     :member-invite/result :retry}
            :matched-dispatch :conflict}
           (result-view
            (run-cell
             :member/claim-invitation
             (resources conn)
             {:member/member-id member-id
              :member-invite/requested-at requested-at
              :member-invite/state state}
             :conflict))))))

(deftest begin-create-cell-fences-the-external-create
  (let [{:keys [conn member-id]}
        (tc/new-system "member-invitation-begin-create-cell")]
    (seed-member! conn member-id
                  {:status accepting
                   :generation 2
                   :code "create-code"
                   :expiry expires-at})
    (is (= {:pass? true
            :output {:member-invite/create-status :begun
                     :member-invite/attempt-generation 3
                     :member-invite/expected-status creating
                     :member-invite/expected-generation 3
                     :keycloak/user-attributes
                     {"probematic-member-id" [(str member-id)]
                      "probematic-invite-generation" ["3"]}}
            :matched-dispatch :begun}
           (result-view
            (run-cell
             :member/begin-keycloak-create
             (resources conn)
             {:member/member-id member-id
              :member-invite/attempt-generation 2}
             :begun))))
    (is (= {:status creating
            :generation 3
            :status-at transitioned-at
            :code "create-code"
            :expires-at expires-at
            :keycloak-id nil}
           (invitation-view conn member-id)))
    (is (= {:pass? true
            :output {:member-invite/create-status :conflict
                     :member-invite/result :retry}
            :matched-dispatch :conflict}
           (result-view
            (run-cell
             :member/begin-keycloak-create
             (resources conn)
             {:member/member-id member-id
              :member-invite/attempt-generation 2}
             :conflict))))))

(deftest link-cell-excludes-later-compensation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-link-cell")]
    (seed-member! conn member-id
                  {:status accepting
                   :generation 2
                   :code "link-code"
                   :expiry expires-at})
    (is (= {:pass? true
            :output {:member-invite/link-status :linked
                     :member-invite/activation-generation 3
                     :keycloak/enabled? true}
            :matched-dispatch :linked}
           (result-view
            (run-cell
             :member/link-keycloak-user
             (resources conn)
             {:member/member-id member-id
              :member-invite/attempt-generation 2
              :member-invite/expected-status accepting
              :member-invite/expected-generation 2
              :keycloak/user-id "link-user"}
             :linked))))
    (is (= {:pass? true
            :output {:member-invite/compensation-status :conflict
                     :member-invite/result :retry}
            :matched-dispatch :conflict}
           (result-view
            (run-cell
             :member/begin-invitation-compensation
             (resources conn)
             {:member/member-id member-id
              :member-invite/attempt-generation 2
              :member-invite/expected-status accepting
              :member-invite/expected-generation 2}
             :conflict))))))

(deftest compensation-cell-excludes-a-later-link
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-compensation-cell")]
    (seed-member! conn member-id
                  {:status accepting
                   :generation 2
                   :code "compensate-code"
                   :expiry expires-at})
    (is (= {:pass? true
            :output {:member-invite/compensation-status :begun
                     :member-invite/compensation-generation 3}
            :matched-dispatch :begun}
           (result-view
            (run-cell
             :member/begin-invitation-compensation
             (resources conn)
             {:member/member-id member-id
              :member-invite/attempt-generation 2
              :member-invite/expected-status accepting
              :member-invite/expected-generation 2}
             :begun))))
    (is (= {:pass? true
            :output {:member-invite/link-status :conflict
                     :member-invite/result :retry}
            :matched-dispatch :conflict}
           (result-view
            (run-cell
             :member/link-keycloak-user
             (resources conn)
             {:member/member-id member-id
              :member-invite/attempt-generation 2
              :member-invite/expected-status accepting
              :member-invite/expected-generation 2
              :keycloak/user-id "late-user"}
             :conflict))))))

(deftest creating-state-can-link-or-compensate-only-at-its-current-generation
  (let [{link-conn :conn link-member-id :member-id}
        (tc/new-system "member-invitation-creating-link-cell")
        {comp-conn :conn comp-member-id :member-id}
        (tc/new-system "member-invitation-creating-comp-cell")]
    (doseq [[conn member-id code]
            [[link-conn link-member-id "creating-link-code"]
             [comp-conn comp-member-id "creating-comp-code"]]]
      (seed-member! conn member-id
                    {:status creating
                     :generation 3
                     :code code
                     :expiry expires-at}))
    (is (= {:pass? true
            :output {:member-invite/link-status :linked
                     :member-invite/activation-generation 4
                     :keycloak/enabled? true}
            :matched-dispatch :linked}
           (result-view
            (run-cell
             :member/link-keycloak-user
             (resources link-conn)
             {:member/member-id link-member-id
              :member-invite/attempt-generation 3
              :member-invite/expected-status creating
              :member-invite/expected-generation 3
              :keycloak/user-id "created-user"}
             :linked))))
    (is (= {:pass? true
            :output {:member-invite/compensation-status :begun
                     :member-invite/compensation-generation 4}
            :matched-dispatch :begun}
           (result-view
            (run-cell
             :member/begin-invitation-compensation
             (resources comp-conn)
             {:member/member-id comp-member-id
              :member-invite/attempt-generation 3
              :member-invite/expected-status creating
              :member-invite/expected-generation 3}
             :begun))))))

(deftest release-cell-preserves-the-bearer-and-guards-its-generation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-release-cell")]
    (seed-member! conn member-id
                  {:status compensating
                   :generation 3
                   :code "release-code"
                   :expiry expires-at})
    (is (= {:pass? true
            :output {:member-invite/release-status :conflict
                     :member-invite/result :retry}
            :matched-dispatch :conflict}
           (result-view
            (run-cell
             :member/release-invitation
             (resources conn)
             {:member/member-id member-id
              :member-invite/compensation-generation 2}
             :conflict))))
    (is (= {:pass? true
            :output {:member-invite/release-status :released
                     :member-invite/result :pending}
            :matched-dispatch :released}
           (result-view
            (run-cell
             :member/release-invitation
             (resources conn)
             {:member/member-id member-id
              :member-invite/compensation-generation 3}
             :released))))
    (is (= {:status pending
            :generation 4
            :status-at transitioned-at
            :code "release-code"
            :expires-at expires-at
            :keycloak-id nil}
           (invitation-view conn member-id)))))

(deftest finalize-cell-retracts-the-bearer-and-guards-user-and-generation
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-finalize-cell")]
    (seed-member! conn member-id
                  {:status activating
                   :generation 3
                   :code "finalize-code"
                   :expiry expires-at
                   :keycloak-id "finalize-user"})
    (doseq [input [{:member/member-id member-id
                    :member-invite/activation-generation 2
                    :keycloak/user-id "finalize-user"}
                   {:member/member-id member-id
                    :member-invite/activation-generation 3
                    :keycloak/user-id "wrong-user"}]]
      (is (= {:pass? true
              :output {:member-invite/finalize-status :conflict
                       :member-invite/result :retry}
              :matched-dispatch :conflict}
             (result-view
              (run-cell
               :member/finalize-invitation
               (resources conn)
               input
               :conflict)))))
    (is (= {:pass? true
            :output {:member-invite/finalize-status :finalized
                     :member-invite/result :accepted}
            :matched-dispatch :finalized}
           (result-view
            (run-cell
             :member/finalize-invitation
             (resources conn)
             {:member/member-id member-id
              :member-invite/activation-generation 3
              :keycloak/user-id "finalize-user"}
             :finalized))))
    (is (= {:status accepted
            :generation 4
            :status-at transitioned-at
            :code nil
            :expires-at nil
            :keycloak-id "finalize-user"}
           (invitation-view conn member-id)))))

(deftest cells-require-a-zero-argument-clock-that-returns-an-inst
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-clock-cell")]
    (seed-member! conn member-id
                  {:status accepting
                   :generation 2
                   :code "clock-code"
                   :expiry expires-at})
    (let [handler (:handler (cell/get-cell :member/link-keycloak-user))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler {:datomic-conn conn}
                            {:member/member-id member-id
                             :member-invite/attempt-generation 2
                             :member-invite/expected-status accepting
                             :member-invite/expected-generation 2
                             :keycloak/user-id "clock-user"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler {:datomic-conn conn
                             :clock (constantly :not-an-inst)}
                            {:member/member-id member-id
                             :member-invite/attempt-generation 2
                             :member-invite/expected-status accepting
                             :member-invite/expected-generation 2
                             :keycloak/user-id "clock-user"}))))))

(deftest non-cas-datomic-failures-escape-the-cell
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-cell-unknown")
        other-member-id          (random-uuid)]
    (seed-member! conn member-id
                  {:status accepting
                   :generation 2
                   :code "unknown-code"
                   :expiry expires-at})
    @(d/transact conn [{:member/member-id other-member-id
                        :member/keycloak-id "already-owned"}])
    (let [handler (:handler (cell/get-cell :member/link-keycloak-user))]
      (is (thrown? Throwable
                   (handler (resources conn)
                            {:member/member-id member-id
                             :member-invite/attempt-generation 2
                             :member-invite/expected-status accepting
                             :member-invite/expected-generation 2
                             :keycloak/user-id "already-owned"}))))))

(def member-id
  #uuid "f2ecef79-b96a-4ee0-9e67-c4966144c4c8")

(def other-member-id
  #uuid "12a56ee0-2e80-40ae-b266-4b1ca7edfc79")

(def planning-requested-at
  #inst "2026-07-16T12:00:00.000-00:00")

(def exact-markers
  {"probematic-member-id" [(str member-id)]
   "probematic-invite-generation" ["7"]})

(defn- invoke [cell-id data]
  ((:handler (cell/get-cell! cell-id)) {} data))

(defn- entry-input [observed-status observed-generation state]
  {:member-invite/observed-status observed-status
   :member-invite/observed-generation observed-generation
   :member-invite/requested-at planning-requested-at
   :member-invite/state state})

(deftest plan-entry-decision-table-test
  (doseq [[case input expected]
          [["matching unexpired pending state starts a claim"
            (entry-input :member.invite.status/pending
                         6
                         {:status :member.invite.status/pending
                          :generation 6
                          :expires-at #inst "2026-07-16T12:00:00.001-00:00"})
            {:member-invite/entry :claim}]
           ["expiry equal to the request time is stale"
            (entry-input :member.invite.status/pending
                         6
                         {:status :member.invite.status/pending
                          :generation 6
                          :expires-at planning-requested-at})
            {:member-invite/entry :stale
             :member-invite/result :stale}]
           ["expiry before the request time is stale"
            (entry-input :member.invite.status/pending
                         6
                         {:status :member.invite.status/pending
                          :generation 6
                          :expires-at #inst "2026-07-16T11:59:59.999-00:00"})
            {:member-invite/entry :stale
             :member-invite/result :stale}]
           ["pending state without an expiry is stale"
            (entry-input :member.invite.status/pending
                         6
                         {:status :member.invite.status/pending
                          :generation 6})
            {:member-invite/entry :stale
             :member-invite/result :stale}]
           ["matching accepting state resumes after expiry"
            (entry-input :member.invite.status/accepting
                         7
                         {:status :member.invite.status/accepting
                          :generation 7
                          :expires-at #inst "2026-07-01T00:00:00.000-00:00"})
            {:member-invite/entry :resume}]
           ["matching creating state resumes after expiry"
            (entry-input :member.invite.status/creating
                         8
                         {:status :member.invite.status/creating
                          :generation 8
                          :expires-at #inst "2026-07-01T00:00:00.000-00:00"})
            {:member-invite/entry :resume}]
           ["matching activating state resumes"
            (entry-input :member.invite.status/activating
                         8
                         {:status :member.invite.status/activating
                          :generation 8
                          :keycloak-id "kc-1"})
            {:member-invite/entry :resume}]
           ["matching compensating state resumes"
            (entry-input :member.invite.status/compensating
                         8
                         {:status :member.invite.status/compensating
                          :generation 8})
            {:member-invite/entry :resume}]
           ["matching accepted state is complete"
            (entry-input :member.invite.status/accepted
                         9
                         {:status :member.invite.status/accepted
                          :generation 9
                          :keycloak-id "kc-1"})
            {:member-invite/entry :complete
             :member-invite/result :accepted}]
           ["matching revoked state is stale"
            (entry-input :member.invite.status/revoked
                         7
                         {:status :member.invite.status/revoked
                          :generation 7})
            {:member-invite/entry :stale
             :member-invite/result :stale}]
           ["a changed status is stale"
            (entry-input :member.invite.status/pending
                         6
                         {:status :member.invite.status/accepting
                          :generation 7})
            {:member-invite/entry :stale
             :member-invite/result :stale}]
           ["a changed generation is stale"
            (entry-input :member.invite.status/accepting
                         6
                         {:status :member.invite.status/accepting
                          :generation 7})
            {:member-invite/entry :stale
             :member-invite/result :stale}]]]
    (testing case
      (is (= expected
             (invoke :member-invite/plan-entry input))))))

(deftest plan-progress-decision-table-test
  (doseq [[case state expected]
          [["accepting provisions with the current generation"
            {:status :member.invite.status/accepting
             :generation 7}
            {:member-invite/progress :provision
             :member-invite/attempt-generation 7
             :member-invite/expected-status
             :member.invite.status/accepting
             :member-invite/expected-generation 7
             :keycloak/user-attributes exact-markers}]
           ["creating reconciles its own external-user generation"
            {:status :member.invite.status/creating
             :generation 7}
            {:member-invite/progress :reconcile-create
             :member-invite/attempt-generation 7
             :member-invite/expected-status
             :member.invite.status/creating
             :member-invite/expected-generation 7
             :keycloak/user-attributes exact-markers}]
           ["activating resumes the preceding attempt generation"
            {:status :member.invite.status/activating
             :generation 8
             :keycloak-id "kc-1"}
            {:member-invite/progress :activate
             :member-invite/attempt-generation 7
             :member-invite/activation-generation 8
             :keycloak/user-id "kc-1"
             :keycloak/user-attributes exact-markers
             :keycloak/enabled? true}]
           ["compensating resumes the preceding attempt generation"
            {:status :member.invite.status/compensating
             :generation 8}
            {:member-invite/progress :compensate
             :member-invite/attempt-generation 7
             :member-invite/compensation-generation 8
             :keycloak/user-attributes exact-markers}]
           ["accepted with a linked account is complete"
            {:status :member.invite.status/accepted
             :generation 9
             :keycloak-id "kc-1"}
            {:member-invite/progress :complete
             :member-invite/result :accepted}]
           ["pending is stale after entry planning"
            {:status :member.invite.status/pending
             :generation 6}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["revoked is stale"
            {:status :member.invite.status/revoked
             :generation 7}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["accepting with a linked account is stale"
            {:status :member.invite.status/accepting
             :generation 7
             :keycloak-id "kc-1"}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["activating without a linked account is stale"
            {:status :member.invite.status/activating
             :generation 8}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["activating cannot derive generation zero"
            {:status :member.invite.status/activating
             :generation 1
             :keycloak-id "kc-1"}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["creating with a linked account is stale"
            {:status :member.invite.status/creating
             :generation 8
             :keycloak-id "kc-1"}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["creating cannot derive generation zero"
            {:status :member.invite.status/creating
             :generation 1}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["compensating with a linked account is stale"
            {:status :member.invite.status/compensating
             :generation 8
             :keycloak-id "kc-1"}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["compensating cannot derive generation zero"
            {:status :member.invite.status/compensating
             :generation 1}
            {:member-invite/progress :stale
             :member-invite/result :stale}]
           ["accepted without a linked account is stale"
            {:status :member.invite.status/accepted
             :generation 9}
            {:member-invite/progress :stale
             :member-invite/result :stale}]]]
    (testing case
      (is (= expected
             (invoke :member-invite/plan-progress
                     {:member/member-id member-id
                      :member-invite/state state}))))))

(deftest build-keycloak-user-spec-test
  (is (= {:keycloak/user-spec
          {:username "alice.example"
           :email "alice@example.com"
           :first-name "Alice Example"
           :enabled? false
           :email-verified? true
           :attributes exact-markers}}
         (invoke
          :member-invite/build-keycloak-user-spec
          {:member-invite/keycloak-profile
           {:username "alice.example"
            :email "alice@example.com"
            :first-name "Alice Example"}
           :keycloak/user-attributes exact-markers}))))

(defn- user-input [progress enabled? attributes]
  {:member-invite/progress progress
   :member-invite/attempt-generation 7
   :member/member-id member-id
   :keycloak/user {:id "kc-1"
                   :username "alice.example"
                   :email "alice@example.com"
                   :enabled? enabled?
                   :attributes attributes}})

(deftest classify-attempt-user-decision-table-test
  (doseq [[case progress enabled? owned? expected]
          [["owned disabled provisioning user is configurable"
            :provision false true
            {:member-invite/user-step :configure}]
           ["owned disabled creating user is configurable"
            :reconcile-create false true
            {:member-invite/user-step :configure}]
           ["enabled creating user is unsafe"
            :reconcile-create true true
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["enabled unlinked provisioning user is unsafe"
            :provision true true
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["unowned disabled provisioning user is unsafe"
            :provision false false
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["unowned enabled provisioning user is unsafe"
            :provision true false
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["owned disabled activating user must be enabled"
            :activate false true
            {:member-invite/user-step :enable
             :keycloak/enabled? true}]
           ["owned enabled activating user can be finalized"
            :activate true true
            {:member-invite/user-step :finalize}]
           ["unowned disabled activating user is unsafe"
            :activate false false
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["unowned enabled activating user is unsafe"
            :activate true false
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["owned disabled compensation user can be deleted"
            :compensate false true
            {:member-invite/user-step :delete}]
           ["enabled unlinked compensation user is unsafe"
            :compensate true true
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["unowned disabled compensation user is unsafe"
            :compensate false false
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           ["unowned enabled compensation user is unsafe"
            :compensate true false
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]]]
    (testing case
      (is (= expected
             (invoke :member-invite/classify-attempt-user
                     (user-input progress
                                 enabled?
                                 (if owned? exact-markers {}))))))))

(deftest classify-attempt-user-requires-exact-marker-values-test
  (doseq [[case attributes]
          [["missing member marker"
            {"probematic-invite-generation" ["7"]}]
           ["wrong member marker"
            {"probematic-member-id" [(str other-member-id)]
             "probematic-invite-generation" ["7"]}]
           ["duplicate member marker values"
            {"probematic-member-id" [(str member-id) (str member-id)]
             "probematic-invite-generation" ["7"]}]
           ["missing generation marker"
            {"probematic-member-id" [(str member-id)]}]
           ["wrong generation marker"
            {"probematic-member-id" [(str member-id)]
             "probematic-invite-generation" ["8"]}]
           ["duplicate generation marker values"
            {"probematic-member-id" [(str member-id)]
             "probematic-invite-generation" ["7" "7"]}]]]
    (testing case
      (is (= {:member-invite/user-step :unsafe
              :member-invite/result :operator-required}
             (invoke :member-invite/classify-attempt-user
                     (user-input :compensate false attributes))))))

  (testing "unrelated attributes do not change exact marker ownership"
    (is (= {:member-invite/user-step :delete}
           (invoke :member-invite/classify-attempt-user
                   (user-input :compensate
                               false
                               (assoc exact-markers "department" ["music"])))))))

(def contract-member-id
  #uuid "f2ecef79-b96a-4ee0-9e67-c4966144c4c8")

(def contract-requested-at
  #inst "2026-07-16T12:00:00.000-00:00")

(def contract-markers
  {"probematic-member-id" [(str contract-member-id)]
   "probematic-invite-generation" ["7"]})

(def entry-dispatches
  [[:claim #(= :claim (:member-invite/entry %))]
   [:resume #(= :resume (:member-invite/entry %))]
   [:done #(contains? #{:complete :stale}
                      (:member-invite/entry %))]])

(def progress-dispatches
  [[:provision #(= :provision (:member-invite/progress %))]
   [:reconcile-create #(= :reconcile-create
                          (:member-invite/progress %))]
   [:activate #(= :activate (:member-invite/progress %))]
   [:compensate #(= :compensate (:member-invite/progress %))]
   [:done #(contains? #{:complete :stale}
                      (:member-invite/progress %))]])

(def user-dispatches
  [[:configure #(= :configure (:member-invite/user-step %))]
   [:enable #(= :enable (:member-invite/user-step %))]
   [:finalize #(= :finalize (:member-invite/user-step %))]
   [:delete #(= :delete (:member-invite/user-step %))]
   [:stop #(= :unsafe (:member-invite/user-step %))]])

(def planning-cell-ids
  #{:member-invite/plan-entry
    :member-invite/plan-progress
    :member-invite/build-keycloak-user-spec
    :member-invite/classify-attempt-user
    :member-invite/flag-ambiguous-attempt-user})

(defn- load-cell-contract-fixture [f]
  (require 'app.members.invite.cells :reload)
  (f))

(use-fixtures :once load-cell-contract-fixture)

(defn- checked-result [cell-id opts]
  (select-keys (myc.dev/test-cell cell-id opts)
               [:pass? :errors :output :matched-dispatch]))

(deftest registered-planning-cells-have-valid-malli-contracts-test
  (doseq [cell-id planning-cell-ids]
    (let [contract (:schema (cell/cell-spec cell-id))]
      (is (m/schema? (:input contract)))
      (is (valid-output-contract? (:output contract))))))

(deftest plan-entry-cell-contract-test
  (doseq [[dispatch state expected]
          [[:claim
            {:status :member.invite.status/pending
             :generation 6
             :expires-at #inst "2026-07-16T12:00:00.001-00:00"}
            {:member-invite/entry :claim}]
           [:resume
            {:status :member.invite.status/accepting
             :generation 7}
            {:member-invite/entry :resume}]
           [:done
            {:status :member.invite.status/accepted
             :generation 9
             :keycloak-id "kc-1"}
            {:member-invite/entry :complete
             :member-invite/result :accepted}]
           [:done
            {:status :member.invite.status/revoked
             :generation 7}
            {:member-invite/entry :stale
             :member-invite/result :stale}]]]
    (is (= {:pass? true
            :errors []
            :output expected
            :matched-dispatch dispatch}
           (checked-result
            :member-invite/plan-entry
            {:input {:member-invite/observed-status (:status state)
                     :member-invite/observed-generation (:generation state)
                     :member-invite/requested-at contract-requested-at
                     :member-invite/state state}
             :dispatches entry-dispatches
             :expected-dispatch dispatch})))))

(deftest plan-progress-cell-contract-test
  (doseq [[dispatch state expected]
          [[:provision
            {:status :member.invite.status/accepting
             :generation 7}
            {:member-invite/progress :provision
             :member-invite/attempt-generation 7
             :member-invite/expected-status
             :member.invite.status/accepting
             :member-invite/expected-generation 7
             :keycloak/user-attributes contract-markers}]
           [:reconcile-create
            {:status :member.invite.status/creating
             :generation 7}
            {:member-invite/progress :reconcile-create
             :member-invite/attempt-generation 7
             :member-invite/expected-status
             :member.invite.status/creating
             :member-invite/expected-generation 7
             :keycloak/user-attributes contract-markers}]
           [:activate
            {:status :member.invite.status/activating
             :generation 8
             :keycloak-id "kc-1"}
            {:member-invite/progress :activate
             :member-invite/attempt-generation 7
             :member-invite/activation-generation 8
             :keycloak/user-id "kc-1"
             :keycloak/user-attributes contract-markers
             :keycloak/enabled? true}]
           [:compensate
            {:status :member.invite.status/compensating
             :generation 8}
            {:member-invite/progress :compensate
             :member-invite/attempt-generation 7
             :member-invite/compensation-generation 8
             :keycloak/user-attributes contract-markers}]
           [:done
            {:status :member.invite.status/accepted
             :generation 9
             :keycloak-id "kc-1"}
            {:member-invite/progress :complete
             :member-invite/result :accepted}]
           [:done
            {:status :member.invite.status/revoked
             :generation 7}
            {:member-invite/progress :stale
             :member-invite/result :stale}]]]
    (is (= {:pass? true
            :errors []
            :output expected
            :matched-dispatch dispatch}
           (checked-result
            :member-invite/plan-progress
            {:input {:member/member-id contract-member-id
                     :member-invite/state state}
             :dispatches progress-dispatches
             :expected-dispatch dispatch})))))

(deftest build-keycloak-user-spec-cell-contract-test
  (let [expected
        {:keycloak/user-spec
         {:username "alice.example"
          :email "alice@example.com"
          :first-name "Alice Example"
          :enabled? false
          :email-verified? true
          :attributes contract-markers}}]
    (is (= {:pass? true
            :errors []
            :output expected
            :matched-dispatch nil}
           (checked-result
            :member-invite/build-keycloak-user-spec
            {:input {:member-invite/keycloak-profile
                     {:username "alice.example"
                      :email "alice@example.com"
                      :first-name "Alice Example"}
                     :keycloak/user-attributes contract-markers}})))))

(deftest classify-attempt-user-cell-contract-test
  (doseq [[dispatch progress enabled? expected]
          [[:configure :provision false
            {:member-invite/user-step :configure}]
           [:configure :reconcile-create false
            {:member-invite/user-step :configure}]
           [:stop :reconcile-create true
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]
           [:enable :activate false
            {:member-invite/user-step :enable
             :keycloak/enabled? true}]
           [:finalize :activate true
            {:member-invite/user-step :finalize}]
           [:delete :compensate false
            {:member-invite/user-step :delete}]
           [:stop :compensate true
            {:member-invite/user-step :unsafe
             :member-invite/result :operator-required}]]]
    (is (= {:pass? true
            :errors []
            :output expected
            :matched-dispatch dispatch}
           (checked-result
            :member-invite/classify-attempt-user
            {:input {:member-invite/progress progress
                     :member-invite/attempt-generation 7
                     :member/member-id contract-member-id
                     :keycloak/user
                     {:id "kc-1"
                      :username "alice.example"
                      :email "alice@example.com"
                      :enabled? enabled?
                      :attributes contract-markers}}
             :dispatches user-dispatches
             :expected-dispatch dispatch})))))

(deftest ambiguous-attempt-user-cell-contract-test
  (is (= {:pass? true
          :errors []
          :output {:member-invite/result :operator-required}
          :matched-dispatch nil}
         (checked-result
          :member-invite/flag-ambiguous-attempt-user
          {:input {:keycloak/user-lookup :ambiguous
                   :keycloak/match-count 2}}))))
