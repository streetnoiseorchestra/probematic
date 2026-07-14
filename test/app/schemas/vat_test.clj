(ns app.schemas.vat-test
  (:require
   [clojure.test :refer [deftest is]]
   [app.schemas.vat :as sut]))

(deftest vat-number-cleaner
  (is (= nil (sut/clean-vat-number nil)))
  (is (= nil (sut/clean-vat-number "")))
  (is (= "12345" (sut/clean-vat-number "DE12345")))
  (is (= "67890AB" (sut/clean-vat-number "67890 Ab")))
  (is (= "XX123" (sut/clean-vat-number "XX123")))
  (is (= "U54321" (sut/clean-vat-number "AT U 54321 "))))
