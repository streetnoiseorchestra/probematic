(ns app.insurance.ui-test
  (:require
   [app.insurance.ui :as ui]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
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
        comments-view (ui/comments-card [])]
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

(defn coverage-token-summary
  [tokens]
  (let [icon-wrapper (l/select-one
                      "[data-insurance-coverage-type-icon]"
                      tokens)
        icon         (l/select-one ico/Icon tokens)
        tooltip      (l/select-one 'wa-tooltip tokens)
        label        (l/select-one
                      "[data-insurance-coverage-type-label]"
                      tokens)]
    {:icon    (when icon-wrapper
                {:key      (:data-insurance-coverage-type-icon
                            (l/attrs icon-wrapper))
                 :label    (:aria-label (l/attrs icon-wrapper))
                 :tabindex (:tabindex (l/attrs icon-wrapper))
                 :library  (::ico/library (l/attrs icon))
                 :name     (::ico/name (l/attrs icon))})
     :tooltip (when tooltip
                {:label   (l/text tooltip)
                 :trigger (:trigger (l/attrs tooltip))})
     :label   (some-> label l/text)}))

(deftest coverage-type-token-uses-stored-icon-metadata-test
  (testing "an arbitrary user-defined name renders its registered stored icon"
    (is (= {:icon    {:key      "phosphor/car-profile"
                      :label    "Worldwide touring"
                      :tabindex 0
                      :library  :phosphor
                      :name     :car-profile}
            :tooltip {:label   "Worldwide touring"
                      :trigger "click hover focus"}
            :label   nil}
           (coverage-token-summary
            (ui/coverage-type-token
             "coverage-type"
             (random-uuid)
             0
             {:insurance.coverage.type/name "Worldwide touring"
              :insurance.coverage.type/icon :phosphor/car-profile}))))))

(deftest coverage-type-token-falls-back-for-unusable-icon-metadata-test
  (testing "missing and invalid legacy icon keys preserve readable labels"
    (let [coverage-id (random-uuid)
          cases       [{:name "No icon metadata"}
                       {:name "Unknown library"
                        :icon :unknown/shield}
                       {:name "Unknown icon"
                        :icon :phosphor/not-registered}]]
      (is (= [{:icon nil :tooltip nil :label "No icon metadata"}
              {:icon nil :tooltip nil :label "Unknown library"}
              {:icon nil :tooltip nil :label "Unknown icon"}]
             (mapv (fn [{:keys [name icon]}]
                     (coverage-token-summary
                      (ui/coverage-type-token
                       "coverage-type"
                       coverage-id
                       0
                       {:insurance.coverage.type/name name
                        :insurance.coverage.type/icon icon})))
                   cases))))))
