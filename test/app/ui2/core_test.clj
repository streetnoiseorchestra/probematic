(ns app.ui2.core-test
  (:require
   [app.ui2.core :as uic]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest bad-opt-value-callout-includes-header-and-body-copy
  (let [rendered (uic/bad-opt-value-callout
                  {:point-of-interest-opts {:header "component: Widget"
                                            :body   "Value for the attribute ::widget/name should be a string"}
                   :callout-opts           {:type          :warning
                                            :theme         :gutter
                                            :label         "WARNING Invalid ui attribute"
                                            :data?         true
                                            :margin-top    0
                                            :margin-bottom 0}})]
    (is (= {:label?  true
            :header? true
            :body?   true}
           {:label?  (str/includes? rendered "WARNING Invalid ui attribute")
            :header? (str/includes? rendered "component: Widget")
            :body?   (str/includes? rendered "Value for the attribute ::widget/name should be a string")}))))

(deftest warning-body-prints-readable-stack-frames
  (let [body (uic/warning-body {:opt   :app.ui2.avatar/avatar-template
                                :msg   "should be a string"
                                :trace (uic/stack-trace)})]
    (is (= {:has-readable-frame? true
            :has-empty-frame?    false}
           {:has-readable-frame? (str/includes? body "app.ui2.core/stack-trace")
            :has-empty-frame?    (str/includes? body "[]")}))))
