(ns app.test-common
  (:require
   [app.datomic.system :as datomic.system]
   [app.nexus :as app-nexus]
   [datomic.api :as d]))

(defn new-system [name-prefix]
  (let [uri (str "datomic:mem://" name-prefix "-" (random-uuid))]
    (d/create-database uri)
    (let [conn      (d/connect uri)
          member-id (random-uuid)]
      @(datomic.system/transact-schema conn)
      @(d/transact conn [{:member/member-id member-id}])
      {:conn conn
       :member-id member-id})))

(defn dispatch-with-nexus [handler nexus-config system req]
  (let [interceptor (app-nexus/nexus-interceptor nexus-config system)
        ctx         ((:enter interceptor) {:request req})
        response    (handler (:request ctx))]
    (:response ((:leave interceptor) (assoc ctx :response response)))))
