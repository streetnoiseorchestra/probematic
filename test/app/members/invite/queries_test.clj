(ns app.members.invite.queries-test
  (:require
   [app.game-loop.storage :as storage]
   [app.jobs.log-dispatch :as log]
   [app.members.invite.cells-test :as cells]
   [app.members.invite.domain :as domain]
   [app.members.invite.jobs :as jobs]
   [app.members.invite.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections tc/with-sqlite-db)

(defn with-invitation [f]
  (let [{:keys [conn]} (tc/new-system "invitation-status")
        member-id      (random-uuid)
        client         (drip/make-client tc/*sqlite-db*)]
    (drip/migrate! client)
    (log/initialize! conn client (d/basis-t (d/db conn)))
    (cells/seed-invitation! conn member-id {:status cells/pending        :generation 1
                                            :code   "status-test-bearer" :expiry     cells/expires-at})
    (f {:conn conn :client client :member-id member-id})))

(defn transition! [{:keys [conn member-id]} plan-fn]
  @(d/transact conn (:tx-data (plan-fn (d/db conn)
                                       {:member-id        member-id           :state           (domain/invitation-state (d/db conn) member-id)
                                        :requested-at     cells/requested-at  :transitioned-at cells/transitioned-at
                                        :keycloak-user-id "test-created-user"}))))

(defn status-for-code [{:keys [conn client]} code now]
  (storage/with-read-dbs {:jobs tc/*sqlite-db*}
    #(queries/status-for-code (d/db conn) client (:jobs %) now code)))

(defn status [{:keys [conn client member-id]}]
  (let [db (d/db conn)]
    (storage/with-read-dbs {:jobs tc/*sqlite-db*}
      #(queries/setup-status db client (:jobs %) member-id))))

(deftest pending-intent-and-pruned-failure-do-not-look-the-same
  (with-invitation
    (fn [{:keys [conn client member-id] :as context}]
      (is (= :pending (status context)))
      (transition! context jobs/claim-tx)
      (is (= :creating (status context)))
      (log/dispatch-pending! conn client 100)
      (is (= :creating (status context)))
      (let [job (first (drip/list-jobs client {}))]
        (transition! context domain/begin-create-tx)
        (is (= :creating (status context)))
        (drip/cancel-job client (:id job))
        (is (= :operator-required (status context)))
        (drip/retry-job client (:id job))
        (is (= :creating (status context)))
        (drip/discard-job client (:id job))
        (is (= :operator-required (status context)))
        (drip/delete-job client (:id job))
        (is (= :operator-required (status context))))
      (drip/insert-job client "accept-invitation" {:member-id member-id :claim-generation 5}
                       {:queue "invitation-setup"})
      (is (= :operator-required (status context))))))

(deftest query-finds-the-correct-claim-beyond-the-first-job-page
  (with-invitation
    (fn [{:keys [conn client] :as context}]
      (transition! context jobs/claim-tx)
      (log/dispatch-pending! conn client 100)
      (dotimes [_ 101]
        (drip/insert-job client "accept-invitation" {:member-id (random-uuid) :claim-generation 2}
                         {:queue "invitation-setup"}))
      (is (= :creating (status context))))))

(deftest receipt-wins-over-failed-or-pruned-job-state
  (with-invitation
    (fn [{:keys [conn client] :as context}]
      (transition! context jobs/claim-tx)
      (log/dispatch-pending! conn client 100)
      (let [job (first (drip/list-jobs client {}))]
        (drip/discard-job client (:id job))
        (doseq [plan [domain/begin-create-tx domain/link-keycloak-user-tx domain/finalize-tx]]
          (transition! context plan))
        (is (= :accepted (status context)))
        (drip/delete-job client (:id job))
        (is (= :accepted (status context)))))))

(deftest consumed-legacy-claim-without-intent-requires-operator-help
  (with-invitation
    (fn [{:keys [conn client] :as context}]
      (transition! context domain/claim-tx)
      (log/dispatch-pending! conn client 100)
      (is (= :operator-required (status context)))
      (is (empty? (drip/list-jobs client {}))))))

(deftest compensation-and-new-claim-do-not-adopt-the-old-job
  (with-invitation
    (fn [{:keys [conn client] :as context}]
      (transition! context jobs/claim-tx)
      (log/dispatch-pending! conn client 100)
      (transition! context domain/begin-compensation-tx)
      (is (= :creating (status context)))
      (transition! context domain/release-tx)
      (is (= :pending (status context)))
      (transition! context domain/claim-tx)
      (log/dispatch-pending! conn client 100)
      (is (= :operator-required (status context))))))

(deftest bearer-status-is-narrow-and-revalidated-on-each-frame
  (with-invitation
    (fn [{:keys [conn member-id] :as context}]
      (let [other-id (random-uuid)
            expected {:status :pending :member {:member/member-id member-id :member/email "alice@example.com"}}]
        @(d/transact conn [{:member/member-id        other-id               :member/name              "Other Member"
                            :member/email            "other@example.com"    :member/username          "other.member"
                            :member/invite-status    cells/pending          :member/invite-generation 1
                            :member/invite-code      "other-private-bearer" :member/invite-expires-at cells/expires-at
                            :member/invite-status-at cells/issued-at}])
        (is (= expected (status-for-code context "status-test-bearer" cells/requested-at)))
        (is (= {:status :pending :member {:member/member-id other-id :member/email "other@example.com"}}
               (status-for-code context "other-private-bearer" cells/requested-at)))
        (doseq [code [nil "" "unknown"]]
          (is (= {:status :unavailable} (status-for-code context code cells/requested-at))))
        (is (= {:status :unavailable} (status-for-code context "status-test-bearer" cells/expires-at)))
        (transition! context domain/revoke-tx)
        (is (= {:status :unavailable} (status-for-code context "status-test-bearer" cells/requested-at)))))))

(deftest claimed-and-completed-bearers-survive-pending-expiry
  (with-invitation
    (fn [{:keys [conn client member-id] :as context}]
      (transition! context jobs/claim-tx)
      (log/dispatch-pending! conn client 100)
      (is (= {:status :creating :member {:member/member-id member-id :member/email "alice@example.com"}}
             (status-for-code context "status-test-bearer" cells/expires-at)))
      (doseq [plan [domain/begin-create-tx domain/link-keycloak-user-tx domain/finalize-tx]]
        (transition! context plan))
      (is (= {:status :accepted :member {:member/member-id member-id :member/email "alice@example.com"}}
             (status-for-code context "status-test-bearer" cells/expires-at))))))
