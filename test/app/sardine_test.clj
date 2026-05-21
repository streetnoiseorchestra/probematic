(ns app.sardine-test
  (:require
   [app.sardine :as sardine]
   [clojure.test :refer [deftest is]]))

(deftest build-full-path-test
  (let [config {:webdav-base-path "/remote.php/dav/files/user/"}]
    (is (= ["/remote.php/dav/files/user/Scores/Foo"
            "/remote.php/dav/files/user/Scores/Foo"
            "/remote.php/dav/files/user"
            "/remote.php/dav/files/user"]
           (mapv #(sardine/build-full-path config %)
                 ["/Scores/Foo" "Scores/Foo" "" nil])))))

(deftest component-paths-test
  (is (= {"/foo/bar"      ["/foo" "/foo/bar"]
          "/foo//bar"     ["/foo" "/foo/" "/foo//bar"]
          "/foo/./bar"    ["/foo" "/foo/." "/foo/./bar"]
          "/foo/../bar"   ["/foo" "/foo/.." "/foo/../bar"]}
         (into {}
               (map (fn [path] [path (vec (@#'sardine/component-paths path))]))
               ["/foo/bar" "/foo//bar" "/foo/./bar" "/foo/../bar"]))))

(deftest validate-base-path-test
  (is (= {:child-path    :ok
          :sibling-prefix :ok
          :parent-path   :blocked}
         {:child-path    (try (@#'sardine/validate-base-path! "/tmp/probematic-base"
                                                              "/tmp/probematic-base/file")
                              :ok
                              (catch clojure.lang.ExceptionInfo _e :blocked))
          :sibling-prefix (try (@#'sardine/validate-base-path! "/tmp/probematic-base"
                                                               "/tmp/probematic-base-sibling/file")
                               :ok
                               (catch clojure.lang.ExceptionInfo _e :blocked))
          :parent-path   (try (@#'sardine/validate-base-path! "/tmp/probematic-base/subdir"
                                                              "/tmp/probematic-base/file")
                              :ok
                              (catch clojure.lang.ExceptionInfo _e :blocked))})))
