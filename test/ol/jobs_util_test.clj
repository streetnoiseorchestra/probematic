(ns ol.jobs-util-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.jobs-util :as jobs]
   [tick.core :as t]))

(deftest immediate-one-shot-schedules-remove-their-registry-entries
  (dotimes [_ 30]
    (let [completed (promise)
          entries   (jobs/make-one-shot-job (fn [_] (deliver completed true)) [0 :seconds])
          schedule  (last entries)]
      (is (= true (deref completed 5000 ::timeout)))
      (is (nil? (deref (:closeable schedule) 5000 ::timeout)))
      (.close ^java.lang.AutoCloseable (:closeable schedule))
      (is (not-any? #(= (:id schedule) (:id %)) @jobs/schedules)))))

(deftest stopping-a-schedule-drains-its-handler-without-interrupting-it
  (doseq [one-shot? [false true]]
    (testing (if one-shot? "one-shot schedule" "repeating schedule")
      (let [entered     (promise)
            release     (promise)
            interrupted (promise)
            before      (set (map :id @jobs/schedules))
            handler     (fn [_]
                          (deliver entered true)
                          (try @release
                               (catch InterruptedException _
                                 (deliver interrupted true)
                                 @release)))]
        (try
          (if one-shot?
            (jobs/make-one-shot-job handler [0 :seconds])
            (jobs/create-schedule :handler handler :frequency (t/new-duration 1 :days)))
          (is (= true (deref entered 5000 ::timeout)))
          (let [id      (:id (first (remove #(contains? before (:id %)) @jobs/schedules)))
                stopped (future (jobs/stop-schedule id) :stopped)]
            (is (= ::waiting (deref stopped 250 ::waiting)))
            (is (not (realized? interrupted)))
            (deliver release true)
            (is (= :stopped (deref stopped 5000 ::timeout)))
            (is (not-any? #(= id (:id %)) @jobs/schedules)))
          (finally
            (deliver release true)
            (doseq [{:keys [id]} (remove #(contains? before (:id %)) @jobs/schedules)]
              (jobs/stop-schedule id))))))))
