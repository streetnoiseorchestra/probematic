(ns app.members.index.actions-test
  (:require
   [app.members.index.actions :as actions]
   [clojure.test :refer [deftest is testing]]))

(deftest set-search-phrase-action-test
  (is (= [[:app.datastar/assoc-state [:members-index :search] "Alice"]]
         (actions/set-search-phrase-action
          {}
          {:members-index {:search "Alice"}})))

  (is (= [[:app.datastar/assoc-state [:members-index :search] ""]]
         (actions/set-search-phrase-action
          {}
          {:members-index {:search nil}}))))

(deftest set-filter-preset-action-test
  (is (= [[:app.datastar/assoc-state [:members-index :filter-preset] "inactive"]]
         (actions/set-filter-preset-action
          {}
          {:members-index {:filter-preset "inactive"}})))

  (is (= [[:app.datastar/assoc-state [:members-index :filter-preset] "active"]]
         (actions/set-filter-preset-action
          {}
          {:members-index {:filter-preset "wat"}}))))

(deftest set-sort-action-test
  (testing "toggles the sort order when the same field is requested again"
    (is (= [[:app.datastar/assoc-state [:members-index :sort-field] "name"]
            [:app.datastar/assoc-state [:members-index :sort-order] "desc"]]
           (actions/set-sort-action
            {}
            {:members-index {:sort-field         "name"
                             :sort-order         "asc"
                             :sort-request-field "name"}}))))

  (testing "resets the sort order to ascending when a different field is requested"
    (is (= [[:app.datastar/assoc-state [:members-index :sort-field] "email"]
            [:app.datastar/assoc-state [:members-index :sort-order] "asc"]]
           (actions/set-sort-action
            {}
            {:members-index {:sort-field         "name"
                             :sort-order         "desc"
                             :sort-request-field "email"}}))))

  (testing "accepts the travel discount field"
    (is (= [[:app.datastar/assoc-state [:members-index :sort-field] "travel-discount"]
            [:app.datastar/assoc-state [:members-index :sort-order] "asc"]]
           (actions/set-sort-action
            {}
            {:members-index {:sort-field         "name"
                             :sort-order         "desc"
                             :sort-request-field "travel-discount"}}))))

  (testing "falls back to the default sort field when the request is invalid"
    (is (= [[:app.datastar/assoc-state [:members-index :sort-field] "name"]
            [:app.datastar/assoc-state [:members-index :sort-order] "asc"]]
           (actions/set-sort-action
            {}
            {:members-index {:sort-field         "email"
                             :sort-order         "desc"
                             :sort-request-field "wat"}})))))

(deftest resend-invitation-action-test
  (let [now (java.util.Date.)]
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
  (let [now (java.util.Date.)]
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
  (let [now       (java.util.Date.)
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
  (let [now (java.util.Date.)]
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
