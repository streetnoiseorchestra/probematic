(ns app.members.invite-accept.service-test
  (:require
   [app.members.invite-accept.service :as service]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn seed-invited-member! [conn member-id]
  @(d/transact conn [{:section/name "Trumpets"
                      :section/active? true
                      :section/position 1}
                     {:member/member-id member-id
                      :member/name "Alice Example"
                      :member/nick "alice"
                      :member/email "alice@example.com"
                      :member/username "alice.example"
                      :member/phone "+43677123456"}]))

(defn setup-req [{:keys [conn invite-code]}]
  {:db (d/db conn)
   :datomic-conn conn
   :params {:invite-code invite-code}
   :system {:env {:app/base-url "https://dev.streetnoise.at"}}})

(deftest setup-account-test
  (testing "rejects an expired invite code"
    (let [{:keys [conn]} (tc/new-system "invite-accept-expired")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invite code expired"
           (service/setup-account!
            {:fetch-invite-code (fn [_req _invite-code] nil)}
            (setup-req {:conn conn
                        :invite-code "expired"}))))))

  (testing "creates the keycloak user without a password, stores the keycloak id, and deletes the invite"
    (let [{:keys [conn]} (tc/new-system "invite-accept-success")
          member-id (random-uuid)
          deleted-codes_ (atom [])
          created_ (atom [])]
      (seed-invited-member! conn member-id)
      (let [member (service/setup-account!
                    {:fetch-invite-code (fn [_req _invite-code] member-id)
                     :create-new-member! (fn [_kc member can-login?]
                                           (swap! created_ conj {:member member
                                                                 :can-login? can-login?})
                                           {:user/user-id "keycloak-123"})
                     :delete-invitation! (fn [_req invite-code]
                                           (swap! deleted-codes_ conj invite-code))}
                    (setup-req {:conn conn
                                :invite-code "invite-123"}))]
        (is (= "keycloak-123" (:member/keycloak-id member)))
        (is (= "keycloak-123"
               (:member/keycloak-id (q/retrieve-member (d/db conn) member-id))))
        (is (= ["invite-123"] @deleted-codes_))
        (is (= [{:member {:member/member-id member-id
                          :member/email "alice@example.com"
                          :member/username "alice.example"
                          :member/name "Alice Example"}
                 :can-login? true}]
               [{:member (select-keys (:member (first @created_))
                                      [:member/member-id :member/email :member/username :member/name])
                 :can-login? (:can-login? (first @created_))}]))))))
