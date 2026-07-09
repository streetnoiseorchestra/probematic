(ns app.ui2.page-header-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def missing-component ::missing-component)

(defn resolve-page-header []
  (try
    (requiring-resolve 'app.ui2.page-header/PageHeader)
    (catch Throwable _
      nil)))

(defn page-header-alias []
  (or (resolve-page-header) missing-component))

(defn page-header-html [attrs]
  (let [page-header (page-header-alias)]
    (when-not (= missing-component page-header)
      (html/->str [@page-header attrs]))))

(deftest page-header-renders-semantic-title-and-subtitle
  (let [page-header (page-header-alias)]
    (is (not= missing-component page-header) "PageHeader alias should exist")
    (when-not (= missing-component page-header)
      (let [html (page-header-html {:id       "policy-settings-header"
                                    :title    "Policy Settings"
                                    :subtitle "Configure settings for Insurance 2026."})]
        (is (str/includes? html "<header"))
        (is (str/includes? html "class=\"sno-page-header\""))
        (is (str/includes? html "id=\"policy-settings-header\""))
        (is (str/includes? html "<h1>Policy Settings</h1>"))
        (is (str/includes? html "<p>Configure settings for Insurance 2026.</p>"))
        (is (not (str/includes? html "wa-caption-s")))
        (is (not (str/includes? html "app.ui2.page-header/title")))
        (is (not (str/includes? html "subtitle=\"")))))))

(deftest page-header-wraps-rich-title-content-in-h1
  (let [page-header (page-header-alias)]
    (is (not= missing-component page-header) "PageHeader alias should exist")
    (when-not (= missing-component page-header)
      (let [html (page-header-html {:title [:span {:class "wa-cluster"}
                                            "Insurance 2026"
                                            [:wa-badge {:appearance "outlined"} "Active"]]})]
        (is (str/includes? html "<h1>"))
        (is (str/includes? html "<span class=\"wa-cluster\">Insurance 2026<wa-badge"))
        (is (not (str/includes? html "<h1><h1")))))))

(deftest page-header-renders-actions-as-a-menu
  (let [page-header (page-header-alias)]
    (is (not= missing-component page-header) "PageHeader alias should exist")
    (when-not (= missing-component page-header)
      (let [html (page-header-html {:title   "Policy Settings"
                                    :actions [[:button {:type "button"} "Save"]
                                              [:a {:href "/insurance"} "Back"]]})]
        (is (str/includes? html "<menu"))
        (is (str/includes? html "<li><button type=\"button\">Save</button></li>"))
        (is (str/includes? html "<li><a href=\"/insurance\">Back</a></li>"))))))
