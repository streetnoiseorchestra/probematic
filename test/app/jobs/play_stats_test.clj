(ns app.jobs.play-stats-test
  (:require
   [app.gigs.domain :as gig-domain]
   [app.gigs.log-plays.actions :as plays]
   [app.jobs.worker :as jobs-worker]
   [app.nexus :as nexus]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :refer [with-runtime]]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]
   [tick.core :as t]))

(use-fixtures :each tc/with-released-test-connections)

(deftest play-statistics-job-uses-current-writer-state
  (with-runtime
    (fn [runtime client conn]
      (let [gig-id  (random-uuid)
            song-id (random-uuid)
            control (:write-runner runtime)]
        (writer/call!
         control
         (fn []
           @(d/transact conn
                        [(gig-domain/gig->db {:gig/gig-id   gig-id                                 :gig/title  "Rehearsal"
                                              :gig/gig-type :gig.type/probe                        :gig/status :gig.status/confirmed
                                              :gig/date     (t/<< (t/date) (t/new-period 1 :days))})
                         {:song/song-id song-id :song/title "One song" :song/active? true}])
           (let [[[_ tx-data opts]] (plays/update-rating-action
                                     {:db (d/db conn) :durable-jobs? true}
                                     {:gig-log-plays {:gig-id gig-id :song-id song-id :rating "play-rating/good"}})]
             (nexus/db-transact-fx {} {:system {:datomic {:conn conn}} :request {}}
                                   [[tx-data (dissoc opts :on-success)]]))
           ;; The refresh must include this newer state, not just its source transaction.
           @(d/transact conn [[:db/add [:played/gig+song (pr-str [gig-id song-id])]
                               :played/rating :play-rating/bad]])))
        (let [worker (jobs-worker/start! {:frame-loop runtime
                                          :job-queue  {:client client}
                                          :datomic    {:conn conn}})]
          (try
            (is (true?
                 (loop [remaining 500]
                   (if (= :completed (:state (first (drip/list-jobs client {:kind "refresh-play-stats"}))))
                     true
                     (when (pos? remaining)
                       (Thread/sleep 10)
                       (recur (dec remaining)))))))
            (let [song (d/entity (d/db conn) [:song/song-id song-id])]
              (is (= 1 (:song/total-rating-bad song)))
              (is (= 0 (:song/total-rating-good song))))
            (finally (jobs-worker/stop! worker))))))))
