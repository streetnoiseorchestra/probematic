(ns app.ledger.domain
  (:require
   [app.datomic :as d]
   [app.schemas :as s]
   [app.util :as util]
   [medley.core :as m]
   [taoensso.nippy :as nippy]
   [tick.core :as t]))

(def entry-meta-types #{:ledger.entry.meta.type/insurance})

(def entry-attribute-label-key
  {:ledger.entry/amount      :ledger/amount
   :ledger.entry/description :ledger/reference
   :ledger.entry/tx-date     :ledger/transaction-date})

(def entry-direction-label-key
  {["debt" "credit"]    :ledger/direction-credit
   ["debt" "debit"]     :ledger/direction-debit
   ["payment" "credit"] :ledger/payment-direction-credit
   ["payment" "debit"]  :ledger/payment-direction-debit})

(def LedgerEntryMetadataEntity
  [:map {:name :app.entity/ledger.entry.meta}
   [:ledger.entry.meta/meta-type (s/enum-from entry-meta-types)]])

(def LedgerEntryEntity
  [:map {:name :app.entity/ledger.entry}
   [:ledger.entry/entry-id :uuid]
   [:ledger.entry/amount :int]
   [:ledger.entry/description ::s/non-blank-string]
   [:ledger.entry/posting-date ::s/inst]
   [:ledger.entry/tx-date ::s/date]
   [:ledger.entry/metadata {:optional true} LedgerEntryMetadataEntity]])

(def LedgerEntity
  [:map {:name :app.entity/ledger}
   [:ledger/ledger-id :uuid]
   [:ledger/owner ::s/datomic-ref]
   [:ledger/entries {:optional true} [:sequential ::s/datomic-ref]]
   [:ledger/balance :int]])

(defn entry->db [entry]
  (-> entry
      (update :ledger.entry/posting-date #(t/inst (t/in % (t/zone "Europe/Vienna"))))
      (update :ledger.entry/tx-date str)
      (m/update-existing :ledger.entry/data nippy/freeze)))

(defn db->entry
  [entry]
  (when entry
    (-> (s/decode-datomic LedgerEntryEntity entry)
        (m/update-existing :ledger.entry/posting-date t/date-time)
        (m/update-existing :ledger.entry/tx-date t/date))))

(defn db->ledger [ent]
  (when ent
    (update ent :ledger/entries #(->> %
                                      (map db->entry)
                                      (sort-by (juxt :ledger.entry/tx-date :ledger.entry/posting-date))
                                      (reverse)))))

(defn txs-new-member-ledger [tmpid ledger-id owner]
  [{:db/id tmpid
    :ledger/ledger-id ledger-id
    :ledger/owner owner
    :ledger/balance 0}])

(defn txs-balance-adjustment
  "Calculates the adjustment datoms for the specified transaction"
  [ledger entry]
  (let [balance-before (:ledger/balance ledger)
        entry-amount (:ledger.entry/amount entry)
        balance-after (+ balance-before entry-amount)]
    [[:db/add (d/ref ledger) :ledger/entries (:db/id entry)]
     [:db/add (d/ref ledger) :ledger/balance balance-after]]))

(defn append-balance-adjustment-txs
  "Appends the datomic transaction commands necessary to adjust balances
  for the transaction"
  [ledger transaction]
  (concat [transaction] (txs-balance-adjustment ledger transaction)))

(defn append-entry-metadata-txs [metadata entry-tmpid datoms]
  (if metadata
    (concat datoms
            [(merge metadata {:db/id "metadata"})
             [:db/add entry-tmpid :ledger.entry/metadata "metadata"]])
    datoms))

(defn prepare-transaction-data
  "Takes the raw transaction data and makes it ready to use with d/transact"
  [_db ledger entry metadata]
  (assert (s/valid? LedgerEntryEntity entry))
  (assert ledger)
  (let [entry-tmpid (d/tempid)]
    (->> (entry->db entry)
         (merge {:db/id entry-tmpid})
         (append-balance-adjustment-txs ledger)
         (append-entry-metadata-txs metadata entry-tmpid))))

(defn coerce-amount [amount-str]
  (when-let [num (util/parse-number amount-str)]
    (-> num
        (* 100)
        (double)
        (Math/round)
        (int))))
