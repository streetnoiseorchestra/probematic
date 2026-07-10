(ns app.settings.teams.views-test
  (:require
   [app.i18n :as i18n]
   [app.settings.teams.views :as views]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn page-html [locale]
  (let [{:keys [conn]} (tc/new-system "settings-team-views")
        team-id        (random-uuid)]
    @(d/transact conn [{:team/team-id   team-id
                        :team/name      "Brass"
                        :team/team-type :team.type/insurance}])
    (views/page
     {:db         (d/db conn)
      :page-state {}
      :tr         (i18n/tr-with (i18n/read-langs) [locale])
      ::r/router  router})))

(deftest teams-page-uses-fluent-translations-test
  (let [html (page-html :de)]
    (is (every? #(str/includes? html %)
                ["Teams verwalten"
                 "Teams helfen dabei, Mitglieder nach Verantwortlichkeiten zu organisieren."
                 "Team erstellen"
                 "Mitglieder"
                 "Typ"
                 "Keine Mitglieder"
                 "Versicherungsteam"
                 "Bist du sicher, dass du das Team “Brass” löschen möchtest?"
                 "Erstelle Teams und verwalte ihre Mitglieder."]))))
