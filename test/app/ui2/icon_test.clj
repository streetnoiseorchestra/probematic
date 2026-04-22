(ns app.ui2.icon-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def missing-component ::missing-component)

(defn resolve-var [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _
      nil)))

(defn render-alias [alias-var attrs]
  (let [v (resolve-var alias-var)]
    (when v
      (html/->str [@v attrs]))))

(deftest icon-alias-renders-custom-plus-icon
  (let [html (render-alias 'app.ui2.icon/Icon {:app.ui2.icon/name :plus})]
    (is (some? html) "Icon alias should exist")
    (when html
      (is (str/includes? html "<svg"))
      (is (str/includes? html "viewBox=\"0 0 448 512\""))
      (is (str/includes? html "currentColor")))))

(deftest icon-alias-renders-custom-bars-icon
  (let [html (render-alias 'app.ui2.icon/Icon {:app.ui2.icon/name :bars})]
    (is (some? html) "Icon alias should exist")
    (when html
      (is (str/includes? html "<svg"))
      (is (str/includes? html "M3.75 5.25h16.5")))))

(deftest spinner-alias-renders-svg-spinner
  (let [html (render-alias 'app.ui2.icon/Spinner {})]
    (is (some? html) "Spinner alias should exist")
    (when html
      (is (str/includes? html "spinner animate-spin"))
      (is (str/includes? html "#svg-sprite-spinner")))))
