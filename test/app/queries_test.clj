(ns app.queries-test
  (:require
   [app.insurance.test-support :as insurance-support]
   [app.queries :as q]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(deftest open-survey-for-member-ignores-open-survey-without-policy-test
  (testing "orphaned open survey responses are not returned for the dashboard widget"
    (let [{:keys [conn member-id]} (tc/new-system "query-open-survey-orphan")]
      (insurance-support/seed-survey! conn {:member-id member-id})
      (is (nil? (q/open-survey-for-member (d/db conn) {:member/member-id member-id}))))))

(deftest policy-has-open-surveys?-only-counts-open-surveys-test
  (testing "open surveys block policy deletion, closed and unrelated surveys do not"
    (let [{:keys [conn member-id]} (tc/new-system "query-policy-open-surveys")
          open-policy-id           (random-uuid)
          closed-policy-id         (random-uuid)
          unrelated-policy-id      (random-uuid)]
      (doseq [policy-id [open-policy-id closed-policy-id unrelated-policy-id]]
        (insurance-support/seed-policy! conn policy-id))
      (insurance-support/seed-survey! conn {:member-id member-id
                                            :policy-id open-policy-id})
      (insurance-support/seed-survey! conn {:member-id  member-id
                                            :policy-id  closed-policy-id
                                            :closed-at  #inst "2026-03-15T00:00:00.000-00:00"})
      (let [db (d/db conn)]
        (is (true? (q/policy-has-open-surveys? db open-policy-id)))
        (is (false? (q/policy-has-open-surveys? db closed-policy-id)))
        (is (false? (q/policy-has-open-surveys? db unrelated-policy-id)))))))
