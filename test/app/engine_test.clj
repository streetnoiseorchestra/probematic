(ns app.engine-test
  (:require
   [app.ig]
   [app.system]
   [clojure.test :refer [deftest is]]
   [integrant.core :as ig]))

(deftest engine-integrant-component-builds-an-engine-env
  (let [services {:env        {:app/name "probematic-test"}
                  :datomic    {:conn ::conn}
                  :i18n-langs {:en {:hello "Hello"}}
                  :redis      {:spec ::redis-spec}}
        engine   (ig/init-key :app.ig/engine services)]
    (is (= services
           (select-keys engine (keys services))))
    (is (map? (:engine/registry engine)))))

(deftest system-config-wires-engine-into-the-handler-system
  (let [cfg (app.system/system-config {:profile :test})]
    (is (= (ig/ref :app.ig/engine)
           (get-in cfg [:app.ig/handler :engine])))))
