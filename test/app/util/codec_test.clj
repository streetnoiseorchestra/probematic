;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2

(ns app.util.codec-test
  (:require
   [app.util.codec :as codec]
   [clojure.test :refer [are deftest is]])
  (:import [java.util Base64]))

(deftest hex-encoding
  (are [input expected] (= expected (codec/->hex input))
    (.getBytes "test" "UTF-8") "74657374"
    (.getBytes "hello" "UTF-8") "68656c6c6f"
    (.getBytes "" "UTF-8") ""
    (byte-array [0 15 16 -1]) "000f10ff"
    (byte-array [127 -128 -1]) "7f80ff"))

(deftest base64-encoding
  (are [input expected] (= expected (codec/->base64 input))
    (.getBytes "test" "UTF-8") "dGVzdA"
    (.getBytes "hello" "UTF-8") "aGVsbG8"
    (.getBytes "" "UTF-8") ""
    (byte-array [-5 -1]) "-_8"))

(deftest encoding-roundtrips
  (let [data (byte-array (map unchecked-byte (range 256)))]
    (is (= (seq data) (seq (codec/hex-> (codec/->hex data)))))
    (is (= (seq data)
           (seq (.decode (Base64/getUrlDecoder) (codec/->base64 data)))))))

(deftest invalid-hex
  (is (thrown? Exception (codec/hex-> "a")))
  (is (thrown? NumberFormatException (codec/hex-> "zz"))))

(deftest digesting-values
  (is (= (codec/digest "test") (codec/digest "test")))
  (is (not= (codec/digest "test1") (codec/digest "test2"))))
