(ns ol.system-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [app.test-system :refer [with-system-fixture *system*]]))

(defn new-system
  "Define a minimal system which is just enough for the tests in this
  namespace to run"
  []
  {})

(use-fixtures :once (with-system-fixture new-system))

(deftest system-test
  (is *system*)
  (is (= {} *system*)))
