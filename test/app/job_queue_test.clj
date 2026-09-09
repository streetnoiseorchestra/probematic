(ns app.job-queue-test
  (:require [app.ig]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [s-exp.drip :as drip]))

(deftest persists-jobs-across-restarts
  (let [dir      (.toFile (java.nio.file.Files/createTempDirectory
                           "probematic-jobs" (make-array java.nio.file.attribute.FileAttribute 0)))
        filename (str (io/file dir "jobs.sqlite"))
        config   {:app.ig/job-queue {:filename filename}}]
    (try
      (let [system (ig/init config)]
        (try
          (drip/insert-job (get-in system [:app.ig/job-queue :client]) "test" {:value 42})
          (finally (ig/halt! system))))
      (let [system (ig/init config)]
        (try
          (is (= [{:kind "test" :args {:value 42} :state :available}]
                 (mapv #(select-keys % [:kind :args :state])
                       (drip/list-jobs (get-in system [:app.ig/job-queue :client]) {}))))
          (finally (ig/halt! system))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file))))))
