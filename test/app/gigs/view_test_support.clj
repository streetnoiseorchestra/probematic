(ns app.gigs.view-test-support
  (:require
   [app.gigs.domain :as domain]
   [app.test-common :as tc]
   [datomic.api :as d]
   [reitit.core :as r]
   [tick.core :as t]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def translations
  {[:gigs/type-extra-probe] "Extra Probe"
   [:gigs/type-gig]         "Gig"
   [:gigs/type-meeting]     "Meeting"
   [:gigs/type-probe]       "Probe"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path _args]
   (tr path)))

(defn new-system [prefix]
  (tc/new-system prefix))

(defn request
  ([conn]
   (request conn {}))
  ([conn extra]
   (merge {::r/router       router
           :current-locale :en
           :db             (d/db conn)
           :page-state     {}
           :system         {:env {}}
           :tr             tr}
          extra)))

(defn seed-gig!
  ([conn gig-id]
   (seed-gig! conn gig-id {}))
  ([conn gig-id gig]
   @(d/transact
     conn
     [(domain/gig->db
       (merge {:gig/gig-id    gig-id
               :gig/title     "Summer Concert"
               :gig/status    :gig.status/confirmed
               :gig/gig-type  :gig.type/gig
               :gig/date      (t/date "2026-07-15")
               :gig/location  "Band room"
               :gig/call-time (t/time "18:00")}
              gig))])))
