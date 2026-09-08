(ns app.members.index.actions-test
  (:require
   [app.members.index.actions :as actions]
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]))

(deftest resend-invitation-action-test
  (let [now (t/inst)]
    (is (= [[:app.members.index/resend-invitation "invite-123"]
            [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
            [:app.datastar/respond-sse
             [[:app.datastar.sse/merge-signals
               {:invite {:action nil
                         :code nil
                         :member-id nil
                         :generation nil
                         :inflight false}}]]]]
           (actions/resend-invitation-action
            {:now now}
            {:invite {:code "invite-123"}})))))

(deftest reissue-invitation-action-test
  (let [now (t/inst)]
    (is (= [[:app.members.index/reissue-invitation "expired-code"]
            [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
            [:app.datastar/respond-sse
             [[:app.datastar.sse/merge-signals
               {:invite {:action nil
                         :code nil
                         :member-id nil
                         :generation nil
                         :inflight false}}]]]]
           (actions/reissue-invitation-action
            {:now now}
            {:invite {:code "expired-code"}})))))

(deftest reissue-revoked-invitation-action-test
  (let [now       (t/inst)
        member-id (random-uuid)]
    (if-let [action (ns-resolve 'app.members.index.actions
                                'reissue-revoked-invitation-action)]
      (is (= [[:app.members.index/reissue-revoked-invitation member-id 5]
              [:app.datastar/assoc-state
               [:members-index :last-invitation-action-at]
               now]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/merge-signals
                 {:invite {:action nil
                           :code nil
                           :member-id nil
                           :generation nil
                           :inflight false}}]]]]
             (action {:now now}
                     {:invite {:member-id (str member-id)
                               :generation 5}})))
      (is false "The revoked invitation reissue action is not implemented"))))

(deftest delete-invitation-action-test
  (let [now (t/inst)]
    (is (= [[:app.members.index/delete-invitation "invite-123"]
            [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
            [:app.datastar/respond-sse
             [[:app.datastar.sse/merge-signals
               {:invite {:action nil
                         :code nil
                         :member-id nil
                         :generation nil
                         :inflight false}}]]]]
           (actions/delete-invitation-action
            {:now now}
            {:invite {:code "invite-123"}})))))
