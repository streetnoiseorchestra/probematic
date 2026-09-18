(ns app.test-common
  (:require
   [lookup.core :as l]
   [app.datomic.system :as datomic.system]
   [clojure.edn :as edn]
   [app.sqlite :as sqlite]
   [datomic.api :as d]))

(def ^:dynamic *sqlite-db* nil)

(defn with-sqlite-db [f]
  (let [db (sqlite/start {:filename ":memory:"})]
    (try
      (binding [*sqlite-db* db]
        (f))
      (finally (sqlite/stop db)))))

(def ^:dynamic *test-connections* nil)

(defn register-test-connection! [conn]
  (when *test-connections*
    (swap! *test-connections* conj conn))
  conn)

(defn with-released-test-connections [f]
  (binding [*test-connections* (atom [])]
    (try
      (f)
      (finally
        (run! d/release @*test-connections*)))))

(defn new-system [name-prefix]
  (let [uri (str "datomic:mem://" name-prefix "-" (random-uuid))]
    (d/create-database uri)
    (let [conn      (register-test-connection! (d/connect uri))
          member-id (random-uuid)]
      (datomic.system/prepare-database! conn)
      @(d/transact conn [{:member/member-id member-id}])
      {:conn      conn
       :member-id member-id})))

(defn committed-jobs [conn]
  (into []
        (mapcat (comp :jobs edn/read-string second))
        (sort-by first (d/q '[:find ?tx ?jobs :where [?tx :audit/jobs ?jobs]] (d/db conn)))))

(defn select-attribute
  [selector path data]
  (let [elements (l/select selector data)]
    (->> (keep (fn [element]
                 (when (map? (second element))
                   (get-in (second element) path)))
               elements))))
