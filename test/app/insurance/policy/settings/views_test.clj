(ns app.insurance.policy.settings.views-test
  (:require
   [app.i18n.fluent :as fluent]
   [app.icons :as icons]
   [app.insurance.policy.settings.views :as sut]
   [app.test-common :as tu]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
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
   [:insurance/exporter] "Exporter"
   [:insurance/coverage-type-impact-confirmation] "This will add the coverage type to %count instruments."
   [:insurance/coverage-type-confirmation-count] "Type %count to confirm"
   [:insurance/coverage-type-confirmation-count-hint] "Enter the current number of affected instruments."
   [:insurance.policy-settings/read-only-title] "Settings are read-only"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (if (map? args)
     (reduce-kv (fn [s key arg]
                  (str/replace s (str "%" (name key)) (str arg)))
                (tr path)
                args)
     (reduce (fn [s [idx arg]]
               (str/replace s (str "%" (inc idx)) (str arg)))
             (tr path)
             (map-indexed vector args)))))

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
   :coverage-counts        {:total 0 :private 0 :band 0}
   :coverage-type-rows     [{:type-id        coverage-type-id
                             :name           "Basic"
                             :description    "Base coverage"
                             :premium-factor 1.0M
                             :icon           :phosphor/shield
                             :required?      true
                             :missing-coverage-counts
                             {:total 0 :private 0 :band 0}
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
   :exporter-options       [{:exporter-id
                             :insurance.exporter/inventory-xls-v1
                             :label-key
                             :insurance/exporter-inventory-xls-v1
                             :role-rows
                             [{:role      :overnight-vehicle
                               :label-key :insurance/exporter-role-overnight-vehicle
                               :required? true}
                              {:role      :unattended-building
                               :label-key :insurance/exporter-role-unattended-building
                               :required? true}]}]
   :exporter-configuration
   {:exporter-id :insurance.exporter/inventory-xls-v1
    :status      :complete
    :role-rows   [{:role             :overnight-vehicle
                   :label-key
                   :insurance/exporter-role-overnight-vehicle
                   :required?        true
                   :coverage-type-id coverage-type-id
                   :coverage-type-name "Basic"}
                  {:role             :unattended-building
                   :label-key
                   :insurance/exporter-role-unattended-building
                   :required?        true
                   :coverage-type-id coverage-type-id
                   :coverage-type-name "Basic"}]}
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

(defn option-by-value
  [value hiccup]
  (some #(when (= value (:value (l/attrs %))) %)
        (l/select 'wa-option hiccup)))

(defn registered-icon-values
  []
  (into #{}
        (mapcat (fn [{:keys [id icons]}]
                  (map #(str (name id) "/" (name %)) icons)))
        icons/icon-libraries))

(deftest coverage-type-metadata-translations
  (testing "Coverage metadata controls have complete English and German copy."
    (let [locale-states {:en (fluent/new-locale :en)
                         :de (fluent/new-locale :de)}
          messages
          [:insurance/coverage-type-icon
           :insurance/coverage-type-icon-hint
           :insurance/error-invalid-coverage-type-icon
           :insurance/coverage-type-required
           :insurance/coverage-type-required-hint
           :insurance/coverage-type-add-to-band-instruments
           :insurance/coverage-type-add-to-band-instruments-hint
           :insurance/coverage-type-impact-confirmation
           :insurance/coverage-type-confirmation-count
           :insurance/coverage-type-confirmation-count-hint
           :insurance/error-stale-impact-count]
          translated
          (fn [locale]
            (mapv #(fluent/translate (locale-states locale) % {:count 3})
                  messages))]
      (is (= {:en ["Icon"
                   "Choose the icon shown for this coverage type."
                   "Choose an icon from the list."
                   "Required"
                   "This coverage type will be added to every insured instrument."
                   "Add to existing band instruments"
                   "This optional coverage type will be added to every currently insured band instrument."
                   "This will add the coverage type to 3 instruments."
                   "Type 3 to confirm"
                   "Enter the current number of affected instruments."
                   "The number of affected instruments changed. Review the new count and enter it to confirm."]
              :de ["Symbol"
                   "Wähle das Symbol, das für diese Versicherungsart angezeigt wird."
                   "Wähle ein Symbol aus der Liste."
                   "Erforderlich"
                   "Diese Versicherungsart wird allen versicherten Instrumenten hinzugefügt."
                   "Zu bestehenden Bandinstrumenten hinzufügen"
                   "Diese optionale Versicherungsart wird allen aktuell versicherten Bandinstrumenten hinzugefügt."
                   "Diese Versicherungsart wird 3 Instrumenten hinzugefügt."
                   "Zur Bestätigung 3 eingeben"
                   "Gib die aktuelle Anzahl der betroffenen Instrumente ein."
                   "Die Anzahl der betroffenen Instrumente hat sich geändert. Prüfe die neue Anzahl und gib sie zur Bestätigung ein."]}
             {:en (translated :en)
              :de (translated :de)})))))

(deftest coverage-type-impact-confirmation-singular-translation
  (testing "The impact confirmation keeps the exact singular count visible."
    (is (= {:en "This will add the coverage type to 1 instrument."
            :de "Diese Versicherungsart wird 1 Instrument hinzugefügt."}
           {:en (fluent/translate
                 (fluent/new-locale :en)
                 :insurance/coverage-type-impact-confirmation
                 {:count 1})
            :de (fluent/translate
                 (fluent/new-locale :de)
                 :insurance/coverage-type-impact-confirmation
                 {:count 1})}))))

(deftest exporter-settings-translations
  (testing "Exporter settings have complete English and German copy."
    (let [messages
          [:insurance/exporter
           :insurance/exporter-subtitle
           :insurance/exporter-none
           :insurance/exporter-inventory-xls-v1
           :insurance/exporter-role-overnight-vehicle
           :insurance/exporter-role-unattended-building
           :insurance/exporter-role-unmapped
           :insurance/error-invalid-exporter
           :insurance/error-incomplete-exporter-mapping
           :insurance/error-invalid-exporter-mapping
           :insurance/error-duplicate-exporter-role
           :insurance/exporter-not-configured-guidance
           :insurance/exporter-unknown-guidance
           :insurance/exporter-incomplete-guidance]
          translated
          (fn [locale]
            (mapv #(fluent/translate (fluent/new-locale locale) % {})
                  messages))]
      (is (= {:en ["Exporter"
                   "Choose the spreadsheet format for this policy and map its required roles to coverage types."
                   "No exporter"
                   "Inventory spreadsheet (version 1)"
                   "Overnight in a vehicle"
                   "Unattended in a locked building"
                   "Choose a coverage type"
                   "Choose a registered exporter."
                   "Map every required exporter role."
                   "Choose coverage types from this policy for the exporter roles."
                   "Each exporter role may be mapped only once."
                   "Choose and configure an exporter in the policy settings before previewing or sending spreadsheets."
                   "The policy uses an exporter version this application does not recognize. Choose a supported version in the policy settings."
                   "Map every required exporter role in the policy settings before previewing or sending spreadsheets."]
              :de ["Exportformat"
                   "Wähle das Tabellenformat für diese Police und ordne seine erforderlichen Rollen den Versicherungsarten zu."
                   "Kein Exportformat"
                   "Inventartabelle (Version 1)"
                   "Über Nacht im Fahrzeug"
                   "Unbeaufsichtigt in einem verschlossenen Gebäude"
                   "Versicherungsart wählen"
                   "Wähle ein registriertes Exportformat."
                   "Ordne jede erforderliche Exportrolle zu."
                   "Wähle für die Exportrollen Versicherungsarten dieser Police."
                   "Jede Exportrolle darf nur einmal zugeordnet werden."
                   "Wähle und konfiguriere in den Policeneinstellungen ein Exportformat, bevor du Tabellen ansiehst oder sendest."
                   "Die Police verwendet eine unbekannte Exportversion. Wähle in den Policeneinstellungen eine unterstützte Version."
                   "Ordne in den Policeneinstellungen alle erforderlichen Exportrollen zu, bevor du Tabellen ansiehst oder sendest."]}
             {:en (translated :en)
              :de (translated :de)})))))

(deftest editable-policy
  (testing "An insurance-team member is viewing an editable draft policy."
    (let [view (settings-view editable-draft-settings)]
      (testing "The page shows each policy settings section."
        (let [cards (l/select card/Card view)]
          (is (= {:count    5
                  :headings ["Policy details"
                             "Coverage types"
                             "Category factors"
                             "Exporter"
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
                :currency      (mapv l/text
                                     (l/select
                                      "option[selected]"
                                      (l/select-one
                                       "#insurance-policy-settings-currency"
                                       view)))})))
      (testing "Coverage types and category factors can be created, edited, and removed."
        (is (= #{:save-policy-details
                 :open-coverage-type-create
                 :open-coverage-type-edit
                 :delete-coverage-type
                 :open-category-factor-create
                 :open-category-factor-edit
                 :delete-category-factor
                 :save-exporter}
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

(deftest coverage-type-icon-combobox
  (testing "The coverage type form offers every registered application icon."
    (let [request-with-dialog
          (assoc-in req
                    [:page-state
                     :insurance-policy-settings
                     :coverage-type-create]
                    {:open           true
                     :policy-id      policy-id
                     :name           "New type"
                     :description    "New description"
                     :premium-factor "0.25"
                     :icon           :phosphor/shield
                     :required?      false})
          view     (settings-view request-with-dialog editable-draft-settings)
          combobox (l/select-one "#coverage-type-create-icon" view)
          options  (l/select 'wa-option combobox)
          shield   (option-by-value "phosphor/shield" combobox)
          outlined (option-by-value "snoico/circle-check-outline" combobox)
          edit-request
          (assoc-in req
                    [:page-state
                     :insurance-policy-settings
                     :coverage-type]
                    {:policy-id      policy-id
                     :type-id        coverage-type-id
                     :name           "Basic"
                     :description    "Base coverage"
                     :premium-factor "1.0"
                     :icon           :snoico/home})
          edit-view     (settings-view edit-request editable-draft-settings)
          edit-combobox (l/select-one "#coverage-type-edit-icon" edit-view)]
      (is (= {:control
              {:required?          true
               :multiple?          false
               :allow-custom-value? false
               :allow-create?      false
               :data-bind
               "insurancePolicySettings.coverageType.icon"}
              :option-count (count (registered-icon-values))
              :option-values (registered-icon-values)
              :selected-values ["phosphor/shield"]
              :edit-selected-values ["snoico/home"]
              :labels {:shield "Shield"
                       :outlined "Circle Check Outline"}
              :previews
              {:shield {::ico/library :phosphor
                        ::ico/name    :shield
                        :slot         "start"}
               :outlined {::ico/library :snoico
                          ::ico/name    :circle-check-outline
                          :slot         "start"}}
              :wa-icons 0}
             {:control
              (let [attrs (l/attrs combobox)]
                {:required?          (= true (:required attrs))
                 :multiple?          (contains? attrs :multiple)
                 :allow-custom-value? (contains? attrs :allow-custom-value)
                 :allow-create?      (contains? attrs :allow-create)
                 :data-bind          (:data-bind attrs)})
              :option-count (count options)
              :option-values (into #{} (map (comp :value l/attrs)) options)
              :selected-values
              (mapv (comp :value l/attrs)
                    (filter #(= true (:selected (l/attrs %))) options))
              :edit-selected-values
              (mapv (comp :value l/attrs)
                    (filter #(= true (:selected (l/attrs %)))
                            (l/select 'wa-option edit-combobox)))
              :labels {:shield   (l/text shield)
                       :outlined (l/text outlined)}
              :previews
              {:shield (select-keys
                        (l/attrs (l/select-one ico/Icon shield))
                        [::ico/library ::ico/name :slot])
               :outlined (select-keys
                          (l/attrs (l/select-one ico/Icon outlined))
                          [::ico/library ::ico/name :slot])}
              :wa-icons (count (l/select 'wa-icon combobox))})))))

(deftest coverage-type-required-and-impact-controls
  (testing "Create and edit forms expose required state and exact-count confirmation."
    (let [create-request
          (assoc-in req
                    [:page-state
                     :insurance-policy-settings
                     :coverage-type-create]
                    {:open                    true
                     :policy-id               policy-id
                     :name                    "New type"
                     :description             "Description"
                     :premium-factor          "0.25"
                     :icon                    :phosphor/shield
                     :required?               true
                     :add-to-band-instruments? false
                     :impact-count            3
                     :confirmation-count      "2"
                     :_error
                     {:confirmation-count {:error "Type 3 to confirm."}}})
          edit-request
          (assoc-in req
                    [:page-state
                     :insurance-policy-settings
                     :coverage-type]
                    {:policy-id          policy-id
                     :type-id            coverage-type-id
                     :name               "Basic"
                     :description        "Base coverage"
                     :premium-factor     "1.0"
                     :icon               :phosphor/shield
                     :required?          true
                     :impact-count       2
                     :confirmation-count ""})
          create-view (settings-view create-request editable-draft-settings)
          edit-view   (settings-view edit-request editable-draft-settings)]
      (is (= {:create
              {:required
               {:checked true
                :data-attr:checked
                "$insurancePolicySettings.coverageType.required"}
               :add-to-band
               {:checked? false
                :data-attr:checked
                "$insurancePolicySettings.coverageType.addToBandInstruments"}
               :confirmation
               {:type      "number"
                :value     "2"
                :min       "0"
                :required  true
                :data-bind
                "insurancePolicySettings.coverageType.confirmationCount"}
               :impact-message
               "This will add the coverage type to 3 instruments."
               :field-error "Type 3 to confirm."}
              :edit
              {:required
               {:checked true
                :data-attr:checked
                "$insurancePolicySettings.coverageType.required"}
               :confirmation
               {:type      "number"
                :value     ""
                :min       "0"
                :required  true
                :data-bind
                "insurancePolicySettings.coverageType.confirmationCount"}
               :impact-message
               "This will add the coverage type to 2 instruments."}}
             {:create
              {:required
               (select-keys
                (select-attrs "#coverage-type-create-required" create-view)
                [:checked :data-attr:checked])
               :add-to-band
               (let [attrs
                     (select-attrs
                      "#coverage-type-create-add-to-band-instruments"
                      create-view)]
                 {:checked?          (= true (:checked attrs))
                  :data-attr:checked (:data-attr:checked attrs)})
               :confirmation
               (select-keys
                (select-attrs
                 "#coverage-type-create-confirmation-count"
                 create-view)
                [:type :value :min :required :data-bind])
               :impact-message
               (some #{"This will add the coverage type to 3 instruments."}
                     (map l/text (l/select 'span create-view)))
               :field-error
               (some #{"Type 3 to confirm."}
                     (map l/text (l/select 'small create-view)))}
              :edit
              {:required
               (select-keys
                (select-attrs "#coverage-type-edit-required" edit-view)
                [:checked :data-attr:checked])
               :confirmation
               (select-keys
                (select-attrs
                 "#coverage-type-edit-confirmation-count"
                 edit-view)
                [:type :value :min :required :data-bind])
               :impact-message
               (some #{"This will add the coverage type to 2 instruments."}
                     (map l/text (l/select 'span edit-view)))}})))))

(deftest exporter-settings-fields
  (testing "The exporter section renders version and role selectors from its read model."
    (let [view          (settings-view editable-draft-settings)
          exporter-form (l/select-one
                         "#insurance-policy-settings-exporter-form"
                         view)
          role-selects  (l/select
                         "select[data-exporter-role]"
                         exporter-form)]
      (is (= {:version
              {:data-bind
               "insurancePolicySettings.exporter.exporterId"
               :options
               [["" "exporter-none"]
                ["insurance.exporter/inventory-xls-v1"
                 "exporter-inventory-xls-v1"]]
               :selected ["insurance.exporter/inventory-xls-v1"]}
              :roles
              [{:role "overnight-vehicle"
                :data-bind
                "insurancePolicySettings.exporter.mappings.0.coverageTypeId"
                :selected [(str coverage-type-id)]}
               {:role "unattended-building"
                :data-bind
                "insurancePolicySettings.exporter.mappings.1.coverageTypeId"
                :selected [(str coverage-type-id)]}]
              :action #{:save-exporter}}
             {:version
              (let [select (l/select-one
                            "#insurance-policy-settings-exporter-id"
                            exporter-form)]
                {:data-bind (:data-bind (l/attrs select))
                 :options   (mapv (fn [option]
                                    [(:value (l/attrs option))
                                     (l/text option)])
                                  (l/select 'option select))
                 :selected  (mapv (comp :value l/attrs)
                                  (l/select "option[selected]" select))})
              :roles
              (mapv (fn [select]
                      {:role      (:data-exporter-role (l/attrs select))
                       :data-bind (:data-bind (l/attrs select))
                       :selected
                       (mapv (comp :value l/attrs)
                             (l/select "option[selected]" select))})
                    role-selects)
              :action (action-keywords exporter-form)})))))

(deftest unconfigured-exporter-settings-fields
  (testing "Choosing a version prepares that version's role mappings."
    (let [settings (assoc editable-draft-settings
                          :exporter-configuration
                          {:exporter-id nil
                           :status      :not-configured
                           :role-rows   []})
          view     (settings-view settings)
          version  (l/select-one "#insurance-policy-settings-exporter-id" view)
          role-set (some #(when (= "insurance.exporter/inventory-xls-v1"
                                   (:data-exporter-id (l/attrs %)))
                            %)
                         (l/select 'div view))
          on-change (:data-on:change (l/attrs version))]
      (is (= {:selected [""]
              :show
              "$insurancePolicySettings.exporter.exporterId === 'insurance.exporter/inventory-xls-v1'"
              :prepares-version? true
              :prepares-roles?   true}
             {:selected (mapv (comp :value l/attrs)
                              (l/select "option[selected]" version))
              :show (:data-show (l/attrs role-set))
              :prepares-version?
              (str/includes? on-change
                             "insurance.exporter/inventory-xls-v1")
              :prepares-roles?
              (and (str/includes? on-change "overnight-vehicle")
                   (str/includes? on-change "unattended-building"))})))))

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
