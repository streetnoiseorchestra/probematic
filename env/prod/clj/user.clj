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
        {:env (:app.ig/env main/system)
         :gigo (:app.ig/gigo-client main/system)
         :datomic (:app.ig/datomic-db main/system)
         :i18n-langs (:app.ig/i18n-langs main/system)
         :redis (:app.ig/redis main/system)}]
    ;; (tap> (q/active-reminders-by-type db))
    (reminders/send-reminders! sys nil)
    ;;
    )

  (datomic/transact (-> main/system :app.ig/datomic-db :conn)
                    {:tx-data [[:db/add [:reminder/reminder-id #uuid "01881ecb-7613-873d-8b27-5ab7e9aed06b"]
                                :reminder/remind-at #inst "2023-05-13T09:43:50.547Z"]]})

  (require '[com.yetanalytics.squuid :as sq])

  (tap>
   (let [conn (-> main/system :app.ig/datomic-db :conn)
         cat-id (sq/generate-squuid)
         tx
         {:db/id "cello"
          :instrument.category/name "Celli und Gamben"
          :instrument.category/code "8"
          :instrument.category/category-id cat-id}]
     (datomic/transact conn {:tx-data [tx]})))

  (def db (datomic/db (-> main/system :app.ig/datomic-db :conn)))
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
   (let [conn (-> main/system :app.ig/datomic-db :conn)
         cat-id #uuid "018fa117-e2ed-829b-84c5-38f850d4153a"
         tx [{:insurance.category.factor/category-factor-id (sq/generate-squuid)
              :db/id "cat_fact"
              :insurance.category.factor/factor (bigdec 1.2)
              :insurance.category.factor/category [:instrument.category/category-id cat-id]}
             [:db/add [:insurance.policy/policy-id broken-p-id] :insurance.policy/category-factors "cat_fact"]]]
     (datomic/transact conn {:tx-data tx})))

  ;;
  )
