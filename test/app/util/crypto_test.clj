;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2

(ns app.util.crypto-test
  (:require
   [app.util.codec :as codec]
   [app.util.crypto :as crypto]
   [app.util.random :as random]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import [java.security SecureRandom]))

(deftest secure-random-values
  (is (instance? SecureRandom (random/secure-random)))
  (is (identical? (random/secure-random) (random/secure-random)))
  (let [rng (random/make-reseeding-secure-random 1)]
    (is (identical? (rng) (rng))))
  (let [sizes   [16 20 32]
        results (mapv crypto/bytes sizes)]
    (is (= [[true true true] sizes]
           [(mapv bytes? results) (mapv count results)])))
  (is (re-matches #"[A-Za-z0-9_-]{43}" (crypto/rand-string 32)))
  (is (re-matches #"[A-Za-z0-9_-]{27}" (crypto/new-uid)))
  (is (= "" (crypto/rand-string 0))))

(deftest byte-hashing
  (doseq [[hash-fn input expected]
          [[crypto/sha256 "abc" "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"]
           [crypto/sha256 "" "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]
           [crypto/sha384 "abc" "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7"]]]
    (is (= expected (codec/->hex (hash-fn (.getBytes ^String input "UTF-8")))))))

(deftest file-hashing
  (let [file (java.io.File/createTempFile "probematic-crypto-" ".txt")
        path (.getAbsolutePath file)]
    (try
      (spit file "hello world")
      (is (= [32 48 "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"]
             [(count (crypto/sha256-file path))
              (count (crypto/sha384-file path))
              (crypto/sha256-file-hex path)]))
      (doseq [[file-hash stream-hash] [[crypto/sha256-file crypto/sha256-stream]
                                       [crypto/sha384-file crypto/sha384-stream]]]
        (with-open [in (io/input-stream file)]
          (is (= (seq (file-hash path)) (seq (stream-hash in))))))
      (doseq [algo ["sha256" "sha384" "sha512"]]
        (with-open [in (io/input-stream file)]
          (is (= (crypto/sri-hash-stream algo in) (crypto/sri-hash-file algo path)))))
      (is (= (crypto/sri-hash-file "sha384" path) (crypto/sri-sha384-file path)))
      (finally (io/delete-file file)))
    (is (= [nil nil nil nil]
           [(crypto/sha256-file path)
            (crypto/sha256-file-hex path)
            (crypto/sha384-file path)
            (crypto/sri-sha384-file path)]))))

(deftest resource-hashing
  (let [path     "app/util/crypto_test.clj"
        hex-hash (crypto/sha256-resource-hex path)]
    (is (= [32 48] [(count (crypto/sha256-resource path))
                    (count (crypto/sha384-resource path))]))
    (is (re-matches #"[0-9a-f]{64}" hex-hash))
    (is (= (str "sha384-" (codec/->base64 (crypto/sha384-resource path)))
           (crypto/sri-sha384-resource path)))
    (doseq [algo ["sha256" "sha384" "sha512"]]
      (with-open [in (io/input-stream (io/resource path))]
        (is (= (crypto/sri-hash-stream algo in) (crypto/sri-hash-resource algo path))))))
  (doseq [hash-fn [crypto/sha256-resource crypto/sha256-resource-hex
                   crypto/sha384-resource crypto/sri-sha384-resource]]
    (is (thrown? clojure.lang.ExceptionInfo (hash-fn "nonexistent-crypto-test-resource")))))

(deftest sri-algorithms
  (doseq [[algo hash-fn] [["sha256" crypto/sha256] ["sha384" crypto/sha384]
                          ["sha512" #(crypto/hash-bytes "SHA-512" %)]]]
    (let [data (.getBytes "test" "UTF-8")]
      (with-open [in (io/input-stream data)]
        (is (= (str algo "-" (codec/->base64 (hash-fn data)))
               (crypto/sri-hash-stream algo in))))))
  (doseq [algo ["MD5" "md5" "SHA256" "SHA-256" "sha1" nil]]
    (with-open [in (io/input-stream (byte-array 0))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid SRI algorithm"
                            (crypto/sri-hash-stream algo in))))))

(deftest hmac-and-equality
  (let [key    (crypto/secret-key->hmac-sha256-keyspec "key")
        result (crypto/hmac-sha256 key "The quick brown fox jumps over the lazy dog")]
    (is (= "97yD9DBThCSxMpjmqm-xQ-9NWaFJRhdZl0edvC0aPNg" result))
    (is (crypto/eq? result result))
    (is (not (crypto/eq? result (str "x" (subs result 1)))))))

(deftest hkdf-rfc5869-vector
  (testing "RFC 5869 appendix A.1"
    (is (= (str "3cb25f25faacd57a90434f64d0362f2a"
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                "34007208d5b887185865")
           (codec/->hex
            (crypto/derive-subkey-hkdf-sha256
             (byte-array (repeat 22 11))
             {:salt   (codec/hex-> "000102030405060708090a0b0c")
              :info   (codec/hex-> "f0f1f2f3f4f5f6f7f8f9")
              :length 42}))))))

(deftest hkdf-length-and-context
  (let [ikm     (byte-array (range 32))
        primary (crypto/secret-bytes->hmac-sha256-keyspec ikm)]
    (doseq [length [0 32 4096 8160]]
      (is (= length (alength (crypto/derive-subkey-hkdf-sha256 ikm {:length length})))))
    (doseq [length [-1 8161 1.5 nil]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (crypto/derive-subkey-hkdf-sha256 ikm {:length length}))))
    (is (= (seq (crypto/derive-subkey-hkdf-sha256 ikm {:info (.getBytes "test/v1" "UTF-8")}))
           (seq (.getEncoded (crypto/derive-hmac-keyspec primary "test/v1")))))
    (is (not= (seq (.getEncoded (crypto/derive-hmac-keyspec primary "test/v1")))
              (seq (.getEncoded (crypto/derive-hmac-keyspec primary "other/v1")))))
    (is (thrown? clojure.lang.ExceptionInfo (crypto/derive-hmac-keyspec primary " ")))))

(deftest csrf-key-derivation
  (let [primary                                    (crypto/key->hmac-sha256-keyspec
                                                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        ^javax.crypto.spec.SecretKeySpec csrf-key  (crypto/derive-csrf-hmac-keyspec primary)
        ^javax.crypto.spec.SecretKeySpec short-key (crypto/derive-csrf-hmac-keyspec primary {:length 16})]
    (is (= [javax.crypto.spec.SecretKeySpec
            "ea194113a5ca61485b1243cd66d19c79c41ed4c9a28b623a73b227e3a9c9a31b"
            true false 16]
           [(class csrf-key)
            (codec/->hex (.getEncoded csrf-key))
            (= csrf-key (crypto/derive-csrf-hmac-keyspec primary))
            (= csrf-key (crypto/derive-hmac-keyspec primary "app.other.hmac/v1"))
            (alength (.getEncoded short-key))]))))
