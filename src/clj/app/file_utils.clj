(ns app.file-utils
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files Paths]))

(defn- file
  ^File [& args]
  (apply io/file args))

(defn path-join
  "Joins paths together and returns the resulting path as a string. nil and empty strings are
   silently discarded. "
  ^String [& paths]
  (let [paths' (remove empty? paths)]
    (if (empty? paths')
      ""
      (str (apply file paths')))))

(defn basename
  "Given a path /foo/bar/baz returns baz"
  ^String [^String path]
  (.getName (file path)))

(defn validate-base-path
  "Helper to prevent path traversal attacks. If full-path is not contained inside base-path, will return false, otherwise true"
  [base-path full-path]
  (str/starts-with? (-> (file full-path) (.getCanonicalPath))
                    base-path))

(defn validate-base-path!
  "Helper to prevent path traversal attacks. If full-path is not contained inside base-path, will throw an exception"
  [base-path full-path]
  (when-not (validate-base-path base-path full-path)
    (throw (ex-info "Path traversal attack detected" {:base-path base-path
                                                      :full-path full-path}))))

(defn- to-path [v]
  (cond
    (string? v) (Paths/get v (into-array String []))
    (instance? java.nio.file.Path v) v
    (instance? java.io.File v) (.toPath ^File v)
    :else (throw (ex-info "Unsupported type" {:type (type v)}))))

(defn delete-if-exists [f]
  (Files/deleteIfExists (to-path f)))
