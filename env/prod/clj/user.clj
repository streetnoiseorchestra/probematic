(ns user
  {:clj-kondo/config '{:linters       {:unused-namespace     {:level :off}
                                       :unresolved-namespace {:level :off}
                                       :unused-referred-var  {:level :off}}
                       :skip-comments true}}
  (:require
   [app.main :as main]
   ;; [playback.preload]
   [portal.api :as inspect]
   [app.queries :as q]))

(defn debug-in-prod []
  (inspect/open {:theme :portal.colors/gruvbox
                 :portal.launcher/host "0.0.0.0"
                 :portal.launcher/port  7001})
  (add-tap portal.api/submit))

(comment
  (require '[datomic.client.api :as datomic])

  (debug-in-prod)
  (tap> (keys main/system))

  (require '[com.brunobonacci.mulog :as μ])
  (μ/log ::hello)

  (require '[app.dashboard.routes :as dashboard.routes])
  (dashboard.routes/routes)

  (tap> main/system)

  (require '[app.queries :as q])
  (require '[app.datomic.shim :as datomic])
  (require '[app.jobs.reminders :as reminders])

  (let [db (datomic/db (-> main/system :app.ig/datomic-db :conn))
        sys
        {:env        (:app.ig/env main/system)
         :gigo       (:app.ig/gigo-client main/system)
         :datomic    (:app.ig/datomic-db main/system)
         :i18n-langs (:app.ig/i18n-langs main/system)
         :redis      (:app.ig/redis main/system)}]
    ;; (tap> (q/active-reminders-by-type db))
    (reminders/send-reminders! sys nil)
    ;;
    )

  (datomic/transact (-> main/system :app.ig/datomic-db :conn)
                    {:tx-data [[:db/add [:reminder/reminder-id #uuid "01881ecb-7613-873d-8b27-5ab7e9aed06b"]
                                :reminder/remind-at #inst "2023-05-13T09:43:50.547Z"]]})

  (require '[com.yetanalytics.squuid :as sq])

  (tap>
   (let [conn   (-> main/system :app.ig/datomic-db :conn)
         cat-id (sq/generate-squuid)
         tx
         {:db/id                           "cello"
          :instrument.category/name        "Celli und Gamben"
          :instrument.category/code        "8"
          :instrument.category/category-id cat-id}]
     (datomic/transact conn {:tx-data [tx]})))

  (def db (datomic/db (-> main/system :app.ig/datomic-db :conn)))
  (def conn (-> main/system :app.ig/datomic-db :conn))
  (def policy-id #uuid "018f43e6-281e-805d-b27f-ecb8a34b0497")

  (require '[app.insurance.domain :as id])
  (tap>
   (q/retrieve-policy db policy-id))

  (def policy (q/retrieve-policy db policy-id))

  (tap>
   (id/make-category-factor-lookup policy))

  (tap>
   (q/policies db))

  (def broken-p-id #uuid "018e15c2-ddbe-8b4f-b814-bddd26f8aec2")

  ;; 018fa117-e2ed-829b-84c5-38f850d4153a

  (tap>
   (let [conn   (-> main/system :app.ig/datomic-db :conn)
         cat-id #uuid "018fa117-e2ed-829b-84c5-38f850d4153a"
         tx     [{:insurance.category.factor/category-factor-id (sq/generate-squuid)
                  :db/id                                        "cat_fact"
                  :insurance.category.factor/factor             (bigdec 1.2)
                  :insurance.category.factor/category           [:instrument.category/category-id cat-id]}
                 [:db/add [:insurance.policy/policy-id broken-p-id] :insurance.policy/category-factors "cat_fact"]]]
     (datomic/transact conn {:tx-data tx})))

  (def brenda-coverage1-wrong-id #uuid "0196b0b3-ef53-89ad-903d-38fc80185c58")
  (def brenda-coverage2-wrong-id #uuid "0196b0b2-4ae4-8886-a4c1-7167f2e603ac")
  (def correct-policy-id #uuid "01966d17-a3cc-8480-af4f-37eeac8e2cec")
  (def correct-policy (ffirst
                       (datomic/q '[:find (pull ?e [*])
                                    :in $  ?policy-id
                                    :where [?e :insurance.policy/policy-id ?policy-id]]
                                  db  correct-policy-id)))
  (def brenda-coverage1-wrong (ffirst
                               (datomic/q '[:find (pull ?e [*])
                                            :in $  ?cov-id
                                            :where [?e :instrument.coverage/coverage-id ?cov-id]]
                                          db  brenda-coverage1-wrong-id)))
  (def brenda-coverage2-wrong (ffirst
                               (datomic/q '[:find (pull ?e [*])
                                            :in $  ?cov-id
                                            :where [?e :instrument.coverage/coverage-id ?cov-id]]
                                          db  brenda-coverage2-wrong-id)))
  (def brenda-instrument1
    (datomic/pull db '[*]
                  (-> brenda-coverage1-wrong :instrument.coverage/instrument :db/id)))
  (def brenda-instrument2
    (datomic/pull db '[*]
                  (-> brenda-coverage2-wrong :instrument.coverage/instrument :db/id)))

  (tap> brenda-instrument2)
  (tap> brenda-coverage1-wrong)
  (def brenda-coverage-1-good-id (sq/generate-squuid))
  (def brenda-coverage-2-good-id (sq/generate-squuid))
  (def brenda-coverage1-good
    (-> brenda-coverage1-wrong
        (assoc :db/id "covered_instrument")
        (assoc :instrument.coverage/coverage-id brenda-coverage-1-good-id)
        (assoc :instrument.coverage/types [])))

  (datomic/transact conn {:tx-data
                          [brenda-coverage1-good
                           [:db/add [:insurance.policy/policy-id correct-policy-id] :insurance.policy/covered-instruments "covered_instrument"]]})

  (def brenda-coverage2-good
    (-> brenda-coverage2-wrong
        (assoc :db/id "covered_instrument")
        (assoc :instrument.coverage/coverage-id brenda-coverage-2-good-id)
        (assoc :instrument.coverage/types [])))

  (tap> brenda-coverage2-good)
  (datomic/transact conn {:tx-data
                          [brenda-coverage2-good
                           [:db/add [:insurance.policy/policy-id correct-policy-id] :insurance.policy/covered-instruments "covered_instrument"]]})

  (def brenda-coverage1-good-real (ffirst
                                   (datomic/q '[:find (pull ?e [*])
                                                :in $  ?cov-id
                                                :where [?e :instrument.coverage/coverage-id ?cov-id]]
                                              db  brenda-coverage-1-good-id)))
  (tap> brenda-coverage1-good-real)
  (def ipad-wrong-id #uuid "0196cfb6-3e5e-8a47-96ba-86af55c4c738")
  (def ipad-coverage-wrong (ffirst
                            (datomic/q '[:find (pull ?e [*])
                                         :in $  ?cov-id
                                         :where [?e :instrument.coverage/coverage-id ?cov-id]]
                                       db  ipad-wrong-id)))
  (def ipad-coverage-good-id (sq/generate-squuid))
  (def ipad-coverage-good
    (-> ipad-coverage-wrong
        (assoc :db/id "covered_instrument")
        (assoc :instrument.coverage/coverage-id ipad-coverage-good-id)
        (assoc :instrument.coverage/types [])))
  (tap> ipad-coverage-good)

  (datomic/transact conn {:tx-data
                          [ipad-coverage-good
                           [:db/add [:insurance.policy/policy-id correct-policy-id] :insurance.policy/covered-instruments "covered_instrument"]]})

  (def doro-coverage-wrong-id #uuid "0196ba51-3f40-8865-98db-ed89b5e57de3")
  (def doro-coverage-wrong (ffirst
                            (datomic/q '[:find (pull ?e [*])
                                         :in $  ?cov-id
                                         :where [?e :instrument.coverage/coverage-id ?cov-id]]
                                       db  doro-coverage-wrong-id)))
  (def doro-coverage-good-id (sq/generate-squuid))
  (def doro-coverage-good
    (-> doro-coverage-wrong
        (assoc :db/id "covered_instrument")
        (assoc :instrument.coverage/coverage-id doro-coverage-good-id)
        (assoc :instrument.coverage/types [])))
  (tap> doro-coverage-good)

  (datomic/transact conn {:tx-data
                          [doro-coverage-good
                           [:db/add [:insurance.policy/policy-id correct-policy-id] :insurance.policy/covered-instruments "covered_instrument"]]})

  (def felix-coverage-wrong-id #uuid "0196c849-d9ba-868f-a832-8fe7aac36dc5")
  (def felix-coverage-wrong (ffirst
                             (datomic/q '[:find (pull ?e [*])
                                          :in $  ?cov-id
                                          :where [?e :instrument.coverage/coverage-id ?cov-id]]
                                        db  felix-coverage-wrong-id)))
  (def felix-coverage-good-id (sq/generate-squuid))
  (def felix-coverage-good
    (-> felix-coverage-wrong
        (assoc :db/id "covered_instrument")
        (assoc :instrument.coverage/coverage-id felix-coverage-good-id)
        (assoc :instrument.coverage/types [])))
  (tap> felix-coverage-good)

  (datomic/transact conn {:tx-data
                          [felix-coverage-good
                           [:db/add [:insurance.policy/policy-id correct-policy-id] :insurance.policy/covered-instruments "covered_instrument"]]})
  ;;
  )
