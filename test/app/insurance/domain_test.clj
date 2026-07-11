(ns app.insurance.domain-test
  (:require
   [app.insurance.domain :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest update-total-coverage-price-clears-stale-costs
  (testing "The policy has no factor for the covered instrument category."
    (let [category-id (random-uuid)
          coverage    {:instrument.coverage/cost       99M
                       :instrument.coverage/instrument {:instrument/category
                                                        {:instrument.category/category-id category-id}}
                       :instrument.coverage/types      [{:insurance.coverage.type/type-id (random-uuid)
                                                         :insurance.coverage.type/cost    99M}]}
          result      (sut/update-total-coverage-price
                       {:insurance.policy/category-factors []}
                       coverage)]
      (testing "Coverage and type costs are unavailable even when the input was enriched before."
        (is (= {:coverage-cost nil
                :missing?      true
                :type-costs    [nil]}
               {:coverage-cost (:instrument.coverage/cost result)
                :missing?      (:instrument.coverage/missing-category-factor? result)
                :type-costs    (mapv :insurance.coverage.type/cost
                                     (:instrument.coverage/types result))}))))))
