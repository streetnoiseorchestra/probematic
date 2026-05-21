(ns app.util.zip-test
  (:require
   [app.util.zip :as zip]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(defn- zip-entries [file]
  (with-open [zip-file (ZipFile. file)]
    (into {}
          (map (fn [^ZipEntry entry]
                 [(.getName entry)
                  (with-open [in (.getInputStream zip-file entry)]
                    (slurp in))]))
          (enumeration-seq (.entries zip-file)))))

(deftest temp-file-zip-test
  (let [temp-file (zip/temp-file-zip
                   "probematic-test."
                   ".zip"
                   (fn [^ZipOutputStream zip-output]
                     (.putNextEntry zip-output (ZipEntry. "hello.txt"))
                     (.write zip-output (.getBytes "hello" "UTF-8"))
                     (.closeEntry zip-output)))]
    (try
      (is (= {:file?         true
              :name-matches? true
              :entries       {"hello.txt" "hello"}}
             {:file?         (instance? java.io.File temp-file)
              :name-matches? (and (str/starts-with? (.getName temp-file) "probematic-test.")
                                  (str/ends-with? (.getName temp-file) ".zip"))
              :entries       (zip-entries temp-file)}))
      (finally
        (fs/delete-if-exists temp-file)))))
