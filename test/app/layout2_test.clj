(ns app.layout2-test
  (:require
   [app.layout2 :as layout2]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [lookup.core :as l]))

(def head-request
  {:system {:env {:ig/system {:app.ig/profile :dev}}}})

(defn element-index [pred elements]
  (some (fn [[index element]]
          (when (pred element) index))
        (map-indexed vector elements)))

(deftest appearance-initializer-runs-before-the-main-stylesheet
  (let [head       (layout2/head head-request {})
        children   (vec (l/children head))
        initializer (l/select-one "#appearance-initializer" head)
        init-index (element-index #(= "appearance-initializer"
                                      (:id (l/attrs %)))
                                  children)
        css-index  (element-index #(and (= :link (first %))
                                        (= "stylesheet" (:rel (l/attrs %)))
                                        (some-> (l/attrs %) :href
                                                (.contains "/css/compiled/main2.css")))
                                  children)]
    (is (some? initializer))
    (when initializer
      (is (= {:id "appearance-initializer"
              :blocking "render"}
             (select-keys (l/attrs initializer) [:id :blocking])))
      (is (str/includes? (str (l/first-child initializer))
                         "dataset.accountAppearance = value")))
    (is (number? init-index))
    (is (number? css-index))
    (when (and (number? init-index) (number? css-index))
      (is (< init-index css-index)))))
