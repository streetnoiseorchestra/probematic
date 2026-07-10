(ns app.settings.index.views-test
  (:require
   [app.settings.index.views :as views]
   [app.settings.views-test-support :as support]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def expected-fragments
  {:en ["Band Settings"
        "Teams"
        "Create teams and manage their members."
        "Travel Discounts"
        "Manage the reusable travel discount types members can choose."
        "Sections"
        "Choose which sections are available and how they are ordered."]
   :de ["Bandeinstellungen"
        "Teams"
        "Erstelle Teams und verwalte ihre Mitglieder."
        "Reiserabatte"
        "Verwalte die wiederverwendbaren Reiserabatttypen, die Mitglieder auswählen können."
        "Register"
        "Wähle aus, welche Register verfügbar sind und wie sie angeordnet sind."]})

(deftest settings-index-uses-fluent-translations-test
  (doseq [[locale expected] expected-fragments]
    (testing (name locale)
      (let [html    (views/page {:tr (support/fluent-tr locale)})
            missing (remove #(str/includes? html %) expected)]
        (is (= [] missing))))))
