(ns app.insurance.policy.settings.views-test
  (:require
   [app.insurance.policy.settings.views :as sut]
   [app.ui2.card :as card]
   [app.test-common :as tu]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def translations
  {[:insurance/category-factors] "Category factors"
   [:insurance/coverage-types] "Coverage types"
   [:insurance.dashboard/policy-details] "Policy details"
   [:insurance.policy-settings/add-category-factor] "Add category factor"
   [:insurance.policy-settings/category-factor-create-disabled-tooltip] "Every instrument category already has a category factor."
   [:insurance.policy-settings/current-totals] "Current totals"
   [:insurance.policy-settings/error-not-allowed] "You are not allowed to change policy settings."
   [:insurance.policy-settings/read-only-title] "Settings are read-only"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(def policy-id
  #uuid "00000000-0000-0000-0000-000000000123")

(def coverage-type-id
  #uuid "00000000-0000-0000-0000-000000000201")

(def category-factor-id
  #uuid "00000000-0000-0000-0000-000000000301")

(def brass-category-id
  #uuid "00000000-0000-0000-0000-000000000401")

(def percussion-category-id
  #uuid "00000000-0000-0000-0000-000000000403")

(def editable-draft-settings
  {:policy                 {:insurance.policy/policy-id policy-id
                            :insurance.policy/name      "Insurance 2027"}
   :policy-details         {:policy-id       policy-id
                            :name            "Insurance 2027"
                            :effective-at    #inst "2027-01-01T00:00:00.000-00:00"
                            :effective-until #inst "2027-12-31T00:00:00.000-00:00"
                            :premium-factor  0.025M
                            :currency        :EUR
                            :status          :insurance.policy.status/draft}
   :editable?              true
   :policy-editable?       true
   :insurance-team-member? true
   :supported-currencies   [:EUR :USD]
   :coverage-type-rows     [{:type-id        coverage-type-id
                             :name           "Basic"
                             :description    "Base coverage"
                             :premium-factor 1.0M
                             :usage-count    0
                             :current-cost   0M
                             :used?          false}]
   :category-factor-rows   [{:category-factor-id category-factor-id
                             :category-id        brass-category-id
                             :category-name      "Brass"
                             :factor             0.10M
                             :usage-count        0
                             :current-cost       0M
                             :used?              false}]
   :unused-categories      [{:category-id   percussion-category-id
                             :category-name "Percussion"}]
   :current-totals         {}
   :warnings               []})

(def req
  {:tr        tr
   ::r/router router})

(defn settings-view
  ([settings]
   (settings-view req settings))
  ([req settings]
   (sut/settings-page-content req settings)))

(defn select-attrs
  [selector hiccup]
  (some-> (l/select-one selector hiccup)
          l/attrs))

(defn action-keyword
  [url]
  (when-let [[_ value] (re-find #"[?&]kw=([^&]+)" url)]
    (keyword value)))

(defn action-keywords
  [hiccup]
  (into #{}
        (keep action-keyword)
        (tu/select-attribute '* [:data-action] hiccup)))

(deftest editable-policy
  (testing "An insurance-team member is viewing an editable draft policy."
    (let [view (settings-view editable-draft-settings)]
      (testing "The page shows each policy settings section."
        (let [cards (l/select card/Card view)]
          (is (= {:count    4
                  :headings ["Policy details"
                             "Coverage types"
                             "Category factors"
                             "Current totals"]}
                 {:count    (count cards)
                  :headings (mapv #(-> (l/select-one 'h2 %) l/text) cards)}))))
      (testing "Existing coverage types and category factors are listed."
        (is (= ["Basic" "Brass"]
               (mapv #(-> (l/select-one 'td %) l/text)
                     (l/select '[table tbody tr] view)))))
      (testing "The policy details form contains the current values."
        (is (= {:name-input    {:value     "Insurance 2027"
                                :data-bind "insurancePolicySettings.policy.name"}
                :premium-input {:type "number" :value "0.025" :min "0" :step "any"}
                :currency      ["EUR"]}
               {:name-input    (select-keys
                                (select-attrs "#insurance-policy-settings-name" view)
                                [:value :data-bind])
                :premium-input (select-keys
                                (select-attrs "#insurance-policy-settings-premium-factor" view)
                                [:type :value :min :step])
                :currency      (mapv l/text (l/select "option[selected]" view))})))
      (testing "Coverage types and category factors can be created, edited, and removed."
        (is (= #{:save-policy-details
                 :open-coverage-type-create
                 :open-coverage-type-edit
                 :delete-coverage-type
                 :open-category-factor-create
                 :open-category-factor-edit
                 :delete-category-factor}
               (action-keywords view)))))))

(deftest validation-errors
  (testing "Policy detail validation failed after the member submitted edited values."
    (let [submitted-policy   {:name            ""
                              :effective-at    "2027-02-01"
                              :effective-until "2027-01-01"
                              :premium-factor  "bad"
                              :currency        "USD"
                              :_error          {:_top           {:error "Fix the form."}
                                                :name           {:error "Name is required."}
                                                :effective-at   {:error "Invalid date."}
                                                :premium-factor {:error "Invalid factor."}}}
          request-with-errors (assoc-in req
                                        [:page-state :insurance-policy-settings :policy]
                                        submitted-policy)
          view                (settings-view request-with-errors
                                             editable-draft-settings)]
      (testing "The page shows the summary and field-specific errors."
        (let [cards (l/select card/Card view)]
          (is (= {:top-errors   ["Fix the form."]
                  :field-errors ["Name is required." "Invalid date." "Invalid factor."]}
                 {:top-errors   (mapv l/text
                                      (mapcat #(l/select '[wa-callout strong] %) cards))
                  :field-errors (mapv l/text (l/select '[form small] view))}))))
      (testing "The submitted values remain in the form for correction."
        (is (= {:effective-at   ["2027-02-01"]
                :premium-factor ["bad"]}
               {:effective-at   (vec (tu/select-attribute
                                      "#insurance-policy-settings-effective-at"
                                      [:value]
                                      view))
                :premium-factor (vec (tu/select-attribute
                                      "#insurance-policy-settings-premium-factor"
                                      [:value]
                                      view))}))))))

(deftest management-dialogs
  (testing "Create and edit dialog state is present for coverage types and category factors."
    (let [request-with-dialogs
          (assoc req :page-state
                 {:insurance-policy-settings
                  {:coverage-type-create {:open      true
                                          :policy-id policy-id
                                          :name      "New type"}
                   :coverage-type        {:policy-id policy-id
                                          :type-id   coverage-type-id
                                          :name      "Basic"}
                   :category-factor-create {:open        true
                                            :policy-id   policy-id
                                            :category-id percussion-category-id}
                   :category-factor        {:policy-id          policy-id
                                            :category-factor-id category-factor-id
                                            :category-id        brass-category-id
                                            :category-name      "Brass"}}})
          view            (settings-view request-with-dialogs
                                         editable-draft-settings)
          coverage-create (l/select-one "#coverage-type-create-dialog" view)
          coverage-edit   (l/select-one "#coverage-type-edit-dialog" view)
          category-create (l/select-one "#category-factor-create-dialog" view)
          category-edit   (l/select-one "#category-factor-edit-dialog" view)]
      (testing "Each dialog submits to its matching action."
        (is (= [#{:create-coverage-type}
                #{:update-coverage-type}
                #{:create-category-factor}
                #{:update-category-factor}]
               (mapv action-keywords
                     [coverage-create coverage-edit category-create category-edit]))))
      (testing "Coverage dialogs contain the new and existing coverage names."
        (is (= ["New type" "Basic"]
               (mapv #(first (tu/select-attribute % [:value] view))
                     ["#coverage-type-create-name" "#coverage-type-edit-name"]))))
      (testing "The category create dialog selects the requested unused category."
        (is (= {:options  ["" "Percussion"]
                :selected ["Percussion"]}
               {:options  (mapv l/text (l/select 'option category-create))
                :selected (mapv l/text
                                (l/select "option[selected]" category-create))})))
      (testing "The category edit dialog shows its category as read-only."
        (is (= "Brass"
               (some #{"Brass"}
                     (map l/text (l/select 'span category-edit)))))))))

(deftest complete-category-factors
  (testing "Every available instrument category already has a category factor."
    (let [view     (settings-view
                    (assoc editable-draft-settings :unused-categories []))
          add-area (l/select-one "#category-factor-create-disabled" view)
          tooltip  (l/select-one
                    "wa-tooltip[for=category-factor-create-disabled]"
                    view)]
      (testing "The Add category factor control remains visible but disabled."
        (is (= {:text     ["Add category factor"]
                :disabled [true]}
               {:text     (mapv l/text
                                (l/select :app.ui2.button/button add-area))
                :disabled (vec (tu/select-attribute
                                :app.ui2.button/button
                                [:disabled]
                                add-area))})))
      (testing "The tooltip explains why another factor cannot be added."
        (is (= "Every instrument category already has a category factor."
               (l/text tooltip))))
      (testing "No action can open the category factor create dialog."
        (is (not (contains? (action-keywords view)
                            :open-category-factor-create)))))))

(deftest read-only-policy
  (testing "The current member does not belong to the insurance team."
    (let [read-only-settings (assoc editable-draft-settings
                                    :editable? false
                                    :insurance-team-member? false)
          view               (settings-view read-only-settings)]
      (testing "The page explains why the policy settings are read-only."
        (is (= {:title   "Settings are read-only"
                :message "You are not allowed to change policy settings."}
               {:title   (some #{"Settings are read-only"}
                               (map l/text (l/select 'strong view)))
                :message (some #{"You are not allowed to change policy settings."}
                               (map l/text (l/select 'span view)))})))
      (testing "The policy detail controls and Save button are disabled."
        (is (= {:inputs  [true true true true]
                :selects [true]
                :buttons [true]}
               {:inputs  (vec (tu/select-attribute
                               ["#insurance-policy-settings-policy-form" 'input]
                               [:disabled]
                               view))
                :selects (vec (tu/select-attribute
                               ["#insurance-policy-settings-policy-form" 'select]
                               [:disabled]
                               view))
                :buttons (vec (tu/select-attribute
                               ["#insurance-policy-settings-policy-form"
                                :app.ui2.button/button]
                               [:disabled]
                               view))})))
      (testing "Coverage type and category factor management controls are not rendered."
        (is (= {:actions   #{:save-policy-details}
                :dialogs   0
                :row-menus 0}
               {:actions   (action-keywords view)
                :dialogs   (count (l/select 'wa-dialog view))
                :row-menus (count (l/select 'wa-dropdown view))}))))))
