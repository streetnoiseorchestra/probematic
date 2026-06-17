(ns app.ui2.divider-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def missing-component ::missing-component)

(defn resolve-divider []
  (try
    (requiring-resolve 'app.ui2.divider/Divider)
    (catch Throwable _
      nil)))

(defn divider-alias []
  (or (resolve-divider) missing-component))

(defn divider-html [attrs & children]
  (let [divider (divider-alias)]
    (when-not (= missing-component divider)
      (html/->str (into [@divider attrs] children)))))

(deftest divider-renders-a-native-horizontal-separator-by-default
  (let [divider (divider-alias)]
    (is (not= missing-component divider) "Divider alias should exist")
    (when-not (= missing-component divider)
      (let [html (divider-html {:id "main-separator"})]
        (is (str/includes? html "<hr"))
        (is (not (str/includes? html "<wa-divider")))
        (is (str/includes? html "class=\"sno-divider\""))
        (is (str/includes? html "id=\"main-separator\""))
        (is (str/includes? html "role=\"separator\""))
        (is (str/includes? html "orientation=\"horizontal\""))
        (is (str/includes? html "aria-orientation=\"horizontal\""))))))

(deftest divider-renders-a-native-vertical-separator
  (let [divider (divider-alias)]
    (is (not= missing-component divider) "Divider alias should exist")
    (when-not (= missing-component divider)
      (let [html (divider-html {:orientation "vertical"
                                :class       "menu-divider"})]
        (is (str/includes? html "<hr"))
        (is (not (str/includes? html "<wa-divider")))
        (is (str/includes? html "sno-divider"))
        (is (str/includes? html "menu-divider"))
        (is (str/includes? html "orientation=\"vertical\""))
        (is (str/includes? html "aria-orientation=\"vertical\""))))))

(deftest divider-accepts-qualified-options-and-css-custom-properties
  (let [divider (divider-alias)]
    (is (not= missing-component divider) "Divider alias should exist")
    (when-not (= missing-component divider)
      (let [html (divider-html {:app.ui2.divider/orientation :vertical
                                :app.ui2.divider/color       "tomato"
                                :app.ui2.divider/width       "4px"
                                :app.ui2.divider/spacing     "2rem"
                                :style                       "display: block"
                                :data-divider                "settings"})]
        (is (str/includes? html "orientation=\"vertical\""))
        (is (str/includes? html "aria-orientation=\"vertical\""))
        (is (str/includes? html "style=\"display: block; --color: tomato; --width: 4px; --spacing: 2rem\""))
        (is (str/includes? html "data-divider=\"settings\""))
        (is (not (str/includes? html "app.ui2.divider/orientation")))
        (is (not (str/includes? html "app.ui2.divider/color")))
        (is (not (str/includes? html "app.ui2.divider/width")))
        (is (not (str/includes? html "app.ui2.divider/spacing")))))))

(deftest divider-accepts-plain-option-keys
  (let [divider (divider-alias)]
    (is (not= missing-component divider) "Divider alias should exist")
    (when-not (= missing-component divider)
      (let [html (divider-html {:orientation :vertical
                                :color       "red"
                                :width       "2px"
                                :spacing     "1rem"})]
        (is (str/includes? html "orientation=\"vertical\""))
        (is (str/includes? html "style=\"--color: red; --width: 2px; --spacing: 1rem\""))
        (is (not (str/includes? html "color=\"red\"")))
        (is (not (str/includes? html "width=\"2px\"")))
        (is (not (str/includes? html "spacing=\"1rem\"")))))))
