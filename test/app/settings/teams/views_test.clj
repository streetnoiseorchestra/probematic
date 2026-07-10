(ns app.settings.teams.views-test
  (:require
   [app.settings.teams.views :as views]
   [app.settings.views-test-support :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def expected-translation-keys
  #{:action/add
    :action/cancel
    :action/confirm-delete
    :action/confirm-generic
    :action/create
    :action/remove
    :action/save
    :action/update
    :band-settings/team-column-name
    :band-settings/team-column-type
    :band-settings/team-create
    :band-settings/team-delete-confirm
    :band-settings/team-manage-subtitle
    :band-settings/team-manage-title
    :band-settings/team-member-add
    :band-settings/team-members
    :band-settings/team-name
    :band-settings/team-no-members
    :band-settings/team-page-subtitle
    :band-settings/team-title
    :band-settings/team-type
    :band-settings/team-type-insurance
    :band-settings/title})

(defn page-view []
  (let [{:keys [conn]} (tc/new-system "settings-team-views")
        team-id        (random-uuid)]
    @(d/transact conn [{:team/team-id   team-id
                        :team/name      "Brass"
                        :team/team-type :team.type/insurance}])
    (views/page
     {:db         (d/db conn)
      :page-state {:team-create {:open true}
                   :team        {:team-id team-id}}
      :tr         support/legacy-tr
      ::r/router  support/router})))

(deftest teams-page-returns-translation-data-test
  (let [view (page-view)]
    (is (= {:root             :main
            :translation-keys expected-translation-keys}
           {:root             (first view)
            :translation-keys (support/translation-keys view)}))))
