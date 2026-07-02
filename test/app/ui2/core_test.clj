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

(deftest validate-opts-warning-includes-component-and-context
  (let [doc      {:name   'Widget
                  :schema [:map [::widget-name :string]]}
        rendered (binding [uic/*validate-opts* true]
                   (with-out-str
                     (uic/validate-opts! doc {::widget-name nil})))]
    (is (= {:warning?     true
            :component?   true
            :option?      true
            :message?     true
            :stack-frame? true
            :empty-frame? false}
           {:warning?     (str/includes? rendered "Invalid ui attribute")
            :component?   (str/includes? rendered "Widget")
            :option?      (str/includes? rendered ":app.ui2.core-test/widget-name")
            :message?     (str/includes? rendered "Value for the attribute")
            :stack-frame? (str/includes? rendered "app.ui2.core/validate-opts")
            :empty-frame? (str/includes? rendered "[]")}))))

(deftest assoc-attr-preserves-children-when-attrs-are-absent
  (is (= [:span {:id "example"} "alpha" "beta"]
         (uic/assoc-attr [:span "alpha" "beta"] :id "example"))))

(deftest merge-attrs-prepends-classes-without-nil-padding
  (is (= [{:class "component user" :id "example"}
          {:class "component"}
          {:class "user"}]
         [(uic/merge-attrs {:class "user" :id "example"} :class "component")
          (uic/merge-attrs nil :class "component")
          (uic/merge-attrs {:class "user"} :class nil)])))

(defn- call-with-timeout
  [f timeout-ms]
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (deliver result [:returned (f)])
                            (catch Throwable t
                              (deliver result [:thrown t])))))]
    (.setDaemon thread true)
    (.setPriority thread Thread/MIN_PRIORITY)
    (.start thread)
    (deref result timeout-ms ::timeout)))

(deftest wrap-string-returns-overlong-words
  (is (= [:returned "supercalifragilistic"]
         (call-with-timeout #(uic/wrap-string "supercalifragilistic" 5 2) 250))))

(deftest generate-docstring-omits-null-for-required-options
  (let [docstring (uic/generate-docstring
                   {:name     'Widget
                    :desc     "Test widget."
                    :ns       'app.ui2.widget
                    :as       'widget
                    :examples ["[widget/Widget {::widget/required \"value\"}]"]
                    :schema   [:map
                               [::required {:doc "Required option."} :string]
                               [::optional {:optional true
                                            :doc      "Optional option."} :boolean]
                               [::defaulted {:optional true
                                             :default  "m"
                                             :doc      "Defaulted option."} :string]
                               [::maybe {:optional true
                                         :doc      "Maybe option."} [:maybe :string]]
                               [::choice {:doc "Choice option."} [:enum :one :two]]]})]
    (is (= {:required-null? false
            :required?      true
            :optional?      true
            :defaulted?     true
            :maybe?         true
            :enum?          true}
           {:required-null? (str/includes? docstring "::widget/required - Required option. null")
            :required?      (str/includes? docstring "::widget/required - Required option.")
            :optional?      (str/includes? docstring "::widget/optional - Optional option. (optional)")
            :defaulted?     (str/includes? docstring "::widget/defaulted - Defaulted option. (optional, default m)")
            :maybe?         (str/includes? docstring "::widget/maybe - Maybe option. (optional)")
            :enum?          (str/includes? docstring ":one, :two")}))))
