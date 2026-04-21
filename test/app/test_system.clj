(ns app.test-system
  (:require
   [clojure.string :as str]
   [integrant.core :as ig]
   [app.system])
  (:import
   java.util.regex.Pattern))

(def ^:dynamic *tx* nil)
(def ^:dynamic *system* nil)

(defmacro ^:private with-system
  [system ks & body]
  `(let [system# ~system
         s#      (ig/init system# (or ~ks (keys system#)))]
     (try
       (binding [*system* s#]
         ~@body)
       (finally
         (ig/halt! s#)))))

(defn- default-system
  []
  (app.system/system-config
   {:profile :test}))

(defn with-system-fixture
  ([]
   (with-system-fixture default-system))
  ([system]
   (fn [f]
     (with-system (system) nil
       (f)))))

(defn with-subsystem-fixture
  ([ks]
   (with-subsystem-fixture default-system ks))
  ([system ks]
   (fn [f]
     (with-system (system) ks
       (f)))))

(def ^Pattern sep (Pattern/compile "^.*--;;.*\r?\n" Pattern/MULTILINE))
(def ^Pattern sql-comment (Pattern/compile "^--.*" Pattern/MULTILINE))
(def ^Pattern empty-line (Pattern/compile "^[ ]+" Pattern/MULTILINE))

(defn sanitize [command]
  (-> command
      (str/replace   sql-comment "")
      (str/replace empty-line "")))

(defn split-commands [commands]
  (->> (.split sep commands)
       (map #(sanitize %))
       (remove empty?)
       (not-empty)))
