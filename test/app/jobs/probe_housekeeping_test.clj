(ns app.jobs.probe-housekeeping-test
  (:require
   [app.game-loop :as game]
   [app.gigs.domain :as gigs]
   [app.jobs.probe-housekeeping :as probes]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [chime.core :as chime]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [ol.jobs-util :as jobs]
   [tick.core :as t])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest rehearsal-maintenance-waits-for-the-writer-and-preserves-leader-rotation
  (fixtures/with-runtime
    (fn [runtime _ conn]
      (let [member-id (random-uuid)
            entered   (promise)
            release   (promise)
            system    {:frame-loop runtime :datomic {:conn conn}}]
        (writer/call!
         (:write-runner runtime)
         (fn []
           @(d/transact conn [{:member/member-id member-id                                          :member/name "Rehearsal contact"
                               :member/gigo-key  "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA6K70hwoM"}])
           @(d/transact conn [(gigs/gig->db {:gig/gig-id            (random-uuid)                          :gig/title  "Previous rehearsal"
                                             :gig/gig-type          :gig.type/probe                        :gig/status :gig.status/confirmed
                                             :gig/date              (t/<< (t/date) (t/new-period 1 :days))
                                             :gig/rehearsal-leader2 [:member/member-id member-id]})])))
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (let [result (future (#'probes/probe-housekeeping-job system nil))]
            (is (= ::waiting (deref result 1000 ::waiting)))
            (is (empty? (q/next-probes (d/db conn) q/gig-detail-pattern)))
            (deliver release true)
            (is (= :done (deref result 5000 ::timeout)))
            (let [db       (d/db conn)
                  upcoming (q/next-probes db q/gig-detail-pattern)]
              (is (= 4 (count upcoming)))
              (is (= member-id (get-in (first upcoming) [:gig/rehearsal-leader1 :member/member-id])))
              (#'probes/probe-housekeeping-job system nil)
              (is (= (d/basis-t db) (d/basis-t (d/db conn))))))
          (finally (deliver release true)))))))

(deftest rehearsal-notification-schedule-belongs-to-the-shutdown-registry
  (let [before   (set (map :id @jobs/schedules))
        handles  (atom #{})
        chime-at chime/chime-at]
    (try
      ;; Retain real handles so a failed registration assertion cannot leak a timer.
      (with-redefs [chime/chime-at (fn [& args]
                                     (let [handle (apply chime-at args)]
                                       (swap! handles conj handle)
                                       handle))]
        (t/with-clock (t/instant "2099-01-01T00:00:00Z")
          ((probes/make-probe-housekeeping-job {}) {:job/frequency [1 :days] :job/initial-delay [1 :days]})))
      (let [created (remove #(contains? before (:id %)) @jobs/schedules)]
        (is (= 2 (count created)))
        (doseq [{:keys [id]} created] (jobs/stop-schedule id))
        (is (every? #(realized? (:closeable %)) created))
        (is (= before (set (map :id @jobs/schedules)))))
      (finally
        (doseq [handle @handles] (.close ^java.lang.AutoCloseable handle))
        (doseq [{:keys [id]} (remove #(contains? before (:id %)) @jobs/schedules)]
          (jobs/stop-schedule id))))))
