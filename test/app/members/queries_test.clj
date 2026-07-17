(ns app.members.queries-test
  (:require
   [app.members.queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d])
  (:import
   [java.time Instant]
   [java.util Date]))

(def pending :member.invite.status/pending)
(def accepting :member.invite.status/accepting)
(def creating :member.invite.status/creating)
(def activating :member.invite.status/activating)
(def compensating :member.invite.status/compensating)
(def accepted :member.invite.status/accepted)
(def revoked :member.invite.status/revoked)

(def now (Date/from (Instant/parse "2026-07-16T10:00:00Z")))
(def before-now (Date/from (Instant/parse "2026-07-16T09:59:59Z")))
(def after-now (Date/from (Instant/parse "2026-07-16T10:00:01Z")))

(defn- query-fn [sym]
  (ns-resolve 'app.members.queries sym))

(defn- supports-arity? [v n]
  (some #(= n (count %)) (:arglists (meta v))))

(defn- seed-invitation!
  [conn {:keys [member-id name code expires-at status generation keycloak-id]}]
  @(d/transact
    conn
    [(cond-> {:member/member-id member-id
              :member/name name
              :member/email (str member-id "@example.test")
              :member/username (str "user-" member-id)
              :member/invite-code code
              :member/invite-expires-at expires-at
              :member/invite-status status
              :member/invite-generation generation
              :member/invite-status-at before-now}
       keycloak-id (assoc :member/keycloak-id keycloak-id))]))

(deftest invitation-state-by-code-test
  (if-let [invitation-state-by-code (query-fn 'invitation-state-by-code)]
    (let [{:keys [conn]} (tc/new-system "invitation-state-by-code")
          member-id (random-uuid)]
      (seed-invitation! conn {:member-id member-id
                              :name "Alice"
                              :code "state-code"
                              :expires-at before-now
                              :status pending
                              :generation 7})
      (is (= {:member/member-id member-id
              :member/invite-code "state-code"
              :member/invite-expires-at before-now
              :member/invite-status pending
              :member/invite-generation 7}
             (invitation-state-by-code (d/db conn) "state-code")))
      (is (nil? (invitation-state-by-code (d/db conn) "missing")))
      (is (nil? (invitation-state-by-code (d/db conn) "")))
      (is (nil? (invitation-state-by-code (d/db conn) nil))))
    (is false "app.members.queries/invitation-state-by-code is not implemented")))

(deftest acceptance-invitation-test
  (if-let [acceptance-invitation (query-fn 'acceptance-invitation)]
    (let [{:keys [conn]} (tc/new-system "acceptance-invitation")
          pending-id (random-uuid)
          equal-id (random-uuid)
          expired-id (random-uuid)
          accepting-id (random-uuid)
          creating-id (random-uuid)
          activating-id (random-uuid)
          compensating-id (random-uuid)]
      (doseq [invitation [{:member-id pending-id
                           :name "Pending"
                           :code "pending"
                           :expires-at after-now
                           :status pending
                           :generation 3}
                          {:member-id equal-id
                           :name "Equal"
                           :code "equal"
                           :expires-at now
                           :status pending
                           :generation 4}
                          {:member-id expired-id
                           :name "Expired"
                           :code "expired"
                           :expires-at before-now
                           :status pending
                           :generation 5}
                          {:member-id accepting-id
                           :name "Accepting"
                           :code "accepting"
                           :expires-at before-now
                           :status accepting
                           :generation 6}
                          {:member-id creating-id
                           :name "Creating"
                           :code "creating"
                           :expires-at before-now
                           :status creating
                           :generation 7}
                          {:member-id activating-id
                           :name "Activating"
                           :code "activating"
                           :expires-at before-now
                           :status activating
                           :generation 8
                           :keycloak-id "kc-activating"}
                          {:member-id compensating-id
                           :name "Compensating"
                           :code "compensating"
                           :expires-at before-now
                           :status compensating
                           :generation 9}]]
        (seed-invitation! conn invitation))

      (testing "a pending invitation must be strictly unexpired"
        (is (= {:member-id pending-id
                :invite-code "pending"
                :invite-status pending
                :invite-generation 3}
               (-> (acceptance-invitation (d/db conn) now "pending")
                   (select-keys [:member-id
                                 :invite-code
                                 :invite-status
                                 :invite-generation]))))
        (is (nil? (acceptance-invitation (d/db conn) now "equal")))
        (is (nil? (acceptance-invitation (d/db conn) now "expired"))))

      (testing "claimed lifecycle states remain recoverable after expiry"
        (is (= [accepting accepting-id 6]
               ((juxt :invite-status :member-id :invite-generation)
                (acceptance-invitation (d/db conn) now "accepting"))))
        (is (= [creating creating-id 7]
               ((juxt :invite-status :member-id :invite-generation)
                (acceptance-invitation (d/db conn) now "creating"))))
        (is (= [activating activating-id 8]
               ((juxt :invite-status :member-id :invite-generation)
                (acceptance-invitation (d/db conn) now "activating"))))
        (is (= [compensating compensating-id 9]
               ((juxt :invite-status :member-id :invite-generation)
                (acceptance-invitation (d/db conn) now "compensating")))))

      (testing "the HTTP projection contains only the member fields account setup needs"
        (is (= {:member/member-id pending-id
                :member/name "Pending"
                :member/email (str pending-id "@example.test")
                :member/username (str "user-" pending-id)}
               (:member (acceptance-invitation (d/db conn) now "pending")))))

      (is (nil? (acceptance-invitation (d/db conn) now "")))
      (is (nil? (acceptance-invitation (d/db conn) now nil))))
    (is false "app.members.queries/acceptance-invitation is not implemented")))

(deftest accepted-and-revoked-invitations-do-not-resolve-test
  (if-let [acceptance-invitation (query-fn 'acceptance-invitation)]
    (let [{:keys [conn]} (tc/new-system "closed-acceptance-invitations")]
      (doseq [[status code]
              [[accepted "accepted-inconsistent-code"]
               [revoked "revoked-inconsistent-code"]]]
        (seed-invitation! conn {:member-id (random-uuid)
                                :name (name status)
                                :code code
                                :expires-at after-now
                                :status status
                                :generation 9}))
      (is (nil? (acceptance-invitation (d/db conn) now "accepted-inconsistent-code")))
      (is (nil? (acceptance-invitation (d/db conn) now "revoked-inconsistent-code"))))
    (is false "app.members.queries/acceptance-invitation is not implemented")))

(deftest members-with-pending-invites-test
  (if-let [members-with-pending-invites
           (some-> (query-fn 'members-with-pending-invites)
                   (#(when (supports-arity? % 2) %)))]
    (let [{:keys [conn]} (tc/new-system "members-with-pending-invites")
          open-id    (random-uuid)
          expired-id (random-uuid)]
      (doseq [invitation [{:member-id open-id
                           :name "Open"
                           :code "open"
                           :expires-at after-now
                           :status pending
                           :generation 1}
                          {:member-id expired-id
                           :name "Expired"
                           :code "expired-list"
                           :expires-at before-now
                           :status pending
                           :generation 1}
                          {:member-id (random-uuid)
                           :name "Accepting"
                           :code "accepting-list"
                           :expires-at after-now
                           :status accepting
                           :generation 2}]]
        (seed-invitation! conn invitation))
      (is (= [{:member/member-id expired-id
               :member/name "Expired"
               :member/email (str expired-id "@example.test")
               :member/invite-code "expired-list"
               :invite-expired? true}
              {:member/member-id open-id
               :member/name "Open"
               :member/email (str open-id "@example.test")
               :member/invite-code "open"
               :invite-expired? false}]
             (mapv #(select-keys % [:member/member-id
                                    :member/name
                                    :member/email
                                    :member/invite-code
                                    :invite-expired?])
                   (members-with-pending-invites (d/db conn) now)))))
    (is false "app.members.queries/members-with-pending-invites has no Datomic arity")))

(deftest revoked-invitation-queries-do-not-expose-a-bearer-test
  (let [{:keys [conn]} (tc/new-system "revoked-invitation-queries")
        revoked-id     (random-uuid)
        linked-revoked-id (random-uuid)
        pending-id     (random-uuid)
        revoked-row    {:member/member-id         revoked-id
                        :member/name              "Revoked"
                        :member/email             "revoked@example.test"
                        :member/username          "revoked"
                        :member/invite-status     revoked
                        :member/invite-generation 5
                        :member/invite-status-at  before-now}
        linked-revoked-row
        {:member/member-id         linked-revoked-id
         :member/name              "Linked revoked"
         :member/email             "linked-revoked@example.test"
         :member/username          "linked-revoked"
         :member/keycloak-id       "linked-keycloak-id"
         :member/invite-status     revoked
         :member/invite-generation 7
         :member/invite-status-at  before-now}
        pending-row    {:member/member-id         pending-id
                        :member/name              "Pending"
                        :member/email             "pending@example.test"
                        :member/username          "pending"
                        :member/invite-code       "pending-code"
                        :member/invite-expires-at after-now
                        :member/invite-status     pending
                        :member/invite-generation 3
                        :member/invite-status-at  before-now}]
    @(d/transact conn [revoked-row linked-revoked-row pending-row])
    (if-let [revoked-invitation-by-member-id
             (query-fn 'revoked-invitation-by-member-id)]
      (do
        (is (= (select-keys revoked-row
                            [:member/member-id
                             :member/name
                             :member/email
                             :member/invite-status
                             :member/invite-generation])
               (revoked-invitation-by-member-id (d/db conn) revoked-id)))
        (is (nil? (revoked-invitation-by-member-id
                   (d/db conn)
                   linked-revoked-id)))
        (is (nil? (revoked-invitation-by-member-id (d/db conn) pending-id)))
        (is (nil? (revoked-invitation-by-member-id
                   (d/db conn)
                   (random-uuid)))))
      (is false "The revoked invitation member query is not implemented"))
    (if-let [members-with-revoked-invites
             (query-fn 'members-with-revoked-invites)]
      (is (= [(select-keys revoked-row
                           [:member/member-id
                            :member/name
                            :member/email
                            :member/invite-status
                            :member/invite-generation])]
             (members-with-revoked-invites (d/db conn))))
      (is false "The revoked invitation list query is not implemented"))))
