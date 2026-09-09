(ns app.jobs.feedback-test
  (:require
   [app.i18n :as i18n]
   [app.jobs.feedback :as feedback]
   [app.test-common :as tc]
   [app.write-runner-test :refer [with-runtime]]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each tc/with-released-test-connections)

(deftest durable-feedback-retains-connection-and-member-ownership
  (with-runtime
    (fn [runtime _ _]
      (let [tab       "feedback-test"
            token     (random-uuid)
            member-id (random-uuid)
            action-id (random-uuid)
            client    {:token token :member-id member-id :revision 0 :events [] :action-id action-id}
            origin    (edn/read-string (pr-str {:tab-id tab :token token :member-id member-id :locale :en :action-id action-id}))
            system    {:frame-loop runtime :i18n-langs (i18n/read-langs)}]
        (swap! (:clients runtime) assoc tab client)
        (feedback/failure! system (assoc origin :member-id (random-uuid)))
        (is (empty? (get-in @(:clients runtime) [tab :events])))
        (feedback/failure! system origin)
        (is (= 1 (count (get-in @(:clients runtime) [tab :events]))))
        (swap! (:clients runtime) assoc tab (assoc client :action-id (random-uuid)))
        (feedback/redirect! system origin "/somewhere")
        (is (empty? (get-in @(:clients runtime) [tab :events])))
        (swap! (:clients runtime) assoc tab (assoc client :token (random-uuid)))
        (feedback/failure! system origin)
        (is (empty? (get-in @(:clients runtime) [tab :events])))
        (swap! (:clients runtime) dissoc tab)
        (feedback/failure! system origin)
        (is (empty? @(:clients runtime)))))))
