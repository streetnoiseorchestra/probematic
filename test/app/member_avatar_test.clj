(ns app.member-avatar-test
  (:require
   [app.account.test-support :as support]
   [clojure.test :refer [deftest is]]))

(def member
  {:member/member-id #uuid "11111111-1111-4111-8111-111111111111"
   :member/name "Ada Lovelace"
   :member/avatar-template "/user_avatar/ada/{size}/1.png"})

(def managed-member
  (assoc member
         :member/avatar
         {:image/image-id #uuid "22222222-2222-4222-8222-222222222222"}))

(deftest canonical-avatar-resolver-prefers-managed-renditions
  (let [avatar-image (support/public-fn 'app.member-avatar/avatar-image)]
    (is (fn? avatar-image) "app.member-avatar/avatar-image should exist")
    (when avatar-image
      (is (= {:src (str "/member-avatar/11111111-1111-4111-8111-111111111111/160"
                        "?v=22222222-2222-4222-8222-222222222222")
              :srcset (str "/member-avatar/11111111-1111-4111-8111-111111111111/160"
                           "?v=22222222-2222-4222-8222-222222222222 1x, "
                           "/member-avatar/11111111-1111-4111-8111-111111111111/320"
                           "?v=22222222-2222-4222-8222-222222222222 2x")
              :managed? true}
             (avatar-image managed-member {:size 160}))))))

(deftest canonical-avatar-resolver-falls-back-to-discourse-unless-disabled
  (let [avatar-image (support/public-fn 'app.member-avatar/avatar-image)]
    (is (fn? avatar-image) "app.member-avatar/avatar-image should exist")
    (when avatar-image
      (is (= {:src "https://forum.streetnoise.at/user_avatar/ada/40/1.png"
              :srcset (str "https://forum.streetnoise.at/user_avatar/ada/40/1.png 1x, "
                           "https://forum.streetnoise.at/user_avatar/ada/80/1.png 2x")
              :managed? false}
             (avatar-image member {:size 40})))
      (is (nil? (avatar-image member {:size 160 :allow-legacy? false}))))))
