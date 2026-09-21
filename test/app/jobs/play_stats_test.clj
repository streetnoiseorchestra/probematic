(ns app.jobs.play-stats-test
  (:require
   [app.gigs.domain :as gig-domain]
   [app.gigs.log-plays.actions :as plays]
   [app.jobs.play-stats :as play-stats]
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
            (let [db         (d/db conn)
                  song       (d/entity db [:song/song-id song-id])
                  audit-user (d/q '[:find ?member-id .
                                    :in $ ?action
                                    :where
                                    [?tx :audit/action ?action]
                                    [?tx :audit/user ?member]
                                    [?member :member/member-id ?member-id]]
                                  db
                                  :app.jobs.play-stats/refresh)]
              (is (= 1 (:song/total-rating-bad song)))
              (is (= 0 (:song/total-rating-good song)))
              (is (nil? audit-user)))
            (finally (jobs-worker/stop! worker))))))))

(deftest play-statistics-job-retains-the-source-transaction-actor
  (with-runtime
    (fn [runtime client conn]
      (let [actor-id (random-uuid)
            control  (:write-runner runtime)]
        (writer/call!
         control
         (fn []
           @(d/transact conn [{:member/member-id actor-id}])
           (nexus/db-transact-fx
            {}
            {:system  {:datomic {:conn conn}}
             :request {:app/session         {:session/member {:member/member-id actor-id}}
                       ::nexus/audit-action ::request-play-statistics}}
            [[[] {:jobs [play-stats/job]}]])))
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
            (is (= actor-id
                   (d/q '[:find ?member-id .
                          :in $ ?action
                          :where
                          [?tx :audit/action ?action]
                          [?tx :audit/user ?member]
                          [?member :member/member-id ?member-id]]
                        (d/db conn)
                        :app.jobs.play-stats/refresh)))
            (finally (jobs-worker/stop! worker))))))))
