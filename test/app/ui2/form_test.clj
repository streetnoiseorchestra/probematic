(ns app.ui2.form-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def missing-component ::missing-component)

(def form-config
  {:ns      :team
   :command "saveTeam()"
   :fields  {:team-id      "t-1"
             :team-name    "Booking"
             :team-type    "finance"
             :team-enabled true}})

(defn resolve-var [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _
      nil)))

(defn alias-value [sym]
  (if-let [v (resolve-var sym)]
    @v
    missing-component))

(defn render [alias-sym attrs & children]
  (let [alias (alias-value alias-sym)]
    (when-not (= missing-component alias)
      (html/->str (into [alias attrs] children)))))

(deftest form-alias-initializes-signals-and-submit-wiring
  (let [Input (alias-value 'app.ui2.form/Input)
        html  (render 'app.ui2.form/Form
                      {:app.ui2.form/form form-config}
                      [Input {:app.ui2.form/form  form-config
                              :app.ui2.form/label "Team Name"
                              :name               :team-name}])]
    (is (some? html) "Form alias should exist")
    (when html
      (is (str/includes? html "<form"))
      (is (str/includes? html "data-signals__ifmissing"))
      (is (str/includes? html "team-name"))
      (is (str/includes? html "saveTeam()"))
      (is (str/includes? html "team.error")))))

(deftest root-errors-renders-top-level-error-signal
  (let [html (render 'app.ui2.form/RootErrors
                     {:app.ui2.form/form  form-config
                      :app.ui2.form/title "Errors"})]
    (is (some? html) "RootErrors alias should exist")
    (when html
      (is (str/includes? html "Errors"))
      (is (str/includes? html "$team.error._top"))
      (is (str/includes? html "hidden")))))

(deftest section-renders-title-and-subtitle
  (let [html (render 'app.ui2.form/Section
                     {:app.ui2.form/title    "Team"
                      :app.ui2.form/subtitle "Edit team details"}
                     [:div "child"])]
    (is (some? html) "Section alias should exist")
    (when html
      (is (str/includes? html "Team"))
      (is (str/includes? html "Edit team details"))
      (is (str/includes? html "grid grid-cols-1")))))

(deftest hidden-input-binds-form-signal-and-default-value
  (let [html (render 'app.ui2.form/HiddenInput
                     {:app.ui2.form/form form-config
                      :name              :team-id})]
    (is (some? html) "HiddenInput alias should exist")
    (when html
      (is (str/includes? html "type=\"hidden\""))
      (is (str/includes? html "data-bind=\"team.team-id\""))
      (is (str/includes? html "value=\"t-1\"")))))

(deftest input-renders-label-binding-and-default-value
  (let [html (render 'app.ui2.form/Input
                     {:app.ui2.form/form  form-config
                      :app.ui2.form/label "Team Name"
                      :name               :team-name})]
    (is (some? html) "Input alias should exist")
    (when html
      (is (str/includes? html "Team Name"))
      (is (str/includes? html "data-bind=\"team.team-name\""))
      (is (str/includes? html "value=\"Booking\"")))))

(deftest select-renders-options-and-current-value
  (let [html (render 'app.ui2.form/Select
                     {:app.ui2.form/form    form-config
                      :app.ui2.form/label   "Team Type"
                      :app.ui2.form/options [{:value "finance" :label "Finance"}
                                             {:value "booking" :label "Booking"}]
                      :name                 :team-type})]
    (is (some? html) "Select alias should exist")
    (when html
      (is (str/includes? html "<select"))
      (is (str/includes? html "Finance"))
      (is (str/includes? html "selected"))
      (is (str/includes? html "data-bind=\"team.team-type\"")))))

(deftest toggle-renders-checkbox-ref-and-change-handler
  (let [html (render 'app.ui2.form/Toggle
                     {:app.ui2.form/form  form-config
                      :app.ui2.form/label "Enabled"
                      :name               :team-enabled
                      :value              "enabled"})]
    (is (some? html) "Toggle alias should exist")
    (when html
      (is (str/includes? html "type=\"checkbox\""))
      (is (str/includes? html "data-ref=\"team.team-enabledref\""))
      (is (str/includes? html "$team.team-enabled = $team.team-enabledref.checked")))))

(deftest actions-renders-left-and-right-areas
  (let [html (render 'app.ui2.form/Actions
                     {:app.ui2.form/left  [:div "Cancel"]
                      :app.ui2.form/right [:div "Save"]})]
    (is (some? html) "Actions alias should exist")
    (when html
      (is (str/includes? html "Cancel"))
      (is (str/includes? html "Save"))
      (is (str/includes? html "justify-between")))))
