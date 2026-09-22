(ns app.jobs.identity-test
  (:require [app.jobs.identity :as identity-jobs]
            [app.jobs.log-dispatch :as log-dispatch]
            [app.keycloak :as keycloak]
            [app.members.detail.actions :as actions]
            [app.members.detail.actions-test :as contact-test]
            [app.nexus :as nexus]
            [app.sqlite :as sqlite]
            [app.test-common :as tc]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(deftest contact-change-commits-identity-intent-with-business-data
  (let [{:keys [conn member-id] :as system} (tc/new-system "identity-intent")
        pool                                (sqlite/start {:filename ":memory:"})
        client                              (drip/make-client pool)
        keycloak-id                         (str (random-uuid))]
    (try
      (drip/migrate! client)
      (contact-test/seed-section! conn "Trumpets")
      @(d/transact conn [{:member/member-id member-id :member/name        "Before"
                          :member/username  "alice"   :member/keycloak-id keycloak-id}])
      (log-dispatch/initialize! conn client (d/basis-t (d/db conn)))
      (let [effects          (actions/update-contact-action
                              (assoc (contact-test/admin-state-for system) :tr contact-test/tr :durable-jobs? true)
                              (contact-test/contact-signals member-id {:username       "alice" :keycloak-id             keycloak-id
                                                                       :sno-id-enabled true    :sno-id-enabled-original false}))
            [_ tx-data opts] (second effects)]
        (is (= [:app.datastar/assoc-state :db/transact] (mapv first effects)))
        (is (= [[:app.datastar/respond-sse [[:app.datastar.sse/merge-signals {:loading false :targetid false}]]]
                [:app.datastar/assoc-state [:member-detail :contact] false]]
               (:on-success opts)))
        (nexus/db-transact-fx {} {:system {:datomic {:conn conn}} :request {}}
                              [[tx-data (dissoc opts :on-success)]])
        (log-dispatch/dispatch-pending! conn client 128)
        (let [member (d/entity (d/db conn) [:member/member-id member-id])
              jobs   (drip/list-jobs client {})]
          (is (= "Alice Admin" (:member/name member)))
          (is (true? (:member/keycloak-enabled-request member)))
          (is (= ["sync-member-identity"] (mapv :kind jobs)))
          (is (= {:member-id member-id                        :keycloak-id keycloak-id
                  :changes   {:metadata? true :enabled? true}}
                 (select-keys (:args (first jobs)) [:member-id :keycloak-id :changes])))))
      (finally (sqlite/stop pool)))))

(deftest retries-use-current-values-and-ignore-replaced-links
  (let [{:keys [conn member-id]} (tc/new-system "identity-retry")
        keycloak-id              (str (random-uuid))
        args                     {:member-id member-id :keycloak-id keycloak-id :changes {:metadata? true :enabled? true}}
        calls                    (atom [])
        system                   {:datomic {:conn conn} :keycloak {}}]
    @(d/transact conn [{:member/member-id member-id     :member/keycloak-id              keycloak-id
                        :member/name      "Latest name" :member/keycloak-enabled-request false}])
    (with-redefs [keycloak/update-user-meta! (fn [_ member] (swap! calls conj [:metadata (:member/name member)]))
                  keycloak/lock-account!     (fn [_ member] (swap! calls conj [:lock (:member/keycloak-id member)]))
                  keycloak/unlock-account!   (fn [_ member] (swap! calls conj [:unlock (:member/keycloak-id member)]))]
      (identity-jobs/sync-member! system args)
      (is (= [[:metadata "Latest name"] [:lock keycloak-id]] @calls))
      @(d/transact conn [[:db/add [:member/member-id member-id] :member/keycloak-enabled-request true]])
      (identity-jobs/sync-member! system args)
      (is (= [:unlock keycloak-id] (last @calls)))
      (reset! calls [])
      @(d/transact conn [[:db/add [:member/member-id member-id] :member/keycloak-id (str (random-uuid))]])
      (identity-jobs/sync-member! system args)
      (is (empty? @calls)))))
