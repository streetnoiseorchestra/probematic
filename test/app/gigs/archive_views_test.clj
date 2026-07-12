(ns app.gigs.archive-views-test
  (:require
   [app.gigs.archive.views :as views]
   [app.gigs.view-test-support :as support]
   [clojure.test :refer [deftest is testing]]))

(deftest gigs-archive-page-surface
  (testing "The gig archive returns to the complete gigs directory."
    (let [{:keys [conn]} (support/new-system "gigs-archive-surface")]
      (is (= {:width       :wide
              :breadcrumbs [:gigs/title :gigs/archive-title]
              :mobile      {:label :gigs/title :href "/gigs"}
              :actions     []
              :overflow    []}
             (-> conn support/request views/page support/page-contract))))))
