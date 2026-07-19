(ns app.test-common
  (:require
   [lookup.core :as l]
   [app.datomic.system :as datomic.system]
   [app.nexus :as app-nexus]
   [datomic.api :as d]))

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
      {:conn conn
       :member-id member-id})))

(defn dispatch-with-nexus [handler nexus-config system req]
  (let [interceptor (app-nexus/nexus-interceptor nexus-config system)
        ctx         ((:enter interceptor) {:request req})
        response    (handler (:request ctx))]
    (:response ((:leave interceptor) (assoc ctx :response response)))))

(defn select-attribute
  [selector path data]
  (let [elements (l/select selector data)]
    (->> (keep (fn [element]
                 (when (map? (second element))
                   (get-in (second element) path)))
               elements))))
