(ns app.file-browser.views-test
  (:require
   [app.file-browser.views :as views]
   [clojure.test :refer [deftest is]]))

(deftest selected-file-path-test
  (is (= {"/foo/bar"        "foo/bar"
          "/foo//bar"       "foo//bar"
          "/../foo"         "foo"
          "/foo/../../bar"  "bar"}
         (into {}
               (map (fn [path] [path (@#'views/selected-file-path {:path path})]))
               ["/foo/bar" "/foo//bar" "/../foo" "/foo/../../bar"]))))

(deftest component-paths-test
  (is (= {"/foo/bar"   ["/foo" "/foo/bar"]
          "/foo//bar"  ["/foo" "/foo/" "/foo//bar"]}
         (into {}
               (map (fn [path] [path (vec (@#'views/component-paths path))]))
               ["/foo/bar" "/foo//bar"]))))
