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

(deftest combobox-is-defined-before-datastar-initializes
  (let [head          (layout2/head head-request {})
        loader-script (some (fn [script]
                              (when (str/includes? (str (l/first-child script))
                                                   "startLoader")
                                script))
                            (l/select 'script head))]
    (is (str/includes? (str (some-> loader-script l/first-child))
                       "import 'wa/components/combobox/combobox.js';"))))

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

(deftest datastar-authentication-errors-replace-stale-page-history
  (let [markup (layout2/shim-html head-request {})]
    (is (str/includes? markup
                       "evt.detail.argsRaw.status === &apos;401&apos;"))
    (is (str/includes? markup "window.location.replace"))
    (is (str/includes?
         markup
         "encodeURIComponent(window.location.pathname + window.location.search)"))
    (is (str/includes? markup "retryMaxCount: Infinity"))))

(deftest application-shell-owns-one-empty-logout-form
  (let [body (layout2/app-shell-body
              {:session {:session/member {}}}
              [:main "Page content"])
        forms (filter #(= "logout-form" (:id (l/attrs %)))
                      (l/select 'form body))
        form  (first forms)]
    (is (= {:count 1
            :attrs {:id "logout-form"
                    :method "post"
                    :action "/logout"}
            :children []}
           {:count (count forms)
            :attrs (some-> form l/attrs
                           (select-keys [:id :method :action]))
            :children (if form (vec (l/children form)) [])}))))
