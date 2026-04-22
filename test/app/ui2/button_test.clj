(ns app.ui2.button-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def missing-component ::missing-component)

(defn resolve-button []
  (try
    (requiring-resolve 'app.ui2.button/Button)
    (catch Throwable _
      nil)))

(defn button-alias []
  (or (resolve-button) missing-component))

(defn button-html [attrs & children]
  (let [button (button-alias)]
    (when-not (= missing-component button)
      (html/->str (into [@button attrs] children)))))

(defn icon-stub [icon-name]
  (fn [attrs]
    [:svg (assoc attrs :data-icon icon-name)]))

(deftest button-alias-renders-a-button-by-default
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {} "Save")]
        (is (str/includes? html "<button"))
        (is (str/includes? html "type=\"button\""))
        (is (str/includes? html "btn"))
        (is (str/includes? html "<span>Save</span>"))))))

(deftest button-alias-renders-an-anchor-when-href-is-present
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:href "/members"} "Open")]
        (is (str/includes? html "<a"))
        (is (str/includes? html "href=\"/members\""))
        (is (not (str/includes? html "type=\"button\"")))))))

(deftest button-uses-namespaced-intent-and-loading-options
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:app.ui2.button/intent   :primary
                               :app.ui2.button/loading? true
                               :app.ui2.button/icon     (icon-stub "leading")}
                              "Save")]
        (is (str/includes? html "spinning"))
        (is (str/includes? html "bg-sno-orange-600"))
        (is (not (str/includes? html "data-icon=\"leading\"")))))))

(deftest button-renders-leading-and-trailing-icons-when-not-loading
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:app.ui2.button/icon          (icon-stub "leading")
                               :app.ui2.button/icon-trailing (icon-stub "trailing")}
                              "Save")]
        (is (str/includes? html "data-icon=\"leading\""))
        (is (str/includes? html "data-icon=\"trailing\""))))))

(deftest disabled-button-sets-the-html-disabled-attribute
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [html (button-html {:app.ui2.button/disabled? true} "Save")]
        (is (str/includes? html "disabled"))
        (is (str/includes? html "cursor-not-allowed"))))))

(deftest element-children-are-not-wrapped-and-raw-children-are-not-escaped
  (let [button (button-alias)]
    (is (not= missing-component button) "Button alias should exist")
    (when-not (= missing-component button)
      (let [element-html (button-html {} [:strong "Save"])
            raw-html     (button-html {} (html/raw "&nbsp;"))]
        (is (str/includes? element-html "<strong>Save</strong>"))
        (is (not (str/includes? element-html "<span><strong>Save</strong></span>")))
        (is (str/includes? raw-html "&nbsp;"))
        (is (not (str/includes? raw-html "&amp;nbsp;")))))))
