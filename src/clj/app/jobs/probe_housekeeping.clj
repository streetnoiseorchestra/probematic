(ns app.jobs.probe-housekeeping
  (:require
   [app.datomic :as d]
   [app.email.mailers :as mailers]
   [app.nexus :as nexus]
   [app.errors :as errors]
   [app.gigs.domain :as domain]
   [app.probeplan :as probeplan]
   [app.queries :as q]
   [app.write-runner :as writer]
   [com.yetanalytics.squuid :as sq]
   [app.datomic.shim :as datomic]
   [ol.jobs-util :as jobs]
   [tick.core :as t]))

(def minimum-gigs 4)
(def maximum-create 4)

(defn on-date?
  [date probe]
  (= (:gig/date probe)  date))

(defn- find-probe-dates
  "Returns a list of dates that need probes"
  [n next-probes]
  (let [next-wednesdays (->> (probeplan/wednesday-sequence (t/today))
                             (take 10)
                             (mapv t/date))]
    (->> next-wednesdays
         (remove #(some (partial on-date? %) next-probes))
         (take n))))

(defn newprobe-tx
  [date]
  (domain/gig->db
   {:gig/gig-id    (sq/generate-squuid)
    :gig/title     "Probe"
    :gig/status    :gig.status/confirmed
    :gig/date      date
    :gig/gig-type  :gig.type/probe
    :gig/location  "Proberaum in den Bögen"
    :gig/call-time (t/time "18:45")
    :gig/set-time  (t/time "19:00")
    :gig/end-time  (t/time "22:00")
    :gig/contact   [:member/gigo-key "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA6K70hwoM"]}))

(defn- create-probes!
  [conn probes]
  (let [probe-dates (find-probe-dates (- minimum-gigs (count probes)) probes)
        txs         (take maximum-create (map newprobe-tx probe-dates))]
    (d/transact conn {:tx-data txs
                      :audit   {:audit/action ::create-probes
                                :audit/origin :app.origin/job}})))

(defn- assign-rehearsal-leaders!
  [conn]
  (let [db           (datomic/db conn)
        prev-probe   (q/previous-probe db)
        next-probe   (q/next-probe db)
        last-leader2 (:gig/rehearsal-leader2 prev-probe)
        next-leader1 (:gig/rehearsal-leader1 next-probe)]
    (when (and (some? last-leader2)  (nil? next-leader1))
      (d/transact conn
                  {:tx-data [[:db/add (d/ref next-probe) :gig/rehearsal-leader1 (d/ref last-leader2)]]
                   :audit   {:audit/action ::assign-rehearsal-leaders
                             :audit/origin :app.origin/job}}))))

(defn- probe-housekeeping-job
  [{:keys [datomic] :as system} _]
  (try
    (let [maintain! (fn []
                      (let [conn   (:conn datomic)
                            probes (q/next-probes (datomic/db conn) q/gig-detail-pattern)]
                        (when (< (count probes) minimum-gigs)
                          (create-probes! conn probes))
                        (assign-rehearsal-leaders! conn)
                        :done))]
      (writer/call! system maintain!))
    (catch Exception e
      (tap> e)
      (errors/report-error! e))))

(defn notify-rehearsal-leader!
  [{:keys [datomic] :as system}]
  (try
    (writer/call!
     system
     (fn []
       (let [conn       (:conn datomic)
             next-probe (q/next-probe (datomic/db conn))
             leaders    (distinct (keep #(get next-probe %) [:gig/rehearsal-leader1 :gig/rehearsal-leader2]))]
         (when-not (= (:gig/date next-probe) (t/date))
           (throw (ex-info "notify rehearsal leaders condition failed!"
                           {:probe-date (:gig/date next-probe) :current-date (t/date)})))
         (when (seq leaders)
           (let [intents (mapv #(mailers/job {:current-locale :de} ::mailers/rehearsal-leader
                                             {:gig-id (:gig/gig-id next-probe) :member-id (:member/member-id %)})
                               leaders)]
             (d/transact conn
                         {:tx-data (nexus/batch-transactions [[[] {:jobs intents}]])
                          :audit   {:audit/action ::notify-rehearsal-leader
                                    :audit/origin :app.origin/job}}))))))
    (catch Exception e
      (errors/report-error! e))))

(defn- start-rehearsal-leader-notify!
  [system]
  (let [zone-id   (t/zone "Europe/Berlin")
        first-run (-> (t/instant)
                      (t/in zone-id)
                      t/date
                      (t/at (t/time "22:00"))
                      (t/in zone-id))
        next-wednesdays-at-10-pm
        (->> (iterate #(t/>> % (t/new-period 1 :days)) first-run)
             (filter (comp #{t/WEDNESDAY} t/day-of-week)))]
    (jobs/create-schedule :name ::rehearsal-leader-notify
                          :times next-wednesdays-at-10-pm
                          :start-at first-run
                          :handler (fn [_] (notify-rehearsal-leader! system)))))

(defn make-probe-housekeeping-job
  [system]
  (fn [{:job/keys [frequency initial-delay]}]
    (start-rehearsal-leader-notify! system)
    (jobs/make-repeating-job (partial probe-housekeeping-job system) frequency initial-delay)))
