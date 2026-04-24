(ns app.settings.action-support
  (:require
   [datomic.api :as d]))

(def clear-loading
  [:app.datastar/merge-signals {:loading false :targetid false}])

(defn lookup-eid [db lookup-ref]
  (:db/id (d/entity db lookup-ref)))

(defn lookup-taken-by-other? [db lookup-ref current-ref]
  (let [found-eid   (lookup-eid db lookup-ref)
        current-eid (lookup-eid db current-ref)]
    (boolean (and found-eid (not= found-eid current-eid)))))

(defn with-audit [tx-data member-id]
  (cond-> (vec tx-data)
    member-id (conj [:db/add "datomic.tx" :audit/user [:member/member-id member-id]])))
