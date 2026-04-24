(ns app.ui2.input-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn resolve-var [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _
      nil)))

(deftest input-button-wraps-a-control-and-action-area
  (let [v    (resolve-var 'app.ui2.input/input-button)
        html (when v
               (html/->str (@v {}
                               [:input {:type "text" :name "team-name"}]
                               [:button {:type "button"} "Cancel"]
                               [:button {:type "submit"} "Save"])))]
    (is (some? html) "input-button should exist")
    (when html
      (is (str/includes? html "sm:flex sm:items-center"))
      (is (str/includes? html "w-full sm:max-w-xs"))
      (is (str/includes? html "Cancel"))
      (is (str/includes? html "Save")))))
