(ns app.demo
  (:import
   [java.util.concurrent TimeUnit]
   [com.github.javafaker Faker])
  (:require [clojure.string :as str]
            [app.datomic.shim :as d]
            [tick.core :as t]
            [app.gigs.domain :as domain]
            [com.yetanalytics.squuid :as sq]))

(def faker (Faker.))
(-> faker (.internet) (.password))

(defn last-name []
  (-> faker (.name) (.lastName)))

(defn first-name []
  (-> faker (.name) (.firstName)))

(defn person-name []
  (-> faker (.name) (.name)))

(defn phone []
  (-> faker (.phoneNumber) (.phoneNumber)))

(defn email []
  (-> faker (.internet) (.emailAddress)))

(defn section []
  (rand-nth ["flute"
             "bass"
             "percussion"
             "trumpet"
             "dance"
             "sax alto"
             "sax tenor"
             "trombone/bombardino"
             "sax soprano/clarinet"]))

(defn member-id [] (sq/generate-squuid))
(defn member []
  (let [full-name (person-name)]
    {:member/member-id (member-id)
     :member/name full-name
     :member/nick (-> full-name (str/split #" ") first (str/lower-case))
     :member/email (email)
     :member/phone (phone)
     :member/active? true}))

(defn rand-member-txs []
  [(merge (member) {:member/section [:section/name (section)]})])

(defn rand-members-txs
  "Return a list of txs of length amt with new random members"
  [amt]
  (flatten (repeatedly amt rand-member-txs)))

(defn seed-random-members! [conn]
  (d/transact conn {:tx-data [{:member/member-id "admin"
                               :member/name "Admin Nimda"
                               :member/nick "admin"
                               :member/email "admin@example.com"
                               :member/phone "+112738127387"
                               :member/section [:section/name "bass"]
                               :member/active? true}]})
  (d/transact conn {:tx-data (rand-members-txs 30)}))

(defn seed-gigs! [conn]
  (d/transact conn {:tx-data
                    (map domain/gig->db
                         [{:gig/gig-id (member-id)
                           :gig/title "Rehersal"
                           :gig/location "Proberaum"
                           :gig/gig-type :gig.type/probe
                           :gig/status :gig.status/confirmed
                           :gig/date  (t/>> (t/date) (t/new-period 7 :days))
                           :gig/contact        [:member/member-id "admin"]
                           :gig/call-time (t/time "19:00")
                           :gig/set-time (t/time "19:30")}
                          {:gig/gig-id (member-id)
                           :gig/title "Rehersal"
                           :gig/location "Proberaum"
                           :gig/gig-type :gig.type/probe
                           :gig/status :gig.status/confirmed
                           :gig/date  (t/>> (t/date) (t/new-period 14 :days))
                           :gig/contact        [:member/member-id "admin"]
                           :gig/call-time (t/time "19:00")
                           :gig/set-time (t/time "19:30")}
                          {:gig/gig-id (member-id)
                           :gig/title "Treibhaus Gig"
                           :gig/location "Treibhaus"
                           :gig/gig-type :gig.type/gig
                           :gig/status :gig.status/unconfirmed
                           :gig/date  (t/>> (t/date) (t/new-period 6 :days))
                           :gig/contact        [:member/member-id "admin"]
                           :gig/call-time (t/time "13:00")
                           :gig/leader     "Norbert"
                           :gig/post-gig-plans "Pizza and beer"
                           :gig/more-details   "We're playing at Weltfest again this year."
                           :gig/pay-deal "500 EUR + drinks"
                           :gig/set-time (t/time "13:20")}])}))
