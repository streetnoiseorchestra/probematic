(ns app.members.index.actions-test
  (:require
   [app.members.index.actions :as actions]
   [clojure.test :refer [deftest is testing]]))

(deftest set-search-phrase-action-test
  (is (= [[:app.datastar/assoc-state [:members-index :search] "Alice"]]
         (actions/set-search-phrase-action
          {}
          {:members-index {:search "Alice"}}))))

(deftest set-filter-preset-action-test
  (is (= [[:app.datastar/assoc-state [:members-index :filter-preset] "inactive"]]
         (actions/set-filter-preset-action
          {}
          {:members-index {:filter-preset "inactive"}}))))

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
                             :sort-request-field "email"}})))))

(deftest resend-invitation-action-test
  (let [now (java.util.Date.)]
    (is (= [[:app.members.index/resend-invitation "invite-123"]
            [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
            [:app.datastar/merge-signals {:invite {:action nil
                                                   :code nil
                                                   :inflight false}}]]
           (actions/resend-invitation-action
            {:now now}
            {:invite {:code "invite-123"}})))))

(deftest delete-invitation-action-test
  (let [now (java.util.Date.)]
    (is (= [[:app.members.index/delete-invitation "invite-123"]
            [:app.datastar/assoc-state [:members-index :last-invitation-action-at] now]
            [:app.datastar/merge-signals {:invite {:action nil
                                                   :code nil
                                                   :inflight false}}]]
           (actions/delete-invitation-action
            {:now now}
            {:invite {:code "invite-123"}})))))
