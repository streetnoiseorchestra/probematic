(ns app.routes-test
  (:require
   [app.routes]
   [clojure.test :refer [deftest is]]))

(deftest static-resource-symlink-policy
  (let [options-fn (ns-resolve 'app.routes 'resource-handler-options)]
    (is (some? options-fn) "Static resource handler options should be explicit")
    (when options-fn
      (is (= [{:path "/" :allow-symlinks? true}
              {:path "/" :allow-symlinks? false}]
             (mapv options-fn
                   [{:env {:ig/system {:app.ig/profile :dev}}}
                    {:env {:ig/system {:app.ig/profile :prod}}}]))))))
