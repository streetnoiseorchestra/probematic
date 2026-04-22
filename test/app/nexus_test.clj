(ns app.nexus-test
  (:require
   [app.ig]
   [app.nexus :as app-nexus]
   [app.system]
   [clojure.test :refer [deftest is]]
   [integrant.core :as ig]))

(deftest nexus-integrant-component-builds-a-nexus-config
  (let [nexus-config (ig/init-key :app.ig/nexus {})]
    (is (= app-nexus/system->state
           (:nexus/system->state nexus-config)))
    (is (contains? (:nexus/actions nexus-config)
                   :app.settings.routes/create-discount-type))
    (is (contains? (:nexus/effects nexus-config) :db/transact))))

(deftest system-config-wires-nexus-into-the-handler-system
  (let [cfg (app.system/system-config {:profile :test})]
    (is (= (ig/ref :app.ig/nexus)
           (get-in cfg [:app.ig/handler :nexus])))))
