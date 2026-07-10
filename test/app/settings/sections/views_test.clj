(ns app.settings.sections.views-test
  (:require
   [app.settings.sections.views :as views]
   [app.settings.views-test-support :as support]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def populated-expected-fragments
  {:en ["Sections"
        "Choose which sections are available and how they are ordered."
        "Manage sections"
        "Choose which sections are visible and how they are ordered."
        "Add Section"
        "Section"
        "Reorder"
        "Drag sections to control the order in which they appear on gig pages."
        "Done"
        "Active"
        "Status"
        "Are you sure you want to delete the section “Trumpets”?"
        "Yes, delete it"]
   :de ["Register"
        "Wähle aus, welche Register verfügbar sind und wie sie angeordnet sind."
        "Register verwalten"
        "Wähle aus, welche Register sichtbar sind und wie sie angeordnet sind."
        "Register hinzufügen"
        "Register"
        "Neu anordnen"
        "Ziehe die Register, um die Reihenfolge festzulegen, in der sie auf Gig-Seiten erscheinen."
        "Fertig"
        "Aktiv"
        "Status"
        "Bist du sicher, dass du das Register “Trumpets” löschen möchtest?"
        "Ja, löschen"]})

(def empty-expected-fragments
  {:en ["No sections yet."
        "Add sections to group members and organize gig views."]
   :de ["Noch keine Register."
        "Füge Register hinzu, um Mitglieder zu gruppieren und Gig-Ansichten zu organisieren."]})

(defn page-html [locale populated?]
  (let [{:keys [conn]} (tc/new-system "settings-section-views")]
    (when populated?
      @(d/transact conn [{:section/name     "Trumpets"
                          :section/active?  true
                          :section/position 0}]))
    (views/page
     {:db         (d/db conn)
      :page-state (if populated?
                    {:section-create  {:open true}
                     :section         {:section-id "Trumpets"}
                     :section-reorder {:open true}}
                    {})
      :tr         (support/fluent-tr locale)
      ::r/router  support/router})))

(deftest sections-page-uses-fluent-translations-test
  (doseq [[locale expected] populated-expected-fragments]
    (testing (name locale)
      (let [html    (page-html locale true)
            missing (remove #(str/includes? html %) expected)]
        (is (= [] missing))))))

(deftest sections-empty-state-uses-fluent-translations-test
  (doseq [[locale expected] empty-expected-fragments]
    (testing (name locale)
      (let [html    (page-html locale false)
            missing (remove #(str/includes? html %) expected)]
        (is (= [] missing))))))
