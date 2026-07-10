(ns app.settings.discounts.views-test
  (:require
   [app.settings.discounts.views :as views]
   [app.settings.views-test-support :as support]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def populated-expected-fragments
  {:en ["Travel Discounts"
        "Manage the reusable travel discount types members can choose."
        "Manage travel discounts"
        "Reusable labels for member travel discounts."
        "Add a ÖBB discount type"
        "Discount Type"
        "Active"
        "Status"
        "Are you sure you want to delete the discount type “Klimaticket”?"
        "Yes, delete it"]
   :de ["Reiserabatte"
        "Verwalte die wiederverwendbaren Reiserabatttypen, die Mitglieder auswählen können."
        "Reiserabatte verwalten"
        "Wiederverwendbare Bezeichnungen für Reiserabatte von Mitgliedern."
        "ÖBB-Rabatttyp hinzufügen"
        "Rabatttyp"
        "Aktiv"
        "Status"
        "Bist du sicher, dass du den Rabatttyp “Klimaticket” löschen möchtest?"
        "Ja, löschen"]})

(def empty-expected-fragments
  {:en ["No travel discount types yet."
        "Add discount types so members can select them consistently."]
   :de ["Noch keine Reiserabatttypen."
        "Füge Rabatttypen hinzu, damit Mitglieder sie einheitlich auswählen können."]})

(defn page-html [locale populated?]
  (let [{:keys [conn]} (tc/new-system "settings-discount-views")
        discount-type-id (random-uuid)]
    (when populated?
      @(d/transact conn [{:travel.discount.type/discount-type-id   discount-type-id
                          :travel.discount.type/discount-type-name "Klimaticket"
                          :travel.discount.type/enabled?           true}]))
    (views/page
     {:db         (d/db conn)
      :page-state (if populated?
                    {:discount-type-create {:open true}
                     :discount-type        {:discount-type-id discount-type-id}}
                    {})
      :tr         (support/fluent-tr locale)
      ::r/router  support/router})))

(deftest travel-discounts-page-uses-fluent-translations-test
  (doseq [[locale expected] populated-expected-fragments]
    (testing (name locale)
      (let [html    (page-html locale true)
            missing (remove #(str/includes? html %) expected)]
        (is (= [] missing))))))

(deftest travel-discounts-empty-state-uses-fluent-translations-test
  (doseq [[locale expected] empty-expected-fragments]
    (testing (name locale)
      (let [html    (page-html locale false)
            missing (remove #(str/includes? html %) expected)]
        (is (= [] missing))))))
