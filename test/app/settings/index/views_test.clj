(ns app.settings.index.views-test
  (:require
   [app.settings.index.views :as views]
   [app.settings.views-test-support :as support]
   [clojure.test :refer [deftest is]]))

(deftest settings-index-returns-translation-data-test
  (let [view (views/page {:tr support/legacy-tr})]
    (is (= {:root :main
            :translation-keys
            #{:band-settings/section-page-subtitle
              :band-settings/section-title
              :band-settings/team-page-subtitle
              :band-settings/team-title
              :band-settings/title
              :band-settings/travel-discount-page-subtitle
              :band-settings/travel-discount-title}}
           {:root             (first view)
            :translation-keys (support/translation-keys view)}))))
