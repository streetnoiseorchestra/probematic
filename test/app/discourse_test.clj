(ns app.discourse-test
  (:require
   [app.account.test-support :as support]
   [clojure.test :refer [deftest is]]))

(deftest discourse-member-sync-no-longer-overwrites-avatar-templates
  (let [member-tx (support/public-fn 'app.discourse/discourse-member-tx)
        member-id (random-uuid)]
    (is (fn? member-tx) "app.discourse/discourse-member-tx should exist")
    (when member-tx
      (is (= {:member/member-id member-id
              :member/discourse-id "42"
              :member/nick "ada"}
             (member-tx {:member/member-id member-id
                         :id 42
                         :username "ada"
                         :avatar_template "/user_avatar/ada/{size}/1.png"}))))))
