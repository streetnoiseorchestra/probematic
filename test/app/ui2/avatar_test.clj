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

(defn avatar-html [attrs & children]
  (html/->str (into [avatar/Avatar attrs] children)))

(deftest member-avatar-links-to-member-by-default
  (let [html (avatar-html {::avatar/member member
                           :shape "rounded"})]
    (is (str/includes? html "<a aria-label=\"Ada Lovelace\" href=\"/member/11111111-1111-4111-8111-111111111111\""))
    (is (str/includes? html "<span class=\"sno-avatar\" shape=\"rounded\""))
    (is (not (str/includes? html "<wa-avatar")))
    (is (str/includes? html "<img class=\"image\" src=\"https://forum.streetnoise.at/user_avatar/forum.streetnoise.at/ada/80/1.png\" loading=\"eager\" role=\"img\" aria-label=\"Ada Lovelace\""))
    (is (not (str/includes? html "app.ui2.avatar/member")))))

(deftest avatar-supports-all-web-awesome-attributes-and-the-size-property
  (let [html (avatar-html {:image    "/ada.png"
                           :label    "Ada Lovelace"
                           :initials "AL"
                           :loading  "lazy"
                           :shape    "square"
                           :style    "--size: 5rem"
                           :id       "ada-avatar"})]
    (is (str/includes? html "<span id=\"ada-avatar\" class=\"sno-avatar\" shape=\"square\" style=\"--size: 5rem\""))
    (is (str/includes? html "<img class=\"image\" src=\"/ada.png\" loading=\"lazy\" role=\"img\" aria-label=\"Ada Lovelace\""))
    (is (not (str/includes? html "class=\"initials\"")))))

(deftest avatar-renders-initials-with-the-web-awesome-class-name
  (let [html (avatar-html {:initials "al" :label "Ada Lovelace"})]
    (is (str/includes? html "<span class=\"sno-avatar\""))
    (is (str/includes? html "<span class=\"initials\" role=\"img\" aria-label=\"Ada Lovelace\">al</span>"))
    (is (not (str/includes? html "part=")))))

(deftest avatar-wraps-custom-icon-content-with-the-web-awesome-class-name
  (let [html (avatar-html {:label "Archive"}
                          [:span {:data-custom-icon true} "icon"])]
    (is (str/includes? html "<span class=\"icon\" role=\"img\" aria-label=\"Archive\">"))
    (is (str/includes? html "<span data-custom-icon>icon</span>"))
    (is (not (str/includes? html "slot=\"icon\"")))
    (is (not (str/includes? html "part=")))))

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
    (is (str/starts-with? html "<span"))
    (is (str/includes? html "class=\"sno-avatar plain-avatar\""))
    (is (not (str/includes? html "<a ")))))
