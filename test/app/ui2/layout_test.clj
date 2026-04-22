(ns app.ui2.layout-test
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

(defn alias-value [sym]
  (if-let [v (resolve-var sym)]
    @v
    missing-component))

(defn render [alias-sym attrs & children]
  (let [alias (alias-value alias-sym)]
    (when-not (= missing-component alias)
      (html/->str (into [alias attrs] children)))))

(deftest panel-renders-title-subtitle-buttons-and-children
  (let [html (render 'app.ui2.layout/Panel
                     {:app.ui2.layout/title    "Teams"
                      :app.ui2.layout/subtitle "Because someone has to do the work"
                      :app.ui2.layout/buttons  [:button {:type "button"} "Reorder"]}
                     [:div "Body"]) ]
    (is (some? html) "Panel alias should exist")
    (when html
      (is (str/includes? html "Teams"))
      (is (str/includes? html "Because someone has to do the work"))
      (is (str/includes? html "Reorder"))
      (is (str/includes? html "Body"))
      (is (str/includes? html "bg-white shadow-sm sm:rounded-lg")))))
