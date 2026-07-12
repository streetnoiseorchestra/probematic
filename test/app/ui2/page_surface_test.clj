(ns app.ui2.page-surface-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn page-surface-alias []
  (try
    (some-> (requiring-resolve 'app.ui2.page-surface/PageSurface) deref)
    (catch Throwable _
      nil)))

(defn page-surface-html [attrs & children]
  (when-let [page-surface (page-surface-alias)]
    (html/->str (into [page-surface attrs] children))))

(deftest page-surface-renders-default-width-and-forwards-content
  (let [page-surface (page-surface-alias)]
    (is (some? page-surface) "PageSurface alias should exist")
    (when page-surface
      (let [rendered (page-surface-html {:id "member-page"
                                         :class "member-surface"
                                         :aria-label "Member details"}
                                        [:h1 "Ada Lovelace"])]
        (is (str/starts-with? rendered "<section"))
        (is (str/includes? rendered "class=\"sno-page-surface member-surface\""))
        (is (str/includes? rendered "data-width=\"standard\""))
        (is (str/includes? rendered "id=\"member-page\""))
        (is (str/includes? rendered "aria-label=\"Member details\""))
        (is (str/includes? rendered "<h1>Ada Lovelace</h1>"))))))

(deftest page-surface-supports-every-width-intent
  (is (= ["compact" "standard" "wide"]
         (mapv (fn [width]
                 (let [rendered (page-surface-html
                                 {:app.ui2.page-surface/width width}
                                 width)]
                   (second (re-find #"data-width=\"([^\"]+)\"" rendered))))
               [:compact :standard :wide]))))
