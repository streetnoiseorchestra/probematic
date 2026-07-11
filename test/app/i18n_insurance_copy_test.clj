(ns app.i18n-insurance-copy-test
  (:require
   [app.i18n :as i18n]
   [clojure.test :refer [deftest is testing]]))

(deftest english-insurance-copy
  (testing "English insurance pages use clear English guidance and labels."
    (let [tr (i18n/tr-with (i18n/read-langs) [:en])]
      (is (= {:item-count-hint
              "How many identical items are being insured (for example, one trumpet or four drumsticks)?"
              :value "Insured value"
              :value-abbrev "Insured value"
              :value-hint "For multiple identical insured items, enter the value of one item."
              :description-hint "e.g., color or material"
              :name-hint "e.g., trumpet, Yamaha, or tenor sax mouthpiece"
              :item-left "1 item left."
              :insurer-id-hint "Enter the identifier assigned by Harmonia."
              :payments-empty "No private instrument payments need to be requested for this policy."}
             {:item-count-hint (tr [:insurance/item-count-hint])
              :value (tr [:insurance/value])
              :value-abbrev (tr [:insurance/value-abbrev])
              :value-hint (tr [:insurance/value-hint])
              :description-hint (tr [:instrument/description-hint])
              :name-hint (tr [:instrument/name-hint])
              :item-left (tr [:insurance.review/item-left] [1])
              :insurer-id-hint (tr [:instrument.coverage/insurer-id-hint])
              :payments-empty (tr [:insurance/no-private-payments])})))))
