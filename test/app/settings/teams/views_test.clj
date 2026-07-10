(ns app.settings.teams.views-test
  (:require
   [app.settings.teams.views :as views]
   [app.settings.views-test-support :as support]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def expected-fragments
  {:en ["Manage teams"
        "Teams help organize members around responsibilities."
        "Create Team"
        "Members"
        "Type"
        "No members"
        "Insurance Team"
        "Are you sure you want to delete the team “Brass”?"
        "Create teams and manage their members."]
   :de ["Teams verwalten"
        "Teams helfen dabei, Mitglieder nach Verantwortlichkeiten zu organisieren."
        "Team erstellen"
        "Mitglieder"
        "Typ"
        "Keine Mitglieder"
        "Versicherungsteam"
        "Bist du sicher, dass du das Team “Brass” löschen möchtest?"
        "Erstelle Teams und verwalte ihre Mitglieder."]})

(defn page-html [locale]
  (let [{:keys [conn]} (tc/new-system "settings-team-views")
        team-id        (random-uuid)]
    @(d/transact conn [{:team/team-id   team-id
                        :team/name      "Brass"
                        :team/team-type :team.type/insurance}])
    (views/page
     {:db         (d/db conn)
      :page-state {:team-create {:open true}
                   :team        {:team-id team-id}}
      :tr         (support/fluent-tr locale)
      ::r/router  support/router})))

(deftest teams-page-uses-fluent-translations-test
  (doseq [[locale expected] expected-fragments]
    (testing (name locale)
      (let [html    (page-html locale)
            missing (remove #(str/includes? html %) expected)]
        (is (= [] missing))))))
