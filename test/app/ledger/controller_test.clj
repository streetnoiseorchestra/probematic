(ns app.ledger.controller-test
  (:require
   [clojure.test :refer [deftest is]]))

(deftest ledger-controller-namespace-reloads-after-app-schemas-reloads
  (remove-ns 'app.ledger.controller)
  (remove-ns 'app.schemas)
  (let [result (try
                 (require 'app.schemas :reload)
                 (require 'app.ledger.controller :reload)
                 {:ok true}
                 (catch Throwable t
                   {:ok      false
                    :class   (str (class t))
                    :message (.getMessage t)}))]
    (is (:ok result) (pr-str result))))
