(ns app.settings.index.views-test
  (:require
   [app.settings.index.views :as views]
   [app.settings.views-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(deftest settings-index-returns-translation-data-test
  (let [view (views/page {:tr support/legacy-tr})]
    (testing "The settings directory uses the standard application page shell."
      (is (= {:width             :standard
              :toolbar-label     :band-settings/toolbar-label
              :breadcrumbs       [:home :band-settings/title]
              :mobile            {:href "/" :label :home}
              :actions           []
              :overflow          []
              :heading           :band-settings/title
              :subtitle          nil
              :header-breadcrumb nil}
             (page-shell/page-contract view {:include-header? true}))))
    (testing "Visible copy remains Fluent translation data."
      (is (= {:root :main
              :translation-keys
              #{:home
                :band-settings/toolbar-label
                :band-settings/section-page-subtitle
                :band-settings/section-title
                :band-settings/team-page-subtitle
                :band-settings/team-title
                :band-settings/title
                :band-settings/travel-discount-page-subtitle
                :band-settings/travel-discount-title}}
             {:root             (first view)
              :translation-keys (support/translation-keys view)})))))
