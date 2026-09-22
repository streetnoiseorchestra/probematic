(ns app.audit-test
  (:require
   [app.audit :as audit]
   [clojure.test :refer [deftest is testing]]))

(deftest with-metadata-normalizes-transaction-forms
  (is (= [{:entity/id 1}
          {:db/id "datomic.tx" :audit/jobs "jobs"}
          {:db/id         "datomic.tx"
           :audit/action  :app.actions/save
           :audit/comment "Reason"
           :audit/origin  :app.origin/browser
           :audit/user    [:member/member-id #uuid "8f83933f-e15c-49ca-bf0a-60ca99d0669a"]}]
         (audit/with-metadata
           [{:entity/id 1}
            {:db/id "datomic.tx" :audit/action :app.actions/save :audit/jobs "jobs"}
            [:db/add "datomic.tx" :audit/action :app.actions/save]
            [:db/add "datomic.tx" :audit/comment "Reason"]
            [:db/add "datomic.tx" :audit/user
             [:member/member-id #uuid "8f83933f-e15c-49ca-bf0a-60ca99d0669a"]]]
           {:audit/action :app.actions/save
            :audit/origin :app.origin/browser
            :audit/user   [:member/member-id #uuid "8f83933f-e15c-49ca-bf0a-60ca99d0669a"]}))))
(deftest with-metadata-omits-missing-actor
  (is (= [{:db/id "datomic.tx" :audit/origin :app.origin/browser}]
         (audit/with-metadata [] {:audit/origin :app.origin/browser
                                  :audit/user   nil}))))

(deftest with-metadata-rejects-conflicts
  (testing "explicit transaction metadata conflicts"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Conflicting transaction audit metadata"
         (audit/with-metadata
           [[:db/add "datomic.tx" :audit/user [:member/member-id #uuid "808b87d0-cb8e-4530-8057-326a500ecd30"]]
            {:db/id      "datomic.tx"
             :audit/user [:member/member-id #uuid "f74a901f-03af-47b3-83f9-e356a92165b4"]}]
           {}))))
  (testing "trusted context cannot overwrite explicit metadata"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Trusted transaction audit metadata conflicts"
         (audit/with-metadata
           [{:db/id        "datomic.tx"
             :audit/origin :app.origin/job}]
           {:audit/origin :app.origin/browser}))))
  (testing "unknown context keys are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown transaction audit metadata"
         (audit/with-metadata [] {:audit/typo :value})))))
