
(ns app.ledger.controller
  (:require
   [app.datomic :as d]
   [app.errors :as errors]
   [app.ledger.domain :as domain]
   [app.queries :as q]
   [app.schemas :as s]
   [app.util :as util]
   [app.util.http :as util.http]
   [com.yetanalytics.squuid :as sq]
   [app.datomic.shim :as datomic]
   [tick.core :as t]))

(defn new-member-ledger! [conn {:member/keys [member-id] :as member}]
  (if-let [ledger (q/retrieve-ledger (datomic/db conn) member-id)]
    ledger
    (let [result (datomic/transact conn {:tx-data (domain/txs-new-member-ledger (str member-id) (sq/generate-squuid) (d/ref member))})]
      (q/retrieve-ledger (:db-after result) member-id))))

(defn post-transaction! [conn member entry metadata]
  (if (s/valid? domain/LedgerEntryEntity entry)
    (let [ledger (new-member-ledger! conn member)
          db (datomic/db conn)
          tx-data (domain/prepare-transaction-data db ledger entry metadata)]
      (tap> [:tx-data tx-data])
      (datomic/transact conn {:tx-data tx-data}))
    {:error (s/explain-human domain/LedgerEntryEntity entry) :ledger-entry entry}))

(def AddTransaction
  "This schema describes the http post we receive when creating a transaction"
  [:map {:name ::AddTransaction}
   [:member-id :uuid]
   [:tx-date ::s/date]
   [:description :string]
   [:amount {:decode/string domain/coerce-amount} :int]
   [:tx-direction [:enum "debit" "credit"]]])

(defn adjust-amount-sign [direction amount]
  (assert (> amount 0) "Amount must be positive")
  (if (= direction "credit")
    (- amount)
    amount))

(defn add-transaction! [{:keys [db datomic-conn] :as req}]
  (let [decoded (util/remove-nils (s/decode AddTransaction (util.http/unwrap-params req)))]
    (tap> [:decoded decoded])
    (if (s/valid? AddTransaction decoded)
      (let [member-id (:member-id decoded)
            member (q/retrieve-member db member-id)
            entry {:ledger.entry/amount (adjust-amount-sign (:tx-direction decoded) (:amount decoded))
                   :ledger.entry/tx-date (:tx-date decoded)
                   :ledger.entry/posting-date (t/inst)
                   :ledger.entry/description (:description decoded)
                   :ledger.entry/entry-id (sq/generate-squuid)}
            _ (tap> [:post entry :member member])
            {:keys [db-after] :as result-or-error} (post-transaction! datomic-conn member entry nil)]
        (if db-after
          {:ledger (q/retrieve-ledger db-after member-id)
           :member (q/retrieve-member db-after member-id)}
          result-or-error))
      {:error (s/explain-human AddTransaction decoded)})))

(defn prepare-delete-transaction-txs [{:ledger.entry/keys [amount entry-id] :as entry}]
  (let [{:ledger/keys [balance] :as ledger} (first (:ledger/_entries entry))]
    (assert ledger "Ledger must be present")
    [[:db/retractEntity [:ledger.entry/entry-id entry-id]]
     [:db/add (d/ref ledger) :ledger/balance (- balance amount)]]))

(defn delete-ledger-entry! [{:keys [db member] :as req}]
  (let [params (util.http/unwrap-params req)
        entry-id (util/ensure-uuid! (:ledger-entry-id params))
        tx-data (prepare-delete-transaction-txs (q/retrieve-ledger-entry db entry-id))]
    (try
      (d/transact-wrapper! req {:tx-data tx-data})
      {:member member}
      (catch clojure.lang.ExceptionInfo e
        (errors/report-error! e)
        {:error (ex-data e)
         :exception e
         :msg (ex-message e)}))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))) ;; rcf

  (def albert (q/retrieve-member db #uuid "01860c2a-2929-8727-af1a-5545941b1115"))

  (def maggo-ledger (q/retrieve-ledger db #uuid "01860c2a-2929-8727-af1a-5545941b110f"))
  (datomic/transact conn {:tx-data [[:db/add (d/ref maggo-ledger) :ledger/balance 0]]})
  (:ledger/_entries
   (q/retrieve-ledger-entry db #uuid "018f4031-2d54-822a-a7a2-2fa0cd39847c"))

  (q/retrieve-ledger db (:member/member-id albert))
  (new-member-ledger! conn albert)

  (s/valid? domain/LedgerEntryEntity {:ledger.entry/amount       1000
                                      :ledger.entry/tx-date      (t/date)
                                      :ledger.entry/posting-date (t/inst)
                                      :ledger.entry/description  "prepared"
                                      :ledger.entry/entry-id     (sq/generate-squuid)})

  (post-transaction! conn albert
                     {:ledger.entry/amount       1000
                      :ledger.entry/tx-date      (t/date)
                      :ledger.entry/posting-date (t/inst)
                      :ledger.entry/description  "prepared"
                      :ledger.entry/entry-id     (sq/generate-squuid)} nil)
  (post-transaction! conn albert {:ledger.entry/amount 500
                                  :ledger.entry/tx-date (t/date)
                                  :ledger.entry/posting-date (t/inst)
                                  :ledger.entry/description "prepared"
                                  :ledger.entry/entry-id (sq/generate-squuid)}
                     {:ledger.entry.meta/meta-type :ledger.entry.meta.type/insurance
                      :ledger.entry.meta.insurance/policy [:insurance.policy/policy-id #uuid "018e15c2-ddbe-8b4f-b814-bddd26f8aec2"]})

  (q/find-ledger-entries-insurance db (:member/member-id albert))
  (q/ledger-entry-debit-for-policy db (:member/member-id albert) #uuid "018e15c2-ddbe-8b4f-b814-bddd26f8aec2")
  (q/ledger-entry-debit-for-policy db (:member/member-id albert) #uuid "018f1059-4acd-86c1-8f55-4b3e5eb856e0")

  ;;
  )
