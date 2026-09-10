(ns app.jobs.legacy-schedules-test
  (:require
   [app.jobs.gig-events :as events]
   [app.probeplan.stats :as stats]
   [app.test-common :as tc]
   [chime.core :as chime]
   [clojure.test :refer [deftest is use-fixtures]]
   [ol.jobs-util :as jobs]))

(use-fixtures :each tc/with-released-test-connections)

(deftest legacy-deferred-effects-belong-to-the-shutdown-registry
  (let [{:keys [conn]} (tc/new-system "legacy-effect-schedules")
        before         (set (map :id @jobs/schedules))
        handles        (atom [])
        chime-at       chime/chime-at]
    (try
      (with-redefs [chime/chime-at (fn [& args]
                                     (let [handle (apply chime-at args)]
                                       (swap! handles conj handle)
                                       handle))]
        (events/exec-later (constantly nil))
        (stats/calc-play-stats-in-bg! conn))
      (let [created (remove #(contains? before (:id %)) @jobs/schedules)]
        (is (= 2 (count created)))
        (doseq [{:keys [id]} created] (jobs/stop-schedule id))
        (is (every? #(realized? (:closeable %)) created))
        (is (= before (set (map :id @jobs/schedules)))))
      (finally
        (doseq [handle @handles] (.close ^java.lang.AutoCloseable handle))
        (doseq [{:keys [id]} (remove #(contains? before (:id %)) @jobs/schedules)]
          (jobs/stop-schedule id))))))
