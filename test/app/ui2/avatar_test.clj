(ns app.ui2.avatar-test
  (:require
   [app.html :as html]
   [app.ui2.avatar :as avatar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def member
  {:member/member-id #uuid "11111111-1111-4111-8111-111111111111"
   :member/name "Ada Lovelace"
   :member/nick "Ada"
   :member/avatar-template "/user_avatar/forum.streetnoise.at/ada/{size}/1.png"})

(defn avatar-html [attrs]
  (html/->str [avatar/Avatar attrs]))

(deftest member-avatar-links-to-member-by-default
  (let [html (avatar-html {::avatar/member member
                           :shape "rounded"})]
    (is (str/includes? html "<a aria-label=\"Ada Lovelace\" href=\"/member/11111111-1111-4111-8111-111111111111\""))
    (is (str/includes? html "<wa-avatar"))
    (is (str/includes? html "shape=\"rounded\""))
    (is (str/includes? html "label=\"Ada Lovelace\""))
    (is (str/includes? html "initials=\"AL\""))
    (is (str/includes? html "image=\"https://forum.streetnoise.at/user_avatar/forum.streetnoise.at/ada/80/1.png\""))
    (is (not (str/includes? html "app.ui2.avatar/member")))))

(deftest member-avatar-can-render-nick-text
  (let [html (avatar-html {::avatar/member member
                           ::avatar/text :nick
                           ::avatar/wrapper-attrs {:class "member-chip"}})]
    (is (str/includes? html "member-chip"))
    (is (str/includes? html "wa-flank wa-gap-xs wa-align-items-center"))
    (is (str/includes? html ">Ada</span>"))
    (is (str/includes? html "href=\"/member/11111111-1111-4111-8111-111111111111\""))))

(deftest member-avatar-link-can-be-disabled
  (let [html (avatar-html {::avatar/member member
                           ::avatar/link? false
                           :class "plain-avatar"})]
    (is (str/starts-with? html "<wa-avatar"))
    (is (str/includes? html "class=\"plain-avatar\""))
    (is (not (str/includes? html "<a ")))))
