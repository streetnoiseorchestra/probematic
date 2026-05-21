(ns app.util.zip-test
  (:require
   [app.util.zip :as zip]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [java.nio.charset StandardCharsets]
   [java.util.zip ZipInputStream ZipOutputStream]))

(defn- utf-8-bytes [s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- input-stream [s]
  (ByteArrayInputStream. (utf-8-bytes s)))

(defn- entry-content [^ZipInputStream zip-input]
  (let [out (ByteArrayOutputStream.)]
    (io/copy zip-input out)
    (String. (.toByteArray out) StandardCharsets/UTF_8)))

(defn- zip-stream-entries [stream]
  (with-open [zip-input (ZipInputStream. stream)]
    (loop [entries {}]
      (if-let [entry (.getNextEntry zip-input)]
        (let [entries (assoc entries (.getName entry) (entry-content zip-input))]
          (.closeEntry zip-input)
          (recur entries))
        entries))))

(defn- zip-bytes->entries [^ByteArrayOutputStream out]
  (zip-stream-entries (ByteArrayInputStream. (.toByteArray out))))

(deftest filename-normalization-test
  (is (= {:de-accented "weird user input.jpeg"
          :nil-input   nil
          :encoded     "weird-user-input-.jpeg"
          :safe        "safe name-01.txt"}
         {:de-accented (zip/de-accent "wëird user înput.jpeg")
          :nil-input   (zip/de-accent nil)
          :encoded     (zip/encode-filename "wëird:user:înput:.jpeg")
          :safe        (zip/encode-filename "safe name-01.txt")})))

(deftest append-stream-test
  (testing "appends and normalizes a zip entry"
    (let [out    (ByteArrayOutputStream.)
          result (with-open [zip-output (ZipOutputStream. out StandardCharsets/UTF_8)]
                   (let [returned (zip/append-stream!
                                   zip-output
                                   "wëird:user:înput:.txt"
                                   (input-stream "hello"))]
                     (.finish zip-output)
                     {:returned-same? (identical? zip-output returned)}))]
      (is (= {:returned-same? true
              :entries        {"weird-user-input-.txt" "hello"}}
             (assoc result :entries (zip-bytes->entries out))))))
  (testing "nil input is a no-op"
    (let [out    (ByteArrayOutputStream.)
          result (with-open [zip-output (ZipOutputStream. out StandardCharsets/UTF_8)]
                   (let [returned (zip/append-stream! zip-output "empty.txt" nil)]
                     (.finish zip-output)
                     {:returned-same? (identical? zip-output returned)}))]
      (is (= {:returned-same? true
              :entries        {}}
             (assoc result :entries (zip-bytes->entries out)))))))

(deftest open-and-append-test
  (let [closed? (atom false)
        out     (ByteArrayOutputStream.)]
    (with-open [zip-output (ZipOutputStream. out StandardCharsets/UTF_8)]
      (let [returned (zip/open-and-append!
                      zip-output
                      "opened.txt"
                      (fn []
                        (proxy [ByteArrayInputStream] [(utf-8-bytes "opened")]
                          (close []
                            (reset! closed? true)
                            (proxy-super close)))))]
        (.finish zip-output)
        (is (= {:returned-same? true
                :closed?        true
                :entries        {"opened.txt" "opened"}}
               {:returned-same? (identical? zip-output returned)
                :closed?        @closed?
                :entries        (zip-bytes->entries out)}))))))

(deftest piped-zip-input-stream-test
  (is (= {"one.txt" "one"
          "two.txt" "two"}
         (zip-stream-entries
          (zip/piped-zip-input-stream
           (fn [zip-output]
             (zip/append-stream! zip-output "one.txt" (input-stream "one"))
             (zip/append-stream! zip-output "two.txt" (input-stream "two"))))))))
