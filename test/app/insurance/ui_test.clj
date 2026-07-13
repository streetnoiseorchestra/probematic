(ns app.insurance.ui-test
  (:require
   [app.insurance.ui :as ui]
   [app.ui2.card :as card]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(def coverage
  {:instrument.coverage/status     :instrument.coverage.status/reviewed
   :instrument.coverage/change     :instrument.coverage.change/none
   :instrument.coverage/private?   false
   :instrument.coverage/item-count 1
   :instrument.coverage/value      1000M
   :instrument.coverage/cost       10M
   :instrument.coverage/types      []
   :instrument.coverage/instrument {:instrument/name   "Trumpet"
                                    :instrument/images []
                                    :instrument/owner  {:member/name "Player"}}})

(deftest insurance-cards-render-native-card-bodies
  (let [detail-view   (ui/coverage-detail-card
                       {:tr tr :system {:env {}}}
                       {:coverage coverage
                        :policy   {:insurance.policy/currency :EUR}})
        comments-view (ui/comments-card {:tr tr} [])]
    (testing "Insurance cards retain their Card chassis appearance and body layout."
      (is (= [{:component  card/Card
               :appearance "plain"
               :body-class #{"wa-gap-l" "wa-stack"}}
              {:component  card/Card
               :appearance "plain"
               :body-class #{"wa-stack"}}]
             (mapv (fn [view]
                     (let [card-node (l/select-one card/Card view)]
                       {:component  (first card-node)
                        :appearance (:appearance (l/attrs card-node))
                        :body-class (:class (l/attrs (l/first-child card-node)))}))
                   [detail-view comments-view]))))))
